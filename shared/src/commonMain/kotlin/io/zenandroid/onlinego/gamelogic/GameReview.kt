package io.zenandroid.onlinego.gamelogic

import io.zenandroid.onlinego.data.model.StoneType
import io.zenandroid.onlinego.data.model.katago.KataGoResponse.Response

/*
 * Go Sensei fork: pure helpers for KataGo-based game review (no Android dependencies, unit tested).
 *
 * Sente's katago.cfg uses `reportAnalysisWinratesAs = WHITE`: winrates, score leads and ownership
 * are from White's point of view. Everything here is converted to BLACK's point of view, which is
 * what players and the tutor expect ("Black leads by 3.5").
 */

/** Evaluation of a position, from Black's point of view. */
data class PositionEval(
  val scoreLeadBlack: Float,
  val winrateBlack: Float,
  val visits: Int,
  val bestMove: String?,
  val bestPv: List<String>,
)

enum class MistakeLevel(val minLoss: Float) {
  BLUNDER(8f),
  MISTAKE(4f),
  INACCURACY(1.5f);

  companion object {
    fun of(loss: Float): MistakeLevel? = entries.firstOrNull { loss >= it.minLoss }
  }
}

/** A move of the main line and what it cost the player who made it. */
data class MoveReview(
  /** 1-based number of the move in the game. */
  val moveNumber: Int,
  val player: StoneType,
  /** GTP coordinate of the move played ("PASS" for a pass). */
  val played: String,
  /** Points lost by [player] with this move (negative = better than expected). */
  val loss: Float,
  val bestMove: String?,
  val bestPv: List<String>,
  /** null when the move is fine or is the engine's own first choice. */
  val level: MistakeLevel?,
)

fun Response.toEval(reportedAsWhite: Boolean = true): PositionEval {
  val sign = if (reportedAsWhite) -1f else 1f
  val best = moveInfos.minByOrNull { it.order }
  val lead = rootInfo.scoreLead ?: best?.scoreLead ?: 0f
  val winrate = rootInfo.winrate ?: best?.winrate ?: 0.5f
  return PositionEval(
    scoreLeadBlack = sign * lead,
    winrateBlack = if (reportedAsWhite) 1f - winrate else winrate,
    visits = rootInfo.visits ?: moveInfos.sumOf { it.visits },
    bestMove = best?.move,
    bestPv = best?.pv.orEmpty(),
  )
}

/**
 * Per-move review of the main line.
 * [evals] maps a turn number (0 = before the first move) to the evaluation of that position;
 * [moves] are the moves played, in order, as (player, GTP coordinate).
 */
fun reviewMoves(evals: Map<Int, PositionEval>, moves: List<Pair<StoneType, String>>): List<MoveReview> =
  moves.mapIndexedNotNull { i, (player, played) ->
    val before = evals[i] ?: return@mapIndexedNotNull null
    val after = evals[i + 1] ?: return@mapIndexedNotNull null
    val sign = if (player == StoneType.BLACK) 1f else -1f
    val loss = (before.scoreLeadBlack - after.scoreLeadBlack) * sign
    val isEngineChoice = before.bestMove != null && before.bestMove.equals(played, ignoreCase = true)
    MoveReview(
      moveNumber = i + 1,
      player = player,
      played = played,
      loss = loss,
      bestMove = before.bestMove,
      bestPv = before.bestPv,
      level = if (isEngineChoice) null else MistakeLevel.of(loss),
    )
  }

/** OGS rule names to KataGo rule names. */
fun katagoRules(ogsRules: String?): String = when (ogsRules?.lowercase()) {
  "chinese" -> "chinese"
  "aga" -> "aga"
  "korean" -> "korean"
  "nz", "new zealand", "new-zealand" -> "new-zealand"
  "ing" -> "chinese" // closest supported ruleset (area scoring)
  else -> "japanese"
}

/** Visits budgets tuned for a phone CPU (Eigen backend). */
fun liveVisitsFor(boardSize: Int) = when {
  boardSize <= 9 -> 400
  boardSize <= 13 -> 300
  else -> 200
}

fun reviewVisitsFor(boardSize: Int) = when {
  boardSize <= 9 -> 80
  boardSize <= 13 -> 50
  else -> 32
}

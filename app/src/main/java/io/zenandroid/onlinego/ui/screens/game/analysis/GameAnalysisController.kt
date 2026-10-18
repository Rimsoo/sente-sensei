package io.zenandroid.onlinego.ui.screens.game.analysis

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import io.zenandroid.onlinego.ai.KataGoAnalysisEngine
import io.zenandroid.onlinego.data.model.Position
import io.zenandroid.onlinego.data.model.StoneType
import io.zenandroid.onlinego.data.model.Variation
import io.zenandroid.onlinego.data.model.katago.KataGoResponse.Response
import io.zenandroid.onlinego.data.model.local.Game
import io.zenandroid.onlinego.gamelogic.MoveReview
import io.zenandroid.onlinego.gamelogic.PositionEval
import io.zenandroid.onlinego.gamelogic.RulesManager
import io.zenandroid.onlinego.gamelogic.Util
import io.zenandroid.onlinego.gamelogic.katagoRules
import io.zenandroid.onlinego.gamelogic.liveVisitsFor
import io.zenandroid.onlinego.gamelogic.reviewMoves
import io.zenandroid.onlinego.gamelogic.reviewVisitsFor
import io.zenandroid.onlinego.gamelogic.toEval
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Go Sensei fork: KataGo analysis of finished OGS games (live analysis of the shown position and
 * whole-game review). Kept out of GameViewModel so the upstream screen stays mostly untouched.
 * State is exposed as Compose snapshot state so GameViewModel's molecule picks changes up.
 */
class GameAnalysisController(
  private val engine: KataGoAnalysisEngine,
  private val scope: CoroutineScope,
  private val appScope: CoroutineScope,
) {
  enum class EngineStatus { OFF, STARTING, READY, FAILED }

  @Immutable
  data class Candidate(
    val move: String,
    /** Score lead of the player to move after this move. */
    val scoreForMover: Float,
    val winrateForMover: Float,
    val visits: Int,
    val pv: List<String>,
    /** Points lost compared to the engine's best move (>= 0). */
    val lossVsBest: Float,
  )

  @Immutable
  data class LiveAnalysis(
    val key: String,
    val toPlay: StoneType,
    val eval: PositionEval,
    val candidates: List<Candidate>,
    /** Raw KataGo ownership, White's point of view (what the board expects). */
    val ownership: List<Float>?,
    val final: Boolean,
  )

  @Immutable
  data class Review(
    val done: Int,
    val total: Int,
    val evals: Map<Int, PositionEval>,
    val moves: List<MoveReview>,
    val error: String? = null,
  ) {
    val finished get() = done >= total
  }

  var status by mutableStateOf(EngineStatus.OFF)
    private set
  var live by mutableStateOf<LiveAnalysis?>(null)
    private set
  var review by mutableStateOf<Review?>(null)
    private set
  var showHints by mutableStateOf(true)
  var showOwnership by mutableStateOf(false)

  private var acquired = false
  private val startMutex = Mutex()
  private var liveJob: Job? = null
  private var reviewJob: Job? = null

  private suspend fun ensureStarted(): Boolean = startMutex.withLock {
    if (acquired) return true
    status = EngineStatus.STARTING
    return try {
      withContext(Dispatchers.IO) { engine.acquire() }
      acquired = true
      status = EngineStatus.READY
      true
    } catch (e: Exception) {
      Logger.e("Could not start KataGo for game analysis", e, "GameAnalysis")
      status = EngineStatus.FAILED
      false
    }
  }

  /** Positions 0..[moveNumber] of the game (with the current variation), for building KataGo queries. */
  suspend fun sequence(game: Game, moveNumber: Int, variation: Variation?): List<Position> =
    withContext(Dispatchers.Default) {
      (0..moveNumber).map { RulesManager.replay(game, it, false, variation) }
    }

  /** Analyses the position shown on the board; partial results arrive every half second. */
  fun analyze(game: Game, moveNumber: Int, variation: Variation?) {
    liveJob?.cancel()
    liveJob = scope.launch {
      if (!ensureStarted()) return@launch
      val seq = sequence(game, moveNumber, variation)
      val key = seq.joinToString(" ") { p -> p.lastMove?.let { "${it.x},${it.y}" } ?: "-" }
      if (live?.key == key && live?.final == true) return@launch
      val toPlay = seq.last().nextToMove
      val query = engine.buildQuery(
        sequence = seq,
        komi = game.komi,
        rules = katagoRules(game.rules),
        maxVisits = maxOf(liveVisitsFor(game.width), engine.searchThreads),
        includeOwnership = true,
        reportDuringSearchEvery = 0.5f,
        priority = 10,
      )
      try {
        engine.analyze(query).collect { r -> live = r.toLive(key, toPlay) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Logger.e("Live analysis failed", e, "GameAnalysis")
      }
    }
  }

  /** Reviews every move of the main line (low visits, background priority). */
  fun startReview(game: Game) {
    reviewJob?.cancel()
    reviewJob = scope.launch {
      if (!ensureStarted()) return@launch
      val moveCount = game.moves?.size ?: 0
      val seq = sequence(game, moveCount, null)
      val moves = seq.drop(1).map { p ->
        (p.lastPlayerToMove ?: StoneType.BLACK) to Util.getGTPCoordinates(p.lastMove ?: return@map null, p.boardHeight)
      }.filterNotNull()
      val turns = (0..moveCount).toList()
      val evals = mutableMapOf<Int, PositionEval>()
      review = Review(0, turns.size, emptyMap(), emptyList())
      val query = engine.buildQuery(
        sequence = seq,
        komi = game.komi,
        rules = katagoRules(game.rules),
        maxVisits = maxOf(reviewVisitsFor(game.width), engine.searchThreads),
        analyzeTurns = turns,
        priority = 0,
      )
      try {
        engine.analyze(query, expectedFinalResponses = turns.size).collect { r ->
          if (r.isDuringSearch) return@collect
          evals[r.turnNumber] = r.toEval()
          review = Review(evals.size, turns.size, evals.toMap(), reviewMoves(evals, moves))
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Logger.e("Game review failed", e, "GameAnalysis")
        review = review?.copy(error = e.message ?: "erreur")
      }
    }
  }

  /** Snapshot for the UI (read inside GameViewModel's molecule, so it recomposes on changes). */
  fun uiState(game: Game, userId: Long?, moveNumber: Int, variation: Variation?): KataGoState {
    val inVariation = variation != null && moveNumber > variation.rootMoveNo
    val nextMain = if (!inVariation) game.moves?.getOrNull(moveNumber) else null
    return KataGoState(
      status = status,
      live = live,
      showHints = showHints,
      showOwnership = showOwnership,
      review = review,
      studentColor = when (userId) {
        null -> null
        game.blackPlayer.id -> StoneType.BLACK
        game.whitePlayer.id -> StoneType.WHITE
        else -> null
      },
      moveNumber = moveNumber,
      mainLineLength = game.moves?.size ?: 0,
      inVariation = inVariation,
      nextMainMove = nextMain?.let { Util.getGTPCoordinates(it, game.height) },
    )
  }

  fun stopLive() {
    liveJob?.cancel()
    liveJob = null
  }

  fun close() {
    liveJob?.cancel()
    reviewJob?.cancel()
    if (acquired) {
      acquired = false
      appScope.launch(Dispatchers.IO) { engine.release() }
    }
  }

  private fun Response.toLive(key: String, toPlay: StoneType): LiveAnalysis {
    // Sente reports everything from White's point of view.
    val sign = if (toPlay == StoneType.WHITE) 1f else -1f
    val sorted = moveInfos.sortedBy { it.order }
    val bestScore = sorted.firstOrNull()?.let { sign * it.scoreLead } ?: 0f
    return LiveAnalysis(
      key = key,
      toPlay = toPlay,
      eval = toEval(),
      candidates = sorted.map {
        val score = sign * it.scoreLead
        Candidate(
          move = it.move,
          scoreForMover = score,
          winrateForMover = if (toPlay == StoneType.WHITE) it.winrate else 1f - it.winrate,
          visits = it.visits,
          pv = it.pv,
          lossVsBest = (bestScore - score).coerceAtLeast(0f),
        )
      },
      ownership = ownership,
      final = !isDuringSearch,
    )
  }
}

@Immutable
data class KataGoState(
  val status: GameAnalysisController.EngineStatus,
  val live: GameAnalysisController.LiveAnalysis?,
  val showHints: Boolean,
  val showOwnership: Boolean,
  val review: GameAnalysisController.Review?,
  /** Colour of the logged-in user in this game, if they played it. */
  val studentColor: StoneType?,
  val moveNumber: Int,
  val mainLineLength: Int,
  val inVariation: Boolean,
  /** GTP coordinate of the move actually played next in the game (null inside a variation). */
  val nextMainMove: String?,
)

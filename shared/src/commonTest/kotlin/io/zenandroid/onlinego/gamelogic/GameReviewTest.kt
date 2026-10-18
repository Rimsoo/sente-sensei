package io.zenandroid.onlinego.gamelogic

import io.zenandroid.onlinego.data.model.StoneType
import io.zenandroid.onlinego.data.model.katago.KataGoResponse.Response
import io.zenandroid.onlinego.data.model.katago.MoveInfo
import io.zenandroid.onlinego.data.model.katago.RootInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GameReviewTest {

  private fun eval(lead: Float, best: String? = null) =
    PositionEval(scoreLeadBlack = lead, winrateBlack = 0.5f, visits = 100, bestMove = best, bestPv = listOfNotNull(best))

  private fun moveInfo(move: String, order: Int, scoreLead: Float, winrate: Float) = MoveInfo(
    move = move, visits = 10, winrate = winrate, scoreStdev = 1f, scoreLead = scoreLead, scoreSelfplay = scoreLead,
    prior = 0.1f, utility = 0f, lcb = winrate, utilityLcb = 0f, order = order, pv = listOf(move, "D4"),
  )

  @Test
  fun responseIsConvertedToBlackPointOfView() {
    val r = Response(
      id = "1", turnNumber = 0,
      moveInfos = listOf(moveInfo("C3", 1, 1f, 0.6f), moveInfo("E5", 0, 2f, 0.7f)),
      rootInfo = RootInfo(scoreLead = 2.5f, winrate = 0.75f, visits = 200),
    )
    val e = r.toEval()
    assertEquals(-2.5f, e.scoreLeadBlack) // White leads by 2.5 => Black -2.5
    assertEquals(0.25f, e.winrateBlack, 1e-6f)
    assertEquals("E5", e.bestMove) // lowest "order" wins, not list position
    assertEquals(listOf("E5", "D4"), e.bestPv)
    assertEquals(200, e.visits)
  }

  @Test
  fun lossIsComputedFromTheMoverPointOfView() {
    val evals = mapOf(0 to eval(5f, "E5"), 1 to eval(-3f, "C3"), 2 to eval(4f, "D4"))
    val moves = listOf(StoneType.BLACK to "A1", StoneType.WHITE to "B2")
    val review = reviewMoves(evals, moves)
    // Black went from +5 to -3: lost 8 points => blunder
    assertEquals(8f, review[0].loss)
    assertEquals(MistakeLevel.BLUNDER, review[0].level)
    assertEquals("E5", review[0].bestMove)
    // White went from Black+(-3) to Black+4: White lost 7 points => mistake
    assertEquals(7f, review[1].loss)
    assertEquals(MistakeLevel.MISTAKE, review[1].level)
    assertEquals(2, review[1].moveNumber)
  }

  @Test
  fun engineChoiceIsNeverAMistake() {
    // Noisy evaluations can make the engine's own move look bad; don't blame the player for it.
    val evals = mapOf(0 to eval(5f, "E5"), 1 to eval(2f))
    val review = reviewMoves(evals, listOf(StoneType.BLACK to "e5"))
    assertEquals(3f, review[0].loss)
    assertNull(review[0].level)
  }

  @Test
  fun movesWithoutEvaluationsAreSkipped() {
    val evals = mapOf(0 to eval(0f), 1 to eval(0.5f))
    val review = reviewMoves(evals, listOf(StoneType.BLACK to "A1", StoneType.WHITE to "B1"))
    assertEquals(1, review.size)
    assertNull(review[0].level) // gained points
  }

  @Test
  fun mistakeLevels() {
    assertNull(MistakeLevel.of(1.4f))
    assertEquals(MistakeLevel.INACCURACY, MistakeLevel.of(1.5f))
    assertEquals(MistakeLevel.MISTAKE, MistakeLevel.of(4f))
    assertEquals(MistakeLevel.BLUNDER, MistakeLevel.of(12f))
  }

  @Test
  fun rulesMapping() {
    assertEquals("new-zealand", katagoRules("nz"))
    assertEquals("chinese", katagoRules("Chinese"))
    assertEquals("japanese", katagoRules(null))
    assertEquals("aga", katagoRules("aga"))
  }
}

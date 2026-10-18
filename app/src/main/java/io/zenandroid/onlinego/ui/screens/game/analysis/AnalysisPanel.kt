package io.zenandroid.onlinego.ui.screens.game.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.zenandroid.onlinego.R
import io.zenandroid.onlinego.gamelogic.MistakeLevel
import io.zenandroid.onlinego.gamelogic.Util
import io.zenandroid.onlinego.ui.composables.BoardCandidate
import io.zenandroid.onlinego.ui.screens.game.UserAction
import io.zenandroid.onlinego.ui.screens.game.UserAction.KataGoGoToMove
import io.zenandroid.onlinego.ui.screens.game.UserAction.KataGoPlayMove
import io.zenandroid.onlinego.ui.screens.game.UserAction.KataGoStartReview
import io.zenandroid.onlinego.ui.screens.game.UserAction.KataGoToggleHints
import io.zenandroid.onlinego.ui.screens.game.UserAction.KataGoToggleOwnership
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlin.math.abs

/** Colour of a candidate move according to the points it loses compared to the best one. */
fun lossColor(loss: Float): Color = when {
  loss <= 0.3f -> Color(0xFF3EC1A8)
  loss <= 1f -> Color(0xFF7BD36B)
  loss <= 3f -> Color(0xFFE3C548)
  loss <= 6f -> Color(0xFFE8893F)
  else -> Color(0xFFE0564F)
}

private fun levelColor(level: MistakeLevel) = when (level) {
  MistakeLevel.BLUNDER -> Color(0xFFE0564F)
  MistakeLevel.MISTAKE -> Color(0xFFE3B448)
  MistakeLevel.INACCURACY -> Color(0xFFB8C27A)
}

private fun signed(v: Float) = (if (v >= 0) "+" else "−") + "%.1f".format(abs(v))

/** Candidates shown on the board: the best one plus the moves the engine explored enough. */
fun KataGoState.boardCandidates(boardHeight: Int): ImmutableList<BoardCandidate>? {
  val l = live?.takeIf { showHints } ?: return null
  val total = l.candidates.sumOf { it.visits }
  return l.candidates
    .filterIndexed { i, c -> i == 0 || c.visits >= maxOf(3, total * 3 / 100) }
    .take(8)
    .map {
      BoardCandidate(
        cell = Util.getCoordinatesFromGTP(it.move, boardHeight),
        text = signed(it.scoreForMover),
        color = lossColor(it.lossVsBest),
        highlighted = it === l.candidates.first(),
      )
    }
    .toImmutableList()
}

fun KataGoState.boardOwnership(): ImmutableList<Float>? =
  live?.ownership?.takeIf { showOwnership }?.toImmutableList()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalysisPanel(
  state: KataGoState,
  onUserAction: (UserAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 12.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    EvalHeader(state)

    // Candidate moves (tap = play it as a variation)
    val candidates = state.live?.candidates.orEmpty().take(6)
    if (candidates.isNotEmpty()) {
      Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        candidates.forEach { c ->
          Row(
            modifier = Modifier
              .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
              .clickable { onUserAction(KataGoPlayMove(c.move)) }
              .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(Modifier.size(9.dp).background(lossColor(c.lossVsBest), CircleShape))
            Text(" ${c.move} ", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(signed(c.scoreForMover), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilterChip(
        selected = state.showHints,
        onClick = { onUserAction(KataGoToggleHints) },
        label = { Text(stringResource(R.string.analysis_suggestions)) },
      )
      FilterChip(
        selected = state.showOwnership,
        onClick = { onUserAction(KataGoToggleOwnership) },
        label = { Text(stringResource(R.string.analysis_territory)) },
      )
    }

    ReviewSection(state, onUserAction)
  }
}

@Composable
private fun EvalHeader(state: KataGoState) {
  val live = state.live
  when {
    state.status == GameAnalysisController.EngineStatus.FAILED ->
      Text(stringResource(R.string.analysis_engine_failed), color = MaterialTheme.colorScheme.error)

    live == null ->
      Text(stringResource(R.string.analysis_engine_starting), color = MaterialTheme.colorScheme.onSurfaceVariant)

    else -> {
      val lead = live.eval.scoreLeadBlack
      val leadText = if (abs(lead) < 0.5f) stringResource(R.string.analysis_even) else stringResource(
        R.string.analysis_lead,
        stringResource(if (lead > 0) R.string.analysis_black else R.string.analysis_white),
        abs(lead),
      )
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(leadText, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(
          stringResource(R.string.analysis_visits, live.eval.visits),
          fontSize = 12.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      LinearProgressIndicator(
        progress = { live.eval.winrateBlack },
        modifier = Modifier.fillMaxWidth().height(6.dp),
        color = Color(0xFF111111),
        trackColor = Color(0xFFEEEEEE),
        drawStopIndicator = {},
      )
      Text(
        stringResource(R.string.analysis_black_winrate, live.eval.winrateBlack * 100),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
  // What was actually played next, and what it cost (needs the review)
  val next = state.nextMainMove
  if (state.inVariation) {
    Text(stringResource(R.string.analysis_variation), fontSize = 12.sp, color = Color(0xFFE3B448))
  } else if (next != null) {
    val mr = state.review?.moves?.firstOrNull { it.moveNumber == state.moveNumber + 1 }
    val cost = when {
      mr == null -> ""
      mr.loss > 0.05f -> " (" + stringResource(R.string.analysis_points_lost, mr.loss) + ")"
      else -> " (" + stringResource(R.string.analysis_good_move) + ")"
    }
    Text(
      stringResource(R.string.analysis_played_next, next) + cost,
      fontSize = 12.sp,
      color = mr?.let { lossColor(it.loss) } ?: Color(0xFF3D7FE0),
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewSection(state: KataGoState, onUserAction: (UserAction) -> Unit) {
  val review = state.review
  if (review == null) {
    OutlinedButton(onClick = { onUserAction(KataGoStartReview) }) {
      Text(stringResource(R.string.analysis_review_start))
    }
    return
  }
  if (!review.finished) {
    Text(
      stringResource(R.string.analysis_review_progress, review.done, review.total),
      fontSize = 12.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LinearProgressIndicator(
      progress = { review.done.toFloat() / review.total.coerceAtLeast(1) },
      modifier = Modifier.fillMaxWidth(),
    )
  }
  review.error?.let {
    Text(stringResource(R.string.analysis_review_error, it), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
  }
  ScoreGraph(state, onUserAction)

  val mistakes = review.moves.filter { it.level != null && (state.studentColor == null || it.player == state.studentColor) }
  Text(
    stringResource(if (state.studentColor != null) R.string.analysis_mistakes_mine else R.string.analysis_mistakes_all),
    fontWeight = FontWeight.Bold,
    fontSize = 13.sp,
  )
  if (mistakes.isEmpty() && review.finished) {
    Text(stringResource(R.string.analysis_no_mistakes), fontSize = 12.sp)
  }
  FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    mistakes.forEach { m ->
      val color = levelColor(m.level!!)
      val current = !state.inVariation && state.moveNumber == m.moveNumber - 1
      Text(
        "${m.moveNumber}. ${m.played} −${"%.1f".format(m.loss)}",
        fontSize = 12.sp,
        color = color,
        modifier = Modifier
          .border(1.dp, color, RoundedCornerShape(50))
          .background(if (current) color.copy(alpha = .15f) else Color.Transparent, RoundedCornerShape(50))
          // Go to the position just BEFORE the mistake, to see what should have been played
          .clickable { onUserAction(KataGoGoToMove(m.moveNumber - 1)) }
          .padding(horizontal = 9.dp, vertical = 3.dp),
      )
    }
  }
}

@Composable
private fun ScoreGraph(state: KataGoState, onUserAction: (UserAction) -> Unit) {
  val review = state.review ?: return
  val total = state.mainLineLength.coerceAtLeast(1)
  val points = review.evals.toSortedMap().map { (turn, e) -> turn to e.scoreLeadBlack }
  if (points.size < 2) return
  val maxAbs = points.maxOf { abs(it.second) }.coerceIn(10f, 60f)
  val lineColor = MaterialTheme.colorScheme.primary
  val axisColor = MaterialTheme.colorScheme.outlineVariant
  val cursorColor = MaterialTheme.colorScheme.onSurface
  Text(stringResource(R.string.analysis_score_graph), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Canvas(
    modifier = Modifier
      .fillMaxWidth()
      .height(64.dp)
      .pointerInput(total) {
        detectTapGestures { offset ->
          val turn = (offset.x / size.width * total).toInt().coerceIn(0, total)
          onUserAction(KataGoGoToMove(turn))
        }
      }
  ) {
    val w = size.width
    val h = size.height
    fun x(t: Int) = t.toFloat() / total * w
    fun y(s: Float) = h / 2 - (s.coerceIn(-maxAbs, maxAbs) / maxAbs) * (h / 2 - 2)
    drawLine(axisColor, Offset(0f, h / 2), Offset(w, h / 2))
    val path = Path().apply {
      points.forEachIndexed { i, (t, s) -> if (i == 0) moveTo(x(t), y(s)) else lineTo(x(t), y(s)) }
    }
    drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
    review.moves.filter { it.level != null && (state.studentColor == null || it.player == state.studentColor) }
      .forEach { m ->
        review.evals[m.moveNumber]?.let { e ->
          drawCircle(levelColor(m.level!!), 3.dp.toPx(), Offset(x(m.moveNumber), y(e.scoreLeadBlack)))
        }
      }
    val cx = x(state.moveNumber)
    drawLine(cursorColor, Offset(cx, 0f), Offset(cx, h), strokeWidth = 1.dp.toPx())
  }
}

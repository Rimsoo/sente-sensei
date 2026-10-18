package io.zenandroid.onlinego.ai

import android.content.Context
import co.touchlab.kermit.Logger
import io.zenandroid.onlinego.data.model.Position
import io.zenandroid.onlinego.data.model.StoneType
import io.zenandroid.onlinego.data.model.katago.KataGoResponse
import io.zenandroid.onlinego.data.model.katago.KataGoResponse.ErrorResponse
import io.zenandroid.onlinego.data.model.katago.KataGoResponse.Response
import io.zenandroid.onlinego.data.model.katago.OverrideSettings
import io.zenandroid.onlinego.data.model.katago.Query
import io.zenandroid.onlinego.data.model.katago.TerminateQuery
import io.zenandroid.onlinego.gamelogic.Util
import io.zenandroid.onlinego.utils.CrashReporter
import io.zenandroid.onlinego.utils.appJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transformWhile
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.ZipFile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.incrementAndFetch

class KataGoAnalysisEngine(private val context: Context) {
  var started = false
    private set
  var shouldShutDown = false
    private set
  private var process: Process? = null
  private var writer: OutputStreamWriter? = null
  private var reader: BufferedReader? = null
  private val requestIDX = AtomicLong(0)
  // Large buffer: a whole-game review (analyzeTurns) can produce hundreds of responses in a burst,
  // and tryEmit silently drops them when the buffer is full.
  private val responseFlow = MutableSharedFlow<KataGoResponse>(extraBufferCapacity = 1024)
  // Number of screens currently using the engine (local AI game, OGS game analysis...). The process
  // is only stopped when the last one releases it, so they don't kill each other's searches.
  private var users = 0

  // Callers also raise maxVisits to at least this many - KataGo spins up this many search
  // threads per query regardless of budget, wasting the rest otherwise.
  val searchThreads = Runtime.getRuntime().availableProcessors()
  private val netFile by lazy { File(context.filesDir, "katagonet.gz") }
  private val humanNetFile by lazy { File(context.filesDir, "katagohumannet.gz") }
  private val cfgFile by lazy { File(context.filesDir, "katago.cfg") }

  /** Registers a user of the engine and starts it if needed. Blocking: call from Dispatchers.IO. */
  @Throws(IOException::class)
  @Synchronized
  fun acquire() {
    users++
    try {
      start()
    } catch (e: Exception) {
      users--
      throw e
    }
  }

  /** Counterpart of [acquire]: the engine stops (after a grace period) once nobody uses it. */
  @Synchronized
  fun release() {
    users = (users - 1).coerceAtLeast(0)
    if (users == 0) {
      stop()
    }
  }

  @Throws(IOException::class)
  @Synchronized
  fun start() {
    shouldShutDown = false
    if (started) {
      return
    }
    ensureResourcesAreUnpacked()
    process = ProcessBuilder(
      "./libkatago.so",
      "analysis",
      "-model", netFile.absolutePath,
      "-human-model", humanNetFile.absolutePath,
      "-config", cfgFile.absolutePath
    )
      .apply { environment()["LD_LIBRARY_PATH"] = "." }
      .directory(File(context.applicationInfo.nativeLibraryDir))
      .start()
      .apply {
        reader = BufferedReader(InputStreamReader(inputStream))
        writer = OutputStreamWriter(outputStream)
        val errorReader = BufferedReader(InputStreamReader(errorStream))
        val errors = StringBuffer()

        reader?.let {
          while (true) {
            val line = errorReader.readLine() ?: break
            if (line.startsWith("KataGo v")) {
              continue
            } else if (line == "Started, ready to begin handling requests") {
              requestIDX.store(0)
              started = true
              break
            } else {
              Logger.e(line, tag = "KataGoAnalysisEngine")
              errors.appendLine(line)
            }
          }
        }

        if (started) {
          Thread {
            while (true) {
              val line = reader?.readLine() ?: break
              try {
                if (line.contains("\"error\":") || line.contains("\"warning\":")) {
                  Logger.e(line, tag = "KataGoAnalysisEngine")
                  CrashReporter.recordException(Exception("Katago: $line"))
                  responseFlow.tryEmit(appJson.decodeFromString<ErrorResponse>(line))
                } else {
                  Logger.i("< $line", tag = "KataGoAnalysisEngine")
                  responseFlow.tryEmit(appJson.decodeFromString<Response>(line))
                }
              } catch (e: Exception) {
                Logger.e("Failed to parse KataGo line: $line", e, "KataGoAnalysisEngine")
                CrashReporter.recordException(e)
              }
            }
            Logger.i("End of input, killing reader thread", tag = "KataGoAnalysisEngine")
            started = false
          }.start()
        } else {
          Logger.e("Could not start KataGo", tag = "KataGoAnalysisEngine")
          CrashReporter.recordException(Exception("Could not start KataGo $errors"))
          throw RuntimeException("Could not start KataGo")
        }
      }
  }

  @Synchronized
  fun stop() {
    shouldShutDown = true
    if (!started) {
      return
    }
    Thread {
      Thread.sleep(2000)
      synchronized(this@KataGoAnalysisEngine) {
        if (shouldShutDown && started) {
          try {
            writer?.close()
            process?.waitFor()
          } catch (t: Throwable) {
            process?.destroy()
          }
          process = null
          started = false
          shouldShutDown = false
        }
      }
    }.start()
  }

  suspend fun analyzeMoveSequence(
    sequence: List<Position>,
    komi: Float? = null,
    rules: String = "japanese",
    maxVisits: Int? = null,
    includeOwnership: Boolean? = null,
    includeMovesOwnership: Boolean? = null,
    includePolicy: Boolean? = null,
    overrideSettings: OverrideSettings? = null
  ): Response = analyze(
    buildQuery(
      sequence, komi, rules, maxVisits, includeOwnership, includeMovesOwnership,
      includePolicy, overrideSettings
    )
  ).first { !it.isDuringSearch }

  /**
   * Builds a query from a list of positions: the first one (no last move) gives the initial stones,
   * each following one contributes its last move.
   */
  fun buildQuery(
    sequence: List<Position>,
    komi: Float? = null,
    rules: String = "japanese",
    maxVisits: Int? = null,
    includeOwnership: Boolean? = null,
    includeMovesOwnership: Boolean? = null,
    includePolicy: Boolean? = null,
    overrideSettings: OverrideSettings? = null,
    analyzeTurns: List<Int>? = null,
    reportDuringSearchEvery: Float? = null,
    priority: Int? = null,
  ): Query {
    val initialPosition = mutableSetOf<List<String>>()
    val history = mutableListOf<List<String>>()
    sequence.map { pos ->
      val lastMove = pos.lastMove
      if (lastMove == null) {
        initialPosition.addAll(pos.whiteStones.map {
          listOf("W", Util.getGTPCoordinates(it, pos.boardHeight))
        })
        initialPosition.addAll(pos.blackStones.map {
          listOf("B", Util.getGTPCoordinates(it, pos.boardHeight))
        })
      } else {
        val lastPlayer = if (pos.lastPlayerToMove == StoneType.BLACK) "B" else "W"
        history.add(listOf(lastPlayer, Util.getGTPCoordinates(lastMove, pos.boardHeight)))
      }
    }

    return Query(
      id = generateId(),
      boardXSize = sequence.firstOrNull()?.boardWidth ?: 19,
      boardYSize = sequence.firstOrNull()?.boardHeight ?: 19,
      includeOwnership = includeOwnership,
      includeMovesOwnership = includeMovesOwnership,
      includePolicy = includePolicy,
      initialStones = initialPosition.toList(),
      komi = komi,
      maxVisits = maxVisits,
      overrideSettings = overrideSettings,
      moves = history,
      rules = rules,
      analyzeTurns = analyzeTurns,
      reportDuringSearchEvery = reportDuringSearchEvery,
      priority = priority,
    )
  }

  /**
   * Sends [query] and emits its responses (partial ones too when reportDuringSearchEvery is set)
   * until [expectedFinalResponses] final responses arrived - one per entry of analyzeTurns.
   * Cancelling the collector terminates the search in KataGo.
   */
  fun analyze(query: Query, expectedFinalResponses: Int = 1): Flow<Response> = flow {
    var finals = 0
    try {
      emitAll(
        responseFlow
          // Subscribe before writing, otherwise a fast answer could be missed.
          .onSubscription { send(appJson.encodeToString(query)) }
          .filter { it.id == query.id }
          .transformWhile { r ->
            when (r) {
              is ErrorResponse -> {
                r.error?.let { throw RuntimeException(it) }
                true // a warning: keep waiting for the real answer
              }

              is Response -> {
                emit(r)
                if (!r.isDuringSearch) finals++
                finals < expectedFinalResponses
              }
            }
          }
      )
    } finally {
      if (finals < expectedFinalResponses) {
        terminate(query.id)
      }
    }
  }

  private fun terminate(queryId: String) {
    if (started) {
      send(appJson.encodeToString(TerminateQuery(id = "stop-$queryId", terminateId = queryId)))
    }
  }

  @Synchronized
  private fun send(line: String) {
    Logger.i("> $line", tag = "KataGoAnalysisEngine")
    try {
      writer?.apply {
        write(line + "\n")
        flush()
      }
    } catch (e: IOException) {
      Logger.e("Could not write to KataGo", e, "KataGoAnalysisEngine")
    }
  }

  private fun generateId() = requestIDX.incrementAndFetch().toString()

  private fun ensureResourcesAreUnpacked() {
    unpackResource("katago.net", netFile)
    unpackResource("katago_human.net", humanNetFile)
    writeConfigWithThreadCount()
  }

  // Thread pool size is fixed at KataGo process startup, unlike humanSLProfile - it can't
  // be set via per-query overrideSettings, so the cfg has to be regenerated per engine start.
  private fun writeConfigWithThreadCount() {
    val template = context.assets.open("katago.cfg")
      .bufferedReader().use { it.readText() }
    val configured = template.replace(
      Regex("""(?m)^numSearchThreadsPerAnalysisThread\s*=.*$"""),
      "numSearchThreadsPerAnalysisThread = $searchThreads"
    )
    cfgFile.writeText(configured)
  }

  // Reads the real size from the APK's zip central directory - AssetManager.openFd() fails
  // on AAPT-compressed assets, and this avoids a hardcoded byte count to keep in sync by hand.
  private fun expectedAssetSize(srcName: String): Long {
    val apkPath = context.applicationInfo.sourceDir
    ZipFile(apkPath).use { zip ->
      return zip.getEntry("assets/$srcName")?.size
        ?: throw IOException("Asset '$srcName' not found in APK at $apkPath")
    }
  }

  private fun unpackResource(srcName: String, destFile: File) {
    val assets = context.assets
    val expectedSize = expectedAssetSize(srcName)
    if (!destFile.exists() || destFile.length() != expectedSize) {
      destFile.delete()
      assets.open(srcName).use { input ->
        destFile.outputStream().use { output -> input.copyTo(output) }
      }
    }
  }
}
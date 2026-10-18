@file:UseSerializers(OGSBooleanSerializer::class, LenientIntSerializer::class)

package io.zenandroid.onlinego.data.model.katago

import io.zenandroid.onlinego.data.ogs.LenientIntSerializer
import io.zenandroid.onlinego.data.ogs.OGSBooleanSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

// Note: see https://github.com/lightvector/KataGo/blob/master/docs/Analysis_Engine.md for meanings
@Serializable
data class Query (
        val id: String,
        val moves: List<List<String>>,
        val initialStones: List<List<String>>? = null,
        val initialPlayer: String? = null,
        val rules: String,
        val komi: Float? = null,
        val boardXSize: Int,
        val boardYSize: Int,
        val analyzeTurns: List<Int>? = null,
        val maxVisits: Int? = null,
        val rootFpuReductionMax: Float? = null,
        val includeOwnership: Boolean? = null,
        val includeMovesOwnership: Boolean? = null,
        val includePolicy: Boolean? = null,
        val includePVVisits: Boolean? = null,
        val avoidMoves: List<List<String>>? = null,
        val allowMoves: List<List<String>>? = null,
        val overrideSettings: OverrideSettings? = null,
        val priority: Int? = null,
        // Go Sensei: progressive results (seconds between partial responses)
        val reportDuringSearchEvery: Float? = null,
)

// Go Sensei: stops a running query (see "terminate" in Analysis_Engine.md)
@Serializable
data class TerminateQuery(
        val id: String,
        val action: String = "terminate",
        val terminateId: String,
)

// humanSLProfile format: "rank_{RANK}" (or "preaz_{RANK}"), RANK spanning 20k-9d - see
// AiDifficulty.humanSLProfile for how tiers map to it.
@Serializable
data class OverrideSettings(
        val humanSLProfile: String? = null,
)
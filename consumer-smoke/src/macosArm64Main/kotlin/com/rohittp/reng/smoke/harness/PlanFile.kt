@file:OptIn(ExperimentalForeignApi::class)

package com.rohittp.reng.smoke.harness

import com.rohittp.reng.FramePlan
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs

/**
 * The corpus format: a JSON array of RenG's own [FramePlan], and no harness type anywhere in it.
 *
 * **There is no mirror DTO here, and that is what Cycle K task 2 bought.** The harness decodes
 * straight into the renderer's public types, so a plan file cannot drift from what RenG accepts: a
 * document naming a field RenG does not have fails to decode, and a document holding a value RenG's
 * constructors refuse fails at the same moment with RenG's own message. The alternative -- a harness
 * DTO translated into a `FramePlan` -- would have put a second definition of "a valid frame" in this
 * repository, and the two would have disagreed eventually.
 */
private val planJson = Json {
    // A corpus file is read and hand-edited far more often than it is written, so it is written to
    // be read. The cost is size, which the comparison script never transports.
    prettyPrint = true
    prettyPrintIndent = "  "
    // A plan file that names a field RenG does not have is a mistake in the file, not a field to
    // ignore. Silently dropping it is how a corpus entry ends up testing something other than what
    // it says -- so unknown keys stop the run.
    ignoreUnknownKeys = false
}

private val planListSerializer = ListSerializer(FramePlan.serializer())

/** Decodes a plan file, failing with the decoder's own message rather than a summary of it. */
internal fun decodePlans(text: String): List<FramePlan> {
    val plans = try {
        planJson.decodeFromString(planListSerializer, text)
    } catch (failure: Exception) {
        throw IllegalArgumentException(failure.message ?: "could not be decoded", failure)
    }
    require(plans.isNotEmpty()) { "a plan file holds at least one frame" }
    return plans
}

/** Encodes plans in the corpus's own shape, so an emitted file is byte-identical to a hand-written one. */
internal fun encodePlans(plans: List<FramePlan>): String =
    planJson.encodeToString(planListSerializer, plans)

/** Decodes a harness config, failing with the decoder's own message. */
internal fun decodeConfig(text: String): HarnessConfig =
    try {
        planJson.decodeFromString(HarnessConfig.serializer(), text)
    } catch (failure: Exception) {
        throw IllegalArgumentException(failure.message ?: "could not be decoded", failure)
    }

/**
 * The storyboard as a plain list, so the sequence every previous cycle rendered becomes one ordinary
 * corpus file rather than a code path the corpus has to work around.
 */
internal fun storyboardPlans(): List<FramePlan> = framePlans()

internal fun writeTextFile(path: String, text: String) {
    val handle = fopen(path, "wb") ?: error("cannot write $path")
    try {
        fputs(text, handle)
    } finally {
        fclose(handle)
    }
}

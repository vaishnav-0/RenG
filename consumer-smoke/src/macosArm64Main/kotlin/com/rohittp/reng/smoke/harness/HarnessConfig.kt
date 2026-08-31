@file:OptIn(ExperimentalForeignApi::class)

package com.rohittp.reng.smoke.harness

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

/**
 * A named set of renderer-initialisation parameters, read from a JSON file.
 *
 * **Only initialisation lives here; everything a frame decides lives in the plan file.** The split
 * is the whole point of the Cycle K harness: one config crossed with one plan file is one run, and
 * the comparison script crosses every config with every plan file to enumerate the corpus. Anything
 * that varies frame to frame -- camera, projection, what is drawn -- belongs in the plan and is
 * RenG's own serialized [com.rohittp.reng.FramePlan], not a harness type.
 *
 * [transport], [store] and [diagnosticSink] are deliberately absent: they are adapters the consumer
 * supplies and cannot be described in a document.
 */
@Serializable
internal class HarnessConfig(
    /** Names the run. The comparison script uses it to name the output directory. */
    @SerialName("name") val name: String,
    /**
     * The style's identifier, **never its URL**.
     *
     * The owner's style URLs carry an api key, so no URL is checked in and none appears in a corpus
     * file. The base is machine-local -- `local.properties` or `.env`, both untracked -- and
     * `<base>/<styleId>` composes the URL at run time. A corpus file therefore names "style 59" and
     * a run resolves it; the key never leaves the machine.
     */
    @SerialName("styleId") val styleId: String,
    @SerialName("width") val width: Int = 960,
    @SerialName("height") val height: Int = 540,
    @SerialName("terrainShading") val terrainShading: Boolean = false,
    @SerialName("maximumBasemapTileInstances") val maximumBasemapTileInstances: Int = 512,
) {
    init {
        require(name.isNotBlank()) { "a config has a name" }
        require(styleId.isNotBlank()) { "a config names a style" }
        require(width > 0 && height > 0) { "a config has a positive output size" }
    }
}

/** The property and environment variable naming the style base, in the two places it may live. */
internal const val STYLE_BASE_KEY: String = "reng.harness.styleBaseUrl"
internal const val STYLE_BASE_ENVIRONMENT_KEY: String = "RENG_HARNESS_STYLE_BASE_URL"

/**
 * Composes a style URL from the machine-local base and a corpus file's style id.
 *
 * **A missing base fails loudly and names the variable**, because the alternative -- composing a URL
 * against an empty base -- produces a request that fails somewhere far away, in a transport error
 * that looks like a network problem rather than a missing setting.
 */
internal fun resolveStyleUrl(styleId: String, localProperties: String?, environment: String?): String {
    val base = (localProperties ?: environment)?.trim()?.trimEnd('/')
    require(!base.isNullOrBlank()) {
        "No style base url. The owner's styles carry api keys, so no url is checked in.\n" +
            "  set $STYLE_BASE_KEY in local.properties, or $STYLE_BASE_ENVIRONMENT_KEY in the " +
            "environment.\n" +
            "  A run composes <base>/<styleId>, so the base is everything before the style's own id."
    }
    return "$base/$styleId"
}

/** Reads a whole file, or `null` when it does not exist. Used for configs, plan files and settings. */
internal fun readFileOrNull(path: String): String? {
    val handle = fopen(path, "rb") ?: return null
    try {
        fseek(handle, 0, SEEK_END)
        val length = ftell(handle)
        fseek(handle, 0, SEEK_SET)
        if (length <= 0L) return ""
        return memScoped {
            val buffer = allocArray<kotlinx.cinterop.ByteVar>(length)
            val read = fread(buffer, 1u, length.toULong(), handle)
            buffer.readBytes(read.toInt()).decodeToString()
        }
    } finally {
        fclose(handle)
    }
}

/**
 * Reads one `key=value` out of a `local.properties`-shaped file, ignoring comments and blank lines.
 *
 * Hand-parsed rather than reached through a properties library because this is four lines of logic
 * and the file is the consumer's own untracked settings, never anything RenG ships.
 */
internal fun readLocalProperty(text: String?, key: String): String? = text
    ?.lineSequence()
    ?.map(String::trim)
    ?.filter { it.isNotEmpty() && !it.startsWith("#") }
    ?.firstOrNull { it.substringBefore('=').trim() == key }
    ?.substringAfter('=')
    ?.trim()
    ?.takeIf(String::isNotEmpty)

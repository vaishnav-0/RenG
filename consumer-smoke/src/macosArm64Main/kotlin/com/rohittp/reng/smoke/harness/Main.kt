@file:OptIn(ExperimentalForeignApi::class)

package com.rohittp.reng.smoke.harness

import com.rohittp.reng.Diagnostic
import com.rohittp.reng.DiagnosticSink
import com.rohittp.reng.FramebufferName
import com.rohittp.reng.FramePlan
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.RenGException
import com.rohittp.reng.Renderer
import com.rohittp.reng.RendererConfiguration
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.createRenderer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.concurrent.AtomicReference
import kotlin.system.exitProcess
import kotlin.time.TimeSource

/**
 * The visual harness: a consumer that creates its own Render Context, hands RenG a sequence of
 * `FramePlan`s over a real map style, and writes each finished frame to disk as a PPM.
 *
 * It is a consumer that happens to live in this repository. It creates the GL context, owns the
 * capture framebuffer, reads pixels back, and writes files; RenG only draws. Encoding is
 * `ffmpeg`'s, printed as a command at the end rather than performed here.
 */
/**
 * What the capture target holds before RenG draws into it. A saturated colour nothing in a map
 * style would produce, so "RenG drew nothing here" reads as itself rather than as a dark basemap.
 */
private val UNDRAWN: IntArray = intArrayOf(0, 96, 32, 255)

fun main(arguments: Array<String>) {
    val options = parseArguments(arguments) ?: exitProcess(2)

    if (options.emitCorpusTo != null) {
        corpusPlans().forEach { (name, plans) ->
            val path = "${options.emitCorpusTo}/$name.json"
            writeTextFile(path, encodePlans(plans))
            println("  $name: ${plans.size} plan(s) -> $path")
        }
        return
    }

    if (options.emitPlansTo != null) {
        // With `--plans`, this decodes that file and writes it back out, which is an idempotence
        // check anyone can run: `--emit-plans` a corpus file onto itself and a clean `git diff` says
        // the file survives a full decode/encode cycle. Without `--plans` it emits the built-in
        // storyboard, which is how the first corpus file was authored.
        val source = options.plansPath.takeIf { it.isNotBlank() }
        val plans = if (source == null) {
            storyboardPlans()
        } else {
            try {
                decodePlans(readFileOrNull(source) ?: error("no plan file at $source"))
            } catch (failure: IllegalArgumentException) {
                println("plans: ${failure.message}")
                exitProcess(2)
            }
        }
        writeTextFile(options.emitPlansTo, encodePlans(plans))
        println("wrote ${plans.size} plans to ${options.emitPlansTo}" +
            if (source == null) " from the built-in storyboard" else " from $source")
        return
    }

    val config = try {
        decodeConfig(readFileOrNull(options.configPath) ?: error("no config at ${options.configPath}"))
    } catch (failure: IllegalArgumentException) {
        println("config: ${failure.message}")
        exitProcess(2)
    }

    val plans = try {
        decodePlans(readFileOrNull(options.plansPath) ?: error("no plan file at ${options.plansPath}"))
    } catch (failure: IllegalArgumentException) {
        println("plans: ${failure.message}")
        exitProcess(2)
    }

    val styleUrl = try {
        resolveStyleUrl(
            styleId = config.styleId,
            localProperties = readLocalProperty(readFileOrNull(options.localPropertiesPath), STYLE_BASE_KEY),
            environment = getenv(STYLE_BASE_ENVIRONMENT_KEY)?.toKString(),
        )
    } catch (failure: IllegalArgumentException) {
        println(failure.message)
        exitProcess(2)
    }

    val context = CglCoreProfileContext.create()
    println("Render Context: " + describeCurrentContext())
    println("config ${config.name}: style ${config.styleId}, ${config.width}x${config.height}, " +
        "terrainShading=${config.terrainShading}, ${plans.size} plans from ${options.plansPath}")

    val transport = NsUrlTransport(embeddedImages(), options.verbose)
    val store = MemoryStore()
    val diagnostics = DiagnosticTally()

    val renderer = try {
        createRenderer(
            RendererConfiguration(
                outputPixelSize = OutputPixelSize(config.width, config.height),
                transport = transport,
                store = store,
                basemapStyle = ResourceLocator(styleUrl),
                maximumBasemapTileInstances = config.maximumBasemapTileInstances,
                diagnosticSink = diagnostics,
                terrainShading = config.terrainShading,
            ),
        )
    } catch (failure: RenGException) {
        println("createRenderer failed: ${failure.code} at ${failure.stage}")
        context.destroy()
        exitProcess(1)
    }

    val target = CaptureTarget.create(config.width, config.height)
    val started = TimeSource.Monotonic.markNow()
    var written = 0
    var failed = 0

    try {
        plans.forEach { plan ->
            if (renderOneFrame(renderer, target, plan, options.outputDirectory)) {
                written += 1
            } else {
                failed += 1
            }
        }
    } finally {
        target.destroy()
        renderer.close()
        context.destroy()
    }

    println()
    println("wrote ${written + failed} frames, $failed of them failed, in ${started.elapsedNow()}")
    println(transport.summary())
    println(store.summary())
    println(diagnostics.summary())
    transport.failureSummary().forEach { println("  transport: $it") }
    diagnostics.samples().forEach { println("  diagnostic: $it") }

    println()
    println("Assemble the video with:")
    println(
        "  ffmpeg -y -framerate 12 -i ${options.outputDirectory}/frame-%04d.ppm " +
            "-c:v libx264 -pix_fmt yuv420p ${options.outputDirectory}/harness.mp4",
    )
    if (failed > 0) exitProcess(1)
}

/**
 * Clears the capture target, prepares and draws one plan, and writes the readback out.
 *
 * `prepare` and `draw` are separate calls because they are separate operations (ADR 0002): the
 * first is suspending and may reach the network through the consumer's transport, the second is
 * synchronous GL work that must run on the thread holding the Render Context.
 */
private fun renderOneFrame(
    renderer: Renderer,
    target: CaptureTarget,
    plan: FramePlan,
    outputDirectory: String,
): Boolean {
    target.clearTo(UNDRAWN)
    val renderTarget = renderer.mintRenderTarget(FramebufferName(target.framebuffer))

    var note = ""
    val frame = try {
        runBlockingHarness { renderer.prepare(plan) }
    } catch (failure: RenGException) {
        note = " PREPARE FAILED ${failure.code} at ${failure.stage}"
        null
    }

    if (frame != null) {
        try {
            renderer.draw(frame, renderTarget)
        } catch (failure: RenGException) {
            note = " DRAW FAILED ${failure.code} at ${failure.stage}"
        } finally {
            frame.close()
        }
    }
    drainGlErrors("frame ${plan.frameIndex}")

    // The frame is written whichever way it went. A failed frame is the cleared target, which is
    // exactly what the owner needs to see in the sequence: a gap in the numbering would hide it from
    // ffmpeg, and a silently skipped frame is the one outcome a visual harness must never produce.
    val pixels = target.readPixels()
    val index = plan.frameIndex.toString().padStart(4, '0')
    writePpm("$outputDirectory/frame-$index.ppm", target.width, target.height, pixels)
    println("frame $index: ${describeFrame(pixels)}$note")
    return note.isEmpty()
}

/**
 * A one-line census of a frame, printed as it is written.
 *
 * Not a gate and not a baseline -- an operator's readout. "Undrawn" counts pixels still carrying
 * the harness's own clear colour, which is the single number that separates "RenG drew a map" from
 * "RenG drew nothing and the file looks plausible".
 */
private fun describeFrame(pixels: ByteArray): String {
    var undrawn = 0
    var red = 0L
    var green = 0L
    var blue = 0L
    val total = pixels.size / 4
    for (pixel in 0 until total) {
        val offset = pixel * 4
        val r = pixels[offset].toInt() and 0xff
        val g = pixels[offset + 1].toInt() and 0xff
        val b = pixels[offset + 2].toInt() and 0xff
        if (r == UNDRAWN[0] && g == UNDRAWN[1] && b == UNDRAWN[2]) undrawn += 1
        red += r
        green += g
        blue += b
    }
    val percent = (undrawn * 1000L / total) / 10.0
    return "mean rgb (${red / total}, ${green / total}, ${blue / total}), undrawn $percent%"
}

private class HarnessOptions(
    val configPath: String,
    val plansPath: String,
    val outputDirectory: String,
    val localPropertiesPath: String,
    val emitPlansTo: String?,
    val emitCorpusTo: String?,
    val verbose: Boolean,
)

/**
 * Three paths and a flag, and **an unrecognised argument is a hard failure**.
 *
 * The parser this replaced advanced `index += 2` unconditionally, so it read only even positions:
 * a valueless flag ahead of a value option hid that option entirely, and silently, because an
 * unmatched argument was merely skipped. `--globe` was emitted before `--zoom`, so every globe run
 * ignored `--zoom` and rendered at the storyboard's default 11.5 -- a zoom where the sphere is
 * larger than the viewport and a correct globe is indistinguishable from a flat map. The frames
 * looked like a renderer defect and were investigated as one, for hours.
 *
 * Two things here make that class of failure impossible rather than unlikely. The flag pile is gone,
 * so there is nothing left for a value to hide behind: what used to be nine flags is now a config
 * file and a plan file. And anything this parser does not recognise stops the run, so a
 * misremembered or misspelled argument can never again be read as "the default was wanted".
 */
private fun parseArguments(arguments: Array<String>): HarnessOptions? {
    var configPath = ""
    var plansPath = ""
    var outputDirectory = ""
    var localPropertiesPath = "local.properties"
    var emitPlansTo: String? = null
    var emitCorpusTo: String? = null
    var verbose = false

    var index = 0
    while (index < arguments.size) {
        val argument = arguments[index]
        val value = arguments.getOrNull(index + 1)
        fun requireValue(): String? {
            if (value == null || value.startsWith("--")) {
                println("$argument needs a value.")
                return null
            }
            return value
        }
        when (argument) {
            "--verbose" -> { verbose = true; index += 1 }
            "--config" -> { configPath = requireValue() ?: return null; index += 2 }
            "--plans" -> { plansPath = requireValue() ?: return null; index += 2 }
            "--out" -> { outputDirectory = requireValue() ?: return null; index += 2 }
            "--local-properties" -> { localPropertiesPath = requireValue() ?: return null; index += 2 }
            "--emit-plans" -> { emitPlansTo = requireValue() ?: return null; index += 2 }
            "--emit-corpus" -> { emitCorpusTo = requireValue() ?: return null; index += 2 }
            else -> {
                println(
                    "Unrecognised argument `$argument`.\n" +
                        "  --config <path>  --plans <path>  --out <directory>\n" +
                        "  --local-properties <path>  --emit-plans <path>  " +
                        "--emit-corpus <directory>  --verbose",
                )
                return null
            }
        }
    }

    if (emitPlansTo != null || emitCorpusTo != null) {
        return HarnessOptions(
            "", plansPath, "", localPropertiesPath, emitPlansTo, emitCorpusTo, verbose,
        )
    }
    if (configPath.isBlank()) {
        println("No config. Pass --config <path to a harness config json>.")
        return null
    }
    if (plansPath.isBlank()) {
        println("No plan file. Pass --plans <path to a json array of frame plans>.")
        return null
    }
    if (outputDirectory.isBlank()) {
        println("No output directory. Pass --out <directory>.")
        return null
    }
    return HarnessOptions(
        configPath, plansPath, outputDirectory, localPropertiesPath, null, null, verbose,
    )
}

/** Counts diagnostics by code and keeps the first few, so a warning storm prints as one line. */
private class DiagnosticTally : DiagnosticSink {
    private val counts = AtomicReference<Map<String, Int>>(emptyMap())
    private val samples = AtomicReference<List<String>>(emptyList())

    override fun emit(diagnostic: Diagnostic) {
        val label = "${diagnostic.severity} ${diagnostic.code}"
        while (true) {
            val current = counts.value
            if (counts.compareAndSet(current, current + (label to (current[label] ?: 0) + 1))) break
        }
        val detail = "$label stage=${diagnostic.stage} class=${diagnostic.resourceClass} " +
            "field=${diagnostic.fieldName} status=${diagnostic.statusCode} " +
            "limit=${diagnostic.limit} actual=${diagnostic.actual}"
        while (true) {
            val current = samples.value
            if (current.size >= 12 || detail in current) break
            if (samples.compareAndSet(current, current + detail)) break
        }
    }

    fun summary(): String =
        if (counts.value.isEmpty()) {
            "diagnostics: none"
        } else {
            "diagnostics: " + counts.value.entries.sortedBy { it.key }.joinToString(", ") {
                "${it.key} x${it.value}"
            }
        }

    fun samples(): List<String> = samples.value
}

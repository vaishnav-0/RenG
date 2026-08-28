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
private const val OUTPUT_WIDTH: Int = 960
private const val OUTPUT_HEIGHT: Int = 540

/**
 * What the capture target holds before RenG draws into it. A saturated colour nothing in a map
 * style would produce, so "RenG drew nothing here" reads as itself rather than as a dark basemap.
 */
private val UNDRAWN: IntArray = intArrayOf(0, 96, 32, 255)

fun main(arguments: Array<String>) {
    val options = parseArguments(arguments) ?: exitProcess(2)

    val context = CglCoreProfileContext.create()
    println("Render Context: " + describeCurrentContext())

    val transport = NsUrlTransport(embeddedImages(), options.verbose)
    val store = MemoryStore()
    val diagnostics = DiagnosticTally()

    val renderer = try {
        createRenderer(
            RendererConfiguration(
                outputPixelSize = OutputPixelSize(OUTPUT_WIDTH, OUTPUT_HEIGHT),
                transport = transport,
                store = store,
                basemapStyle = ResourceLocator(options.styleUrl),
                diagnosticSink = diagnostics,
            ),
        )
    } catch (failure: RenGException) {
        println("createRenderer failed: ${failure.code} at ${failure.stage}")
        context.destroy()
        exitProcess(1)
    }

    val target = CaptureTarget.create(OUTPUT_WIDTH, OUTPUT_HEIGHT)
    val started = TimeSource.Monotonic.markNow()
    var written = 0
    var failed = 0

    try {
        framePlans(options.groundless, options.modelUrl, options.labelless)
            .take(options.frameCount)
            .forEach { plan ->
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
    val styleUrl: String,
    val modelUrl: String?,
    val outputDirectory: String,
    val frameCount: Int,
    val groundless: Boolean,
    val labelless: Boolean,
    val verbose: Boolean,
)

private fun parseArguments(arguments: Array<String>): HarnessOptions? {
    var styleUrl = ""
    var modelUrl: String? = null
    var outputDirectory = ""
    var frameCount = FRAME_COUNT
    val groundless = arguments.contains("--no-basemap")
    val labelless = arguments.contains("--no-labels")
    val verbose = arguments.contains("--verbose")
    var index = 0
    while (index + 1 < arguments.size) {
        when (arguments[index]) {
            "--style" -> styleUrl = arguments[index + 1]
            "--model" -> modelUrl = arguments[index + 1].takeIf(String::isNotBlank)
            "--out" -> outputDirectory = arguments[index + 1]
            "--frames" -> frameCount = arguments[index + 1].toIntOrNull() ?: frameCount
        }
        index += 2
    }
    if (styleUrl.isBlank()) {
        println(
            "No style url. The owner's styles carry api keys, so none is checked in.\n" +
                "  ./gradlew -p consumer-smoke runHarness -PstyleUrl=<style url> [-PmodelUrl=<glb url>]\n" +
                "or set RENG_HARNESS_STYLE_URL in the environment.",
        )
        return null
    }
    if (outputDirectory.isBlank()) {
        println("No output directory. Pass --out <directory>.")
        return null
    }
    return HarnessOptions(styleUrl, modelUrl, outputDirectory, frameCount, groundless, labelless, verbose)
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

package com.rohittp.reng.internal.terrain

import com.rohittp.reng.internal.driver.validatesDemTerrainEncoding
import com.rohittp.reng.internal.image.PngDecodeResult
import com.rohittp.reng.internal.image.decodePng

/**
 * Which of the two DEM channel packings a terrain source declares, as **RenG's own** enum rather than
 * Rentile's `TerrainDemEncoding`.
 *
 * This follows the pattern the firewall already established for every other engine type RenG must
 * agree with: [com.rohittp.reng.internal.firewall.engineKeyedResourceClassOf] maps RenG's
 * [com.rohittp.reng.ResourceClass] onto `com.rohittp.rentile.ResourceClass` in one place, and
 * `BasemapEngineHost.engineTileIdOf` maps a `CanonicalBasemapTile` onto a `TileId` in one place, so no
 * Rentile type is named anywhere but `internal/firewall/`. Terrain decode is arithmetic over bytes and
 * has no business importing the engine's vocabulary to do it; the translation from
 * `TerrainDemEncoding` belongs beside the acquisition that produced it.
 *
 * The two constants are Rentile's own two and nothing more. A source declaring anything else never
 * reaches here — Rentile's style compiler rejects it before a descriptor exists.
 */
internal enum class DemEncoding {
    MAPBOX,
    TERRARIUM,
}

/**
 * Why [decodeDemElevation] refused a DEM tile's bytes. Each of the three is a check Rentile's
 * `ValidatedDemTile` does **not** perform, so each is genuinely RenG's to make:
 *
 * - [UNDECODABLE] — Rentile validates a DEM with `Image.makeFromEncoded`, which is multi-format, so
 *   "validated" does not mean PNG. RenG owns exactly one decoder and it is a PNG decoder.
 * - [DIMENSIONS] — Rentile checks dimensions against its *policy limits*, never against the
 *   `tileSizePx` its own `TerrainSourceDescriptor` declares, so a source can hand back an image of a
 *   size the caller's sampling arithmetic was never built for.
 * - [NON_OPAQUE] — Rentile decodes DEM pixels into a **premultiplied** N32 bitmap
 *   (`DefaultBasemapRasterizer.kt:1671-1672`), which silently scales R/G/B by alpha before any elevation
 *   formula reads them. A DEM with a translucent pixel is therefore not a DEM whose height RenG can
 *   state; `CONTEXT.md`'s **Terrain Sample** entry says the samples must be bit-exact with no
 *   premultiplication for exactly this reason.
 */
internal enum class DemReject {
    UNDECODABLE,
    DIMENSIONS,
    NON_OPAQUE,
}

/** The outcome of decoding one DEM tile's bytes into elevations. */
internal sealed interface DemDecodeResult {
    data class Success(val tile: DemElevationTile) : DemDecodeResult
    data class Rejected(val reason: DemReject) : DemDecodeResult
}

/**
 * One DEM tile's elevations in **metres**, one per texel, row-major with row `0` the image's first
 * PNG row — which under the XYZ scheme every RenG tile uses is the tile's **northern** edge. `x`
 * therefore increases eastward and `y` southward, matching [DemTileWindow]'s `v`.
 *
 * Metres, not logical pixels: the plan's standing obligation is that "elevation arrives in metres and
 * is converted once", and this is the arriving end. Whatever displaces the ground performs that
 * conversion; nothing here knows about a camera.
 *
 * Held as `Double` because the CPU side of the cycle (a ground-relative placement's height) is
 * `Double` arithmetic, and because the Terrarium formula's `blue / 256.0` term is exact in `Double`
 * for every input. The GPU never reads this — it decodes the same formula from the texture itself
 * (design §3) — so this array exists for the sparse CPU lookups of wave 2 and for the readback gates.
 */
internal class DemElevationTile(val sizePx: Int, metres: DoubleArray) {
    private val elevations: DoubleArray = metres.copyOf()

    init {
        require(sizePx > 0) { "a DEM tile has a positive edge length" }
        require(metres.size == sizePx * sizePx) { "a DEM tile carries exactly one elevation per texel" }
    }

    /** The elevation in metres at texel ([x], [y]), with `y = 0` the northern row. */
    fun elevationAt(x: Int, y: Int): Double {
        require(x in 0 until sizePx && y in 0 until sizePx) { "texel is outside the tile" }
        return elevations[y * sizePx + x]
    }

    /** A fresh copy, so a caller can never observe or corrupt what this holds. */
    fun elevationSnapshot(): DoubleArray = elevations.copyOf()
}

/**
 * The elevation one DEM texel encodes, in metres, for each of the two supported encodings.
 *
 * Both lines are transcribed from Rentile's own `heightAt`
 * (`internal/DefaultBasemapRasterizer.kt:1706-1709`) rather than from memory or from the Mapbox
 * documentation, because Rentile is the authority on what its acquired bytes mean:
 *
 * ```
 * MAPBOX    : -10000.0 + (red * 65536 + green * 256 + blue) * 0.1
 * TERRARIUM : red * 256.0 + green + blue / 256.0 - 32768.0
 * ```
 *
 * Rentile decodes elevation in that one private place and exposes it nowhere, so this is RenG's
 * only copy of it and — per design §5 — the one Task 8's shared GLSL fragment must agree with.
 *
 * Two boundary values are worth stating because they are what a swapped pair of formulas breaks
 * loudest: Mapbox `(0, 0, 0)` is exactly `-10000.0`, and Terrarium `(128, 0, 0)` is exactly `0.0`.
 * Neither is repaired: design §11 rules out no-data handling, so a Mapbox `(0, 0, 0)` really does mean
 * ten kilometres below the ellipsoid and that is the style's problem, not RenG's.
 *
 * The Mapbox packing cannot overflow `Int`: its largest value is `255 * 65536 + 255 * 256 + 255 =
 * 16_777_215`.
 */
internal fun demElevationMetres(red: Int, green: Int, blue: Int, encoding: DemEncoding): Double =
    when (encoding) {
        DemEncoding.MAPBOX -> -10_000.0 + (red * 65_536 + green * 256 + blue) * 0.1
        DemEncoding.TERRARIUM -> red * 256.0 + green + blue / 256.0 - 32_768.0
    }

/**
 * Decodes one acquired DEM tile's [bytes] into [DemElevationTile], or refuses them with the reason.
 *
 * **This is where the three checks Rentile's `ValidatedDemTile` does not make are made** — see
 * [DemReject] for what each one closes and why the type name promises more than it delivers.
 *
 * Opacity is delegated to
 * [validatesDemTerrainEncoding][com.rohittp.reng.internal.driver.validatesDemTerrainEncoding], the
 * function ADR 0016's write-path obligation already calls, rather than being reimplemented here: one
 * definition of "an eight-bit RGB terrain encoding" is the point, and a second copy of the alpha scan
 * could drift from the firewall's. Its `private` helper `isEightBitRgbTerrainEncoding` is what this
 * would rather call, since that takes an already-decoded image; calling the public wrapper instead
 * means **the bytes are inflated twice on the accepting path**. That cost is deliberate and recorded
 * rather than paid down by duplicating the scan: it is the same shape as F-2's per-frame GLB reparse,
 * and it becomes one decode the moment `isEightBitRgbTerrainEncoding` is widened to `internal`.
 *
 * Because [validatesDemTerrainEncoding] is exactly "decodes as PNG **and** every pixel is opaque", and
 * because this has already decoded these same bytes under this same ceiling immediately above, a
 * `false` here can only mean the alpha scan — which is why [DemReject.NON_OPAQUE] is an honest reason
 * rather than a guess.
 *
 * **Nothing here throws**, including for a descriptor declaring a non-positive `tileSizePx`: ADR 0041
 * makes terrain the one basemap resource that degrades instead of failing a frame, and a decode that
 * threw would take the frame with it. No guard is needed for that case — [decodePng] admits only
 * positive dimensions, so no decoded image can ever equal a non-positive `tileSizePx` and it lands in
 * [DemReject.DIMENSIONS] with the rest.
 *
 * [maximumDecodedBytes] is the caller's ceiling on the *declared* raster size, enforced by [decodePng]
 * from the PNG header alone before any array is allocated. RenG has no `maximumDecodedDemBytes`: the
 * limit a caller passes is `ResourceLimits.maximumDecodedImageBytes`, still shared with every raster
 * and every model texture, which is F-2's debt unchanged.
 */
internal fun decodeDemElevation(
    bytes: ByteArray,
    encoding: DemEncoding,
    tileSizePx: Int,
    maximumDecodedBytes: Long,
): DemDecodeResult {
    val image = (decodePng(bytes, maximumDecodedBytes) as? PngDecodeResult.Success)?.image
        ?: return DemDecodeResult.Rejected(DemReject.UNDECODABLE)
    if (image.width != tileSizePx || image.height != tileSizePx) {
        return DemDecodeResult.Rejected(DemReject.DIMENSIONS)
    }
    if (!validatesDemTerrainEncoding(bytes, maximumDecodedBytes)) {
        return DemDecodeResult.Rejected(DemReject.NON_OPAQUE)
    }

    val rgba = image.rgbaSnapshot()
    val metres = DoubleArray(tileSizePx * tileSizePx)
    var texel = 0
    while (texel < metres.size) {
        val channel = texel * 4
        metres[texel] = demElevationMetres(
            red = rgba[channel].toInt() and 0xFF,
            green = rgba[channel + 1].toInt() and 0xFF,
            blue = rgba[channel + 2].toInt() and 0xFF,
            encoding = encoding,
        )
        texel++
    }
    return DemDecodeResult.Success(DemElevationTile(tileSizePx, metres))
}

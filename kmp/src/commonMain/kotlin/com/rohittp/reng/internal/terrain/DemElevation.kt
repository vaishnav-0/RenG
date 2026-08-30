package com.rohittp.reng.internal.terrain

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
 * One DEM tile's elevations in **metres**, one per texel, row-major with row `0` the image's first
 * row — which is top-down from the tile's own top edge in both the engine's decoded texels and RenG's
 * canonical form, and which under the XYZ scheme every RenG tile uses is the tile's **northern**
 * edge. `x` therefore increases eastward and `y` southward, matching [DemTileWindow]'s `v`. Walking
 * these rows bottom-up mirrors every tile about its own centre line.
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
 * Turns one acquired DEM tile's already-decoded [texels] into elevations in metres, or `null` when
 * they are not a positive square this arithmetic describes.
 *
 * **The bytes are never inflated here, and were the last place in RenG that would have been.**
 * Rentile decodes a DEM to validate it and `0.7.0` keeps the result, so both of RenG's terrain paths
 * -- this one and [demTexelsOf]'s texture path -- read the same pixels the engine already produced.
 * What that deletes is not only a second decoder but a *format*: RenG owns a PNG decoder, and five of
 * the corpus's six terrain styles serve WebP.
 *
 * **The three checks this used to make are down to none, and each is accounted for rather than
 * dropped.** PNG-ness was RenG's own restriction and was the defect. Dimension agreement against the
 * source's declared `tileSizePx` is made once, at adoption, by [demTexelsOf] -- one authority for
 * both paths rather than the two this file used to describe. Opacity was a guard against Rentile's
 * old premultiplied bitmap; its texels are documented as never premultiplied, so a translucent
 * texel's R, G and B are still the values the DEM packed and this formula still reads the height the
 * tile encodes. What remains here is arithmetic, and the guard below is against a [DemTexels] some
 * future caller assembled rather than against anything an acquisition can produce.
 *
 * **Nothing here throws** (ADR 0041): terrain is the one basemap resource that degrades instead of
 * failing a frame, and [DemElevationTile]'s own `require`s would do exactly that.
 */
internal fun demElevationTile(texels: DemTexels, encoding: DemEncoding): DemElevationTile? {
    val image = texels.image
    val sizePx = image.width
    if (sizePx <= 0 || image.height != sizePx) return null
    val rgba = image.rgbaSnapshot()
    if (rgba.size.toLong() != sizePx.toLong() * sizePx.toLong() * 4L) return null

    val metres = DoubleArray(sizePx * sizePx)
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
    return DemElevationTile(sizePx, metres)
}

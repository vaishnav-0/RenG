package com.rohittp.reng.internal.terrain

import com.rohittp.reng.internal.driver.validatesDemTerrainEncoding
import com.rohittp.reng.internal.image.DecodedImage
import com.rohittp.reng.internal.image.PngDecodeResult
import com.rohittp.reng.internal.image.decodePng

/**
 * One DEM tile's texels in RenG's canonical decoded form, paired with the engine's own identity for
 * the bytes they came from.
 *
 * **The texels are the DEM's *encoded* channels, not elevations.** [decodeDemElevation] is the CPU
 * path -- wave 2's sparse lookups and the readback gates -- and it produces metres. This is the GPU
 * path, where the vertex shader decodes the same formula from the same RGB triple itself (design
 * section 3), so anything that rewrote a channel on the way to the texture would rewrite the height.
 * [DecodedImage] is exactly the right carrier for that: Cycle C's canonical form is tightly packed,
 * **unpremultiplied** RGBA8, which is what `CONTEXT.md`'s **Terrain Sample** entry demands when it
 * says the samples must be bit-exact with no premultiplication.
 *
 * [contentDigest] is Rentile's `ValidatedDemTile.contentDigest`, carried through
 * [com.rohittp.reng.internal.firewall.AcquiredDemTile]. It is here rather than alongside because
 * [PaddedDemTexture.contentKey] is composed from the digests of all nine tiles, and a padded texture
 * keyed on its centre alone is stale the moment an absent neighbour arrives -- see that property.
 */
internal class DemTexels(val image: DecodedImage, val contentDigest: String)

/** The outcome of decoding one DEM tile's bytes into the texels a texture is assembled from. */
internal sealed interface DemTexelDecodeResult {
    data class Success(val texels: DemTexels) : DemTexelDecodeResult
    data class Rejected(val reason: DemReject) : DemTexelDecodeResult
}

/**
 * Decodes one acquired DEM tile's [bytes] into canonical RGBA8 texels, or refuses them for one of
 * [DemReject]'s three reasons.
 *
 * **The same three checks [decodeDemElevation] makes, for the same reason and in the same order**:
 * Rentile's `ValidatedDemTile` covers status, size, that Skia decoded *something*, dimensions against
 * its own policy limits, and a digest -- not PNG-ness, not the `tileSizePx` its own
 * `TerrainSourceDescriptor` declares, and not opacity. Design section 10 puts all three on RenG's own
 * terrain decode, and there are two of those because the CPU wants metres and the GPU wants bytes.
 *
 * **This is the second caller of [validatesDemTerrainEncoding] and it inherits that function's cost**
 * -- one extra inflate of the accepting path's bytes, because the `private`
 * `isEightBitRgbTerrainEncoding` that would take the already-decoded image is not visible here.
 * [decodeDemElevation] records the same debt and the same one-line fix; duplicating the alpha scan
 * instead would put a third definition of "an eight-bit RGB terrain encoding" in the tree, free to
 * drift from the firewall's.
 *
 * **Nothing here throws** (ADR 0041): a decode that threw would take the frame with it, and terrain is
 * the one basemap resource that degrades to a picture RenG has already shipped.
 */
internal fun decodeDemTexels(
    bytes: ByteArray,
    tileSizePx: Int,
    maximumDecodedBytes: Long,
    contentDigest: String,
): DemTexelDecodeResult {
    val image = (decodePng(bytes, maximumDecodedBytes) as? PngDecodeResult.Success)?.image
        ?: return DemTexelDecodeResult.Rejected(DemReject.UNDECODABLE)
    if (image.width != tileSizePx || image.height != tileSizePx) {
        return DemTexelDecodeResult.Rejected(DemReject.DIMENSIONS)
    }
    if (!validatesDemTerrainEncoding(bytes, maximumDecodedBytes)) {
        return DemTexelDecodeResult.Rejected(DemReject.NON_OPAQUE)
    }
    return DemTexelDecodeResult.Success(DemTexels(image = image, contentDigest = contentDigest))
}

/**
 * The eight tiles whose texels a padded DEM borrows, named by compass direction.
 *
 * [deltaY] is **positive southward**, matching [DemTileWindow]'s `v` and the XYZ scheme's own row
 * order: row `0` of a tile's PNG is its northern row, so `NORTH` is `y - 1`. Getting that sign
 * backwards mirrors every tile about its own centre line, which is the failure mode
 * `GroundPipeline`'s KDoc already calls "invisible on a solid-coloured tile and catastrophic on a
 * real map".
 *
 * The four diagonals are members rather than an afterthought: a padded border needs its four corner
 * texels as much as its four edges, and the diagonal neighbour is the only tile that carries them.
 * [com.rohittp.reng.internal.firewall.terrainTileRequest] already asks for all eight.
 */
internal enum class DemNeighbour(val deltaX: Int, val deltaY: Int) {
    NORTH_WEST(-1, -1),
    NORTH(0, -1),
    NORTH_EAST(1, -1),
    WEST(-1, 0),
    EAST(1, 0),
    SOUTH_WEST(-1, 1),
    SOUTH(0, 1),
    SOUTH_EAST(1, 1),
}

/**
 * Why one of [PaddedDemTexture]'s eight border sources is the centre tile's own edge rather than a
 * neighbour's.
 *
 * **The fill itself is edge replication in every case**: the padded texel takes the centre tile's
 * nearest own texel, which is precisely what `GL_CLAMP_TO_EDGE` would have produced with no ring at
 * all. It is the only fill that invents no elevation -- every alternative writes a height the DEM
 * does not contain, and at eight-bit channel resolution every such height is a cliff. A Mapbox
 * `(0, 0, 0)` sentinel would be a ten-kilometre trench around every coverage gap and along both
 * world edges of every terrain frame; a "sea level" sentinel needs a different triple per encoding
 * and still steps to zero from whatever the edge really was. Replication continues the tile's own
 * slope-free plateau outward by one texel, which is the smallest lie available.
 *
 * **A replicated ring is still a lie, and this enum is what stops it being mistaken for real
 * elevation.** It is not distinguishable *in the texels* -- deliberately, since anything
 * distinguishable there would be visible relief the style never declared -- so it is distinguishable
 * in the value instead: [PaddedDemTexture.filledNeighbours] names every direction that was filled and
 * which of these two reasons applied, and [PaddedDemTexture.contentKey] carries the same distinction
 * so a filled texture can never be reused under the key of a complete one.
 */
internal enum class DemNeighbourFill {

    /**
     * No such tile exists anywhere: the neighbour's `y` is outside `0 until 2^z`, which is the world
     * north of +85.0511 degrees and south of -85.0511 degrees. Structural and permanent, so it is
     * **not** a coverage gap and must not be counted as one by ADR 0041's
     * `TERRAIN_COVERAGE_INCOMPLETE`, which would then fire on every frame whose visible set touches
     * the top or bottom row of the world.
     *
     * `x` never lands here: the world is a cylinder, so an out-of-range `x` wraps to a tile that does
     * exist, exactly as `terrainTileRequest` and Rentile's own `RasterSample.neighbor` wrap it.
     */
    WORLD_EDGE,

    /**
     * The tile exists and RenG does not have it: Rentile dropped it below the source's `minimumZoom`
     * or outside its `bounds` through `mapNotNull` with no diagnostic, its decode was refused, or the
     * frame never asked for it. This is the case ADR 0041's coverage diagnostic reports.
     *
     * A neighbour whose edge length disagrees with the centre's also lands here. [decodeDemTexels]
     * makes that unreachable for tiles that came through it -- every one is exactly `tileSizePx`
     * square -- and folding it in means a future caller that mixes sizes degrades rather than throws.
     */
    ABSENT,
}

/**
 * One DEM tile widened to `(N+2)` square, with a one-texel ring copied from its eight neighbours.
 *
 * **Why the ring exists is the whole of this file.** The DEM grid is *edge-exclusive*: texel `i` is
 * centred at `(i + 0.5)/N`, so a tile boundary sits half a texel **outside** the outermost texel
 * centre and is represented by no texel in either tile. Rentile's own `heightAt` treats pixel `-1` of
 * one tile as pixel `W-1` of its neighbour. Clamp each ground tile to its own data and the two sides
 * of every shared edge displace to different heights -- a crack in the terrain at every tile
 * boundary, in every frame. This is mapbox-gl-js's solution, and design section 4 is where it was
 * decided.
 *
 * **What "agree" means, precisely, and it is a statement about texels rather than about a sampler.**
 * Take a tile `T` and its eastern neighbour `E`, both padded. The two texels straddling their shared
 * boundary are, read out of `T`'s padded image, `(T[N-1], E[0])` -- interior column `N-1` then the
 * east ring -- and read out of `E`'s padded image, `(T[N-1], E[0])` -- the west ring then interior
 * column `0`. **The same ordered pair, from both sides.** A vertex on the boundary therefore reads an
 * identical source value whichever tile's texture it samples, for any sampling rule that depends only
 * on position relative to the texel grid, which is every rule GL has. That is what makes the crack
 * close; nothing about it needs the two draws to round the same way.
 *
 * The interior is the centre tile's own texels, unaltered and in their own order: padded texel
 * `(x + 1, y + 1)` is the tile's `(x, y)`. An interior written at `(x, y)` instead shifts the whole
 * DEM half a tile north-west against the ground it displaces.
 *
 * [image] is uploaded by [com.rohittp.reng.internal.gl.uploadDemTexture], which is also where the
 * `GL_NEAREST` obligation is stated and enforced.
 */
internal class PaddedDemTexture(
    /** `N`: the edge length of the centre tile the ring was built around, in texels. */
    val interiorSizePx: Int,
    /** The `(N+2)` square RGBA8 texels, ring included, in canonical unpremultiplied form. */
    val image: DecodedImage,
    /**
     * Every direction whose border texels came from the centre tile's own edge, and why -- see
     * [DemNeighbourFill]. Empty for a tile in the interior of a complete coverage, which is the
     * common case.
     */
    val filledNeighbours: Map<DemNeighbour, DemNeighbourFill>,
    /**
     * A complete content identity for these `(N+2)` square texels: the centre tile's engine digest
     * followed by all eight neighbours', each replaced by its [DemNeighbourFill] where one applied.
     *
     * **Keying a padded texture on its centre digest alone is the bug this property exists to
     * prevent, and it is invisible.** Frame one has centre `C` with its western neighbour absent, so
     * `C`'s west ring is replicated. Frame two acquires that neighbour. The centre's bytes did not
     * change, so a centre-only key hits the resident texture and the frame draws the *replicated*
     * ring -- a crack at that edge, in a frame that has everything it needs to close it, for as long
     * as the texture stays resident. Composing the ring's provenance into the key makes the second
     * frame a miss, which is the correct answer.
     *
     * **No tile coordinate is in it, deliberately.** Two centres with identical bytes and identical
     * neighbour bytes assemble byte-identical textures and should share one; a coordinate would split
     * them for no gain. The encoding is absent for the same reason -- it decides what a triple
     * *means*, in the shader, and changes no texel here.
     *
     * The caller derives a [com.rohittp.reng.ResourceKey] from this, as `uploadGlyphAtlas`'s caller
     * derives one from `LabelGlyphAtlas.contentKey`; deriving a canonical identity is the acquiring
     * caller's job under ADR 0018 and this file neither derives nor validates one.
     */
    val contentKey: String,
) {
    override fun toString(): String =
        "PaddedDemTexture(interior=$interiorSizePx, padded=${image.width}, filled=${filledNeighbours.size})"
}

/**
 * Assembles the padded texture for [centre] out of [texelsByTile], or `null` when [centre] itself is
 * not in it.
 *
 * **[texelsByTile] is keyed by *source* tile, not by requested tile, and that is not a detail.** Under
 * overzoom several requested tiles share one DEM image, and
 * [demTileWindowFor] is what maps a requested tile onto its sub-rectangle of that image at sampling
 * time. The texture is therefore the *source* tile's, its ring is the source tile's neighbours, and a
 * ring texel is reached only by a requested tile sitting against the source tile's own boundary --
 * whose neighbouring request lands in the neighbouring source tile, which is exactly what
 * [com.rohittp.reng.internal.firewall.terrainTileRequest]'s 3x3 expansion guarantees is asked for.
 *
 * `null` rather than a throw for an unknown [centre], and a fill rather than a `null` for an unknown
 * neighbour: ADR 0041 makes terrain degrade instead of failing a frame, so a tile RenG has no DEM for
 * draws flat and a tile whose neighbour is missing draws its own relief with a replicated edge.
 *
 * A neighbour's `x` **wraps** and its `y` is **clipped**, exactly as
 * `terrainTileRequest` and Rentile's own `RasterSample.neighbor` do it: the world is a cylinder in
 * `x` and ends in `y`. Kotlin's `%` keeps the dividend's sign, so wrapping needs a floored modulus and
 * `-1 % 4 == -1` is the trap.
 *
 * Zooms outside `0..30` answer `null`. That is far above `demTileWindowFor`'s ceiling of 22 -- it is
 * not a policy limit but the bound that keeps `1L shl z` an honest shift rather than one Kotlin has
 * masked to 63, and that keeps a world dimension comparable against an `Int` tile coordinate.
 */
internal fun padDemTexture(
    centre: DemTileCoordinate,
    texelsByTile: Map<DemTileCoordinate, DemTexels>,
): PaddedDemTexture? {
    if (centre.z !in 0..MAXIMUM_PADDED_DEM_ZOOM) return null
    val centreTexels = texelsByTile[centre] ?: return null
    val interior = centreTexels.image.width
    if (interior <= 0 || centreTexels.image.height != interior) return null

    val dimension = 1L shl centre.z
    val fills = LinkedHashMap<DemNeighbour, DemNeighbourFill>()
    val sources = LinkedHashMap<DemNeighbour, DemTexels>()
    DemNeighbour.entries.forEach { neighbour ->
        val neighbourY = centre.y.toLong() + neighbour.deltaY
        if (neighbourY !in 0 until dimension) {
            fills[neighbour] = DemNeighbourFill.WORLD_EDGE
            return@forEach
        }
        val coordinate = DemTileCoordinate(
            z = centre.z,
            x = floorModOf(centre.x.toLong() + neighbour.deltaX, dimension).toInt(),
            y = neighbourY.toInt(),
        )
        val texels = texelsByTile[coordinate]
        if (texels == null || texels.image.width != interior || texels.image.height != interior) {
            fills[neighbour] = DemNeighbourFill.ABSENT
        } else {
            sources[neighbour] = texels
        }
    }

    val padded = assemblePaddedTexels(centreTexels.image, interior, sources)
    return PaddedDemTexture(
        interiorSizePx = interior,
        image = DecodedImage(width = interior + 2, height = interior + 2, rgba = padded),
        filledNeighbours = fills,
        contentKey = paddedDemContentKey(centreTexels, sources, fills),
    )
}

/**
 * The `(N+2)` square RGBA8 bytes: [centre]'s texels in the middle, and one texel of each present
 * neighbour around them.
 *
 * Each of the four edges is written by its own statement rather than by one general per-texel rule,
 * because each is its own opportunity to take the wrong column of the right tile or the right column
 * of the wrong tile, and a shared expression hides both. The **east** ring takes the eastern
 * neighbour's *western*most column (`x = 0`) and the **west** ring takes the western neighbour's
 * *eastern*most column (`x = N-1`); a ring taken from the centre's own edge column instead reads
 * plausibly, compiles, renders, and reopens the crack the ring exists to close. The four corners go
 * through [copyCorner], which states its geometry once and takes a direction.
 */
private fun assemblePaddedTexels(
    centre: DecodedImage,
    interior: Int,
    sources: Map<DemNeighbour, DemTexels>,
): ByteArray {
    val padded = interior + 2
    val centreBytes = centre.rgbaSnapshot()
    val out = ByteArray(padded * padded * RGBA_CHANNELS)

    for (y in 0 until interior) {
        val from = y * interior * RGBA_CHANNELS
        centreBytes.copyInto(
            destination = out,
            destinationOffset = texelOffset(1, y + 1, padded),
            startIndex = from,
            endIndex = from + interior * RGBA_CHANNELS,
        )
    }

    val north = sources[DemNeighbour.NORTH]?.image?.rgbaSnapshot()
    val south = sources[DemNeighbour.SOUTH]?.image?.rgbaSnapshot()
    val west = sources[DemNeighbour.WEST]?.image?.rgbaSnapshot()
    val east = sources[DemNeighbour.EAST]?.image?.rgbaSnapshot()

    // The northern neighbour's southernmost row becomes the row above this tile's own first, and the
    // southern neighbour's northernmost row becomes the row below its last. An absent neighbour
    // replicates the centre's own facing row instead -- see [DemNeighbourFill].
    copyRow(
        source = north ?: centreBytes,
        sourceY = if (north != null) interior - 1 else 0,
        interior = interior,
        out = out,
        outX = 1,
        outY = 0,
        padded = padded,
    )
    copyRow(
        source = south ?: centreBytes,
        sourceY = if (south != null) 0 else interior - 1,
        interior = interior,
        out = out,
        outX = 1,
        outY = padded - 1,
        padded = padded,
    )

    for (y in 0 until interior) {
        copyTexel(
            source = west ?: centreBytes,
            sourceX = if (west != null) interior - 1 else 0,
            sourceY = y,
            interior = interior,
            out = out,
            outX = 0,
            outY = y + 1,
            padded = padded,
        )
        copyTexel(
            source = east ?: centreBytes,
            sourceX = if (east != null) 0 else interior - 1,
            sourceY = y,
            interior = interior,
            out = out,
            outX = padded - 1,
            outY = y + 1,
            padded = padded,
        )
    }

    copyCorner(DemNeighbour.NORTH_WEST, sources, centreBytes, interior, out)
    copyCorner(DemNeighbour.NORTH_EAST, sources, centreBytes, interior, out)
    copyCorner(DemNeighbour.SOUTH_WEST, sources, centreBytes, interior, out)
    copyCorner(DemNeighbour.SOUTH_EAST, sources, centreBytes, interior, out)
    return out
}

/** Copies `interior` texels of row [sourceY] into [out] starting at ([outX], [outY]). */
private fun copyRow(
    source: ByteArray,
    sourceY: Int,
    interior: Int,
    out: ByteArray,
    outX: Int,
    outY: Int,
    padded: Int,
) {
    val from = sourceY * interior * RGBA_CHANNELS
    source.copyInto(
        destination = out,
        destinationOffset = texelOffset(outX, outY, padded),
        startIndex = from,
        endIndex = from + interior * RGBA_CHANNELS,
    )
}

/** Copies the single texel ([sourceX], [sourceY]) into [out] at ([outX], [outY]). */
private fun copyTexel(
    source: ByteArray,
    sourceX: Int,
    sourceY: Int,
    interior: Int,
    out: ByteArray,
    outX: Int,
    outY: Int,
    padded: Int,
) {
    val from = (sourceY * interior + sourceX) * RGBA_CHANNELS
    source.copyInto(
        destination = out,
        destinationOffset = texelOffset(outX, outY, padded),
        startIndex = from,
        endIndex = from + RGBA_CHANNELS,
    )
}

/**
 * Copies the one corner texel [diagonal] contributes: **that neighbour's corner facing this tile**
 * when it is present -- the north-west tile's *south-east* texel is the one sitting above and to the
 * left of this tile's own `(0, 0)` -- and otherwise the centre tile's own corner in that direction,
 * which is the texel `GL_CLAMP_TO_EDGE` would have reached.
 *
 * All six indices follow from [DemNeighbour.deltaX] and [DemNeighbour.deltaY] rather than being
 * written out four times, so the only thing a call site states, and the only thing a reader can check
 * by eye, is **which diagonal each corner belongs to**. That is also the one mistake here that no
 * axis-aligned assertion can see: a corner taken from the wrong diagonal is a single texel from a
 * real neighbouring tile, sitting where no edge assertion looks.
 */
private fun copyCorner(
    diagonal: DemNeighbour,
    sources: Map<DemNeighbour, DemTexels>,
    centreBytes: ByteArray,
    interior: Int,
    out: ByteArray,
) {
    val padded = interior + 2
    val westward = diagonal.deltaX < 0
    val northward = diagonal.deltaY < 0
    val neighbour = sources[diagonal]
    // Facing: the neighbour's texel nearest this tile. Own: the centre's own corner in the same
    // direction, which is what a missing diagonal replicates.
    val facingX = if (westward) interior - 1 else 0
    val facingY = if (northward) interior - 1 else 0
    val ownX = if (westward) 0 else interior - 1
    val ownY = if (northward) 0 else interior - 1
    copyTexel(
        source = neighbour?.image?.rgbaSnapshot() ?: centreBytes,
        sourceX = if (neighbour != null) facingX else ownX,
        sourceY = if (neighbour != null) facingY else ownY,
        interior = interior,
        out = out,
        outX = if (westward) 0 else padded - 1,
        outY = if (northward) 0 else padded - 1,
        padded = padded,
    )
}

/** See [PaddedDemTexture.contentKey]: the centre's digest, then all eight neighbours' in enum order. */
private fun paddedDemContentKey(
    centre: DemTexels,
    sources: Map<DemNeighbour, DemTexels>,
    fills: Map<DemNeighbour, DemNeighbourFill>,
): String = buildString {
    append("dem-padded-v1|")
    append(centre.contentDigest)
    DemNeighbour.entries.forEach { neighbour ->
        append('|')
        append(neighbour.name)
        append('=')
        append(sources[neighbour]?.contentDigest ?: "fill:${fills.getValue(neighbour).name}")
    }
}

private fun texelOffset(x: Int, y: Int, width: Int): Int = (y * width + x) * RGBA_CHANNELS

/** RGBA8: four bytes a texel, which is Cycle C's one canonical decoded form and the upload's format. */
private const val RGBA_CHANNELS: Int = 4

/** See [padDemTexture]: an arithmetic bound on the shift, not a zoom policy. */
private const val MAXIMUM_PADDED_DEM_ZOOM: Int = 30

/** Kotlin's `%` keeps the dividend's sign; a cylindrical world needs the divisor's. */
private fun floorModOf(value: Long, divisor: Long): Long {
    val remainder = value % divisor
    return if (remainder < 0L) remainder + divisor else remainder
}

package com.rohittp.reng.internal.firewall

import com.rohittp.reng.ConcurrentRecorder
import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.Store
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.Transport
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.TransportResponseMetadata
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import com.rohittp.reng.internal.resource.ResourceRouteKey

/*
 * The Label handover fixture: one style, one vector tile, three Glyph Ranges.
 *
 * **Deliberately asymmetric, and that is the whole point.** Two symbol layers over two different
 * source layers, with two different font stacks, and text spanning two 256-codepoint blocks:
 *
 *  - `place`, `text-font: ["Label Sans Regular"]`, feature `name` = `AA` with a macron -- U+0041 in
 *    block `0-255` and U+0100 in block `256-511`;
 *  - `town_label`, `text-font: ["Label Serif Regular"]`, feature `name` = `B` -- block `0-255`.
 *
 * Three `(stack, block)` pairs out of two features. A closure returning one range per stack, or one per
 * feature, or the right count from the wrong blocks, fails here. A single-range fixture cannot even
 * tell an ordered list from a set, which is exactly the property the set-equality assertions rest on.
 *
 * **The style carries a credential**, `?key=...`, because the one thing `glyphUrls` structurally cannot
 * check is the credential: it compares only redacted template forms. A fixture whose glyphs template
 * had no query at all would make [LABEL_STALE_GLYPH_TEMPLATE] indistinguishable from a plain template
 * mismatch, and the stale-credential path is the one this cycle has to name.
 *
 * **The style must carry `glyphs` at its root**, or `planLabelCandidates` short-circuits to an empty
 * closure and every assertion downstream passes vacuously. The lower-bound assertion -- three urls,
 * against a literal expected set -- is what stops that, and it is why it is written as an exact set
 * rather than as "not empty".
 *
 * The bytes are hand-encoded below rather than taken from a real provider (route 2 of the handover
 * spike's three), and cross-validated against Rentile's own Wire-generated decoders in
 * `LabelHandoverWireCrossCheckTest` (route 1). Neither format needs an encoder -- only the six protobuf
 * constructs these two schemas use.
 */

internal const val LABEL_STYLE_BASE_URI: String = "https://styles.example/labels.json"
internal const val LABEL_TILE_URL: String = "https://tiles.example/l/1/0/0.pbf"
internal const val LABEL_TILE_TEMPLATE: String = "https://tiles.example/l/{z}/{x}/{y}.pbf"

/** The provider credential the fixture's style declares. Redacted to `key=<redacted>` by both sides. */
internal const val LABEL_GLYPH_KEY: String = "reng-live-key"

/** A different credential with an identical redacted form -- see [LABEL_STALE_GLYPH_TEMPLATE]. */
internal const val LABEL_STALE_GLYPH_KEY: String = "reng-stale-key"

internal const val LABEL_GLYPH_TEMPLATE: String =
    "https://glyphs.example/{fontstack}/{range}.pbf?key=$LABEL_GLYPH_KEY"

/**
 * The same template with a stale credential. `withRedactedAuthenticationQuery` rewrites `key=` on both,
 * so the two agree exactly where `glyphUrls` looks and differ exactly where the acquisition does.
 */
internal const val LABEL_STALE_GLYPH_TEMPLATE: String =
    "https://glyphs.example/{fontstack}/{range}.pbf?key=$LABEL_STALE_GLYPH_KEY"

/** A template whose *redacted* form differs, which is the disagreement `glyphUrls` does catch. */
internal const val LABEL_FOREIGN_GLYPH_TEMPLATE: String =
    "https://elsewhere.example/{fontstack}/{range}.pbf?key=$LABEL_GLYPH_KEY"

internal const val LABEL_SANS_STACK: String = "Label Sans Regular"
internal const val LABEL_SERIF_STACK: String = "Label Serif Regular"

/** `TileId(z = 1, x = 0, y = 0)`, the tile the fixture's point features sit in the middle of. */
internal val LABEL_TILE: CanonicalBasemapTile = CanonicalBasemapTile(lod = 1, tileY = 0, canonicalX = 0)

internal val LABEL_STYLE_JSON: String =
    """{"version":8,"name":"reng-label-handover",""" +
        """"glyphs":"$LABEL_GLYPH_TEMPLATE",""" +
        """"sources":{"v":{"type":"vector","tiles":["$LABEL_TILE_TEMPLATE"],"minzoom":0,"maxzoom":14}},""" +
        """"layers":[""" +
        """{"id":"place","type":"symbol","source":"v","source-layer":"place",""" +
        """"layout":{"text-field":"{name}","text-font":["$LABEL_SANS_STACK"],"text-size":16}},""" +
        """{"id":"town","type":"symbol","source":"v","source-layer":"town_label",""" +
        """"layout":{"text-field":"{name}","text-font":["$LABEL_SERIF_STACK"],"text-size":16}}""" +
        """]}"""

/** The same style with its `glyphs` key removed: a legal style that plans an empty closure. */
internal val LABEL_STYLE_JSON_WITHOUT_GLYPHS: String =
    """{"version":8,"name":"reng-label-handover-no-glyphs",""" +
        """"sources":{"v":{"type":"vector","tiles":["$LABEL_TILE_TEMPLATE"],"minzoom":0,"maxzoom":14}},""" +
        """"layers":[""" +
        """{"id":"place","type":"symbol","source":"v","source-layer":"place",""" +
        """"layout":{"text-field":"{name}","text-font":["$LABEL_SANS_STACK"],"text-size":16}}""" +
        """]}"""

internal val labelStyleKey: ResourceKey = ResourceKeyDeriver(PureKotlinSha256)
    .external(ResourceClass.BASEMAP_STYLE, ResourceLocator(LABEL_STYLE_BASE_URI))
    .key

internal val labelTileRoute: ResourceRouteKey = ResourceRouteKey(
    accessMode = ResourceAccessMode.NORMAL,
    locator = ResourceLocator(LABEL_TILE_URL),
    resourceClass = ResourceClass.BASEMAP_VECTOR_TILE,
    maximumResponseBytes = ResourceLimits().maximumBasemapTileBytes,
)

/**
 * The exact three urls the acquisition asks for, composed the way Rentile composes them: `{fontstack}`
 * percent-encoded over UTF-8 with `,` and the RFC 3986 unreserved set kept, `{range}` as `start-end`,
 * and everything else -- the credential included -- left exactly as the caller's template had it.
 *
 * Written out as literals rather than derived, because a derivation would be the composition under
 * test restated. The order is Rentile's own: `(fontStack, rangeStart)` ascending.
 */
internal fun labelGlyphUrls(key: String = LABEL_GLYPH_KEY): List<String> = listOf(
    "https://glyphs.example/Label%20Sans%20Regular/0-255.pbf?key=$key",
    "https://glyphs.example/Label%20Sans%20Regular/256-511.pbf?key=$key",
    "https://glyphs.example/Label%20Serif%20Regular/0-255.pbf?key=$key",
)

// ---- the transport and store the handover runs against ---------------------------------------

/**
 * Answers the fixture's vector tile and its three Glyph Ranges, and 404s everything else, recording
 * every url it was asked for.
 *
 * [ConcurrentRecorder] rather than a plain list: Rentile acquires the ranges as concurrent
 * `Dispatchers.Default` children, so a `MutableList` genuinely loses entries here.
 */
internal class LabelRecordingTransport(
    private val glyphKey: String = LABEL_GLYPH_KEY,
) : Transport {
    private val urls = ConcurrentRecorder<String>()
    private val classes = ConcurrentRecorder<ResourceClass>()

    suspend fun requestedUrls(): List<String> = urls.snapshot()

    suspend fun requestedClasses(): List<ResourceClass> = classes.snapshot()

    /** Everything asked for that was not the label tile: the glyph half of the exchange. */
    suspend fun requestedGlyphUrls(): List<String> = urls.snapshot().filter { it != LABEL_TILE_URL }

    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        urls.record(url)
        classes.record(request.resourceClass)
        val body = when (url) {
            LABEL_TILE_URL -> LABEL_MVT_BYTES
            labelGlyphUrls(glyphKey)[0] -> LABEL_SANS_RANGE_0_BYTES
            labelGlyphUrls(glyphKey)[1] -> LABEL_SANS_RANGE_256_BYTES
            labelGlyphUrls(glyphKey)[2] -> LABEL_SERIF_RANGE_0_BYTES
            else -> null
        } ?: return TransportResponse(statusCode = 404, body = ByteArray(0))
        return TransportResponse(
            statusCode = 200,
            body = body,
            metadata = TransportResponseMetadata(contentType = "application/x-protobuf"),
        )
    }
}

/** A store that answers every read as a miss and accepts every write, counting both. */
internal class LabelRecordingStore : Store {
    private val readKeys = ConcurrentRecorder<RawResourceKey>()
    private val writeKeys = ConcurrentRecorder<RawResourceKey>()

    suspend fun readClasses(): List<ResourceClass> = readKeys.snapshot().map { it.resourceClass }

    suspend fun writtenClasses(): List<ResourceClass> = writeKeys.snapshot().map { it.resourceClass }

    override suspend fun read(key: RawResourceKey): StoredRawResource? {
        readKeys.record(key)
        return null
    }

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        writeKeys.record(key)
    }
}

// ---- hand-encoded protobuf --------------------------------------------------------------------

/**
 * The six protobuf wire constructs the MVT and glyph schemas need, and nothing else. No format
 * knowledge lives here -- [labelMvtLayer] and [labelGlyphRange] supply all of it.
 */
internal class ProtoBuffer {
    private val out = mutableListOf<Byte>()

    fun bytes(): ByteArray = out.toByteArray()

    fun varint(value: Long): ProtoBuffer {
        var remaining = value
        while (true) {
            val septet = (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
            if (remaining == 0L) {
                out += septet.toByte()
                return this
            }
            out += (septet or 0x80).toByte()
        }
    }

    fun tag(field: Int, wireType: Int): ProtoBuffer = varint(((field shl 3) or wireType).toLong())

    fun varintField(field: Int, value: Long): ProtoBuffer = tag(field, 0).varint(value)

    fun bytesField(field: Int, value: ByteArray): ProtoBuffer {
        tag(field, 2).varint(value.size.toLong())
        out += value.toList()
        return this
    }

    fun stringField(field: Int, value: String): ProtoBuffer = bytesField(field, value.encodeToByteArray())

    fun messageField(field: Int, build: ProtoBuffer.() -> Unit): ProtoBuffer =
        bytesField(field, ProtoBuffer().apply(build).bytes())

    fun packedVarints(field: Int, values: List<Long>): ProtoBuffer {
        val packed = ProtoBuffer()
        values.forEach { packed.varint(it) }
        return bytesField(field, packed.bytes())
    }
}

/** MVT's zig-zag encoding for a geometry coordinate delta. */
private fun zigZag(value: Int): Long = ((value shl 1) xor (value shr 31)).toLong()

/** An MVT geometry command integer: the low three bits are the command, the rest the repeat count. */
private fun command(id: Int, count: Int): Long = ((count shl 3) or id).toLong()

/**
 * One MVT layer holding one point feature with a single `name` property.
 *
 * The anchor sits at `(2048, 2048)` of a 4096 extent -- mid-tile. That is not decoration: Rentile's
 * assembler attributes a point outside `[0, extent)` to a different tile and silently produces no
 * candidate at all, which is the second-easiest way to write a vacuous label fixture.
 */
private fun ProtoBuffer.labelMvtLayer(name: String, text: String) {
    varintField(15, 2L)
    stringField(1, name)
    messageField(2) {
        varintField(1, 1L)
        packedVarints(2, listOf(0L, 0L))
        varintField(3, 1L)
        packedVarints(4, listOf(command(1, 1), zigZag(2048), zigZag(2048)))
    }
    stringField(3, "name")
    messageField(4) { stringField(1, text) }
    varintField(5, 4096L)
}

/**
 * A vector tile of one point feature per named source layer, each carrying the text given for it.
 *
 * Parameterised because the text is the only thing some fixtures need to vary -- a style whose layers
 * are unchanged still loses every label when the *feature* text is a script the engine cannot shape,
 * which is what `runLabelIntegrationReadbackSuite` uses it for -- and because a second hand-written
 * protobuf encoder beside this one is exactly the duplication that lets a bug ship in one copy.
 */
internal fun labelMvtBytes(vararg layers: Pair<String, String>): ByteArray =
    ProtoBuffer()
        .apply { layers.forEach { (name, text) -> messageField(3) { labelMvtLayer(name, text) } } }
        .bytes()

/** The fixture's vector tile: two layers, two features, two font stacks, two codepoint blocks. */
internal val LABEL_MVT_BYTES: ByteArray = labelMvtBytes("place" to "AĀ", "town_label" to "B")

/**
 * One glyph, at a declared extent whose buffered bitmap is exactly `(width + 6) * (height + 6)` bytes
 * -- Rentile's decoder rejects any other size, and the three-pixel signed-distance-field buffer on
 * every side is what makes the packed cell larger than the declared glyph.
 *
 * [field] is the signed distance at each texel of that buffered cell. The default ramps across
 * `64..191`, which is what the routing cases here want: distinct, asymmetric bytes that survive a
 * round trip. It is a **parameter** because the fill edge sits at `0.75` of the byte range (191.25),
 * so that default never reaches full coverage in the fragment shader -- a suite that needs an
 * unambiguously opaque glyph pixel on a real driver passes a saturated field instead. See
 * `runLabelIntegrationReadbackSuite`.
 */
internal fun ProtoBuffer.labelGlyph(
    codepoint: Int,
    width: Int,
    height: Int,
    field: (Int) -> Byte = { index -> (64 + index % 128).toByte() },
) {
    varintField(1, codepoint.toLong())
    bytesField(2, ByteArray((width + 6) * (height + 6), field))
    varintField(3, width.toLong())
    varintField(4, height.toLong())
    varintField(5, zigZag(1))
    varintField(6, zigZag(-12))
    varintField(7, 12L)
}

/** One Glyph Range protobuf: exactly one font stack, whose `range` the decoder checks against the url. */
internal fun labelGlyphRange(
    stack: String,
    range: String,
    codepoints: List<Int>,
    field: (Int) -> Byte = { index -> (64 + index % 128).toByte() },
): ByteArray =
    ProtoBuffer()
        .apply {
            messageField(1) {
                stringField(1, stack)
                stringField(2, range)
                codepoints.forEach { codepoint -> messageField(3) { labelGlyph(codepoint, 8, 10, field) } }
            }
        }
        .bytes()

internal val LABEL_SANS_RANGE_0_BYTES: ByteArray = labelGlyphRange(LABEL_SANS_STACK, "0-255", listOf(65))
internal val LABEL_SANS_RANGE_256_BYTES: ByteArray = labelGlyphRange(LABEL_SANS_STACK, "256-511", listOf(256))
internal val LABEL_SERIF_RANGE_0_BYTES: ByteArray = labelGlyphRange(LABEL_SERIF_STACK, "0-255", listOf(66))

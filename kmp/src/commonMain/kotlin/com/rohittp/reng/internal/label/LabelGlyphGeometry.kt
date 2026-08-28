package com.rohittp.reng.internal.label

import com.rohittp.reng.internal.gl.ResolvedGlyphQuad
import com.rohittp.reng.internal.gl.ResolvedLabelPaint
import com.rohittp.rentile.LabelGlyphAtlas
import com.rohittp.rentile.LabelGlyphQuad

/**
 * The one piece of geometry both placement modes share: turning one engine glyph into one screen
 * quad, given the frame that glyph is drawn in.
 *
 * **The frame is the only difference between a point label and a line label.** A point label gives
 * every one of its glyphs the same frame -- origin at the projected anchor, axis at the label's own
 * rotation -- so the row stays a rigid rotated row. A line label gives each glyph its *own* frame,
 * sampled where that glyph sits along the projected polyline, so the row bends. Nothing else in
 * either path differs, which is what [ResolvedGlyphQuad]'s four-arbitrary-corners seam was for: a
 * bent glyph is different numbers, not a different pipeline.
 */
internal class GlyphFrame(
    val originX: Double,
    val originY: Double,
    /**
     * The unit direction label-local **+x** maps to on screen, and with it the direction
     * label-local +y maps to: the two are locked at a quarter turn clockwise in `CONTEXT.md`'s
     * y-down output-pixel space, so a frame cannot shear, mirror or scale a glyph. For a point
     * label this is `(cos, sin)` of the label's rotation; for a line label it is the polyline's
     * unit tangent, negated when `keepUpright` reads the line backwards.
     */
    val axisX: Double,
    val axisY: Double,
    /**
     * The label-local x this frame's [originX]/[originY] stands at. Zero for a point label, whose
     * origin is the label anchor at local x = 0; the glyph's own centre for a line label, whose
     * origin is the point on the polyline that glyph sits at.
     */
    val pivotLocalX: Double,
)

/** Label-local `(x, y)` in this frame, on screen: `origin + (x - pivot) * axis + y * normal`. */
private fun GlyphFrame.screenX(x: Double, y: Double): Double =
    originX + (x - pivotLocalX) * axisX - y * axisY

/** See [screenX]. The normal is the axis turned a quarter clockwise, which is `(-axisY, axisX)`. */
private fun GlyphFrame.screenY(x: Double, y: Double): Double =
    originY + (x - pivotLocalX) * axisY + y * axisX

/**
 * One validated glyph: its cell in label-local coordinates and its cell in normalised atlas
 * coordinates, with every check that can reject it already made.
 *
 * Separated from [resolveQuad] because line placement needs [left] and [right] -- the glyph's own
 * extent along the label -- *before* it can decide where on the polyline to sample the frame from,
 * and re-deriving them there would be a second chance to disagree with the quad.
 */
internal class GlyphCell(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val u0: Float,
    val v0: Float,
    val u1: Float,
    val v1: Float,
    val scale: Float,
) {
    /** The label-local x this glyph turns about when it is placed on a curve. */
    val centreLocalX: Double get() = (left + right) / 2.0
}

/**
 * The glyph's cell, or `null` when it is not drawable.
 *
 * Every rejection here drops one label rather than failing the frame, for the reason
 * [com.rohittp.reng.internal.projection.projectGeographicPosition] is total: these numbers cross
 * the firewall from the engine, and a frame that fails because one label of several hundred carried
 * an unusable glyph would make `drawLabels = true` a coin toss.
 */
internal fun LabelGlyphAtlas.glyphCellOrNull(glyph: LabelGlyphQuad): GlyphCell? {
    // A batch with no glyphs at all carries a zero-extent atlas, which is legal; a candidate that
    // references one is not, and dividing by it would make every atlas coordinate infinite.
    if (width <= 0 || height <= 0) return null
    val entry = entries.getOrNull(glyph.entryIndex) ?: return null
    val scale = glyph.scale.toFloat()
    if (!(scale > 0.0f) || !scale.isFinite()) return null
    if (!glyph.x.isFinite() || !glyph.y.isFinite()) return null

    val atlasWidth = width.toFloat()
    val atlasHeight = height.toFloat()
    return GlyphCell(
        left = glyph.x,
        top = glyph.y,
        right = glyph.x + entry.width * glyph.scale,
        bottom = glyph.y + entry.height * glyph.scale,
        u0 = entry.x / atlasWidth,
        v0 = entry.y / atlasHeight,
        u1 = (entry.x + entry.width) / atlasWidth,
        v1 = (entry.y + entry.height) / atlasHeight,
        scale = scale,
    )
}

/**
 * This cell's four screen corners in [ResolvedGlyphQuad]'s documented order -- top-left, top-right,
 * bottom-right, bottom-left -- with the atlas coordinates of the same four corners beside them.
 *
 * `null` when any corner leaves `Float` range. A non-finite corner is worse than a missing label:
 * it compares false against every bound, so it would survive the viewport cull, collide with
 * nothing, and reach the GPU as a NaN vertex.
 */
internal fun GlyphCell.resolveQuad(frame: GlyphFrame, paint: ResolvedLabelPaint): ResolvedGlyphQuad? {
    val cornersXy = floatArrayOf(
        frame.screenX(left, top).toFloat(), frame.screenY(left, top).toFloat(),
        frame.screenX(right, top).toFloat(), frame.screenY(right, top).toFloat(),
        frame.screenX(right, bottom).toFloat(), frame.screenY(right, bottom).toFloat(),
        frame.screenX(left, bottom).toFloat(), frame.screenY(left, bottom).toFloat(),
    )
    if (cornersXy.any { !it.isFinite() }) return null

    return ResolvedGlyphQuad(
        cornersXy = cornersXy,
        cornersUv = floatArrayOf(u0, v0, u1, v0, u1, v1, u0, v1),
        paint = paint,
    )
}

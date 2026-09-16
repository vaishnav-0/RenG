package com.rohittp.reng.internal.gl

/**
 * A [GlBinding] that saves a texture unit's prior bindings the first time a draw overwrites them,
 * and puts back exactly those (ADR 0055).
 *
 * This replaces walking a fixed fifteen units up front, which cost 30 queries and 62 writes every
 * frame regardless of content where a measured frame touches two -- and, more importantly, had a
 * ceiling, leaving a pass binding a unit above it unprotected exactly as units 1..14 once were.
 * Saving inside the call that overwrites has no ceiling to get wrong: a unit is saved because it is
 * being written, not because someone predicted it would be.
 *
 * Delegation is `by delegate` on purpose — the three methods that move a texture unit are overridden
 * and the other eighty-eight are forwarded by the compiler, so a method added to [GlBinding] later
 * cannot silently bypass this.
 */
internal class TextureUnitCapturingBinding(
    private val delegate: GlBinding,
) : GlBinding by delegate {

    /**
     * The active unit as a `GL_TEXTUREi` token, tracked rather than queried.
     *
     * Seeded with one query at construction — the only unconditional query this class makes — because
     * a draw may bind a texture without making a unit active first, and that bind lands on whatever
     * the host left active.
     */
    private var activeUnit: Int = delegate.activeTextureToken()

    private val originalActiveUnit: Int = activeUnit

    /** Prior state per `GL_TEXTUREi` token, in the order the draw first touched them. */
    private val saved: LinkedHashMap<Int, GlTextureUnitState> = LinkedHashMap()

    override fun activeTexture(texture: Int) {
        activeUnit = texture
        delegate.activeTexture(texture)
    }

    override fun bindTexture(target: Int, texture: Int) {
        // Only GL_TEXTURE_2D is part of the Restore Set, and it is the only target RenG binds.
        if (target == GL_TEXTURE_2D) rememberUnit(activeUnit)
        delegate.bindTexture(target, texture)
    }

    override fun bindSampler(unit: Int, sampler: Int) {
        rememberUnit(GL_TEXTURE0 + unit)
        delegate.bindSampler(unit, sampler)
    }

    /**
     * Saves [unitToken]'s bindings unless they are already saved.
     *
     * Reading a unit's bindings requires it to be active. RenG always binds a sampler to the unit it
     * has just made active, so the switch below is not taken in practice — it is written rather than
     * asserted because "RenG only ever does X with texture units" is the class of claim ADR 0055
     * exists to stop this file relying on.
     */
    private fun rememberUnit(unitToken: Int) {
        if (saved.containsKey(unitToken)) return
        val restoreActive = unitToken != activeUnit
        if (restoreActive) delegate.activeTexture(unitToken)
        saved[unitToken] = GlTextureUnitState(
            unit = unitToken,
            texture2d = delegate.queryInt(GL_TEXTURE_BINDING_2D),
            sampler = delegate.queryInt(GL_SAMPLER_BINDING),
        )
        if (restoreActive) delegate.activeTexture(activeUnit)
    }

    /**
     * Puts back every unit this draw overwrote, then the unit that was active when it began.
     *
     * The active unit is restored last for the same reason [captureGlState] reinstated it last: each
     * per-unit restore below has to make its own unit active to reach it.
     */
    fun restoreTouchedUnits() {
        saved.values.forEach { unit ->
            delegate.activeTexture(unit.unit)
            delegate.bindTexture(GL_TEXTURE_2D, unit.texture2d)
            delegate.bindSampler(unit.unit - GL_TEXTURE0, unit.sampler)
        }
        delegate.activeTexture(originalActiveUnit)
        saved.clear()
        activeUnit = originalActiveUnit
    }

    /** How many units this draw has overwritten so far. Read by tests, and by nothing else. */
    val touchedUnitCount: Int get() = saved.size
}

private fun GlBinding.activeTextureToken(): Int = queryInt(GL_ACTIVE_TEXTURE)

private fun GlBinding.queryInt(pname: Int): Int {
    val out = IntArray(1)
    getIntegerv(pname, out)
    return out.single()
}

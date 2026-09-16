package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceKey
import com.rohittp.reng.internal.shader.ShaderProfilePlan

internal class GlProgramCache {
    private val programs: LinkedHashMap<ResourceKey, Int> = LinkedHashMap()

    internal fun getOrCompile(
        binding: GlBinding,
        dialect: ShaderDialect,
        key: ResourceKey,
        vertexPlan: ShaderProfilePlan,
        fragmentPlan: ShaderProfilePlan,
        consumerAuthored: Boolean = false,
        infoLogObserver: ShaderInfoLogObserver = ShaderInfoLogObserver { _, _ -> },
    ): GlProgramResult {
        programs[key]?.let { return GlProgramResult.Linked(it) }
        val result = compileShaderProgram(
            binding, dialect, key, vertexPlan, fragmentPlan, consumerAuthored, infoLogObserver,
        )
        if (result is GlProgramResult.Linked) programs[key] = result.program
        return result
    }

    internal fun program(key: ResourceKey): Int? = programs[key]

    internal fun keys(): List<ResourceKey> = ArrayList(programs.keys)

    internal fun remove(key: ResourceKey): Int? = programs.remove(key)

    /** Drops every cached program name without issuing a delete, for declared GPU object loss. */
    internal fun forgetAll() {
        programs.clear()
    }
}

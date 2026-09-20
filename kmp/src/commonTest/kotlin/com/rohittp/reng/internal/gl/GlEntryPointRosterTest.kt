package com.rohittp.reng.internal.gl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlEntryPointRosterTest {
    @Test fun rosterHasExactlyNinetyTwoEntryPoints() {
        assertEquals(92, GlEntryPoint.entries.size)
    }

    @Test fun theRosterContainsTheThreeUniformSettersTheShaderInterfaceNeeds() {
        val cNames = GlEntryPoint.entries.map { it.cName }
        assertTrue("glUniform2f" in cNames)
        assertTrue("glUniform3f" in cNames)
        assertTrue("glUniform1ui" in cNames)
    }

    @Test fun theRosterContainsTheFourUniformBlockEntryPointsSkinningNeeds() {
        val cNames = GlEntryPoint.entries.map { it.cName }
        assertTrue("glBindBufferBase" in cNames)
        assertTrue("glGetUniformBlockIndex" in cNames)
        assertTrue("glUniformBlockBinding" in cNames)
        assertTrue("glGetIntegeri_v" in cNames)
    }

    @Test fun everyCNameIsDistinctAndWellFormed() {
        val names = GlEntryPoint.entries.map { it.cName }
        assertEquals(names.size, names.toSet().size)
        names.forEach { name ->
            assertTrue(name.startsWith("gl"), "entry point $name must be a GL C name")
            assertTrue(name.length > 2 && name[2].isUpperCase(), "entry point $name is malformed")
            // The character class admits '_' alongside alphanumerics: glGetIntegeri_v is the real C
            // name for this entry point (the indexed variant of glGetIntegerv), and renaming it to
            // satisfy a stricter class would make the roster lie about the symbol it resolves.
            assertTrue(name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' })
        }
    }

    @Test fun rosterOrderIsStableForOrdinalIndexedTables() {
        assertEquals(GlEntryPoint.GET_ERROR, GlEntryPoint.entries.first())
        assertEquals("glGetError", GlEntryPoint.GET_ERROR.cName)
        assertEquals("glGetStringi", GlEntryPoint.GET_STRINGI.cName)
        assertEquals("glDepthRangef", GlEntryPoint.DEPTH_RANGEF.cName)
        assertEquals("glTexStorage2D", GlEntryPoint.TEX_STORAGE_2D.cName)
    }

    @Test fun tokensThatDifferBetweenDialectsAreNotFolded() {
        assertEquals(0x8DB9, GL_FRAMEBUFFER_SRGB)
        assertEquals(0x0C01, GL_DRAW_BUFFER)
        assertEquals(0x0B20, GL_LINE_SMOOTH)
        assertEquals(0x0CF5, GL_UNPACK_ALIGNMENT)
        assertEquals(0x0D05, GL_PACK_ALIGNMENT)
        assertEquals(0x821D, GL_NUM_EXTENSIONS)
        assertEquals(0x1F03, GL_EXTENSIONS)
        assertEquals(0x2700, GL_NEAREST_MIPMAP_NEAREST)
        assertEquals(0x2701, GL_LINEAR_MIPMAP_NEAREST)
        assertEquals(0x2702, GL_NEAREST_MIPMAP_LINEAR)
        assertEquals(0x2703, GL_LINEAR_MIPMAP_LINEAR)
        assertEquals(0x2901, GL_REPEAT)
    }
}

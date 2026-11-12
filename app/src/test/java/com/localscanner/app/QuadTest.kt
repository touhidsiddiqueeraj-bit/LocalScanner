package com.localscanner.app

import com.localscanner.app.domain.Pt
import com.localscanner.app.domain.QuadF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuadTest {

    @Test
    fun `full frame quad has area 1`() {
        assertEquals(1f, QuadF.fullFrame().area(), 0.0001f)
    }

    @Test
    fun `ordered fixes arbitrary corner input`() {
        val shuffled = QuadF.ordered(
            listOf(
                Pt(1f, 1f),  // br
                Pt(0f, 0f),  // tl
                Pt(1f, 0f),  // tr
                Pt(0f, 1f),  // bl
            ),
        )
        assertNotNull(shuffled)
        assertEquals(Pt(0f, 0f), shuffled!!.tl)
        assertEquals(Pt(1f, 0f), shuffled.tr)
        assertEquals(Pt(1f, 1f), shuffled.br)
        assertEquals(Pt(0f, 1f), shuffled.bl)
    }

    @Test
    fun `ordered rejects fewer than four points`() {
        assertNull(QuadF.ordered(listOf(Pt(0f, 0f), Pt(1f, 1f))))
    }

    @Test
    fun `small quads are not plausible documents`() {
        val tiny = QuadF(
            Pt(0.4f, 0.4f), Pt(0.5f, 0.4f), Pt(0.5f, 0.5f), Pt(0.4f, 0.5f),
        )
        assertFalse(tiny.isPlausible(0.10f))
    }

    @Test
    fun `large quads are plausible documents`() {
        assertTrue(QuadF.fullFrame().isPlausible(0.10f))
    }

    @Test
    fun `centroid distance works`() {
        val a = QuadF.fullFrame()
        val b = QuadF(Pt(0.1f, 0.1f), Pt(1.1f, 0.1f), Pt(1.1f, 1.1f), Pt(0.1f, 1.1f))
        assertEquals(0.1414f, QuadF.centroidDistance(a, b), 0.001f)
    }
}

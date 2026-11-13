package com.localscanner.app

import com.localscanner.app.domain.LineF
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

    // ----- convexity -----------------------------------------------------

    @Test
    fun `rectangle is convex`() {
        assertTrue(QuadF.fullFrame().isConvex())
    }

    @Test
    fun `bowtie quad is not convex`() {
        val bowtie = QuadF(Pt(0f, 0f), Pt(0f, 1f), Pt(1f, 0f), Pt(1f, 1f))
        assertFalse(bowtie.isConvex())
    }

    @Test
    fun `quad with folded corner is not convex`() {
        // TL corner bent inwards creates a reflex angle
        val bent = QuadF(Pt(0.1f, 0.1f), Pt(0.9f, 0f), Pt(1f, 1f), Pt(0f, 1f))
        // this one is still convex-ish; use a real reflex shape instead
        val reflex = QuadF(Pt(0.2f, 0.2f), Pt(0.5f, 0.8f), Pt(0.8f, 0.2f), Pt(0.8f, 0.9f))
        assertFalse(reflex.isConvex())
        assertTrue(bent.isConvex())
    }

    // ----- angle sanity --------------------------------------------------

    @Test
    fun `rectangle has near zero angle cos`() {
        assertTrue(QuadF.fullFrame().maxAngleCos() < 0.01f)
    }

    @Test
    fun `razor thin quad has high angle cos`() {
        // arrowhead-like shape with a very sharp interior angle at (0.99, 0.02)
        val sharp = QuadF(Pt(0f, 0f), Pt(1f, 0f), Pt(0.99f, 0.02f), Pt(0f, 1f))
        assertTrue(sharp.maxAngleCos() > 0.8f)
        // a slightly skewed document-like quad stays well below
        val doc = QuadF(Pt(0.05f, 0.1f), Pt(0.95f, 0.05f), Pt(0.98f, 0.9f), Pt(0.02f, 0.95f))
        assertTrue(doc.maxAngleCos() < 0.5f)
    }

    // ----- polygon to quad (bent corner) ---------------------------------

    @Test
    fun `five point polygon with bent corner yields clean quad`() {
        // Paper outline with a small fold on the top-right corner
        val bent = listOf(
            Pt(0.10f, 0.12f),  // TL
            Pt(0.88f, 0.10f),  // TR main
            Pt(0.90f, 0.14f),  // folded vertex (slightly inset)
            Pt(0.91f, 0.90f),  // BR
            Pt(0.09f, 0.91f),  // BL
        )
        val quad = QuadF.fromPolygon(bent)
        assertNotNull(quad)
        assertTrue(quad!!.isConvex())
        // The quad must be close to the real paper bounds, not shrink to the fold
        assertTrue(quad.area() > 0.6f)
    }

    @Test
    fun `four point polygon passes through ordering`() {
        val quad = QuadF.fromPolygon(
            listOf(Pt(1f, 1f), Pt(0f, 0f), Pt(1f, 0f), Pt(0f, 1f)),
        )
        assertNotNull(quad)
        assertEquals(Pt(0f, 0f), quad!!.tl)
    }

    @Test
    fun `polygon with wrong point count is rejected`() {
        assertNull(QuadF.fromPolygon(listOf(Pt(0f, 0f), Pt(1f, 0f), Pt(0f, 1f))))
    }

    // ----- inset fallback rectangle ---------------------------------------

    @Test
    fun `inset frame leaves a margin on every side`() {
        val q = QuadF.insetFrame(0.08f)
        assertEquals(0.08f, q.tl.x, 0.001f)
        assertEquals(0.08f, q.tl.y, 0.001f)
        assertEquals(0.92f, q.br.x, 0.001f)
        assertEquals(0.92f, q.br.y, 0.001f)
        assertEquals(0.7056f, q.area(), 0.001f)
    }

    // ----- line fit and intersection (edge snap) ---------------------------

    @Test
    fun `line fit recovers a straight edge`() {
        val pts = (0 until 10).map { Pt(100f + it * 10f, 50f + it * 2f) }
        val line = LineF.fit(pts)
        assertNotNull(line)
        // direction is nearly (10, 2) normalized
        val dx = line!!.dx
        val dy = line.dy
        assertEquals(2f / 10f, dy / dx, 0.01f)
    }

    @Test
    fun `perpendicular lines intersect at the corner`() {
        val horizontal = LineF(0f, 100f, 1f, 0f)
        val vertical = LineF(200f, 0f, 0f, 1f)
        val corner = horizontal.intersect(vertical)
        assertNotNull(corner)
        assertEquals(200f, corner!!.x, 0.01f)
        assertEquals(100f, corner.y, 0.01f)
    }

    @Test
    fun `parallel lines do not intersect`() {
        val a = LineF(0f, 0f, 1f, 0f)
        val b = LineF(0f, 5f, 1f, 0f)
        assertNull(a.intersect(b))
    }
}

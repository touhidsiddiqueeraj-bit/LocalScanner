package com.localscanner.app.domain

/**
 * A lightweight point in pure Kotlin (no Android dependencies) so quad
 * geometry can be unit tested on the JVM.
 */
data class Pt(val x: Float, val y: Float)

/**
 * A document quad in image coordinates, ordered as top-left, top-right,
 * bottom-right, bottom-left.
 */
data class QuadF(
    val tl: Pt,
    val tr: Pt,
    val br: Pt,
    val bl: Pt,
) {
    fun points(): List<Pt> = listOf(tl, tr, br, bl)

    fun centroid(): Pt = Pt(
        (tl.x + tr.x + br.x + bl.x) / 4f,
        (tl.y + tr.y + br.y + bl.y) / 4f,
    )

    /** Shoelace formula. */
    fun area(): Float {
        val p = points()
        var sum = 0f
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            sum += a.x * b.y - b.x * a.y
        }
        return Math.abs(sum) / 2f
    }

    fun perimeter(): Float {
        val p = points()
        var sum = 0f
        for (i in p.indices) {
            sum += dist(p[i], p[(i + 1) % p.size])
        }
        return sum
    }

    private fun dist(a: Pt, b: Pt): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    /**
     * Strict convexity check: every turn must keep the same winding sign.
     * Self-intersecting ("bowtie") or collinear quads fail.
     */
    fun isConvex(): Boolean {
        val p = points()
        var sign = 0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            val c = p[(i + 2) % p.size]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (Math.abs(cross) < 1e-6f) return false
            val s = if (cross > 0f) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /**
     * Largest |cos| of the four interior angles: ~0 for right angles, ~1 for
     * degenerate/sharp corners. Used to reject razor-thin "quads" that are
     * clearly not documents.
     */
    fun maxAngleCos(): Float {
        val p = points()
        var m = 0f
        for (i in 0 until p.size) {
            val prev = p[(i + p.size - 1) % p.size]
            val v = p[i]
            val next = p[(i + 1) % p.size]
            val v1x = prev.x - v.x
            val v1y = prev.y - v.y
            val v2x = next.x - v.x
            val v2y = next.y - v.y
            val d1 = Math.sqrt((v1x * v1x + v1y * v1y).toDouble()).toFloat()
            val d2 = Math.sqrt((v2x * v2x + v2y * v2y).toDouble()).toFloat()
            if (d1 <= 1e-6f || d2 <= 1e-6f) return 1f
            val cos = (v1x * v2x + v1y * v2y) / (d1 * d2)
            if (Math.abs(cos) > m) m = Math.abs(cos)
        }
        return m
    }

    /**
     * A quad is considered a plausible document when it covers a reasonable
     * share of the image and its corners are roughly in the right order.
     */
    fun isPlausible(minAreaRatio: Float = 0.10f): Boolean {
        if (area() < minAreaRatio) return false
        if (perimeter() <= 0f) return false
        // Normalised corner sanity: tl above br, tr above bl (allow slack).
        val ySlack = 0.35f
        if (tl.y > br.y + ySlack || tr.y > bl.y + ySlack) return false
        return true
    }

    /** Midpoint of the edge starting at corner [i] (0=top, 1=right, 2=bottom, 3=left). */
    fun edgeMidpoint(i: Int): Pt {
        val p = points()
        val a = p[i]
        val b = p[(i + 1) % 4]
        return Pt((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    }

    /**
     * CamScanner-style edge drag: corner [i] and its clockwise neighbour move
     * together, translated along the edge's normal by the perpendicular
     * component of (dx, dy). Returns null when the move would invert or
     * degenerate the quad.
     */
    fun movedEdge(i: Int, dx: Float, dy: Float, minArea: Float = 0.008f): QuadF? {
        val p = points()
        val a = p[i]
        val b = p[(i + 1) % 4]
        val ex = b.x - a.x
        val ey = b.y - a.y
        val len = Math.sqrt((ex * ex + ey * ey).toDouble()).toFloat()
        if (len < 1e-6f) return null
        // Unit normal pointing left of the edge direction.
        val nx = -ey / len
        val ny = ex / len
        val proj = dx * nx + dy * ny
        val tx = nx * proj
        val ty = ny * proj

        fun clamp(v: Pt): Pt = Pt(v.x + tx, v.y + ty)
        val moved = when (i) {
            0 -> copy(tl = clamp(tl), tr = clamp(tr))
            1 -> copy(tr = clamp(tr), br = clamp(br))
            2 -> copy(br = clamp(br), bl = clamp(bl))
            else -> copy(bl = clamp(bl), tl = clamp(tl))
        }
        return moved.validIf(minArea)
    }

    /**
     * Corner drag with a degeneracy guard: returns null when the move would
     * collapse or twist the quad past usability.
     */
    fun movedCorner(i: Int, dx: Float, dy: Float, minArea: Float = 0.008f): QuadF? {
        fun mv(p: Pt) = Pt(p.x + dx, p.y + dy)
        val moved = when (i) {
            0 -> copy(tl = mv(tl))
            1 -> copy(tr = mv(tr))
            2 -> copy(br = mv(br))
            else -> copy(bl = mv(bl))
        }
        return moved.validIf(minArea)
    }

    private fun validIf(minArea: Float): QuadF? {
        val pts = points()
        // Every point must stay inside the image bounds.
        pts.forEach {
            if (it.x < -0.02f || it.x > 1.02f || it.y < -0.02f || it.y > 1.02f) return null
        }
        if (area() < minArea) return null
        // Reject fully twisted quads (both horizontal edges inverted, or the
        // whole quad vertically flipped). Mild skew stays allowed.
        if (pts[0].x >= pts[1].x && pts[3].x >= pts[2].x) return null
        if (pts[0].y >= pts[3].y && pts[1].y >= pts[2].y) return null
        return this
    }

    companion object {
        /** Orders four arbitrary points into TL/TR/BR/BL. */
        fun ordered(points: List<Pt>): QuadF? {
            if (points.size != 4) return null
            val sumSorted = points.sortedBy { it.x + it.y }
            val tl = sumSorted.first()
            val br = sumSorted.last()
            val rest = points.filter { it != tl && it != br }
            val diffSorted = rest.sortedBy { it.y - it.x }
            val tr = diffSorted.first()
            val bl = diffSorted.last()
            if (tl == br || tr == bl) return null
            return QuadF(tl, tr, br, bl)
        }

        /**
         * Builds a quad from an approximated contour polygon.
         * Handles the classic "bent corner" failure: when approxPolyDP yields
         * 5 points (curled paper corner), every 4-point subset is tried and
         * the largest convex quad wins - removing the folded vertex produces
         * the biggest clean rectangle.
         */
        fun fromPolygon(points: List<Pt>): QuadF? = when (points.size) {
            4 -> ordered(points)
            5 -> {
                var best: QuadF? = null
                for (i in points.indices) {
                    val subset = points.filterIndexed { idx, _ -> idx != i }
                    val q = ordered(subset) ?: continue
                    if (!q.isConvex()) continue
                    if (best == null || q.area() > best.area()) best = q
                }
                best
            }
            else -> null
        }

        fun fullFrame(): QuadF = QuadF(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f))

        /**
         * Default selection rectangle inset by [margin] on every side, used
         * when automatic detection fails (never silently select the whole
         * frame - the user gets an obvious starting point to adjust).
         */
        fun insetFrame(margin: Float): QuadF {
            val m = margin.coerceIn(0f, 0.45f)
            return QuadF(Pt(m, m), Pt(1f - m, m), Pt(1f - m, 1f - m), Pt(m, 1f - m))
        }

        /** Distance between quad centroids - used for capture stability. */
        fun centroidDistance(a: QuadF, b: QuadF): Float {
            val ca = a.centroid()
            val cb = b.centroid()
            val dx = ca.x - cb.x
            val dy = ca.y - cb.y
            return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }
    }
}

/**
 * Infinite line represented by a point and a direction, with a
 * total-least-squares fit through a point cloud. Used by the gradient
 * edge-snap refinement to intersect refined edges into precise corners.
 */
data class LineF(val px: Float, val py: Float, val dx: Float, val dy: Float) {

    /** Intersection with [other]; null when near-parallel. */
    fun intersect(other: LineF): Pt? {
        val denom = dx * other.dy - dy * other.dx
        if (Math.abs(denom) < 1e-9f) return null
        val t = ((other.px - px) * other.dy - (other.py - py) * other.dx) / denom
        return Pt(px + t * dx, py + t * dy)
    }

    companion object {
        /**
         * Total-least-squares line through [points]: the line direction is
         * the principal eigenvector of the covariance matrix.
         */
        fun fit(points: List<Pt>): LineF? {
            if (points.size < 2) return null
            val n = points.size
            val cx = points.sumOf { it.x.toDouble() } / n
            val cy = points.sumOf { it.y.toDouble() } / n
            var sxx = 0.0
            var syy = 0.0
            var sxy = 0.0
            for (p in points) {
                val dx = p.x - cx
                val dy = p.y - cy
                sxx += dx * dx
                syy += dy * dy
                sxy += dx * dy
            }
            val theta = 0.5 * Math.atan2(2.0 * sxy, sxx - syy)
            return LineF(
                cx.toFloat(),
                cy.toFloat(),
                Math.cos(theta).toFloat(),
                Math.sin(theta).toFloat(),
            )
        }
    }
}

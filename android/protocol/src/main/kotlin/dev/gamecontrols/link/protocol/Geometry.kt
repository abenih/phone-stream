package dev.gamecontrols.link.protocol

import kotlin.math.floor
import kotlin.math.min

data class Point(val x: Float, val y: Float)

data class Rect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right get() = left + width
    val bottom get() = top + height
}

data class Geometry(
    val displayWidth: Int,
    val displayHeight: Int,
    val encodedWidth: Int,
    val encodedHeight: Int,
    val rotation: Int,
    val epoch: Int = 1,
) {
    init {
        require(displayWidth in 1..8192 && displayHeight in 1..8192)
        require(encodedWidth in 2..1920 && encodedHeight in 2..1920 &&
            encodedWidth % 2 == 0 && encodedHeight % 2 == 0)
        require(rotation in setOf(0, 90, 180, 270) && epoch > 0)
    }

    fun videoRect(viewWidth: Int, viewHeight: Int): Rect {
        require(viewWidth > 0 && viewHeight > 0)
        return fit(encodedWidth, encodedHeight, Rect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat()))
    }

    fun contentRect(viewWidth: Int, viewHeight: Int) =
        fit(displayWidth, displayHeight, videoRect(viewWidth, viewHeight))

    fun mapTouch(x: Float, y: Float, viewWidth: Int, viewHeight: Int): Point? {
        if (!x.isFinite() || !y.isFinite() || viewWidth <= 0 || viewHeight <= 0) return null
        val rect = contentRect(viewWidth, viewHeight)
        if (x < rect.left || x > rect.right || y < rect.top || y > rect.bottom) return null
        return Point((x - rect.left) / rect.width, (y - rect.top) / rect.height)
    }

    fun hostPoint(normalized: Point): Point {
        require(normalized.x.isFinite() && normalized.y.isFinite() &&
            normalized.x in 0f..1f && normalized.y in 0f..1f)
        // MediaProjection has already rotated the pixels. Rotating input again would be incorrect.
        return Point(normalized.x * (displayWidth - 1), normalized.y * (displayHeight - 1))
    }

    private fun fit(width: Int, height: Int, outer: Rect): Rect {
        val scale = min(outer.width / width, outer.height / height)
        val fittedWidth = width * scale
        val fittedHeight = height * scale
        return Rect(outer.left + (outer.width - fittedWidth) / 2,
            outer.top + (outer.height - fittedHeight) / 2, fittedWidth, fittedHeight)
    }
}

object EncodeSize {
    fun fit(
        width: Int, height: Int, longEdge: Int = 1280, widthAlignment: Int = 2, heightAlignment: Int = 2,
    ): Pair<Int, Int> {
        require(width in 2..8192 && height in 2..8192 && longEdge in 64..1920)
        require(widthAlignment in 1..64 && heightAlignment in 1..64)
        val aw = if (widthAlignment % 2 == 0) widthAlignment else widthAlignment * 2
        val ah = if (heightAlignment % 2 == 0) heightAlignment else heightAlignment * 2
        val scale = min(1.0, longEdge.toDouble() / maxOf(width, height))
        val divisor = gcd(width, height)
        val baseWidth = width / divisor
        val baseHeight = height / divisor
        val multipleW = aw / gcd(aw, baseWidth)
        val multipleH = ah / gcd(ah, baseHeight)
        val multiple = multipleW / gcd(multipleW, multipleH) * multipleH
        val units = floor(min(width * scale / baseWidth, height * scale / baseHeight) / multiple).toInt() * multiple
        if (units > 0) return baseWidth * units to baseHeight * units
        val fittedWidth = floor(width * scale / aw).toInt() * aw
        val fittedHeight = floor(height * scale / ah).toInt() * ah
        require(fittedWidth >= aw && fittedHeight >= ah) { "Display aspect ratio is unsupported." }
        return fittedWidth to fittedHeight
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}

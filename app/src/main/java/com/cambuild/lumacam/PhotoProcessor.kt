package com.cambuild.lumacam

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.roundToInt

/**
 * Low-allocation, offline color/exposure enhancement.
 *
 * The previous algorithm kept five full-sized pixel buffers and allocated an
 * IntArray and a closure for every pixel in a second sharpening pass.
 * This implementation uses only a source and result array, plus a tiny tone
 * lookup table. The work is always launched from Dispatchers.Default.
 */
object PhotoProcessor {
    fun enhance(input: Bitmap, mode: String, faces: List<Rect> = emptyList()): Bitmap {
        val width = input.width
        val height = input.height
        require(width > 0 && height > 0) { "Invalid image size" }
        val source = IntArray(width * height)
        input.getPixels(source, 0, width, 0, 0, width, height)

        var sampleTotal = 0.0
        var sampleCount = 0
        val step = (source.size / 18000).coerceAtLeast(1)
        for (i in source.indices step step) {
            val p = source[i]
            sampleTotal += 0.2126 * (p shr 16 and 255) +
                    0.7152 * (p shr 8 and 255) + 0.0722 * (p and 255)
            sampleCount++
        }
        val average = if (sampleCount == 0) 0.5 else sampleTotal / sampleCount / 255.0
        val target = when (mode) {
            "Night" -> 0.51
            "Portrait" -> 0.52
            "Natural" -> 0.48
            else -> 0.50
        }
        val exposure = ((target - average) * if (mode == "Night") 0.80 else 0.52)
            .coerceIn(-0.16, 0.23)
        val contrast = when (mode) {
            "Night" -> 1.045
            "Natural" -> 1.025
            else -> 1.06
        }
        val saturation = when (mode) {
            "Natural" -> 1.035
            "Portrait" -> 1.025
            "Night" -> 1.045
            else -> 1.085
        }
        val lookup = IntArray(256) { v ->
            (((v / 255.0 + exposure - 0.5) * contrast + 0.5)
                .coerceIn(0.0, 1.0) * 255.0).roundToInt()
        }
        val result = IntArray(source.size)
        val portrait = mode == "Portrait" && faces.isNotEmpty()
        val sharpen = if (mode == "Night") 0.025 else 0.065

        for (y in 0 until height) {
            // Filtering face rectangles once per row avoids repeated work.
            val rowFaces = if (portrait) faces.filter { y >= it.top && y < it.bottom }
                else emptyList()
            val offset = y * width
            for (x in 0 until width) {
                val i = offset + x
                val p = source[i]
                var red = lookup[p shr 16 and 255].toDouble()
                var green = lookup[p shr 8 and 255].toDouble()
                var blue = lookup[p and 255].toDouble()

                val luminance = red * 0.2126 + green * 0.7152 + blue * 0.0722
                red = luminance + (red - luminance) * saturation
                green = luminance + (green - luminance) * saturation
                blue = luminance + (blue - luminance) * saturation

                if (rowFaces.any { x >= it.left && x < it.right }) {
                    val lift = (255.0 - luminance) * 0.058
                    red += lift
                    green += lift * 0.91
                    blue += lift * 0.82
                }
                // Minimal 1D high-frequency boost, without a second full-image
                // pass, neighbour arrays or creating an array per pixel.
                if (x > 0 && x + 1 < width) {
                    val leftG = source[i - 1] shr 8 and 255
                    val rightG = source[i + 1] shr 8 and 255
                    val midG = p shr 8 and 255
                    val edge = (midG - (leftG + rightG) * 0.5) * sharpen
                    red += edge
                    green += edge
                    blue += edge
                }
                val r = red.roundToInt().coerceIn(0, 255)
                val g = green.roundToInt().coerceIn(0, 255)
                val b = blue.roundToInt().coerceIn(0, 255)
                result[i] = (p and -0x1000000) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(result, width, height, Bitmap.Config.ARGB_8888)
    }
}

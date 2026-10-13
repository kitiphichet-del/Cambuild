package com.cambuild.lumacam

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.*

/** On-device non-generative image enhancement. ML-detected faces can guide exposure. */
object PhotoProcessor {
    fun enhance(input: Bitmap, mode: String, faces: List<Rect> = emptyList()): Bitmap {
        val w = input.width
        val h = input.height
        val src = IntArray(w * h)
        input.getPixels(src, 0, w, 0, 0, w, h)
        var sum = 0.0
        var sampled = 0
        for (i in src.indices step 101) {
            val c = src[i]
            sum += (0.2126 * (c shr 16 and 255) + 0.7152 * (c shr 8 and 255) + 0.0722 * (c and 255)) / 255.0
            sampled++
        }
        val mean = if (sampled > 0) sum / sampled else 0.5
        val target = when (mode) {
            "Night" -> 0.51
            "Portrait" -> 0.52
            "Natural" -> 0.48
            else -> 0.50
        }
        val exposure = ((target - mean) * if (mode == "Night") 0.80 else 0.52).coerceIn(-0.16, 0.23)
        val saturation = when (mode) {
            "Natural" -> 1.035
            "Portrait" -> 1.025
            "Night" -> 1.045
            else -> 1.085
        }
        val contrast = when (mode) { "Night" -> 1.045; "Natural" -> 1.025; else -> 1.06 }
        val sharp = if (mode == "Night") 0.11 else 0.18
        val dst = IntArray(src.size)
        val reds = IntArray(src.size)
        val greens = IntArray(src.size)
        val blues = IntArray(src.size)

        fun adjust(v: Double) = ((v / 255.0 + exposure - 0.5) * contrast + 0.5).coerceIn(0.0, 1.0) * 255.0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val pixel = src[i]
                val a = pixel ushr 24
                var r = (pixel shr 16 and 255).toDouble()
                var g = (pixel shr 8 and 255).toDouble()
                var b = (pixel and 255).toDouble()

                // Minimal channel balance rather than aggressive skin whitening.
                val avg = (r + g + b) / 3.0
                r = adjust(r + (avg - r) * if (mode == "Night") 0.018 else 0.007)
                g = adjust(g)
                b = adjust(b + (avg - b) * 0.008)
                val grey = r * 0.2126 + g * 0.7152 + b * 0.0722
                r = grey + (r - grey) * saturation
                g = grey + (g - grey) * saturation
                b = grey + (b - grey) * saturation

                // Face-aware gentle correction with no geometric warping.
                if (mode == "Portrait" && faces.any { f -> x >= f.left && x < f.right && y >= f.top && y < f.bottom }) {
                    val lift = (255.0 - grey) * 0.058
                    r += lift
                    g += lift * 0.91
                    b += lift * 0.82
                }
                reds[i] = r.roundToInt().coerceIn(0,255)
                greens[i] = g.roundToInt().coerceIn(0,255)
                blues[i] = b.roundToInt().coerceIn(0,255)
                dst[i] = (a shl 24) or (reds[i] shl 16) or (greens[i] shl 8) or blues[i]
            }
        }

        // Light 4-neighbour unsharp masking to avoid fake detail.
        if (w > 2 && h > 2) {
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                val around = intArrayOf(i - 1, i + 1, i - w, i + w)
                fun clarify(c: IntArray): Int {
                    val neighbourMean = around.sumOf { c[it] }.toDouble() / 4.0
                    return (c[i] + (c[i] - neighbourMean) * sharp).roundToInt().coerceIn(0, 255)
                }
                val r = clarify(reds); val g = clarify(greens); val b = clarify(blues)
                dst[i] = (dst[i] and -0x1000000) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(dst, w, h, Bitmap.Config.ARGB_8888)
    }
}

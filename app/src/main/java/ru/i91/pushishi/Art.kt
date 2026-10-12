package ru.i91.pushishi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Pictures made on the phone: the frames cut from the pet's sheet, and the egg (drawn, not downloaded). */
object Art {
    /** The frame names in the order of the sheet (the site sends the same list). */
    val FRAMES = listOf("stand", "blink", "walk1", "walk2", "sleep", "happy", "sad", "held", "eat", "sit", "play", "wave")

    /**
     * Cut a sheet into its poses. The picture model does not keep the grid exact, so the cuts go along the emptiest
     * lines near the ideal ones. Every frame is trimmed, scaled to [height] and put on one common canvas, paws on its
     * bottom edge, so the pet keeps its size and place from frame to frame.
     */
    fun cut(sheet: Bitmap, cols: Int, rows: Int, height: Int): List<Bitmap> {
        val w = sheet.width
        val h = sheet.height
        val px = IntArray(w * h)
        sheet.getPixels(px, 0, w, 0, 0, w, h)
        fun solid(x: Int, y: Int) = (px[y * w + x] ushr 24) > 40
        val rowSum = IntArray(h)
        val colSum = IntArray(w)
        for (y in 0 until h) {
            var x = 0
            while (x < w) { if (solid(x, y)) { rowSum[y]++; colSum[x]++ }; x += 2 }
        }
        fun cuts(sum: IntArray, n: Int, size: Int): IntArray {
            val o = IntArray(n + 1); o[n] = size
            for (k in 1 until n) {
                val ideal = size * k / n.toDouble()
                val span = size / n.toDouble() * 0.3
                var best = Double.MAX_VALUE
                for (p in (ideal - span).toInt()..(ideal + span).toInt()) {
                    val cost = sum[p] + abs(p - ideal) * 0.02
                    if (cost < best) { best = cost; o[k] = p }
                }
            }
            return o
        }
        val ys = cuts(rowSum, rows, h)
        val xs = cuts(colSum, cols, w)
        val trimmed = ArrayList<Bitmap>()
        for (r in 0 until rows) for (c in 0 until cols) {
            val x0 = xs[c]; val y0 = ys[r]; val cw = xs[c + 1] - x0; val ch = ys[r + 1] - y0
            var minX = cw; var minY = ch; var maxX = -1; var maxY = -1
            for (y in 0 until ch) for (x in 0 until cw) if (solid(x0 + x, y0 + y)) {
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
            }
            if (maxX < 0) { minX = 0; minY = 0; maxX = cw - 1; maxY = ch - 1 }
            trimmed += Bitmap.createBitmap(sheet, x0 + minX, y0 + minY, maxX - minX + 1, maxY - minY + 1)
        }
        // one scale for all frames: the standing pose gets [height]
        val scale = height / trimmed[0].height.toFloat()
        val scaled = trimmed.map { Bitmap.createScaledBitmap(it, max(1, (it.width * scale).roundToInt()), max(1, (it.height * scale).roundToInt()), true) }
        val cw = scaled.maxOf { it.width }
        val ch = scaled.maxOf { it.height }
        return scaled.map { f ->
            val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(f, (cw - f.width) / 2f, (ch - f.height).toFloat(), null)
            out
        }
    }

    fun color(hex: String?, fallback: Int): Int = try { Color.parseColor(if (hex!!.startsWith("#")) hex else "#$hex") } catch (_: Exception) { fallback }

    /** The egg, in the colours of the baby inside; [tilt] in degrees (the wobble). */
    fun egg(size: Int, base: Int, mark: Int, pattern: String?, tilt: Float): Bitmap {
        val w = (size * 0.8f).roundToInt()
        val out = Bitmap.createBitmap(w, size, Bitmap.Config.ARGB_8888)
        val g = Canvas(out)
        val cx = w / 2f; val cy = size * 0.56f; val ry = size * 0.43f; val rx = w * 0.4f
        g.rotate(tilt, cx, cy + ry)
        // an ellipse a little wider at the bottom
        val shape = Path()
        val steps = 72
        for (i in 0..steps) {
            val a = i * 2 * Math.PI / steps
            val v = kotlin.math.cos(a).toFloat()
            val k = rx * (1 + 0.14f * v)
            val x = cx + sin(a).toFloat() * k
            val y = cy + v * ry
            if (i == 0) shape.moveTo(x, y) else shape.lineTo(x, y)
        }
        shape.close()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(cx - rx, cy - ry, cx + rx * 0.4f, cy + ry, mix(base, Color.WHITE, 0.25f), mix(base, Color.BLACK, 0.12f), Shader.TileMode.CLAMP)
        g.drawPath(shape, paint)
        g.save()
        g.clipPath(shape)
        val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mark }
        val rng = Random(base xor mark xor ((pattern?.length ?: 0) * 7919)) // the same egg every time
        when (pattern) {
            "stripes" -> {
                markPaint.strokeWidth = size * 0.07f; markPaint.style = Paint.Style.STROKE
                var y = cy - ry + size * 0.18f
                while (y < cy + ry) { g.drawLine(cx - rx * 1.3f, y + rx * 0.25f, cx + rx * 1.3f, y - rx * 0.25f, markPaint); y += size * 0.2f }
            }
            "patches" -> {
                g.drawCircle(cx - rx * 0.45f, cy - ry * 0.25f, size * 0.17f, markPaint)
                g.drawCircle(cx + rx * 0.5f, cy + ry * 0.35f, size * 0.2f, markPaint)
            }
            "socks" -> g.drawRect(cx - rx * 2, cy + ry * 0.55f, cx + rx * 2, cy + ry * 2, markPaint)
            "heart" -> g.drawCircle(cx, cy + ry * 0.15f, size * 0.11f, markPaint)
            null, "", "none" -> {}
            else -> repeat(9) {
                g.drawCircle(cx + (rng.nextFloat() - 0.5f) * rx * 1.7f, cy + (rng.nextFloat() - 0.6f) * ry * 1.6f, size * (0.04f + rng.nextFloat() * 0.04f), markPaint)
            }
        }
        // a soft shine
        g.drawOval(cx - rx * 0.55f, cy - ry * 0.7f, cx - rx * 0.15f, cy - ry * 0.25f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 255, 255) })
        g.restore()
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(2f, size / 34f); color = Color.rgb(0x6B, 0x44, 0x30) }
        g.drawPath(shape, line)
        return out
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val k = min(1f, max(0f, t))
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).roundToInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).roundToInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).roundToInt(),
        )
    }
}

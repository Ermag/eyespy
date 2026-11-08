package com.eyespy.app.camera

import androidx.camera.core.ImageProxy
import kotlin.math.abs

/**
 * Cheap frame-differencing motion detector on a 64x48 luminance grid.
 * The background model adapts slowly so gradual light changes are ignored; sudden global
 * changes (lights switched on, auto-exposure jumps) reset the model instead of firing.
 */
class MotionDetector {
    private val gw = 64
    private val gh = 48
    private var bg: FloatArray? = null
    private var warmup = 0
    private var hits = 0
    @Volatile private var resetRequested = false

    /** 1 (least sensitive) .. 10 (most sensitive) */
    @Volatile var sensitivity = 5

    private val areaBySensitivity = floatArrayOf(0.12f, 0.08f, 0.05f, 0.035f, 0.025f, 0.018f, 0.012f, 0.008f, 0.005f, 0.003f)

    fun reset() {
        bg = null; hits = 0
    }

    /** Returns the fraction of grid cells that changed, or -1 while warming up. RGBA_8888 input. */
    fun process(img: ImageProxy): Float {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val w = img.width
        val h = img.height
        val cur = FloatArray(gw * gh)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                // average 2x2 samples inside each cell
                var sum = 0f
                for (dy in 0..1) for (dx in 0..1) {
                    val x = ((gx * 2 + dx) * w) / (gw * 2)
                    val y = ((gy * 2 + dy) * h) / (gh * 2)
                    val i = y * rs + x * ps
                    val r = buf.get(i).toInt() and 0xff
                    val g = buf.get(i + 1).toInt() and 0xff
                    val b = buf.get(i + 2).toInt() and 0xff
                    sum += 0.299f * r + 0.587f * g + 0.114f * b
                }
                cur[gy * gw + gx] = sum / 4f
            }
        }
        if (resetRequested) { resetRequested = false; bg = null; warmup = 0; hits = 0 }
        val model = bg
        if (model == null || warmup < 8) {
            bg = cur; warmup++
            return -1f
        }
        // Cancel out global brightness shifts (auto-exposure / white balance) before comparing.
        var meanDiff = 0f
        for (i in cur.indices) meanDiff += cur[i] - model[i]
        meanDiff /= cur.size
        var changed = 0
        for (i in cur.indices) {
            if (abs(cur[i] - model[i] - meanDiff) > PIXEL_DELTA) changed++
            model[i] = model[i] * 0.93f + cur[i] * 0.07f
        }
        val frac = changed.toFloat() / cur.size
        if (frac > 0.65f) { // global change: re-learn background
            bg = cur; hits = 0
            return 0f
        }
        return frac
    }

    /** True when motion has been seen in 2 consecutive analysed frames. */
    fun triggered(frac: Float): Boolean {
        val threshold = areaBySensitivity[(sensitivity - 1).coerceIn(0, 9)]
        hits = if (frac >= threshold) hits + 1 else 0
        return hits >= 2
    }

    fun restartWarmup() {
        warmup = 0; bg = null; hits = 0
    }

    /** Thread-safe: re-learn the background on the next analysed frame (e.g. after a segment switch). */
    fun requestReset() {
        resetRequested = true
    }

    companion object {
        private const val PIXEL_DELTA = 22f
    }
}

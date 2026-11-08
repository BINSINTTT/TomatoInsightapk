package com.tomatoinsight.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * RGB 0–1, channels-first input used by both Ultralytics classifiers.
 *
 * Uses ClassifyLetterBox (pad 114): same full-frame resize/pad Ultralytics applies
 * when Deploy is called with imgsz, so on-device pixels stay closer to the cloud path.
 */
internal fun bitmapToNchw(bitmap: Bitmap, size: Int): ByteBuffer {
    val squared = ultralyticsLetterBox(bitmap, size)
    val pixels = IntArray(size * size)
    squared.getPixels(pixels, 0, size, 0, 0, size, size)
    if (squared !== bitmap) squared.recycle()

    val buffer = ByteBuffer.allocateDirect(1 * 3 * size * size * 4)
    buffer.order(ByteOrder.nativeOrder())
    for (channel in 0 until 3) {
        val shift = when (channel) {
            0 -> 16
            1 -> 8
            else -> 0
        }
        for (pixel in pixels) {
            val value = (pixel shr shift) and 0xFF
            buffer.putFloat(value / 255f)
        }
    }
    buffer.rewind()
    return buffer
}

/** Read imgsz from the loaded TFLite input tensor ([1,3,H,W] or [1,H,W,3]). */
internal fun Interpreter.inputImageSize(fallback: Int): Int {
    val shape = getInputTensor(0).shape()
    if (shape.size != 4) return fallback
    return when {
        shape[1] == 3 -> shape[2].coerceAtLeast(1) // NCHW
        shape[3] == 3 -> shape[1].coerceAtLeast(1) // NHWC
        else -> fallback
    }
}

/**
 * Ultralytics ClassifyLetterBox: aspect-preserving resize, pad to size×size with 114.
 * See ultralytics.data.augment.ClassifyLetterBox.
 */
private fun ultralyticsLetterBox(source: Bitmap, size: Int): Bitmap {
    val argb = if (source.config == Bitmap.Config.ARGB_8888) {
        source
    } else {
        source.copy(Bitmap.Config.ARGB_8888, false)
    }
    val imh = argb.height.coerceAtLeast(1)
    val imw = argb.width.coerceAtLeast(1)
    val scale = min(size.toFloat() / imh, size.toFloat() / imw)
    val resizedH = (imh * scale).roundToInt().coerceAtLeast(1)
    val resizedW = (imw * scale).roundToInt().coerceAtLeast(1)
    val resized = if (resizedW == imw && resizedH == imh) {
        argb
    } else {
        Bitmap.createScaledBitmap(argb, resizedW, resizedH, true)
    }

    if (resizedW == size && resizedH == size) {
        if (argb !== source && argb !== resized) argb.recycle()
        return resized
    }

    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    // Ultralytics pad value 114 on RGB → #727272
    canvas.drawColor(Color.rgb(114, 114, 114))
    val left = ((size - resizedW) / 2f - 0.1f).roundToInt().coerceAtLeast(0)
    val top = ((size - resizedH) / 2f - 0.1f).roundToInt().coerceAtLeast(0)
    canvas.drawBitmap(
        resized,
        Rect(0, 0, resizedW, resizedH),
        Rect(left, top, left + resizedW, top + resizedH),
        Paint(Paint.FILTER_BITMAP_FLAG)
    )
    if (resized !== argb && resized !== out) resized.recycle()
    if (argb !== source && argb !== out) argb.recycle()
    return out
}

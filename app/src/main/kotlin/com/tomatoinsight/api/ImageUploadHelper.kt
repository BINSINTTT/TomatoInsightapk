package com.tomatoinsight.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object ImageUploadHelper {

    private const val TAG = "TomatoInsight"
    // Match the website: send a near-original photo so Ultralytics sees the same pixels.
    private const val MAX_IMAGE_SIZE = 4096
    private const val JPEG_QUALITY = 95

    suspend fun checkServerHealth(): HealthResponse = withContext(Dispatchers.IO) {
        Log.d(TAG, "Health check → ${ValidationRetrofitClient.BASE_URL}")
        ValidationRetrofitClient.instance.checkHealth()
    }

    suspend fun validateTomatoLeaf(context: Context, bitmap: Bitmap): ValidationResponse =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Validate → ${ValidationRetrofitClient.BASE_URL}validate")

            val imageBytes = prepareImageBytes(bitmap)
            Log.d(TAG, "Upload size: ${imageBytes.size / 1024} KB")
            WebsiteScanClient.scan(imageBytes)
        }

    /**
     * Same JPEG encode/decode the online path uses, so offline TFLite sees
     * pixels closer to what Ultralytics Deploy receives.
     */
    fun bitmapForOnDeviceScan(bitmap: Bitmap): Bitmap {
        val bytes = prepareImageBytes(bitmap)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: bitmap.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun prepareImageBytes(bitmap: Bitmap): ByteArray {
        val scaled = scaleBitmap(bitmap, MAX_IMAGE_SIZE)
        return ByteArrayOutputStream().use { stream ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            stream.toByteArray()
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxSize && height <= maxSize) return bitmap

        val scale = minOf(maxSize.toFloat() / width, maxSize.toFloat() / height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}

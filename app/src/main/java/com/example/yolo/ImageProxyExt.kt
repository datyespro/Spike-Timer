@file:JvmName("ImageProxyExt")
package com.example.spiketimer.camera

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

object ImageProxyExt {
    /**
     * Convert ImageProxy (OUTPUT_IMAGE_FORMAT_RGBA_8888) → Bitmap ARGB_8888.
     * - Java-friendly: static method via @file:JvmName + @JvmStatic
     * - Tối ưu: tái sử dụng bitmap đầu ra nếu cùng w×h để giảm GC.
     * - An toàn stride: xử lý rowStride/pixelStride (padding) theo từng hàng.
     *
     * Lưu ý: Bạn phải cấu hình ImageAnalysis:
     *   .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
     */
    @JvmStatic
    fun toBitmapRGBA(image: ImageProxy, reuse: Bitmap? = null): Bitmap {
        val width = image.width
        val height = image.height

        // CameraX RGBA_8888: 1 plane duy nhất
        val planes = image.planes
        require(planes.size == 1) {
            "Expected 1 plane for RGBA_8888, but got ${planes.size}. Did you set OUTPUT_IMAGE_FORMAT_RGBA_8888?"
        }
        val plane = planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride // thường = 4

        val outBmp = if (reuse != null && reuse.width == width && reuse.height == height && reuse.config == Bitmap.Config.ARGB_8888) {
            reuse
        } else {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }

        // Mỗi hàng: copy rowStride bytes → convert RGBA → ARGB int[] → setPixels
        val rowBytes = ByteArray(rowStride)
        val argb = IntArray(width)

        for (y in 0 until height) {
            buf.position(y * rowStride)
            buf.get(rowBytes, 0, rowStride)

            var src = 0
            var x = 0
            while (x < width) {
                val r = rowBytes[src].toInt() and 0xFF
                val g = rowBytes[src + 1].toInt() and 0xFF
                val b = rowBytes[src + 2].toInt() and 0xFF
                val a = rowBytes[src + 3].toInt() and 0xFF
                argb[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                src += pixelStride
                x++
            }
            outBmp.setPixels(argb, 0, width, 0, y, width, 1)
        }
        return outBmp
    }
}

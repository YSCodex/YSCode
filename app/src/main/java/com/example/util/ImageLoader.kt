// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/** Prepares an image file for a vision model: magic-byte sniffing, EXIF rotation, optional crop, downscale, JPEG. */
object ImageLoader {
    data class Loaded(
        val mime: String,
        val base64: String,
        val width: Int,
        val height: Int,
        val originalWidth: Int,
        val originalHeight: Int
    )

    fun sniffMime(file: File): String? {
        val h = ByteArray(12)
        val n = runCatching { file.inputStream().use { it.read(h) } }.getOrDefault(-1)
        if (n < 4) return null
        fun b(i: Int) = h[i].toInt() and 0xFF
        return when {
            b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47 -> "image/png"
            b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF -> "image/jpeg"
            b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46 -> "image/gif"
            n >= 12 && b(0) == 0x52 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x46 &&
                b(8) == 0x57 && b(9) == 0x45 && b(10) == 0x42 && b(11) == 0x50 -> "image/webp"
            else -> null
        }
    }

    fun load(file: File, crop: List<Int>? = null, maxEdge: Int = 1568): Loaded {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val ow = bounds.outWidth
        val oh = bounds.outHeight
        require(ow > 0 && oh > 0) { "Cannot decode this image" }

        var bmp: Bitmap = if (crop != null && crop.size == 4) {
            // Crop coordinates are in ORIGINAL pixel space: [x0, y0, x1, y1]
            val r = Rect(
                crop[0].coerceIn(0, ow - 1), crop[1].coerceIn(0, oh - 1),
                crop[2].coerceIn(1, ow), crop[3].coerceIn(1, oh)
            )
            require(r.width() > 8 && r.height() > 8) { "Crop region is too small" }
            val dec = BitmapRegionDecoder.newInstance(file.absolutePath, false) ?: error("Cannot open image for cropping")
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(maxOf(r.width(), r.height()), maxEdge) }
                dec.decodeRegion(r, opts) ?: error("Crop decode failed")
            } finally {
                dec.recycle()
            }
        } else {
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(maxOf(ow, oh), maxEdge) }
            BitmapFactory.decodeFile(file.absolutePath, opts) ?: error("Cannot decode this image")
        }

        bmp = applyExif(file, bmp)
        if (bmp.hasAlpha()) {
            val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
            bmp = flat
        }
        val longest = maxOf(bmp.width, bmp.height)
        if (longest > maxEdge) {
            val s = maxEdge.toFloat() / longest
            bmp = Bitmap.createScaledBitmap(
                bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true
            )
        }
        var quality = 88
        var bytes: ByteArray
        do {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bytes = out.toByteArray()
            quality -= 10
        } while (bytes.size > 1_500_000 && quality >= 40)
        return Loaded("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP), bmp.width, bmp.height, ow, oh)
    }

    /** A 160x160 white picture with one big green circle and a small red square (for the /vision self-test). */
    fun makeVisionProbe(): Loaded {
        val bmp = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(20, 160, 60)
        c.drawCircle(80f, 80f, 55f, paint)
        paint.color = Color.rgb(200, 30, 30)
        c.drawRect(8f, 8f, 28f, 28f, paint)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return Loaded("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), 160, 160, 160, 160)
    }

    private fun sampleFor(longest: Int, target: Int): Int {
        var s = 1
        while (longest / (s * 2) >= target) s *= 2
        return s
    }

    private fun applyExif(file: File, bmp: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            else -> return bmp
        }
        return runCatching { Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true) }.getOrDefault(bmp)
    }
}

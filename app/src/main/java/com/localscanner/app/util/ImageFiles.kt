package com.localscanner.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/** Bitmap decode/encode helpers with EXIF handling. */
object ImageFiles {

    /** Decodes a JPEG from disk, honouring EXIF rotation, capped at maxDim. */
    fun decodeScaled(path: String, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (max(w, h) / 2 >= maxDim) {
            w /= 2
            h /= 2
            sample *= 2
        }
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val rotated = applyExif(path, bmp)
        return clampToMax(rotated, maxDim)
    }

    private fun applyExif(path: String, bmp: Bitmap): Bitmap {
        val degrees = try {
            val exif = ExifInterface(path)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (t: Throwable) {
            0f
        }
        return if (degrees != 0f) rotate(bmp, degrees) else bmp
    }

    fun rotate(src: Bitmap, degrees: Float): Bitmap {
        val m = Matrix().apply { postRotate(degrees) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        return if (out != src) out else src
    }

    /** Rotates a JPEG file in place (90 degrees). Returns success. */
    fun rotateFile(path: String, degrees: Float): Boolean {
        val bmp = decodeScaled(path, 4096) ?: return false
        val rotated = rotate(bmp, degrees)
        val ok = saveJpeg(rotated, File(path), 92)
        if (rotated !== bmp) rotated.recycle()
        bmp.recycle()
        return ok
    }

    fun clampToMax(src: Bitmap, maxDim: Int): Bitmap {
        val biggest = max(src.width, src.height)
        if (biggest <= maxDim) return src
        val scale = maxDim.toFloat() / biggest
        val w = max(32, (src.width * scale).toInt())
        val h = max(32, (src.height * scale).toInt())
        val out = Bitmap.createScaledBitmap(src, w, h, true)
        if (out !== src) src.recycle()
        return out
    }

    /** In-memory downscale (does not recycle the source). */
    fun downscale(src: Bitmap, maxDim: Int): Bitmap {
        val biggest = max(src.width, src.height)
        if (biggest <= maxDim) return src
        val scale = maxDim.toFloat() / biggest
        return Bitmap.createScaledBitmap(src, max(32, (src.width * scale).toInt()), max(32, (src.height * scale).toInt()), true)
    }

    fun saveJpeg(src: Bitmap, file: File, quality: Int): Boolean = try {
        FileOutputStream(file).use { src.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        true
    } catch (t: Throwable) {
        false
    }

    /** Detects whether two quads are near-identical (stability check helper). */
    fun isSimilarSize(a: Int, b: Int, tolerance: Float): Boolean =
        min(a, b).toFloat() / max(a, b).toFloat() >= tolerance
}

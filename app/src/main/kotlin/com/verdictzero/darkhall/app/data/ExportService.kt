package com.verdictzero.darkhall.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import com.verdictzero.darkhall.core.orf.OrfMetadata
import java.io.File
import java.io.FileOutputStream

/** Writes developed JPEGs to Pictures/Darkhall and copies the shooting data into EXIF. */
object ExportService {

    const val ALBUM = "Darkhall"
    const val QUALITY = 95

    class Result(val uri: Uri, val displayPath: String)

    fun saveJpeg(context: Context, bitmap: Bitmap, meta: OrfMetadata, baseName: String): Result {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val fileName = "${baseName}_$stamp.jpg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveViaMediaStore(context, bitmap, meta, fileName)
        else saveLegacy(context, bitmap, meta, fileName)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(context: Context, bitmap: Bitmap, meta: OrfMetadata, fileName: String): Result {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + ALBUM)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("MediaStore refused the file")
        try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
                ?: throw IllegalStateException("Cannot write to $uri")
            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                runCatching { writeExif(ExifInterface(pfd.fileDescriptor), meta) }
            }
        } finally {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return Result(uri, "Pictures/$ALBUM/$fileName")
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, bitmap: Bitmap, meta: OrfMetadata, fileName: String): Result {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM).apply { mkdirs() }
        val f = File(dir, fileName)
        FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        runCatching { writeExif(ExifInterface(f.absolutePath), meta) }
        MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf("image/jpeg"), null)
        return Result(Uri.fromFile(f), "Pictures/$ALBUM/$fileName")
    }

    private fun writeExif(exif: ExifInterface, meta: OrfMetadata) {
        exif.setAttribute(ExifInterface.TAG_MAKE, meta.make)
        exif.setAttribute(ExifInterface.TAG_MODEL, meta.model)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Darkhall")
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        if (meta.dateTime.isNotEmpty()) {
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, meta.dateTime)
            exif.setAttribute(ExifInterface.TAG_DATETIME, meta.dateTime)
        }
        if (meta.iso > 0) exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, meta.iso.toString())
        if (meta.exposureTime > 0) exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, meta.exposureTime.toString())
        if (meta.fNumber > 0) exif.setAttribute(ExifInterface.TAG_F_NUMBER, meta.fNumber.toString())
        if (meta.focalLength > 0) {
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${Math.round(meta.focalLength * 100)}/100")
        }
        exif.saveAttributes()
    }
}

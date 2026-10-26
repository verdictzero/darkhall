package com.verdictzero.darkhall.app.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import com.verdictzero.darkhall.core.export.ExportFormat
import com.verdictzero.darkhall.core.export.PhotoInfo
import java.io.File

/**
 * Publishes finished exports to Pictures/Darkhall, and copies the shooting data into the
 * EXIF of JPEG, PNG and WebP files (TIFF writes its own; TGA has no room for it).
 */
object ExportService {

    const val ALBUM = "Darkhall"

    /** Where a file ended up: its content URI and a path to show the user. */
    class Result(val uri: Uri, val displayPath: String) {
        val folder: String get() = displayPath.substringBeforeLast('/')
    }

    /**
     * Copies the finished [file] into the shared collection as [baseName] + a timestamp. Formats
     * the gallery doesn't index as pictures on this device (TGA, usually) go to Download/Darkhall.
     */
    fun publish(context: Context, file: File, format: ExportFormat, baseName: String): Result {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val fileName = "${baseName}_$stamp.${format.extension}"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                saveViaMediaStore(context, file, fileName, format.mimeType, pictures = true)
            } catch (e: IllegalArgumentException) {
                // MediaStore only takes picture types it knows into Pictures/.
                saveViaMediaStore(context, file, fileName, format.mimeType, pictures = false)
            }
        } else {
            saveLegacy(context, file, fileName, format.mimeType)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(context: Context, file: File, fileName: String, mime: String, pictures: Boolean): Result {
        val resolver = context.contentResolver
        val dir = (if (pictures) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) + File.separator + ALBUM
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (pictures) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("MediaStore refused the file")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("Cannot write to $uri")
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) } // don't leave an empty pending entry behind
            throw e
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return Result(uri, "$dir/$fileName")
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, file: File, fileName: String, mime: String): Result {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM).apply { mkdirs() }
        val f = File(dir, fileName)
        file.copyTo(f, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf(mime), null)
        return Result(Uri.fromFile(f), "Pictures/$ALBUM/$fileName")
    }

    /** Writes the shooting data into a JPEG, PNG or WebP file in place. */
    fun writeExif(file: File, info: PhotoInfo) {
        val exif = ExifInterface(file.absolutePath)
        if (info.make.isNotEmpty()) exif.setAttribute(ExifInterface.TAG_MAKE, info.make)
        if (info.model.isNotEmpty()) exif.setAttribute(ExifInterface.TAG_MODEL, info.model)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, info.software)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        if (info.dateTime.isNotEmpty()) {
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, info.dateTime)
            exif.setAttribute(ExifInterface.TAG_DATETIME, info.dateTime)
        }
        if (info.iso > 0) exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, info.iso.toString())
        if (info.exposureTime > 0) exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, info.exposureTime.toString())
        if (info.fNumber > 0) exif.setAttribute(ExifInterface.TAG_F_NUMBER, info.fNumber.toString())
        if (info.focalLength > 0) {
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${Math.round(info.focalLength * 100)}/100")
        }
        info.gps?.let { gps ->
            exif.setLatLong(gps.latitude, gps.longitude)
            gps.altitude?.let { exif.setAltitude(it) }
            gps.dateStamp?.let { exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, it) }
            gps.timeStamp?.let { exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, it) }
        }
        if (info.description.isNotEmpty()) exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, info.description)
        exif.saveAttributes()
    }
}

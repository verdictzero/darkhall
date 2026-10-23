package com.verdictzero.darkhall.app.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.verdictzero.darkhall.core.develop.Demosaic
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.Developer
import com.verdictzero.darkhall.core.develop.Frame
import com.verdictzero.darkhall.core.develop.RenderContext
import com.verdictzero.darkhall.core.raw.RawMetadata
import com.verdictzero.darkhall.core.raw.RawFormats
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** File access, folder listing and thumbnail caching. */
object RawRepository {

    private const val HEAD_BYTES = 2 * 1024 * 1024
    private const val THUMB_SIZE = 480

    fun readBytes(resolver: ContentResolver, uri: Uri): ByteArray {
        val expected = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        val input = resolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open $uri")
        input.use { return it.readFully(expected) }
    }

    private fun InputStream.readFully(expected: Long): ByteArray {
        if (expected in 1..(64L * 1024 * 1024)) {
            // Known size: read straight into a right-sized array, no intermediate copy.
            val buf = ByteArray(expected.toInt())
            var off = 0
            while (off < buf.size) {
                val n = read(buf, off, buf.size - off)
                if (n < 0) break
                off += n
            }
            if (off == buf.size && read() < 0) return buf
            // Size was wrong; fall back to streaming the rest.
            val out = ByteArrayOutputStream(buf.size + (1 shl 20))
            out.write(buf, 0, off)
            copyTo(out)
            return out.toByteArray()
        }
        val out = ByteArrayOutputStream(16 * 1024 * 1024)
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun readHead(resolver: ContentResolver, uri: Uri, max: Int): ByteArray {
        val input = resolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open $uri")
        input.use {
            val buf = ByteArray(max)
            var off = 0
            while (off < max) {
                val n = it.read(buf, off, max - off)
                if (n < 0) break
                off += n
            }
            return if (off == max) buf else buf.copyOf(off)
        }
    }

    fun describe(resolver: ContentResolver, uri: Uri): RawFile {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "image.raw"
        var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        return RawFile(uri, name, size, 0L)
    }

    /** Lists the raw files directly inside a SAF tree (e.g. the TG-7's DCIM/100OLYMP). */
    fun listFolder(resolver: ContentResolver, treeUri: Uri): List<RawFile> {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val out = ArrayList<RawFile>()
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        resolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(4)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                if (!RawFile.isRawName(name)) continue
                val size = if (c.isNull(2)) 0L else c.getLong(2)
                val mod = if (c.isNull(3)) 0L else c.getLong(3)
                out += RawFile(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), name, size, mod)
            }
        }
        return out.sortedByDescending { if (it.modified > 0) it.modified else 0 }.let { l ->
            if (l.all { it.modified == 0L }) l.sortedBy { it.name } else l
        }
    }

    class Loaded(val bytes: ByteArray, val meta: RawMetadata)

    fun load(resolver: ContentResolver, uri: Uri): Loaded {
        val bytes = readBytes(resolver, uri)
        // The parser names unsupported formats (RAF, CR3, ...) instead of a generic error.
        return Loaded(bytes, RawFormats.parse(bytes))
    }

    /** Only one full raw decode at a time for thumbnails; a grid of them would exhaust the heap. */
    private val heavyThumbnailLock = Any()

    /**
     * Thumbnail from the camera's embedded JPEG preview, cached on disk. Only the start of the
     * file and the preview's own bytes are read; a full decode is the last resort.
     */
    fun thumbnail(context: Context, file: RawFile): Bitmap? {
        val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
        val cached = File(dir, "${file.key}.jpg")
        if (cached.exists()) BitmapFactory.decodeFile(cached.path)?.let { return it }
        val resolver = context.contentResolver
        return try {
            val head = readHead(resolver, file.uri, HEAD_BYTES)
            if (!RawFormats.looksLikeRaw(head)) return null
            val size = file.size.takeIf { it > 0 }
                ?: runCatching { resolver.openAssetFileDescriptor(file.uri, "r")?.use { it.length } }.getOrNull()?.takeIf { it > 0 }
                ?: Long.MAX_VALUE
            var orientation = 1
            var bmp: Bitmap? = RawFormats.locatePreview(head, size)?.let { loc ->
                orientation = loc.orientation
                if (loc.offset + loc.length <= head.size) {
                    decodeJpeg(head, loc.offset.toInt(), loc.length, THUMB_SIZE)
                } else {
                    readRange(resolver, file.uri, loc.offset, loc.length)?.let { decodeJpeg(it, 0, it.size, THUMB_SIZE) }
                }
            }
            if (bmp == null) {
                val bytes = readBytes(resolver, file.uri)
                val meta = RawFormats.parse(bytes)
                orientation = meta.orientation
                bmp = synchronized(heavyThumbnailLock) { renderTiny(bytes, meta) }
            }
            val oriented = applyOrientation(bmp, orientation)
            FileOutputStream(cached).use { oriented.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            oriented
        } catch (e: Throwable) {
            // Includes OutOfMemoryError: a missing thumbnail is better than a dead app.
            null
        }
    }

    /** Reads [length] bytes at [offset], or null if the file is shorter. */
    private fun readRange(resolver: ContentResolver, uri: Uri, offset: Long, length: Int): ByteArray? {
        val input = resolver.openInputStream(uri) ?: return null
        input.use {
            var skipped = 0L
            while (skipped < offset) {
                val k = it.skip(offset - skipped)
                if (k <= 0) { if (it.read() < 0) return null; skipped++ } else skipped += k
            }
            val buf = ByteArray(length)
            var off = 0
            while (off < length) {
                val n = it.read(buf, off, length - off)
                if (n < 0) return null
                off += n
            }
            return buf
        }
    }

    private fun decodeJpeg(bytes: ByteArray, offset: Int, length: Int, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, offset, length, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, offset, length, opts)
    }

    private fun renderTiny(bytes: ByteArray, meta: RawMetadata): Bitmap {
        val raw = RawFormats.decode(bytes, meta)
        val small = Demosaic.preview(raw, THUMB_SIZE)
        val w = small.width
        val h = small.height
        val ctx = RenderContext.forFile(meta, small, raw)
        val px = Developer().render(small, ctx, DevelopParams.defaultsFor(meta, ctx.noise), Frame(0, 0, w.toFloat() / meta.crop.width))
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    fun applyOrientation(bmp: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) { 3 -> 180f; 6 -> 90f; 8 -> 270f; else -> 0f }
        if (degrees == 0f) return bmp
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}

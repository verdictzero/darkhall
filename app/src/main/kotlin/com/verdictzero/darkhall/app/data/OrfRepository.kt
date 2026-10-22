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
import com.verdictzero.darkhall.core.orf.OlympusDecoder
import com.verdictzero.darkhall.core.orf.OrfMetadata
import com.verdictzero.darkhall.core.orf.OrfParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** File access, folder listing and thumbnail caching. */
object OrfRepository {

    private const val HEAD_BYTES = 2 * 1024 * 1024
    private const val THUMB_SIZE = 480

    fun readBytes(resolver: ContentResolver, uri: Uri): ByteArray {
        val input = resolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open $uri")
        input.use { return it.readFully() }
    }

    private fun InputStream.readFully(): ByteArray {
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
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "image.orf"
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

    /** Lists the .ORF files directly inside a SAF tree (e.g. the TG-7's DCIM/100OLYMP). */
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

    class Loaded(val bytes: ByteArray, val meta: OrfMetadata)

    fun load(resolver: ContentResolver, uri: Uri): Loaded {
        val bytes = readBytes(resolver, uri)
        if (!OrfParser.isOrf(bytes)) throw IllegalArgumentException("Not an ORF raw file")
        return Loaded(bytes, OrfParser.parse(bytes))
    }

    /** Thumbnail from the camera's embedded JPEG preview (first ~1 MB of the file), cached on disk. */
    fun thumbnail(context: Context, file: RawFile): Bitmap? {
        val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
        val cached = File(dir, "${file.key}.jpg")
        if (cached.exists()) BitmapFactory.decodeFile(cached.path)?.let { return it }
        val resolver = context.contentResolver
        return try {
            var bytes = readHead(resolver, file.uri, HEAD_BYTES)
            if (!OrfParser.isOrf(bytes)) return null
            var meta = runCatching { OrfParser.parse(bytes) }.getOrNull()
            if (meta == null || meta.previewLength == 0) {
                bytes = readBytes(resolver, file.uri)
                meta = OrfParser.parse(bytes)
            }
            val bmp = if (meta.previewLength > 0) {
                decodeJpeg(bytes, meta.previewOffset.toInt(), meta.previewLength, THUMB_SIZE)
            } else {
                renderTiny(bytes, meta)
            } ?: return null
            val oriented = applyOrientation(bmp, meta.orientation)
            FileOutputStream(cached).use { oriented.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            oriented
        } catch (e: Exception) {
            null
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

    private fun renderTiny(bytes: ByteArray, meta: OrfMetadata): Bitmap {
        val raw = OlympusDecoder.decode(bytes, meta)
        val half = Demosaic.superpixel(raw)
        val w = THUMB_SIZE
        val h = (half.height.toLong() * w / half.width).toInt().coerceAtLeast(1)
        val small = half.resizeArea(w, h)
        val ctx = RenderContext.forFile(meta, small)
        val px = Developer().render(small, ctx, DevelopParams.defaultsFor(meta), Frame(0, 0, w.toFloat() / meta.crop.width))
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    fun applyOrientation(bmp: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) { 3 -> 180f; 6 -> 90f; 8 -> 270f; else -> 0f }
        if (degrees == 0f) return bmp
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}

package com.verdictzero.darkhall.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.verdictzero.darkhall.R
import com.verdictzero.darkhall.core.develop.Adjustment
import com.verdictzero.darkhall.core.develop.Crop
import com.verdictzero.darkhall.core.develop.Demosaic
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.FullRender
import com.verdictzero.darkhall.core.develop.RenderContext
import com.verdictzero.darkhall.core.export.ArgbRows
import com.verdictzero.darkhall.core.export.CroppedRows
import com.verdictzero.darkhall.core.export.ExportFormat
import com.verdictzero.darkhall.core.export.GeometryRows16
import com.verdictzero.darkhall.core.export.PhotoInfo
import com.verdictzero.darkhall.core.export.PngWriter
import com.verdictzero.darkhall.core.export.TgaWriter
import com.verdictzero.darkhall.core.export.TiffWriter
import com.verdictzero.darkhall.core.raw.RawMetadata
import com.verdictzero.darkhall.core.develop.Geometry
import com.verdictzero.darkhall.core.export.RowSource
import com.verdictzero.darkhall.core.stack.Similarity
import com.verdictzero.darkhall.core.stack.FocusStack
import com.verdictzero.darkhall.core.raw.RawFormats
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders each queued raw at full resolution with its saved sliders and writes it in the
 * chosen format: JPEG and WebP through Android's encoders, PNG, TIFF and TGA through
 * Darkhall's own, which also take the 16-bit render.
 */
class ExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val total = inputData.getInt(ExportQueue.KEY_TOTAL, 1)
    private val stack = inputData.getBoolean(ExportQueue.KEY_STACK, false)
    private val settings = ExportQueue.settingsOf(inputData)
    private var folder: String? = null

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0f, null)

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(ExportQueue.KEY_JOB) ?: return Result.failure()
        val uris = ExportQueue.readJob(applicationContext, jobId)
        if (uris.isEmpty()) return Result.failure(workDataOf(ExportQueue.KEY_FAILED_NAMES to "the export's file list is missing"))
        // Promote to a foreground service; if the platform refuses (rare background-start
        // restrictions) the work still runs, just without the guarantee.
        runCatching { setForeground(foregroundInfo(0, 0f, null)) }

        var ok = 0
        val failedNames = ArrayList<String>()
        if (stack) {
            val name = runCatching { RawRepository.describe(applicationContext.contentResolver, uris[0]).name }.getOrDefault("photo")
            try {
                withContext(Dispatchers.Default) { stackAll(uris) { k, f -> reportAsync(k, f, "Focus stack · $name") } }
                ok = 1
            } catch (e: Throwable) {
                failedNames += "focus stack of $name (${e.message ?: e.javaClass.simpleName})"
            }
        }
        for ((i, uri) in uris.withIndex()) {
            if (stack) break
            if (isStopped) break
            val name = runCatching { RawRepository.describe(applicationContext.contentResolver, uri).name }.getOrDefault("photo")
            report(i, 0f, name)
            try {
                withContext(Dispatchers.Default) { exportOne(uri) { f -> reportAsync(i, f, name) } }
                ok++
            } catch (e: Throwable) {
                failedNames += name
            }
            System.gc()
        }
        ExportQueue.deleteJob(applicationContext, jobId)
        return Result.success(
            workDataOf(
                ExportQueue.KEY_OK to ok,
                ExportQueue.KEY_FAILED to failedNames.size,
                ExportQueue.KEY_FAILED_NAMES to failedNames.joinToString(", ").take(2000),
                ExportQueue.KEY_FOLDER to folder,
            )
        )
    }

    private suspend fun report(index: Int, fraction: Float, name: String) {
        setProgress(progressData(index, fraction))
        runCatching { setForeground(foregroundInfo(index, fraction, name)) }
    }

    private var lastReport = 0L

    private fun reportAsync(index: Int, fraction: Float, name: String) {
        val now = System.currentTimeMillis()
        if (now - lastReport < 250) return
        lastReport = now
        setProgressAsync(progressData(index, fraction))
        runCatching { setForegroundAsync(foregroundInfo(index, fraction, name)) }
    }

    private fun progressData(index: Int, fraction: Float) = workDataOf(
        ExportQueue.KEY_DONE to index,
        ExportQueue.KEY_TOTAL to total,
        ExportQueue.KEY_FRACTION to (index + fraction) / total,
    )

    private fun exportOne(uri: Uri, progress: (Float) -> Unit) {
        val context = applicationContext
        val file = RawRepository.describe(context.contentResolver, uri)
        val (raw, meta) = RawRepository.load(context.contentResolver, uri).let { RawFormats.decode(it.bytes, it.meta) to it.meta }
        // (the file buffer is released here)
        val pv = Demosaic.preview(raw, 1280)
        val ctx = RenderContext.forFile(meta, pv, raw, looks = LookLibrary.source(context))
        val defaults = DevelopParams.defaultsFor(meta, ctx.noise)
        val params = EditsStore.load(context, file.key, defaults) ?: defaults
        // Masked sliders need the photo's masks: remembered from the editor, or found now.
        if (params.usesMasks()) ctx.scene = SceneDetector.detect(context, file.key, pv, ctx, defaults.with(Adjustment.WATER, 0f), meta)
        val format = settings.format
        val info = PhotoInfo.from(meta)
        val renderProgress = { f: Float -> progress(f * 0.8f) }
        val encodeProgress = { f: Float -> progress(0.8f + f * 0.18f) }
        val temp = File.createTempFile("export", ".${format.extension}", context.cacheDir)
        try {
            if (settings.bits == 16) {
                val r = FullRender.render16(raw, ctx, params, renderProgress, isCancelled = { isStopped })
                    ?: throw IllegalStateException("cancelled")
                val rows = CroppedRows.of(GeometryRows16(r.rgb, r.width, r.height, r.geometry, meta.orientation), params)
                when (format) {
                    ExportFormat.PNG -> PngWriter.write(rows, temp, mapOf("Software" to info.software), encodeProgress)
                    ExportFormat.TIFF -> TiffWriter.write(rows, temp, settings.tiffCompressed, info, encodeProgress)
                    else -> error("$format has no 16-bit form")
                }
            } else {
                val r = FullRender.render(raw, ctx, params, renderProgress, isCancelled = { isStopped })
                    ?: throw IllegalStateException("cancelled")
                when (format) {
                    ExportFormat.JPEG, ExportFormat.WEBP -> {
                        var bmp = Bitmap.createBitmap(r.argb, r.width, r.height, Bitmap.Config.ARGB_8888)
                        bmp = RawRepository.applyOrientation(bmp, meta.orientation)
                        val crop = Crop.of(params)
                        if (!crop.isFull) {
                            val c = crop.pixels(bmp.width, bmp.height)
                            val cut = Bitmap.createBitmap(bmp, c.x, c.y, c.width, c.height)
                            if (cut !== bmp) bmp.recycle()
                            bmp = cut
                        }
                        FileOutputStream(temp).use { out ->
                            if (format == ExportFormat.JPEG) bmp.compress(Bitmap.CompressFormat.JPEG, settings.jpegQuality, out)
                            else bmp.compress(webpLossless(), 100, out)
                        }
                        bmp.recycle()
                    }
                    else -> {
                        val rows = CroppedRows.of(ArgbRows(r.argb, r.width, r.height, meta.orientation), params)
                        when (format) {
                            ExportFormat.PNG -> PngWriter.write(rows, temp, mapOf("Software" to info.software), encodeProgress)
                            ExportFormat.TIFF -> TiffWriter.write(rows, temp, settings.tiffCompressed, info, encodeProgress)
                            else -> TgaWriter.write(rows, temp, encodeProgress)
                        }
                    }
                }
            }
            if (format == ExportFormat.JPEG || format == ExportFormat.PNG || format == ExportFormat.WEBP) {
                ExportService.writeExif(temp, info)
            }
            progress(0.98f)
            folder = ExportService.publish(context, temp, format, file.baseName).folder
        } finally {
            temp.delete()
        }
        progress(1f)
    }

    /**
     * Merges a focus-bracketed series into one picture, developed with the first photo's
     * settings: each frame is lined up with the one before (on its preview), developed at full
     * size and folded into the result where it is sharpest (see FocusStack). One raw at a time
     * is in memory. [progress] gets the frame index and how far along that frame is.
     */
    private fun stackAll(uris: List<Uri>, progress: (Int, Float) -> Unit) {
        val context = applicationContext
        val first = RawRepository.describe(context.contentResolver, uris[0])
        var meta: RawMetadata? = null
        var ctx: RenderContext? = null
        var params: DevelopParams? = null
        var merger: FocusStack.Merger? = null
        var previous: FloatArray? = null
        var pvSize = 0 to 0
        var toFrame = Similarity.IDENTITY
        for ((k, uri) in uris.withIndex()) {
            if (isStopped) throw IllegalStateException("cancelled")
            val loaded = RawRepository.load(context.contentResolver, uri)
            val raw = RawFormats.decode(loaded.bytes, loaded.meta)
            val m = loaded.meta
            val pv = Demosaic.preview(raw, ALIGN_SIZE)
            if (k == 0) {
                // The first photo sets the look of the result: its context, edits and masks.
                val c = RenderContext.forFile(m, pv, raw, looks = LookLibrary.source(context))
                val defaults = DevelopParams.defaultsFor(m, c.noise)
                val p = EditsStore.load(context, first.key, defaults) ?: defaults
                if (p.usesMasks()) c.scene = SceneDetector.detect(context, first.key, pv, c, defaults.with(Adjustment.WATER, 0f), m)
                meta = m; ctx = c; params = p; pvSize = pv.width to pv.height
                merger = FocusStack.Merger(m.crop.width, m.crop.height)
            } else if (m.crop.width != meta!!.crop.width || m.crop.height != meta.crop.height || pv.width != pvSize.first) {
                throw IllegalArgumentException("${RawRepository.describe(context.contentResolver, uri).name} is a different size")
            }
            val lum = FloatArray(pv.width * pv.height) { i ->
                kotlin.math.sqrt(maxOf(0f, 0.25f * pv.r[i] + 0.5f * pv.g[i] + 0.25f * pv.b[i]))
            }
            previous?.let { prev ->
                val step = FocusStack.align(prev, lum, pv.width, pv.height).scaled(meta!!.crop.width.toDouble() / pv.width)
                toFrame = toFrame.then(step)
            }
            previous = lum
            val frame = FullRender.render16(raw, ctx!!, params!!, { f -> progress(k, f * 0.9f) }, isCancelled = { isStopped })
                ?: throw IllegalStateException("cancelled")
            val sharp = FocusStack.sharpness(FocusStack.luminance(frame.rgb, frame.width, frame.height), frame.width, frame.height)
            merger!!.add(frame.rgb, sharp, toFrame)
            progress(k, 1f)
            System.gc()
        }
        val m = meta!!; val p = params!!; val mg = merger!!
        val info = PhotoInfo.from(m)
        val rows: RowSource = CroppedRows.of(GeometryRows16(mg.rgb, mg.width, mg.height, Geometry.create(mg.width, mg.height, p, ctx!!.lens), m.orientation), p)
        val format = settings.format
        val temp = File.createTempFile("stack", ".${format.extension}", context.cacheDir)
        try {
            when (format) {
                ExportFormat.PNG -> PngWriter.write(if (settings.bits == 16) rows else EightBit(rows), temp, mapOf("Software" to info.software))
                ExportFormat.TIFF -> TiffWriter.write(if (settings.bits == 16) rows else EightBit(rows), temp, settings.tiffCompressed, info)
                ExportFormat.TGA -> TgaWriter.write(EightBit(rows), temp)
                ExportFormat.JPEG, ExportFormat.WEBP -> {
                    val bmp = toBitmap(EightBit(rows))
                    FileOutputStream(temp).use { out ->
                        if (format == ExportFormat.JPEG) bmp.compress(Bitmap.CompressFormat.JPEG, settings.jpegQuality, out)
                        else bmp.compress(webpLossless(), 100, out)
                    }
                    bmp.recycle()
                }
            }
            if (format == ExportFormat.JPEG || format == ExportFormat.PNG || format == ExportFormat.WEBP) ExportService.writeExif(temp, info)
            folder = ExportService.publish(context, temp, format, first.baseName + "_stack").folder
        } finally {
            temp.delete()
        }
    }

    /** A 16-bit row source read as 8 bits. */
    private class EightBit(private val src: RowSource) : RowSource {
        override val width = src.width
        override val height = src.height
        override val bits = 8
        override fun row(y: Int, out: IntArray) {
            src.row(y, out)
            if (src.bits == 16) for (i in 0 until 3 * width) out[i] = (out[i] * 255 + 32767) / 65535
        }
    }

    private fun toBitmap(rows: RowSource): Bitmap {
        val px = IntArray(rows.width * rows.height)
        val line = IntArray(3 * rows.width)
        for (y in 0 until rows.height) {
            rows.row(y, line)
            for (x in 0 until rows.width) px[y * rows.width + x] = (0xFF shl 24) or (line[3 * x] shl 16) or (line[3 * x + 1] shl 8) or line[3 * x + 2]
        }
        return Bitmap.createBitmap(px, rows.width, rows.height, Bitmap.Config.ARGB_8888)
    }

    @Suppress("DEPRECATION")
    private fun webpLossless() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS
        else Bitmap.CompressFormat.WEBP // quality 100 is lossless before Android 11

    private fun foregroundInfo(index: Int, fraction: Float, name: String?): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Exports", NotificationManager.IMPORTANCE_LOW))
        }
        val title = if (total > 1) "Exporting ${index + 1} of $total" else "Exporting"
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_export)
            .setContentTitle(title)
            .setContentText(name ?: "Preparing…")
            .setProgress(1000, (((index + fraction) / total) * 1000).toInt().coerceIn(0, 1000), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        return when {
            Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        /** Preview size the frames of a focus stack are lined up on. */
        private const val ALIGN_SIZE = 1280
        private const val CHANNEL = "export"
        private const val NOTIFICATION_ID = 4711
    }
}

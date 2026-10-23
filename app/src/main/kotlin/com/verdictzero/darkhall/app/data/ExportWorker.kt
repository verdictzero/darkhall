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
import com.verdictzero.darkhall.core.develop.Demosaic
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.FullRender
import com.verdictzero.darkhall.core.develop.RenderContext
import com.verdictzero.darkhall.core.orf.OlympusDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Renders each queued raw at full resolution with its saved sliders and writes a JPEG. */
class ExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val total = inputData.getInt(ExportQueue.KEY_TOTAL, 1)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0f, null)

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(ExportQueue.KEY_JOB) ?: return Result.failure()
        val uris = ExportQueue.readJob(applicationContext, jobId)
        if (uris.isEmpty()) return Result.failure()
        // Promote to a foreground service; if the platform refuses (rare background-start
        // restrictions) the work still runs, just without the guarantee.
        runCatching { setForeground(foregroundInfo(0, 0f, null)) }

        var ok = 0
        val failedNames = ArrayList<String>()
        for ((i, uri) in uris.withIndex()) {
            if (isStopped) break
            val name = runCatching { OrfRepository.describe(applicationContext.contentResolver, uri).name }.getOrDefault("photo")
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
        val file = OrfRepository.describe(context.contentResolver, uri)
        val (raw, ctx, meta) = run {
            val loaded = OrfRepository.load(context.contentResolver, uri)
            val raw = OlympusDecoder.decode(loaded.bytes, loaded.meta)
            val ctx = RenderContext.forFile(loaded.meta, Demosaic.superpixel(raw).let { half ->
                half.resizeArea(1280, (half.height.toLong() * 1280 / half.width).toInt())
            }, raw, looks = LookLibrary.source(context))
            Triple(raw, ctx, loaded.meta) // the 12 MB file buffer is released here
        }
        val defaults = DevelopParams.defaultsFor(meta, ctx.noise)
        val params = EditsStore.load(context, file.key, defaults) ?: defaults
        val rendered = FullRender.render(raw, ctx, params, progress = { progress(it * 0.85f) }, isCancelled = { isStopped })
            ?: throw IllegalStateException("cancelled")
        var bmp = Bitmap.createBitmap(rendered.argb, rendered.width, rendered.height, Bitmap.Config.ARGB_8888)
        bmp = OrfRepository.applyOrientation(bmp, meta.orientation)
        progress(0.92f)
        ExportService.saveJpeg(context, bmp, meta, file.baseName)
        bmp.recycle()
        progress(1f)
    }

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
        private const val CHANNEL = "export"
        private const val NOTIFICATION_ID = 4711
    }
}

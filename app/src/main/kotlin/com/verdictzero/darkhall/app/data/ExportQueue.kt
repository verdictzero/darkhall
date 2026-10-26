package com.verdictzero.darkhall.app.data

import android.content.Context
import android.net.Uri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.verdictzero.darkhall.core.export.ExportFormat
import com.verdictzero.darkhall.core.export.ExportSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID

/**
 * Queues exports with WorkManager so they run in a foreground service and finish even
 * when the user leaves the app. Each request carries a job file listing its raw files (work
 * input data is capped at 10 KB, too small for a big batch of content URIs).
 */
object ExportQueue {
    const val UNIQUE_NAME = "darkhall-export"
    const val KEY_JOB = "job"
    const val KEY_DONE = "done"
    const val KEY_TOTAL = "total"
    const val KEY_FRACTION = "fraction"
    const val KEY_CURRENT = "current"
    const val KEY_OK = "ok"
    const val KEY_FAILED = "failed"
    const val KEY_FAILED_NAMES = "failedNames"
    const val KEY_FOLDER = "folder"
    /** The exported files' content URIs, newline-separated, for sharing. */
    const val KEY_URIS = "uris"
    private const val KEY_LONG_EDGE = "longEdge"
    private const val KEY_FORMAT = "format"
    private const val KEY_QUALITY = "quality"
    private const val KEY_16 = "sixteenBit"
    private const val KEY_ZIP = "tiffZip"
    /** The files are one focus-bracketed series, to be merged into a single picture. */
    const val KEY_STACK = "stack"

    fun settingsOf(data: Data) = ExportSettings(
        format = ExportFormat.entries.firstOrNull { it.name == data.getString(KEY_FORMAT) } ?: ExportFormat.JPEG,
        jpegQuality = data.getInt(KEY_QUALITY, 95),
        sixteenBit = data.getBoolean(KEY_16, false),
        tiffCompressed = data.getBoolean(KEY_ZIP, true),
        longEdge = data.getInt(KEY_LONG_EDGE, 0),
    )

    /** URIs for [KEY_URIS], as many as fit in WorkManager's output limit; only shareable (content) ones. */
    fun joinUris(uris: List<Uri>): String {
        val sb = StringBuilder()
        for (u in uris.filter { it.scheme == "content" }) {
            val s = u.toString()
            if (sb.length + s.length + 1 > 8000) break
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(s)
        }
        return sb.toString()
    }

    private fun jobDir(context: Context) = File(context.filesDir, "export-jobs").apply { mkdirs() }

    fun enqueue(context: Context, uris: List<Uri>, settings: ExportSettings = ExportSettings(), stack: Boolean = false): UUID {
        val jobId = UUID.randomUUID().toString()
        File(jobDir(context), "$jobId.txt").writeText(uris.joinToString("\n") { it.toString() })
        val request = OneTimeWorkRequestBuilder<ExportWorker>()
            .setInputData(
                workDataOf(
                    KEY_JOB to jobId, KEY_TOTAL to uris.size,
                    KEY_FORMAT to settings.format.name, KEY_QUALITY to settings.jpegQuality,
                    KEY_16 to settings.sixteenBit, KEY_ZIP to settings.tiffCompressed,
                    KEY_STACK to stack, KEY_LONG_EDGE to settings.longEdge,
                )
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(UNIQUE_NAME)
            .build()
        // APPEND_OR_REPLACE: exports run one at a time, in order; a failed one does not block the queue.
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        return request.id
    }

    fun readJob(context: Context, jobId: String): List<Uri> {
        val f = File(jobDir(context), "$jobId.txt")
        if (!f.exists()) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.map { Uri.parse(it) }
    }

    fun deleteJob(context: Context, jobId: String) {
        File(jobDir(context), "$jobId.txt").delete()
    }

    /** Overall state for the progress pill. */
    data class Status(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val fraction: Float = 0f,
        val queued: Int = 0,
        val finished: List<Finished> = emptyList(),
    )

    /** [folder] is where the files went, e.g. "Pictures/Darkhall"; [uris] the files themselves, for sharing. */
    data class Finished(val id: UUID, val ok: Int, val failed: Int, val failedNames: String, val folder: String, val uris: List<Uri> = emptyList())

    fun status(context: Context): Flow<Status> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE_NAME).map { infos ->
            val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
            val queued = infos.count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            val finished = infos.filter { it.state.isFinished }.map { info ->
                val out = info.outputData
                val total = info.progress.getInt(KEY_TOTAL, 0)
                Finished(
                    info.id,
                    out.getInt(KEY_OK, 0),
                    if (info.state == WorkInfo.State.SUCCEEDED) out.getInt(KEY_FAILED, 0) else maxOf(1, total),
                    out.getString(KEY_FAILED_NAMES).orEmpty(),
                    out.getString(KEY_FOLDER) ?: "Pictures/${ExportService.ALBUM}",
                    out.getString(KEY_URIS).orEmpty().split('\n').filter { it.isNotBlank() }.map { Uri.parse(it) },
                )
            }
            Status(
                running = running != null,
                done = running?.progress?.getInt(KEY_DONE, 0) ?: 0,
                total = running?.progress?.getInt(KEY_TOTAL, 0) ?: 0,
                fraction = running?.progress?.getFloat(KEY_FRACTION, 0f) ?: 0f,
                queued = queued,
                finished = finished,
            )
        }
}

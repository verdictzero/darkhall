package com.verdictzero.darkhall.core.util

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory

/** Tiny row-band parallel helper so the pipeline stays dependency free. */
object Parallel {
    val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)

    private val pool = Executors.newFixedThreadPool(threads, object : ThreadFactory {
        private var n = 0
        override fun newThread(r: Runnable): Thread =
            Thread(r, "darkhall-worker-${n++}").apply { isDaemon = true; priority = Thread.NORM_PRIORITY }
    })

    /** Runs [body] for row bands [y0, y1) covering 0 until [rows]. */
    fun rows(rows: Int, body: (y0: Int, y1: Int) -> Unit) {
        if (rows <= 0) return
        val bands = minOf(threads * 2, rows)
        if (bands <= 1) { body(0, rows); return }
        val futures = ArrayList<Future<*>>(bands)
        for (b in 0 until bands) {
            val y0 = (rows.toLong() * b / bands).toInt()
            val y1 = (rows.toLong() * (b + 1) / bands).toInt()
            if (y1 > y0) futures += pool.submit { body(y0, y1) }
        }
        var error: Throwable? = null
        for (f in futures) {
            try { f.get() } catch (e: java.util.concurrent.ExecutionException) { if (error == null) error = e.cause ?: e }
        }
        error?.let { throw it }
    }

    /** Runs [body] for column bands [x0, x1) covering 0 until [cols]. */
    fun cols(cols: Int, body: (x0: Int, x1: Int) -> Unit) = rows(cols, body)
}

package com.verdictzero.darkhall.app.ui

import com.verdictzero.darkhall.app.data.RawFile
import com.verdictzero.darkhall.core.raw.ShotFacts
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the library shows: filters, and whether it is grouped by the day the photos were taken. */
data class LibraryFilter(
    /** Lowest star rating shown; 0 shows everything. */
    val minRating: Int = 0,
    /** true: only edited photos; false: only unedited; null: both. */
    val edited: Boolean? = null,
    val underwaterOnly: Boolean = false,
    /** Only this camera ("Make Model"), or every camera when null. */
    val camera: String? = null,
    val byDate: Boolean = false,
) {
    val active: Boolean get() = minRating > 0 || edited != null || underwaterOnly || camera != null

    /** One block of the grid: a day's photos under [title], or all of them (title null). */
    data class Group(val title: String?, val files: List<RawFile>)

    /**
     * The files to show, in groups. Files whose facts haven't been read yet pass the facts-based
     * filters (they'd otherwise flicker in and out) and, grouped by date, sit at the end.
     */
    fun apply(files: List<RawFile>, facts: Map<String, ShotFacts>, ratings: Map<String, Int>, editedKeys: Set<String>): List<Group> {
        val shown = files.filter { f ->
            val fx = facts[f.key]
            (ratings[f.key] ?: 0) >= minRating &&
                (edited == null || (f.key in editedKeys) == edited) &&
                (!underwaterOnly || fx == null || fx.underwater) &&
                (camera == null || fx == null || cameraOf(fx) == camera)
        }
        if (!byDate) return listOf(Group(null, shown))
        val byDay = shown.groupBy { f -> facts[f.key]?.let { day(it) } }
        val days = byDay.keys.filterNotNull().sortedDescending()
        return days.map { d -> Group(title(d), byDay.getValue(d).sortedBy { facts[it.key]?.dateTime }) } +
            listOfNotNull(byDay[null]?.let { Group("Date unknown", it) })
    }

    companion object {
        fun cameraOf(f: ShotFacts): String = listOf(f.make, f.model).filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { "Unknown camera" }

        /** "YYYY-MM-DD" from EXIF "YYYY:MM:DD HH:MM:SS", or null. */
        private fun day(f: ShotFacts): String? =
            f.dateTime.takeIf { it.length >= 10 && it[4] == ':' && it[7] == ':' }?.take(10)?.replace(':', '-')

        private fun title(day: String): String = runCatching {
            LocalDate.parse(day).format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault()))
        }.getOrDefault(day)
    }
}

package com.verdictzero.darkhall.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verdictzero.darkhall.app.data.RawFile
import com.verdictzero.darkhall.app.ui.LibraryFilter
import com.verdictzero.darkhall.core.raw.ShotFacts
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LibraryFilterTest {
    private fun file(name: String) = RawFile(Uri.parse("file:///raws/$name"), name, 1000L + name.hashCode(), 0L)

    private fun facts(model: String, date: String, depth: Double? = null) =
        ShotFacts("OM Digital Solutions", model, date, 100, 0.01, 2.0, 4.5, 4000, 3000, depth, null, null, null, null, null)

    private val a = file("P1.ORF"); private val b = file("P2.ORF"); private val c = file("P3.ORF"); private val d = file("IMG.CR2")
    private val files = listOf(a, b, c, d)
    private val facts = mapOf(
        a.key to facts("TG-7", "2025:08:14 10:00:00", depth = 8.0),
        b.key to facts("TG-7", "2025:08:14 09:00:00"),
        c.key to facts("TG-6", "2025:08:15 12:00:00", depth = 3.0),
        // d's facts not read yet
    )
    private val ratings = mapOf(a.key to 5, c.key to 2)

    private fun names(groups: List<LibraryFilter.Group>) = groups.map { g -> g.title to g.files.map { it.name } }

    @Test
    fun filters() {
        fun shown(f: LibraryFilter) = f.apply(files, facts, ratings, setOf(b.key)).flatMap { it.files }.map { it.name }
        assertEquals(listOf("P1.ORF", "P2.ORF", "P3.ORF", "IMG.CR2"), shown(LibraryFilter()))
        assertEquals(listOf("P1.ORF", "P3.ORF"), shown(LibraryFilter(minRating = 1)))
        assertEquals(listOf("P1.ORF"), shown(LibraryFilter(minRating = 3)))
        assertEquals(listOf("P2.ORF"), shown(LibraryFilter(edited = true)))
        assertEquals(listOf("P1.ORF", "P3.ORF", "IMG.CR2"), shown(LibraryFilter(edited = false)))
        // Files not read yet stay in until their facts say otherwise.
        assertEquals(listOf("P1.ORF", "P3.ORF", "IMG.CR2"), shown(LibraryFilter(underwaterOnly = true)))
        assertEquals(listOf("P3.ORF", "IMG.CR2"), shown(LibraryFilter(camera = "OM Digital Solutions TG-6")))
    }

    @Test
    fun groupsByDayNewestFirst() {
        val groups = LibraryFilter(byDate = true).apply(files, facts, ratings, emptySet())
        val titles = groups.map { it.title!! }
        assertEquals(3, groups.size)
        assertEquals(true, titles[0].contains("15"))
        assertEquals(true, titles[1].contains("14"))
        assertEquals("Date unknown", titles[2])
        // Within a day, in the order taken.
        assertEquals(listOf("P2.ORF", "P1.ORF"), groups[1].files.map { it.name })
        assertEquals(listOf(null to listOf("P1.ORF", "P2.ORF", "P3.ORF", "IMG.CR2")), names(LibraryFilter().apply(files, facts, ratings, emptySet())))
    }
}

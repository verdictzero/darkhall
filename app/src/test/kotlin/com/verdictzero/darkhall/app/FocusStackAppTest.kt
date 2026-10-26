package com.verdictzero.darkhall.app

import android.Manifest
import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.verdictzero.darkhall.app.data.ExportQueue
import com.verdictzero.darkhall.app.data.Prefs
import com.verdictzero.darkhall.app.ui.LibraryViewModel
import com.verdictzero.darkhall.core.export.ExportFormat
import com.verdictzero.darkhall.core.export.ExportSettings
import com.verdictzero.darkhall.core.raw.RawFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration

/**
 * A focus stack from the library, end to end: two frames (copies of the sample) selected,
 * merged by the export worker, written as one full-size 16-bit TIFF named after the first.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FocusStackAppTest {
    private val samplePath = System.getProperty("darkhall.sample").orEmpty()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        Robolectric.setupContentProvider(FakeMediaStore::class.java, "media")
        FakeMediaStore.inserted.clear()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun waitFor(timeoutMs: Long = 600_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
    }

    @Test
    fun stacksTheSelection() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        val dir = File(app.cacheDir, "bracket").apply { mkdirs() }
        val ext = samplePath.substringAfterLast('.')
        val frames = listOf("P0001", "P0002").map { n -> File(dir, "$n.$ext").also { File(samplePath).copyTo(it, overwrite = true) } }
        Prefs(app).extraFiles = frames.map { Uri.fromFile(it) }
        val vm = LibraryViewModel(app)
        waitFor { vm.state.value.files.size == 2 && !vm.state.value.loading }
        vm.state.value.files.forEach { vm.toggle(it) }
        vm.stackSelection(ExportSettings(ExportFormat.TIFF, sixteenBit = true))

        val wm = WorkManager.getInstance(app)
        waitFor { wm.getWorkInfosForUniqueWork(ExportQueue.UNIQUE_NAME).get().isNotEmpty() }
        val id = wm.getWorkInfosForUniqueWork(ExportQueue.UNIQUE_NAME).get().first().id
        if (!wm.getWorkInfoById(id).get()!!.state.isFinished) WorkManagerTestInitHelper.getTestDriver(app)!!.setAllConstraintsMet(id)
        waitFor { wm.getWorkInfoById(id).get()!!.state.isFinished }
        val info = wm.getWorkInfoById(id).get()!!
        assertEquals(info.outputData.toString(), WorkInfo.State.SUCCEEDED, info.state)
        assertEquals(info.outputData.toString(), 1, info.outputData.getInt(ExportQueue.KEY_OK, 0))
        assertEquals(1, FakeMediaStore.inserted.size)
        val (uri, values) = FakeMediaStore.inserted.single()
        val name = values.getAsString("_display_name")
        assertTrue(name, name.startsWith("P0001_stack") && name.endsWith(".tif"))

        val meta = RawFormats.parse(File(samplePath).readBytes())
        val portrait = meta.orientation == 6 || meta.orientation == 8
        val (ew, eh) = if (portrait) meta.crop.height to meta.crop.width else meta.crop.width to meta.crop.height
        val t = ByteBuffer.wrap(FakeMediaStore.fileFor(uri).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val ifd = t.getInt(4)
        val tags = (0 until t.getShort(ifd).toInt()).associate { (t.getShort(ifd + 2 + 12 * it).toInt() and 0xFFFF) to ifd + 2 + 12 * it }
        assertEquals(ew, t.getInt(tags[256]!! + 8))
        assertEquals(eh, t.getInt(tags[257]!! + 8))
        assertTrue(t.capacity() > ew * eh) // a real picture, not an empty file
    }
}

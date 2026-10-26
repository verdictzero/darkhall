package com.verdictzero.darkhall.app

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import android.graphics.BitmapFactory
import com.verdictzero.darkhall.app.ui.SLIDERS_TAG
import kotlin.math.abs
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.verdictzero.darkhall.app.data.ExportQueue
import com.verdictzero.darkhall.core.raw.RawFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * The crop end to end: set in the editor, it shapes the exported file. A class of its own
 * because the view model factory keeps the first test's application (see app/build.gradle.kts).
 * Uses -Pdarkhall.sample; skipped without it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CropExportTest {
    @Suppress("DEPRECATION")
    @get:Rule
    val compose = createEmptyComposeRule()

    private val path = System.getProperty("darkhall.sample").orEmpty()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        Robolectric.setupContentProvider(FakeMediaStore::class.java, "media")
        FakeMediaStore.inserted.clear()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // Methods share the app's files: start each from unedited photos.
        File(app.filesDir, "edits").deleteRecursively()
    }

    private fun waitFor(timeoutMs: Long = 120_000, condition: () -> Boolean) {
        compose.waitUntil(timeoutMs) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(10)
            condition()
        }
    }

    /** Opens the dialog, picks [chips] in order, starts the export and returns the file. */
    private fun export(vararg chips: String): Pair<File, String> {
        val wm = WorkManager.getInstance(app)
        val known = wm.getWorkInfosForUniqueWork(ExportQueue.UNIQUE_NAME).get().map { it.id }.toSet()
        val before = FakeMediaStore.inserted.size
        compose.onNodeWithText("Export").performClick()
        for (c in chips) compose.onNodeWithText(c).performClick()
        compose.onNodeWithText("Start export").performClick()
        // The new request (it may already have run: the executor is synchronous).
        waitFor(30_000) { wm.getWorkInfosForUniqueWork(ExportQueue.UNIQUE_NAME).get().any { it.id !in known } }
        val id = wm.getWorkInfosForUniqueWork(ExportQueue.UNIQUE_NAME).get().first { it.id !in known }.id
        if (!wm.getWorkInfoById(id).get()!!.state.isFinished) WorkManagerTestInitHelper.getTestDriver(app)!!.setAllConstraintsMet(id)
        waitFor(600_000) { wm.getWorkInfoById(id).get()!!.state.isFinished }
        val info = wm.getWorkInfoById(id).get()!!
        assertEquals(info.outputData.toString(), WorkInfo.State.SUCCEEDED, info.state)
        assertEquals(1, info.outputData.getInt(ExportQueue.KEY_OK, 0))
        assertEquals(before + 1, FakeMediaStore.inserted.size)
        val (uri, values) = FakeMediaStore.inserted.last()
        return FakeMediaStore.fileFor(uri) to values.getAsString("_display_name")
    }

    /** A 1:1 crop set in the editor comes out square, through Android's encoder and Darkhall's own. */
    @Test
    fun croppedExport() {
        assumeTrue("no -Pdarkhall.sample", path.isNotEmpty() && File(path).exists())
        val meta = RawFormats.parse(File(path).readBytes())
        val side = minOf(meta.crop.width, meta.crop.height)
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(File(path))).setClass(app, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use {
            waitFor { compose.onAllNodesWithText(" ms · ", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(SLIDERS_TAG).performScrollToNode(hasTestTag("crop-SQUARE"))
            compose.onNodeWithTag("crop-SQUARE").performClick()
            waitFor { compose.onAllNodesWithText("Cropped to 1:1", substring = true).fetchSemanticsNodes().isNotEmpty() }
            // Slide the square sideways; it keeps its shape and size.
            compose.onNodeWithContentDescription("Crop").performTouchInput { swipe(center, center - Offset(width * 0.3f, 0f), 300) }
            compose.waitForIdle()
            assertTrue(compose.onAllNodesWithText("Cropped to 1:1 · %,d × %,d px".format(side, side)).fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithText("Done").performClick()

            val (jpg, _) = export("JPEG")
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(jpg.path, o)
            assertTrue("JPEG ${o.outWidth}x${o.outHeight}, expected $side", abs(o.outWidth - side) <= 1 && abs(o.outHeight - side) <= 1)

            val (tga, _) = export("TGA")
            val g = tga.readBytes()
            val tw = (g[12].toInt() and 0xFF) or ((g[13].toInt() and 0xFF) shl 8)
            val th = (g[14].toInt() and 0xFF) or ((g[15].toInt() and 0xFF) shl 8)
            assertTrue("TGA ${tw}x$th, expected $side", abs(tw - side) <= 1 && abs(th - side) <= 1)
            export("JPEG")
        }
    }
}

package com.verdictzero.darkhall.app

import android.Manifest
import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.verdictzero.darkhall.app.data.ExportQueue
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Runs the real app on the JVM against a real TG-7 file: open it the way a file manager
 * does, move sliders, zoom to full resolution, copy settings, export, and check the JPEG.
 * Run with -Pdarkhall.sample=/path/to/file.orf; skipped otherwise.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class AppSmokeTest {

    @Suppress("DEPRECATION") // v2 rule changes dispatcher semantics; the classic rule suits this end-to-end test
    @get:Rule
    val compose = createEmptyComposeRule()

    private val samplePath = System.getProperty("darkhall.sample").orEmpty()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        Robolectric.setupContentProvider(FakeMediaStore::class.java, "media")
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Robolectric's main-thread clock is simulated: delays and debounces on the main
     * dispatcher only fire when it moves. Advance it while waiting on real background work.
     */
    private fun waitFor(timeoutMs: Long = 120_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                Thread.sleep(10)
                condition()
            }
        } catch (e: Throwable) {
            println("---- screen at timeout ----")
            println(compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
                .mapNotNull { n ->
                    (n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(""))
                        ?.let { "  · $it" }
                }.joinToString("\n"))
            throw e
        }
    }

    private fun waitForText(text: String, timeoutMs: Long = 120_000, substring: Boolean = false) =
        waitFor(timeoutMs) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun opensTg7EditsZoomsAndExports() {
        assumeTrue("no sample configured", samplePath.isNotEmpty() && File(samplePath).exists())
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(File(samplePath))).setClass(app, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            // Develop screen: file decoded and sliders shown.
            waitForText("Exposure")
            // First preview rendered. Robolectric has no OpenGL ES, so this also proves the
            // GPU path backs off cleanly and the CPU takes over.
            waitForText(" ms · CPU", substring = true)
            // The slider list scrolls; bring the TG-specific sliders into view.
            val list = compose.onNode(hasScrollToIndexAction())
            list.performScrollToNode(hasText("Underwater"))
            compose.onNodeWithText("Underwater").assertExistsCompat()
            list.performScrollToNode(hasText("Lens profile"))
            compose.onNodeWithText("Lens profile").assertExistsCompat()
            list.performScrollToIndex(0)
            compose.waitForIdle()

            // Move every slider to a new value through its semantics action, as TalkBack would.
            val sliders = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            val count = sliders.fetchSemanticsNodes().size
            println("visible sliders: $count")
            assertTrue("expected sliders on screen, got $count", count >= 5)
            for (i in 0 until count) {
                sliders[i].performSemanticsAction(SemanticsActions.SetProgress) { it(if (i % 2 == 0) 0.5f else 20f) }
            }
            compose.waitForIdle()

            // Pinch to zoom in: the app should switch to a full-resolution render of the visible area.
            compose.onNodeWithContentDescription("Preview").assertExistsCompat()
            compose.onNodeWithContentDescription("Preview").performTouchInput {
                pinch(center - Offset(20f, 0f), center - Offset(200f, 0f), center + Offset(20f, 0f), center + Offset(200f, 0f))
            }
            waitForText("Full resolution", timeoutMs = 120_000)

            // Auto tone: sets the tone sliders, reports them, and Undo restores the previous values.
            list.performScrollToIndex(0)
            compose.waitForIdle()
            fun exposureText() = compose.onAllNodesWithText(" EV", substring = true).fetchSemanticsNodes()
                .firstNotNullOf { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") }
            val beforeAuto = exposureText()
            compose.onNodeWithText("Auto").performClick()
            waitForText("Auto:", substring = true)
            val afterAuto = exposureText()
            println("exposure before auto: $beforeAuto, after: $afterAuto")
            assertTrue("auto should change exposure", beforeAuto != afterAuto)
            compose.onNodeWithText("Undo").performClick()
            waitFor { exposureText() == beforeAuto }

            // Pick a look in the browser: thumbnails render, the strength slider appears.
            compose.onNodeWithText("Pinch").assertDoesNotExistCompat()
            list.performScrollToIndex(0)
            compose.onNodeWithContentDescription("Browse looks").performClick()
            waitForText("Search films and looks")
            waitFor { compose.onAllNodesWithContentDescription("Look Reef").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Look Reef").performClick()
            waitForText("Look strength")
            compose.onNodeWithText("Reef").assertExistsCompat()

            // Copy settings via the overflow menu.
            compose.onNodeWithContentDescription("More").performClick()
            compose.onNodeWithText("Copy settings").performClick()
            compose.waitForIdle()

            // Export: queued through WorkManager.
            compose.onNodeWithText("Export").performClick()
            waitFor(30_000) {
                WorkManager.getInstance(app).getWorkInfosForUniqueWork(ExportQueue.UNIQUE_NAME).get().isNotEmpty()
            }
            val infos = WorkManager.getInstance(app).getWorkInfosForUniqueWork(ExportQueue.UNIQUE_NAME).get()
            assertEquals(1, infos.size)
            val id = infos[0].id
            WorkManagerTestInitHelper.getTestDriver(app)!!.setAllConstraintsMet(id)
            waitFor(300_000) {
                WorkManager.getInstance(app).getWorkInfoById(id).get()!!.state.isFinished
            }
            val done = WorkManager.getInstance(app).getWorkInfoById(id).get()!!
            println("export state=${done.state} output=${done.outputData}")
            assertEquals(WorkInfo.State.SUCCEEDED, done.state)
            assertEquals(1, done.outputData.getInt(ExportQueue.KEY_OK, 0))

            // The JPEG: full resolution, rotated for the portrait shot, shooting data carried over.
            val (uri, values) = FakeMediaStore.inserted.single()
            val file = FakeMediaStore.fileFor(uri)
            println("exported ${values.getAsString("_display_name")} -> ${file.length()} bytes")
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, opts)
            println("jpeg ${opts.outWidth}x${opts.outHeight}")
            assertEquals(setOf(3000, 4000), setOf(opts.outWidth, opts.outHeight))
            val exif = ExifInterface(file.path)
            println("exif model=${exif.getAttribute(ExifInterface.TAG_MODEL)} description=${exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)}")
            assertTrue(exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty().startsWith("TG-"))
            assertTrue(exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION).orEmpty().contains("hPa"))
            assertEquals("100", exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY))
            val outDir = System.getProperty("darkhall.outDir").orEmpty()
            if (outDir.isNotEmpty()) file.copyTo(File(outDir, "app_export.jpg"), overwrite = true)

            // The chosen look was saved with the photo's edits.
            val key = com.verdictzero.darkhall.app.data.RawFile(Uri.fromFile(File(samplePath)), File(samplePath).name, 0, 0).key
            val saved = com.verdictzero.darkhall.app.data.EditsStore.load(app, key, com.verdictzero.darkhall.core.develop.DevelopParams.NEUTRAL)
            assertEquals("dh-underwater-reef", saved?.look)

            // Back to the library: the develop session is released.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitForText("Darkhall")
        }
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsCompat() = assertExists()
private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()

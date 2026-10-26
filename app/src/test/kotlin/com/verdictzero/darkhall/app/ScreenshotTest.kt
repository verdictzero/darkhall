package com.verdictzero.darkhall.app

import android.Manifest
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import com.verdictzero.darkhall.app.ui.SLIDERS_TAG
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import org.junit.Assert.assertTrue
import kotlin.math.PI
import kotlin.math.tan
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.Duration

/**
 * Renders the editor and the look browser to PNGs (with -Pdarkhall.outDir) so the UI can be
 * reviewed without a device. Skipped unless both a sample and an output directory are given.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @Suppress("DEPRECATION")
    @get:Rule
    val compose = createEmptyComposeRule()

    private val samplePath = System.getProperty("darkhall.sample").orEmpty()
    private val outDir = System.getProperty("darkhall.outDir").orEmpty()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun waitFor(timeoutMs: Long = 120_000, condition: () -> Boolean) {
        compose.waitUntil(timeoutMs) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(10)
            condition()
        }
    }

    /** Draws every window (activity plus any dialog), bottom to top, into one bitmap. */
    private fun shot(name: String) {
        repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); Thread.sleep(20) }
        compose.waitForIdle()
        val wmg = Class.forName("android.view.WindowManagerGlobal")
        val global = wmg.getMethod("getInstance").invoke(null)
        val viewsField = wmg.getDeclaredField("mViews").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val views = (viewsField.get(global) as List<View>).toList()
        val root = views.first()
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        for (v in views) {
            if (v.width == 0 || v.height == 0) continue
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            canvas.save()
            canvas.translate(loc[0].toFloat(), loc[1].toFloat())
            v.draw(canvas)
            canvas.restore()
        }
        FileOutputStream(File(outDir, "screen_$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun editorAndLookBrowser() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists() && outDir.isNotEmpty())
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(File(samplePath))).setClass(app, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use {
            waitFor { compose.onAllNodesWithText(" ms · ", substring = true).fetchSemanticsNodes().isNotEmpty() }
            shot("editor")
            compose.onNodeWithText("Auto").performClick()
            waitFor { compose.onAllNodesWithText("Auto:", substring = true).fetchSemanticsNodes().isNotEmpty() }
            repeat(30) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(40) }
            shot("auto")
            compose.onNodeWithText("Undo").performClick()
            compose.onNodeWithContentDescription("Browse looks").performClick()
            waitFor { compose.onAllNodesWithContentDescription("Look Reef").fetchSemanticsNodes().isNotEmpty() }
            // Let the first thumbnails render.
            repeat(60) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); Thread.sleep(50) }
            shot("looks")
            // (Reef may also sit on the Favorites shelf if another test made it a favorite.)
            compose.onAllNodesWithContentDescription("Look Reef")[0].performClick()
            waitFor { compose.onAllNodesWithText("Look strength").fetchSemanticsNodes().isNotEmpty() }
            repeat(40) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); Thread.sleep(50) }
            shot("editor_reef")
        }
    }

    /**
     * The straighten tools: Draw level shows the grid, a drawn line sets Straighten so that it
     * becomes level, and Auto straighten either levels the photo or says it found nothing.
     * Uses -Pdarkhall.otherSample when given (a photo with a horizon shows more), else the
     * main sample.
     */
    @Test
    fun straightenTools() {
        val path = System.getProperty("darkhall.otherSample").orEmpty().ifEmpty { samplePath }
        assumeTrue(path.isNotEmpty() && File(path).exists())
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(File(path))).setClass(app, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use {
            waitFor { compose.onAllNodesWithText(" ms · ", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(SLIDERS_TAG).performScrollToNode(hasText("Draw level"))
            compose.onNodeWithText("Draw level").performClick()
            waitFor { compose.onAllNodesWithText("Drag along the horizon", substring = true).fetchSemanticsNodes().isNotEmpty() }
            if (outDir.isNotEmpty()) shot("straighten_grid")

            // A line rising 2 degrees to the right: Straighten must turn the picture back by 2.
            compose.onNodeWithContentDescription("Draw a line to level").performTouchInput {
                val y = centerY
                val dx = width * 0.6f
                swipe(Offset(centerX - dx / 2, y + dx / 2 * tan(2.0 * PI / 180).toFloat()), Offset(centerX + dx / 2, y - dx / 2 * tan(2.0 * PI / 180).toFloat()), 400)
            }
            waitFor { compose.onAllNodesWithText("Level: Straighten", substring = true).fetchSemanticsNodes().isNotEmpty() }
            val message = compose.onAllNodesWithText("Level: Straighten", substring = true).fetchSemanticsNodes()
                .first().config[SemanticsProperties.Text].joinToString("")
            assertTrue(message, message.contains("-2.0°") || message.contains("−2.0°"))
            if (outDir.isNotEmpty()) shot("straighten_level")
            compose.onNodeWithText("Undo").performClick()

            compose.onNodeWithText("Auto straighten").performClick()
            waitFor {
                compose.onAllNodesWithText("Auto: Straighten", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText("Auto: already level").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText("No clear horizon", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            if (outDir.isNotEmpty()) shot("straighten_auto")
        }
    }

    /**
     * Comparing and history: holding the photo shows the original, the split views show both,
     * and Undo / Redo step through an Auto.
     */
    @Test
    fun compareAndHistory() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(File(samplePath))).setClass(app, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use {
            waitFor { compose.onAllNodesWithText(" ms · ", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Undo").assertIsNotEnabled()

            // Hold the photo still: the original shows until the finger lifts.
            compose.onNodeWithContentDescription("Preview").performTouchInput { down(center) }
            waitFor(10_000) { compose.onAllNodesWithText("Original").fetchSemanticsNodes().isNotEmpty() }
            if (outDir.isNotEmpty()) shot("hold_original")
            compose.onNodeWithContentDescription("Preview").performTouchInput { up() }
            waitFor(10_000) { compose.onAllNodesWithText("Hold for original").fetchSemanticsNodes().isNotEmpty() }

            compose.onNodeWithContentDescription("Side by side with the original").performClick()
            waitFor { compose.onAllNodesWithText("Edited").fetchSemanticsNodes().isNotEmpty() }
            if (outDir.isNotEmpty()) shot("side_by_side")
            compose.onNodeWithContentDescription("Original above, edit below").performClick()
            if (outDir.isNotEmpty()) shot("top_bottom")
            compose.onNodeWithContentDescription("Single view").performClick()
            waitFor { compose.onAllNodesWithText("Edited").fetchSemanticsNodes().isEmpty() }

            // Auto makes one undo step; Undo takes it back, Redo brings it again.
            compose.onNodeWithText("Auto").performClick()
            waitFor { compose.onAllNodesWithText("Auto:", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Undo").assertIsEnabled()
            compose.onNodeWithContentDescription("Redo").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Undo").performClick()
            waitFor { compose.onAllNodes(hasContentDescription("Redo") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Undo").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Redo").performClick()
            waitFor { compose.onAllNodes(hasContentDescription("Undo") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Redo").assertIsNotEnabled()
            if (outDir.isNotEmpty()) shot("history")
        }
    }

    /** Favorite looks: the heart adds them, they get a shelf and a chip, and a strip in the editor. */
    @Test
    fun favoriteLooks() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(File(samplePath))).setClass(app, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use {
            waitFor { compose.onAllNodesWithText(" ms · ", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Browse looks").performClick()
            waitFor { compose.onAllNodesWithContentDescription("Add Reef to favorites").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Add Reef to favorites").performClick()
            waitFor { compose.onAllNodesWithContentDescription("Remove Reef from favorites").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("♥ Favorites").performClick()
            waitFor { compose.onAllNodesWithContentDescription("Look Reef").fetchSemanticsNodes().size == 1 }
            repeat(30) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); Thread.sleep(40) }
            if (outDir.isNotEmpty()) shot("favorites_shelf")
            compose.onNodeWithContentDescription("Close looks").performClick()

            // The editor's strip applies a favorite in one tap, and a second tap takes it off.
            waitFor { compose.onAllNodesWithContentDescription("Favorite look Reef").fetchSemanticsNodes().isNotEmpty() }
            // (The photo may already wear Reef from an earlier test, so check that each tap flips it.)
            fun lookOn() = compose.onAllNodesWithText("Look strength").fetchSemanticsNodes().isNotEmpty()
            val before = lookOn()
            compose.onNodeWithContentDescription("Favorite look Reef").performClick()
            waitFor { lookOn() != before }
            if (!before) {
                repeat(30) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); Thread.sleep(40) }
                if (outDir.isNotEmpty()) shot("favorites_strip")
            }
            compose.onNodeWithContentDescription("Favorite look Reef").performClick()
            waitFor { lookOn() == before }
        }
    }

    @Test
    fun licenses() {
        assumeTrue(outDir.isNotEmpty())
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithContentDescription("More").performClick()
            compose.onNodeWithText("Open-source licences").performClick()
            shot("licenses")
            compose.onNodeWithText("LibRaw").performClick()
            shot("licenses_libraw")
        }
    }

    /** The export dialog with TIFF and 16-bit picked. */
    @Test
    fun exportDialog() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists() && outDir.isNotEmpty())
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(File(samplePath))).setClass(app, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use {
            waitFor { compose.onAllNodesWithText(" ms · ", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Export").performClick()
            compose.onNodeWithText("TIFF").performClick()
            compose.onNodeWithText("16-bit").performClick()
            shot("export_tiff")
            compose.onNodeWithText("JPEG").performClick()
            shot("export_jpeg")
            compose.onNodeWithText("Cancel").performClick()
        }
    }

    /** The crop tool: a 4:5 crop over the whole picture, then the cropped result. */
    @Test
    fun cropTool() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists() && outDir.isNotEmpty())
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(File(samplePath))).setClass(app, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use {
            waitFor { compose.onAllNodesWithText(" ms · ", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(SLIDERS_TAG).performScrollToNode(hasTestTag("crop-R5_4"))
            compose.onNodeWithTag("crop-R5_4").performClick()
            if (compose.onAllNodesWithContentDescription("Make the crop portrait").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithContentDescription("Make the crop portrait").performScrollTo().performClick()
            }
            waitFor { compose.onAllNodesWithText("Cropped to 4:5", substring = true).fetchSemanticsNodes().isNotEmpty() }
            // Slide it a little to the right.
            compose.onNodeWithContentDescription("Crop").performTouchInput { swipe(center, center + Offset(width * 0.08f, 0f), 300) }
            shot("crop_tool")
            compose.onNodeWithText("Done").performClick()
            waitFor { compose.onAllNodesWithText("Done").fetchSemanticsNodes().isEmpty() }
            shot("crop_done")
            compose.onNodeWithText("Reset").performClick()
        }
    }

    /** Sky: found when the section comes into view, shown as a mask, and the sliders change it. */
    @Test
    fun skyTools() {
        val path = System.getProperty("darkhall.otherSample").orEmpty().ifEmpty { samplePath }
        assumeTrue(path.isNotEmpty() && File(path).exists())
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(File(path))).setClass(app, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use {
            waitFor { compose.onAllNodesWithText(" ms · ", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(SLIDERS_TAG).performScrollToNode(hasText("Show mask"))
            waitFor(180_000) {
                compose.onAllNodesWithText("Sky found", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText("No sky found", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            val found = compose.onAllNodesWithText("Sky found", substring = true).fetchSemanticsNodes().isNotEmpty()
            println("sky: " + if (found) "found" else "none")
            compose.onNodeWithText("Show mask").performClick()
            if (found) {
                waitFor { compose.onAllNodesWithText("Sky mask").fetchSemanticsNodes().isNotEmpty() }
                if (outDir.isNotEmpty()) shot("sky_mask")
                compose.onNodeWithText("Show mask").performClick()
                waitFor { compose.onAllNodesWithText("Sky mask").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag(SLIDERS_TAG).performScrollToNode(hasTestTag("slider-SKY_SATURATION"))
                compose.onNodeWithTag("slider-SKY_EXPOSURE").performSemanticsAction(SemanticsActions.SetProgress) { it(-0.8f) }
                compose.onNodeWithTag("slider-SKY_SATURATION").performSemanticsAction(SemanticsActions.SetProgress) { it(45f) }
                compose.onNodeWithTag("slider-SKY_CLARITY").performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
                waitFor { compose.onAllNodesWithText("-0.80 EV").fetchSemanticsNodes().isNotEmpty() }
                // Sky sliders render on the CPU.
                waitFor { compose.onAllNodesWithText(" ms · CPU", substring = true).fetchSemanticsNodes().isNotEmpty() }
                repeat(30) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); Thread.sleep(40) }
                if (outDir.isNotEmpty()) shot("sky_edited")
            }
        }
    }
}

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
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
            compose.onNodeWithContentDescription("Look Reef").performClick()
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
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Draw level"))
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
}

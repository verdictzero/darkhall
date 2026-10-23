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
import androidx.compose.ui.test.performClick
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
}

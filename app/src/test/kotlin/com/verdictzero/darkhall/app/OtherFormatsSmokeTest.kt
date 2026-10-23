package com.verdictzero.darkhall.app

import android.Manifest
import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * The app end to end on other cameras' raws: open each the way a file manager does, wait for
 * the preview, export, and check the JPEG's size and EXIF.
 * Run with -Pdarkhall.otherSample=/path/to/file (one file per run: WorkManager's test
 * instance does not survive a second app in the same JVM); skipped otherwise.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OtherFormatsSmokeTest {

    private val path = System.getProperty("darkhall.otherSample").orEmpty()

    @Suppress("DEPRECATION")
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        Robolectric.setupContentProvider(FakeMediaStore::class.java, "media")
        FakeMediaStore.inserted.clear()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

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
                .mapNotNull { n -> (n.config.getOrNull(SemanticsProperties.Text)?.joinToString(""))?.let { "  · $it" } }
                .joinToString("\n"))
            throw e
        }
    }

    private fun hasText(text: String, substring: Boolean = false) =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun opensAndExports() {
        assumeTrue("no -Pdarkhall.otherSample", path.isNotEmpty() && File(path).exists())
        val meta = RawFormats.parse(File(path).readBytes())
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(File(path))).setClass(app, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use {
            waitFor { hasText("Exposure") }
            waitFor { hasText(" ms · CPU", substring = true) }
            // A DNG that carries a lens warp gets the Lens profile slider, like a TG file.
            println("${meta.format} ${meta.model}: lens warp=${meta.lensWarp != null}")

            compose.onNodeWithText("Export").performClick()
            waitFor(30_000) { WorkManager.getInstance(app).getWorkInfosForUniqueWork(ExportQueue.UNIQUE_NAME).get().isNotEmpty() }
            val id = WorkManager.getInstance(app).getWorkInfosForUniqueWork(ExportQueue.UNIQUE_NAME).get().last().id
            WorkManagerTestInitHelper.getTestDriver(app)!!.setAllConstraintsMet(id)
            waitFor(600_000) { WorkManager.getInstance(app).getWorkInfoById(id).get()!!.state.isFinished }
            val done = WorkManager.getInstance(app).getWorkInfoById(id).get()!!
            println("export state=${done.state} output=${done.outputData}")
            assertEquals(WorkInfo.State.SUCCEEDED, done.state)

            val file = FakeMediaStore.fileFor(FakeMediaStore.inserted.last().first)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, opts)
            println("jpeg ${opts.outWidth}x${opts.outHeight} for crop ${meta.crop.width}x${meta.crop.height}, orientation ${meta.orientation}")
            assertEquals(setOf(meta.crop.width, meta.crop.height), setOf(opts.outWidth, opts.outHeight))
            val portrait = meta.orientation == 6 || meta.orientation == 8
            assertEquals(portrait, opts.outHeight > opts.outWidth == (meta.crop.width > meta.crop.height))
            val exif = ExifInterface(file.path)
            assertEquals(meta.model, exif.getAttribute(ExifInterface.TAG_MODEL))
            assertTrue(file.length() > 100_000)
            val outDir = System.getProperty("darkhall.outDir").orEmpty()
            if (outDir.isNotEmpty()) file.copyTo(File(outDir, "export_" + File(path).nameWithoutExtension + ".jpg"), overwrite = true)
        }
    }
}

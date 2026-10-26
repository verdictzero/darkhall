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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration

/**
 * Each export format end to end through the dialog: the file in the media store must be that
 * format, full size and upright, at the chosen bit depth, with the shooting data where the
 * format can hold it. Uses -Pdarkhall.sample; skipped without it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportFormatsAppTest {
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

    @Test
    fun everyFormat() {
        assumeTrue("no -Pdarkhall.sample", path.isNotEmpty() && File(path).exists())
        val meta = RawFormats.parse(File(path).readBytes())
        val portrait = meta.orientation == 6 || meta.orientation == 8
        val (ew, eh) = if (portrait) meta.crop.height to meta.crop.width else meta.crop.width to meta.crop.height
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(File(path))).setClass(app, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use {
            waitFor { compose.onAllNodesWithText(" ms · ", substring = true).fetchSemanticsNodes().isNotEmpty() }

            // 16-bit TIFF, ZIP: header, size, bit depth, EXIF and GPS directories.
            val (tif, tifName) = export("TIFF", "16-bit", "ZIP")
            assertTrue(tifName, tifName.endsWith(".tif"))
            val t = ByteBuffer.wrap(tif.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x4949, t.getShort(0).toInt() and 0xFFFF)
            val ifd = t.getInt(4)
            val tags = (0 until t.getShort(ifd).toInt()).associate { (t.getShort(ifd + 2 + 12 * it).toInt() and 0xFFFF) to ifd + 2 + 12 * it }
            assertEquals(ew, t.getInt(tags[256]!! + 8))
            assertEquals(eh, t.getInt(tags[257]!! + 8))
            assertEquals(16, t.getShort(t.getInt(tags[258]!! + 8)).toInt())
            assertEquals(8, t.getShort(tags[259]!! + 8).toInt())
            assertTrue(34665 in tags)
            println("TIFF ${tif.length() / 1_000_000} MB, ${ew}x$eh")

            // 16-bit PNG: IHDR, and EXIF added by ExifInterface.
            val (png, pngName) = export("PNG", "16-bit")
            assertTrue(pngName.endsWith(".png"))
            val p = ByteBuffer.wrap(png.readBytes())
            assertEquals(0x89504E47.toInt(), p.getInt(0))
            assertEquals(ew, p.getInt(16)); assertEquals(eh, p.getInt(20))
            assertEquals(16, p.get(24).toInt())
            assertEquals(meta.model, ExifInterface(png.path).getAttribute(ExifInterface.TAG_MODEL))
            println("PNG ${png.length() / 1_000_000} MB")

            // TGA: run-length encoded, upright size, footer.
            val (tga, tgaName) = export("TGA")
            assertTrue(tgaName.endsWith(".tga"))
            val g = tga.readBytes()
            assertEquals(10, g[2].toInt())
            assertEquals(ew, (g[12].toInt() and 0xFF) or ((g[13].toInt() and 0xFF) shl 8))
            assertEquals(eh, (g[14].toInt() and 0xFF) or ((g[15].toInt() and 0xFF) shl 8))
            assertEquals("TRUEVISION-XFILE.", String(g, g.size - 18, 17, Charsets.US_ASCII))

            // WebP, lossless, with EXIF.
            val (webp, webpName) = export("WebP")
            assertTrue(webpName.endsWith(".webp"))
            val w = webp.readBytes()
            assertEquals("RIFF", String(w, 0, 4, Charsets.US_ASCII)); assertEquals("WEBP", String(w, 8, 4, Charsets.US_ASCII))
            assertEquals(meta.model, ExifInterface(webp.path).getAttribute(ExifInterface.TAG_MODEL))

            // Back to JPEG for whoever exports next.
            val (jpg, jpgName) = export("JPEG")
            assertTrue(jpgName.endsWith(".jpg"))
            assertEquals(0xFFD8, (jpg.readBytes().let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }))
        }
    }
}

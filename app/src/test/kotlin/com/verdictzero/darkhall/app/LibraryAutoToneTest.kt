package com.verdictzero.darkhall.app

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verdictzero.darkhall.app.data.EditsStore
import com.verdictzero.darkhall.app.data.Prefs
import com.verdictzero.darkhall.app.ui.LibraryViewModel
import com.verdictzero.darkhall.core.develop.Adjustment
import com.verdictzero.darkhall.core.develop.DevelopParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** Batch auto tone from the library, through the real view model, on a real ORF. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryAutoToneTest {
    private val samplePath = System.getProperty("darkhall.sample").orEmpty()

    private fun waitFor(timeoutMs: Long = 120_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
    }

    @Test
    fun autoTonesSelectedPhotosAndKeepsOtherEdits() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        val app = ApplicationProvider.getApplicationContext<Application>()
        val uri = Uri.fromFile(File(samplePath))
        Prefs(app).extraFiles = listOf(uri)
        val vm = LibraryViewModel(app)
        waitFor { vm.state.value.files.isNotEmpty() && !vm.state.value.loading }
        val file = vm.state.value.files.single()
        // An existing edit that auto tone must not touch.
        EditsStore.save(app, file.key, DevelopParams.NEUTRAL.with(Adjustment.SATURATION, 37f).withLook("dh-underwater-reef"))
        vm.toggle(file)
        vm.autoToneSelection()
        waitFor { vm.state.value.autoProgress == null && vm.state.value.message != null }
        println("message: ${vm.state.value.message}")
        val saved = EditsStore.load(app, file.key, DevelopParams.NEUTRAL)
        assertNotNull(saved)
        println("saved: " + listOf(Adjustment.EXPOSURE, Adjustment.CONTRAST, Adjustment.WHITES, Adjustment.BLACKS).joinToString { "${it.label}=${saved!![it]}" })
        assertTrue("exposure should be set", saved!![Adjustment.EXPOSURE] != 0f)
        assertEquals(37f, saved[Adjustment.SATURATION], 1e-4f)
        assertEquals("dh-underwater-reef", saved.look)
        assertTrue(file.key in vm.state.value.edited)
    }
}

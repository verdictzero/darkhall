package com.verdictzero.darkhall.app

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
import com.verdictzero.darkhall.app.data.OpenSourceLicenses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/** The open-source licences screen and the licence texts it shows. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LicensesTest {
    @Suppress("DEPRECATION")
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @Test
    fun everyComponentHasItsLicenceText() {
        for (c in OpenSourceLicenses.components) {
            assertTrue("${c.name}: ${c.text} is empty", OpenSourceLicenses.text(app, c).isNotBlank())
        }
        // The copies in the app match the repository's LICENSES/ (tests run from the app module).
        val repo = File("../LICENSES").takeIf { it.isDirectory } ?: return
        for (f in repo.listFiles()!!) {
            val asset = app.assets.open("licenses/${f.name}").bufferedReader().use { it.readText() }
            assertEquals("assets/licenses/${f.name} differs from LICENSES/${f.name}", f.readText(), asset)
        }
    }

    @Test
    fun reachableFromTheLibraryMenu() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            compose.onNodeWithContentDescription("More").performClick()
            compose.onNodeWithText("Open-source licences").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("LibRaw").performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            compose.waitForIdle()
            assertTrue(compose.onAllNodesWithText("COMMON DEVELOPMENT AND DISTRIBUTION LICENSE", substring = true).fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithContentDescription("Close licences").performClick()
            compose.waitForIdle()
            assertTrue(compose.onAllNodesWithText("Open-source licences").fetchSemanticsNodes().isEmpty())
        }
    }
}

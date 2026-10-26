package com.verdictzero.darkhall.app

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.verdictzero.darkhall.app.ui.SLIDERS_TAG
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import org.junit.Assert.assertTrue

/**
 * Screen-reader and touch checks over the library and the editor: everything that can be
 * tapped says what it is and is drawn at least 32 dp each way (touch areas grow to 48 dp from
 * there), and every slider announces its name and value.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilityTest {
    @Suppress("DEPRECATION")
    @get:Rule
    val compose = createEmptyComposeRule()

    private val samplePath = System.getProperty("darkhall.sample").orEmpty()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance").apply { isAccessible = true }.set(null, null)
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun waitFor(condition: () -> Boolean) {
        compose.waitUntil(120_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(10)
            condition()
        }
    }

    private fun label(n: SemanticsNode): String? {
        val c = n.config
        val parts = (c.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + c.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text })
        return parts.joinToString(" ").trim().ifEmpty { null }
    }

    /** Problems with the tappable nodes on screen (merged tree, as TalkBack sees it). */
    private fun problems(): List<String> {
        val density = app.resources.displayMetrics.density
        val out = ArrayList<String>()
        fun visit(n: SemanticsNode) {
            val c = n.config
            val clickable = c.getOrNull(SemanticsActions.OnClick) != null
            val disabled = c.contains(SemanticsProperties.Disabled)
            if (clickable && !disabled) {
                val name = label(n)
                val w = n.boundsInRoot.width / density
                val h = n.boundsInRoot.height / density
                // Only what is fully on screen: a scrolled-off or clipped item is measured when it shows.
                val visible = n.boundsInRoot.width > 0 && n.boundsInRoot.height > 0 && n.boundsInWindow == n.boundsInRoot
                if (name == null) out += "unlabelled clickable at ${n.boundsInRoot}"
                // Compose widens the touch area of anything smaller to 48 dp (Material chips and
                // icon buttons rely on that), so what is checked is the size drawn: under 32 dp
                // the widened areas of neighbours start to fight over the same touches.
                if (visible && (w < 32f || h < 32f)) out += "'${name ?: "?"}' is ${"%.0f".format(w)}x${"%.0f".format(h)} dp"
            }
            if (c.getOrNull(SemanticsActions.SetProgress) != null) {
                if (c.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty()) out += "slider without a name at ${n.boundsInRoot}"
                if (c.getOrNull(SemanticsProperties.StateDescription).isNullOrEmpty()) out += "slider '${label(n)}' without a spoken value"
            }
            n.children.forEach(::visit)
        }
        compose.onAllNodes(isRoot()).fetchSemanticsNodes().forEach(::visit)
        return out.distinct()
    }

    @Test
    fun libraryAndEditorAreAccessible() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        com.verdictzero.darkhall.app.data.Prefs(app).extraFiles = listOf(Uri.fromFile(File(samplePath)))
        val found = ArrayList<String>()
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor { compose.onAllNodesWithText(File(samplePath).name).fetchSemanticsNodes().isNotEmpty() }
            found += problems().map { "library: $it" }
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(File(samplePath))).setClass(app, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use {
            waitFor { compose.onAllNodesWithText(" ms · ", substring = true).fetchSemanticsNodes().isNotEmpty() }
            found += problems().map { "editor: $it" }
            // Further down the slider list: the colour mixer and the crop chips.
            compose.onNodeWithTag(SLIDERS_TAG).performScrollToNode(hasTestTag("mix-RED"))
            repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)) }
            found += problems().map { "editor (mixer): $it" }
        }
        assertTrue("accessibility problems:\n" + found.joinToString("\n"), found.isEmpty())
    }
}

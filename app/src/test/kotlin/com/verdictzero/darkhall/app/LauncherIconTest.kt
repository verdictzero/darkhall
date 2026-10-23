package com.verdictzero.darkhall.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verdictzero.darkhall.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Inflates the launcher icon with the real Android framework and renders it under the common
 * launcher masks: the chevrons must stay clear of the edges whatever shape the phone uses.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {

    private val size = 432 // 108 dp at xxxhdpi

    private fun render(mask: Path?): Bitmap {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val icon = ContextCompat.getDrawable(app, R.mipmap.ic_launcher) as AdaptiveIconDrawable
        assertNotNull(icon.foreground)
        assertNotNull(icon.monochrome)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        icon.background.setBounds(0, 0, size, size); icon.background.draw(c)
        icon.foreground.setBounds(0, 0, size, size); icon.foreground.draw(c)
        if (mask != null) {
            // Keep only what the mask covers (transparent outside).
            val m = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val mc = Canvas(m)
            mc.drawPath(mask, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val oc = Canvas(out)
            oc.drawBitmap(m, 0f, 0f, null)
            oc.drawBitmap(bmp, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) })
            return out
        }
        return bmp
    }

    @Test
    fun chevronsFitEveryMask() {
        val full = render(null)
        // The launcher shows the central 72 dp; the 66 dp circle is the guaranteed safe zone.
        val safeR = size * 33f / 108f
        val cx = size / 2f
        var purpleInside = 0; var purpleOutside = 0
        for (y in 0 until size) for (x in 0 until size) {
            val p = full.getPixel(x, y)
            val isPurple = Color.blue(p) > Color.green(p) + 40 && Color.red(p) > 60
            if (!isPurple) continue
            val dx = x + 0.5f - cx; val dy = y + 0.5f - cx
            if (dx * dx + dy * dy <= safeR * safeR) purpleInside++ else purpleOutside++
        }
        println("chevron pixels inside safe circle: $purpleInside, outside: $purpleOutside")
        assertTrue("chevrons missing", purpleInside > 1000)
        assertTrue("chevrons leave the safe zone", purpleOutside == 0)

        val out = System.getProperty("darkhall.outDir").orEmpty()
        if (out.isNotEmpty()) {
            val visible = size * 72f / 108f
            val o = (size - visible) / 2f
            val circle = Path().apply { addCircle(cx, cx, visible / 2f, Path.Direction.CW) }
            val squircle = Path().apply { addRoundRect(RectF(o, o, o + visible, o + visible), visible * 0.3f, visible * 0.3f, Path.Direction.CW) }
            val square = Path().apply { addRoundRect(RectF(o, o, o + visible, o + visible), visible * 0.08f, visible * 0.08f, Path.Direction.CW) }
            for ((name, bmp) in listOf("full" to full, "circle" to render(circle), "squircle" to render(squircle), "square" to render(square))) {
                FileOutputStream(File(out, "icon_$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}

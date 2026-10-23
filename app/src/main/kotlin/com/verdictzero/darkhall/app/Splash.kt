package com.verdictzero.darkhall.app

import android.app.Activity
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.animation.PathInterpolator
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

/**
 * The launch splash: the vaulted-chevron mark on the darkroom background. On Android 12+ the
 * chevrons light up from the nearest to the farthest (res/drawable/splash_chevrons_animated),
 * and the splash stays up until they have, so a fast start doesn't cut the animation short.
 * It leaves by flying into the hall: the mark grows past the viewer as the splash fades out.
 *
 * Call before `super.onCreate`.
 */
object Splash {
    /** Length of the chevron animation; matches splash_chevrons_animated. */
    private const val ICON_MS = 900L
    private const val EXIT_MS = 520L

    fun install(activity: Activity, firstLaunch: Boolean) {
        val splash = activity.installSplashScreen()
        if (firstLaunch && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The splash appears when the process starts (cold) or just before onCreate (warm).
            val now = SystemClock.uptimeMillis()
            val shownAt = Process.getStartUptimeMillis().takeIf { now - it < 5_000 } ?: now
            splash.setKeepOnScreenCondition { SystemClock.uptimeMillis() - shownAt < ICON_MS }
        }
        splash.setOnExitAnimationListener { provider ->
            val ease = PathInterpolator(0.4f, 0f, 1f, 1f)
            runCatching {
                provider.iconView.animate()
                    .scaleX(2.6f).scaleY(2.6f).alpha(0f)
                    .setDuration(EXIT_MS).setInterpolator(ease)
                    .start()
            }
            provider.view.animate()
                .alpha(0f)
                .setStartDelay(EXIT_MS / 3).setDuration(EXIT_MS * 2 / 3).setInterpolator(ease)
                .withEndAction { provider.remove() }
                .start()
        }
    }
}

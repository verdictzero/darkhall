package com.verdictzero.darkhall.app.gpu

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.Frame
import com.verdictzero.darkhall.core.develop.LinearImage
import com.verdictzero.darkhall.core.develop.RenderContext
import com.verdictzero.darkhall.core.gpu.GpuDeveloper
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * App-wide GPU renderer: one offscreen OpenGL ES 3 context (1x1 pbuffer) on a dedicated
 * thread, owning a [GpuDeveloper]. All GL work is funnelled through [dispatcher].
 *
 * Any failure (no GLES 3, no float render targets, shader compile error, GL error at
 * render time) marks the renderer unavailable for the rest of the process; callers then
 * use the CPU pipeline.
 */
object GpuRenderer {
    private const val TAG = "DarkhallGpu"

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "darkhall-gpu").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()

    private var display: EGLDisplay? = null
    private var surface: EGLSurface? = null
    private var context: EGLContext? = null
    private var developer: GpuDeveloper? = null

    /** Null until [ensureReady] has run; then either a description or the reason it is unavailable. */
    @Volatile var description: String? = null
        private set
    @Volatile var unavailableReason: String? = null
        private set

    val available: Boolean get() = developer != null && unavailableReason == null

    /** Creates the context and compiles the shaders once. Returns false when the GPU can't be used. */
    suspend fun ensureReady(): Boolean = withContext(dispatcher) {
        if (developer != null) return@withContext true
        if (unavailableReason != null) return@withContext false
        try {
            createContext()
            val dev = GpuDeveloper(AndroidGl())
            dev.init()
            developer = dev
            description = dev.description
            Log.i(TAG, "GPU ready: ${dev.description}")
            true
        } catch (e: Throwable) {
            fail("GPU unavailable: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun createContext() {
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (d == null || d == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("no EGL display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(d, version, 0, version, 1)) throw IllegalStateException("eglInitialize failed")
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(d, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            throw IllegalStateException("no OpenGL ES 3 config")
        }
        val config = configs[0]
        val ctx = EGL14.eglCreateContext(d, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext failed (0x${EGL14.eglGetError().toString(16)})")
        val surf = EGL14.eglCreatePbufferSurface(d, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        if (surf == null || surf == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreatePbufferSurface failed")
        if (!EGL14.eglMakeCurrent(d, surf, surf, ctx)) throw IllegalStateException("eglMakeCurrent failed")
        display = d; surface = surf; context = ctx
    }

    /**
     * Uploads the (highlight-reconstructed) preview for the current file; [guide] is the preview
     * before reconstruction, which the region masks are refined against.
     */
    suspend fun setImage(img: LinearImage, guide: LinearImage) = withContext(dispatcher) {
        val dev = developer ?: throw IllegalStateException("GPU not ready")
        guard { dev.setImage(img, guide) }
    }

    /** Full preview render including geometry; packed ARGB. */
    suspend fun render(ctx: RenderContext, params: DevelopParams, frame: Frame): IntArray = withContext(dispatcher) {
        val dev = developer ?: throw IllegalStateException("GPU not ready")
        guard { dev.render(ctx, params, frame) }
    }

    /** Frees the per-image textures (about 100 MB at preview size); keeps the compiled shaders. */
    suspend fun releaseImage() = withContext(dispatcher) {
        runCatching { developer?.let { dev -> dev.setImageReleased() } }
    }

    private fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        fail("GPU error: ${e.message ?: e.javaClass.simpleName}")
        throw e
    }

    /** Marks the GPU unusable for this process and tears the context down. */
    fun fail(reason: String) {
        Log.w(TAG, reason)
        unavailableReason = reason
        executor.execute {
            runCatching { developer?.release() }
            developer = null
            runCatching {
                val d = display
                if (d != null) {
                    EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    context?.let { EGL14.eglDestroyContext(d, it) }
                    surface?.let { EGL14.eglDestroySurface(d, it) }
                }
            }
            display = null; surface = null; context = null
        }
    }
}

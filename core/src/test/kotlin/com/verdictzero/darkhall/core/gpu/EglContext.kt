package com.verdictzero.darkhall.core.gpu

import org.lwjgl.PointerBuffer
import org.lwjgl.egl.EGL
import org.lwjgl.egl.EGL10
import org.lwjgl.egl.EGL12
import org.lwjgl.opengles.GLES
import org.lwjgl.system.Configuration
import org.lwjgl.system.MemoryStack
import java.io.File

/**
 * Headless OpenGL ES 3 context for tests: Mesa (llvmpipe) by default, or Google's ANGLE on
 * SwiftShader (the GLES implementation Chrome ships) with -Pdarkhall.gles=angle.
 */
class EglContext private constructor(private val display: Long, private val surface: Long, private val context: Long) {

    fun close() {
        EGL10.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
        EGL10.eglDestroyContext(display, context)
        if (surface != EGL10.EGL_NO_SURFACE) EGL10.eglDestroySurface(display, surface)
        EGL10.eglTerminate(display)
    }

    companion object {
        private const val EGL_OPENGL_ES3_BIT = 0x40

        /** Null when no usable EGL/GLES 3 is available (the GPU tests are then skipped). */
        fun create(): EglContext? = try {
            val backend = System.getProperty("darkhall.gles").orEmpty().ifEmpty { "mesa" }
            if (backend == "angle") {
                val dir = System.getProperty("darkhall.angleDir").orEmpty()
                Configuration.EGL_LIBRARY_NAME.set(File(dir, "libEGL.so").path)
                Configuration.OPENGLES_LIBRARY_NAME.set(File(dir, "libGLESv2.so").path)
            } else {
                Configuration.EGL_LIBRARY_NAME.set("libEGL.so.1")
                Configuration.OPENGLES_LIBRARY_NAME.set("libGLESv2.so.2")
            }
            MemoryStack.stackPush().use { stack ->
                // ANGLE picks SwiftShader through ANGLE_DEFAULT_PLATFORM (set by the Gradle test task).
                val display = EGL10.eglGetDisplay(0L) // EGL_DEFAULT_DISPLAY
                require(display != EGL10.EGL_NO_DISPLAY) { "no EGL display" }
                val major = stack.mallocInt(1); val minor = stack.mallocInt(1)
                require(EGL10.eglInitialize(display, major, minor)) { "eglInitialize failed" }
                EGL.createDisplayCapabilities(display, major.get(0), minor.get(0))
                require(EGL12.eglBindAPI(EGL12.EGL_OPENGL_ES_API))
                val configAttribs = stack.ints(
                    0x3040 /* EGL_RENDERABLE_TYPE */, EGL_OPENGL_ES3_BIT,
                    EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_NONE,
                )
                val configs: PointerBuffer = stack.mallocPointer(1)
                val count = stack.mallocInt(1)
                require(EGL10.eglChooseConfig(display, configAttribs, configs, count) && count.get(0) > 0) { "no GLES3 config" }
                val config = configs.get(0)
                val surface = EGL10.eglCreatePbufferSurface(display, config, stack.ints(EGL10.EGL_WIDTH, 1, EGL10.EGL_HEIGHT, 1, EGL10.EGL_NONE))
                val ctx = EGL10.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, stack.ints(0x3098, 3, EGL10.EGL_NONE))
                require(ctx != EGL10.EGL_NO_CONTEXT) { "eglCreateContext failed" }
                require(EGL10.eglMakeCurrent(display, surface, surface, ctx)) { "eglMakeCurrent failed" }
                GLES.createCapabilities()
                EglContext(display, surface, ctx)
            }
        } catch (e: Throwable) {
            println("EGL unavailable: $e"); e.printStackTrace(System.out)
            null
        }
    }
}

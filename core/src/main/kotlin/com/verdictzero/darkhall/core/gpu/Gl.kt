package com.verdictzero.darkhall.core.gpu

import java.nio.ByteBuffer

/**
 * The OpenGL ES 3.0 calls the GPU renderer needs. Android implements it with
 * android.opengl.GLES30; desktop tests implement it with LWJGL on Mesa or ANGLE, so the
 * exact same shaders and pass logic run in both places.
 */
interface Gl {
    fun createShader(type: Int): Int
    fun shaderSource(shader: Int, source: String)
    fun compileShader(shader: Int)
    fun compileStatus(shader: Int): Boolean
    fun shaderInfoLog(shader: Int): String
    fun deleteShader(shader: Int)
    fun createProgram(): Int
    fun attachShader(program: Int, shader: Int)
    fun linkProgram(program: Int)
    fun linkStatus(program: Int): Boolean
    fun programInfoLog(program: Int): String
    fun deleteProgram(program: Int)
    fun useProgram(program: Int)
    fun uniformLocation(program: Int, name: String): Int
    fun uniform1i(location: Int, v: Int)
    fun uniform2i(location: Int, x: Int, y: Int)
    fun uniform1f(location: Int, v: Float)
    fun uniform2f(location: Int, x: Float, y: Float)
    fun uniform3f(location: Int, x: Float, y: Float, z: Float)

    fun genTexture(): Int
    fun deleteTexture(texture: Int)
    fun activeTexture(unit: Int)
    fun bindTexture(target: Int, texture: Int)
    fun texParameteri(target: Int, name: Int, value: Int)
    fun texImage2D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, format: Int, type: Int, data: ByteBuffer?)
    fun texSubImage2D(target: Int, level: Int, x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, data: ByteBuffer)
    fun pixelStorei(name: Int, value: Int)

    fun genFramebuffer(): Int
    fun deleteFramebuffer(framebuffer: Int)
    fun bindFramebuffer(target: Int, framebuffer: Int)
    fun framebufferTexture2D(target: Int, attachment: Int, texTarget: Int, texture: Int, level: Int)
    fun checkFramebufferStatus(target: Int): Int
    /** Tells tiled GPUs (Adreno, Mali) the attachment's old contents are not needed. */
    fun invalidateFramebuffer(target: Int, attachment: Int)

    fun genVertexArray(): Int
    fun deleteVertexArray(array: Int)
    fun bindVertexArray(array: Int)

    fun viewport(x: Int, y: Int, width: Int, height: Int)
    fun disable(cap: Int)
    fun clearColor(r: Float, g: Float, b: Float, a: Float)
    fun clear(mask: Int)
    fun drawArrays(mode: Int, first: Int, count: Int)
    fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, data: ByteBuffer)
    fun finish()
    fun getString(name: Int): String?
    fun getInteger(name: Int): Int
    fun getError(): Int
}

/** OpenGL ES enum values used by the renderer (from the Khronos headers). */
object GlConst {
    const val NO_ERROR = 0
    const val VERTEX_SHADER = 0x8B31
    const val FRAGMENT_SHADER = 0x8B30
    const val TEXTURE_2D = 0x0DE1
    const val TEXTURE0 = 0x84C0
    const val TEXTURE_MIN_FILTER = 0x2801
    const val TEXTURE_MAG_FILTER = 0x2800
    const val TEXTURE_WRAP_S = 0x2802
    const val TEXTURE_WRAP_T = 0x2803
    const val NEAREST = 0x2600
    const val CLAMP_TO_EDGE = 0x812F
    const val RGBA = 0x1908
    const val RG = 0x8227
    const val RED = 0x1903
    const val RGBA8 = 0x8058
    const val RGBA32F = 0x8814
    const val RG32F = 0x8230
    const val R32F = 0x822E
    const val RGBA16F = 0x881A
    const val RG16F = 0x822F
    const val R16F = 0x822D
    const val FLOAT = 0x1406
    const val HALF_FLOAT = 0x140B
    const val UNSIGNED_BYTE = 0x1401
    const val FRAMEBUFFER = 0x8D40
    const val COLOR_ATTACHMENT0 = 0x8CE0
    const val FRAMEBUFFER_COMPLETE = 0x8CD5
    const val TRIANGLES = 0x0004
    const val UNPACK_ALIGNMENT = 0x0CF5
    const val PACK_ALIGNMENT = 0x0D05
    const val BLEND = 0x0BE2
    const val DEPTH_TEST = 0x0B71
    const val DITHER = 0x0BD0
    const val COLOR_BUFFER_BIT = 0x00004000
    const val VENDOR = 0x1F00
    const val RENDERER = 0x1F01
    const val VERSION = 0x1F02
    const val EXTENSIONS = 0x1F03
    const val MAX_TEXTURE_SIZE = 0x0D33
}

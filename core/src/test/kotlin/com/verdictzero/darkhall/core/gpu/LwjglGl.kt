package com.verdictzero.darkhall.core.gpu

import org.lwjgl.opengles.GLES30
import java.nio.ByteBuffer

/** [Gl] on LWJGL's OpenGL ES bindings, for running the shaders on desktop drivers in tests. */
class LwjglGl : Gl {
    override fun createShader(type: Int) = GLES30.glCreateShader(type)
    override fun shaderSource(shader: Int, source: String) = GLES30.glShaderSource(shader, source)
    override fun compileShader(shader: Int) = GLES30.glCompileShader(shader)
    override fun compileStatus(shader: Int) = GLES30.glGetShaderi(shader, GLES30.GL_COMPILE_STATUS) != 0
    override fun shaderInfoLog(shader: Int): String = GLES30.glGetShaderInfoLog(shader)
    override fun deleteShader(shader: Int) = GLES30.glDeleteShader(shader)
    override fun createProgram() = GLES30.glCreateProgram()
    override fun attachShader(program: Int, shader: Int) = GLES30.glAttachShader(program, shader)
    override fun linkProgram(program: Int) = GLES30.glLinkProgram(program)
    override fun linkStatus(program: Int) = GLES30.glGetProgrami(program, GLES30.GL_LINK_STATUS) != 0
    override fun programInfoLog(program: Int): String = GLES30.glGetProgramInfoLog(program)
    override fun deleteProgram(program: Int) = GLES30.glDeleteProgram(program)
    override fun useProgram(program: Int) = GLES30.glUseProgram(program)
    override fun uniformLocation(program: Int, name: String) = GLES30.glGetUniformLocation(program, name)
    override fun uniform1i(location: Int, v: Int) = GLES30.glUniform1i(location, v)
    override fun uniform2i(location: Int, x: Int, y: Int) = GLES30.glUniform2i(location, x, y)
    override fun uniform1f(location: Int, v: Float) = GLES30.glUniform1f(location, v)
    override fun uniform2f(location: Int, x: Float, y: Float) = GLES30.glUniform2f(location, x, y)
    override fun uniform3f(location: Int, x: Float, y: Float, z: Float) = GLES30.glUniform3f(location, x, y, z)
    override fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) = GLES30.glUniform4f(location, x, y, z, w)
    override fun genTexture() = GLES30.glGenTextures()
    override fun deleteTexture(texture: Int) = GLES30.glDeleteTextures(texture)
    override fun activeTexture(unit: Int) = GLES30.glActiveTexture(unit)
    override fun bindTexture(target: Int, texture: Int) = GLES30.glBindTexture(target, texture)
    override fun texParameteri(target: Int, name: Int, value: Int) = GLES30.glTexParameteri(target, name, value)
    override fun texImage2D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, format: Int, type: Int, data: ByteBuffer?) =
        GLES30.glTexImage2D(target, level, internalFormat, width, height, 0, format, type, data)
    override fun texSubImage2D(target: Int, level: Int, x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, data: ByteBuffer) =
        GLES30.glTexSubImage2D(target, level, x, y, width, height, format, type, data)
    override fun pixelStorei(name: Int, value: Int) = GLES30.glPixelStorei(name, value)
    override fun genFramebuffer() = GLES30.glGenFramebuffers()
    override fun deleteFramebuffer(framebuffer: Int) = GLES30.glDeleteFramebuffers(framebuffer)
    override fun bindFramebuffer(target: Int, framebuffer: Int) = GLES30.glBindFramebuffer(target, framebuffer)
    override fun framebufferTexture2D(target: Int, attachment: Int, texTarget: Int, texture: Int, level: Int) =
        GLES30.glFramebufferTexture2D(target, attachment, texTarget, texture, level)
    override fun checkFramebufferStatus(target: Int) = GLES30.glCheckFramebufferStatus(target)
    override fun invalidateFramebuffer(target: Int, attachment: Int) = GLES30.glInvalidateFramebuffer(target, attachment)
    override fun genVertexArray() = GLES30.glGenVertexArrays()
    override fun deleteVertexArray(array: Int) = GLES30.glDeleteVertexArrays(array)
    override fun bindVertexArray(array: Int) = GLES30.glBindVertexArray(array)
    override fun viewport(x: Int, y: Int, width: Int, height: Int) = GLES30.glViewport(x, y, width, height)
    override fun disable(cap: Int) = GLES30.glDisable(cap)
    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = GLES30.glClearColor(r, g, b, a)
    override fun clear(mask: Int) = GLES30.glClear(mask)
    override fun drawArrays(mode: Int, first: Int, count: Int) = GLES30.glDrawArrays(mode, first, count)
    override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, data: ByteBuffer) =
        GLES30.glReadPixels(x, y, width, height, format, type, data)
    override fun finish() = GLES30.glFinish()
    override fun getString(name: Int): String? = GLES30.glGetString(name)
    override fun getInteger(name: Int) = GLES30.glGetInteger(name)
    override fun getError() = GLES30.glGetError()
}

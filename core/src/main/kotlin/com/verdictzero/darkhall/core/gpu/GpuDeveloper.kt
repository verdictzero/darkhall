package com.verdictzero.darkhall.core.gpu

import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.Frame
import com.verdictzero.darkhall.core.develop.Geometry
import com.verdictzero.darkhall.core.develop.LinearImage
import com.verdictzero.darkhall.core.develop.Lut
import com.verdictzero.darkhall.core.develop.RenderContext
import com.verdictzero.darkhall.core.develop.RenderPlan
import com.verdictzero.darkhall.core.gpu.GlConst as C
import com.verdictzero.darkhall.core.sky.MaskRegion
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GpuException(message: String) : Exception(message)

/**
 * The slider pipeline on the GPU (OpenGL ES 3.0 fragment shaders). Executes the same
 * [RenderPlan] as the CPU [com.verdictzero.darkhall.core.develop.Developer], pass for pass, on
 * float textures, then quantises and applies geometry in a final pass and reads back 8-bit RGBA.
 *
 * All calls must happen on the thread that owns the GL context.
 */
class GpuDeveloper(
    private val gl: Gl,
    /** Tests only: use the 16-bit float path older GPUs (e.g. Adreno 3xx) fall back to. */
    private val forceHalfFloat: Boolean = false,
) {

    /** e.g. "Adreno (TM) 740 · OpenGL ES 3.2 V@0676.0 · 32-bit float". */
    var description: String = ""
        private set

    private class Program(val id: Int, val gl: Gl) {
        private val locations = HashMap<String, Int>()
        fun loc(name: String) = locations.getOrPut(name) { gl.uniformLocation(id, name) }
    }

    private class Tex(val id: Int, val fbo: Int, val width: Int, val height: Int)

    private val programs = HashMap<String, Program>()
    private var vao = 0
    private var vertexShader = 0
    private var fmt4 = C.RGBA32F
    private var fmt2 = C.RG32F
    private var fmt1 = C.R32F
    private var fmtType = C.FLOAT
    private var maxTexture = 0

    private var width = 0
    private var height = 0
    private val textures = HashMap<String, Tex>()
    private var readBuffer: ByteBuffer? = null

    /** The picture the region masks are refined against (the CPU renderer's input). */
    private var guide: LinearImage? = null
    /** Each region's mask for the last frame, worked out on the CPU (as the CPU renderer does). */
    private val maskCache = HashMap<MaskRegion, Pair<List<Any>, FloatArray>>()
    /** Which masks the mask texture holds. */
    private var uploadedMasks: List<Int?>? = null

    /** Checks capabilities and compiles every shader. Throws [GpuException] if the device can't do it. */
    fun init() {
        val version = gl.getString(C.VERSION).orEmpty()
        val renderer = gl.getString(C.RENDERER).orEmpty()
        val extensions = gl.getString(C.EXTENSIONS).orEmpty()
        val major = Regex("OpenGL ES (\\d+)").find(version)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (major < 3) throw GpuException("OpenGL ES 3.0 required, have '$version'")
        when {
            "GL_EXT_color_buffer_float" in extensions && !forceHalfFloat -> {
                fmt4 = C.RGBA32F; fmt2 = C.RG32F; fmt1 = C.R32F; fmtType = C.FLOAT
            }
            "GL_EXT_color_buffer_half_float" in extensions || ("GL_EXT_color_buffer_float" in extensions && forceHalfFloat) -> {
                fmt4 = C.RGBA16F; fmt2 = C.RG16F; fmt1 = C.R16F; fmtType = C.HALF_FLOAT
            }
            else -> throw GpuException("no renderable float textures on '$renderer'")
        }
        maxTexture = gl.getInteger(C.MAX_TEXTURE_SIZE)
        gl.disable(C.BLEND); gl.disable(C.DEPTH_TEST); gl.disable(C.DITHER)
        vertexShader = compile(C.VERTEX_SHADER, Shaders.VERTEX, "vertex")
        for ((name, src) in listOf(
            "color" to Shaders.COLOR, "dark" to Shaders.DEHAZE_DARK, "dhApply" to Shaders.DEHAZE_APPLY,
            "dhNeg" to Shaders.DEHAZE_NEGATIVE, "min" to Shaders.MIN_1D, "box" to Shaders.BOX_1D,
            "luma" to Shaders.LUMA, "split" to Shaders.CHROMA_SPLIT, "merge" to Shaders.CHROMA_MERGE,
            "atrous" to Shaders.ATROUS_1D, "shrink" to Shaders.SHRINK, "lumaApply" to Shaders.LUMA_APPLY,
            "tone" to Shaders.TONE, "detail" to Shaders.DETAIL_APPLY, "grain" to Shaders.GRAIN, "final" to Shaders.FINAL,
        )) programs[name] = link(name, src)
        vao = gl.genVertexArray()
        checkError("init")
        description = "$renderer · $version · " + (if (fmtType == C.FLOAT) "32-bit float" else "16-bit float")
    }

    private fun compile(type: Int, source: String, name: String): Int {
        val s = gl.createShader(type)
        gl.shaderSource(s, source)
        gl.compileShader(s)
        if (!gl.compileStatus(s)) {
            val log = gl.shaderInfoLog(s)
            gl.deleteShader(s)
            throw GpuException("shader '$name' failed to compile: $log")
        }
        return s
    }

    private fun link(name: String, source: String): Program {
        val fs = compile(C.FRAGMENT_SHADER, source, name)
        val p = gl.createProgram()
        gl.attachShader(p, vertexShader)
        gl.attachShader(p, fs)
        gl.linkProgram(p)
        gl.deleteShader(fs)
        if (!gl.linkStatus(p)) throw GpuException("program '$name' failed to link: ${gl.programInfoLog(p)}")
        return Program(p, gl)
    }

    private fun checkError(where: String) {
        val e = gl.getError()
        if (e != C.NO_ERROR) throw GpuException("GL error 0x${e.toString(16)} in $where")
    }

    // ---- textures ------------------------------------------------------------------------

    private fun newTex(w: Int, h: Int, internal: Int, format: Int, type: Int): Tex {
        val id = gl.genTexture()
        gl.bindTexture(C.TEXTURE_2D, id)
        gl.texParameteri(C.TEXTURE_2D, C.TEXTURE_MIN_FILTER, C.NEAREST)
        gl.texParameteri(C.TEXTURE_2D, C.TEXTURE_MAG_FILTER, C.NEAREST)
        gl.texParameteri(C.TEXTURE_2D, C.TEXTURE_WRAP_S, C.CLAMP_TO_EDGE)
        gl.texParameteri(C.TEXTURE_2D, C.TEXTURE_WRAP_T, C.CLAMP_TO_EDGE)
        gl.texImage2D(C.TEXTURE_2D, 0, internal, w, h, format, type, null)
        gl.bindTexture(C.TEXTURE_2D, 0)
        val fbo = gl.genFramebuffer()
        gl.bindFramebuffer(C.FRAMEBUFFER, fbo)
        gl.framebufferTexture2D(C.FRAMEBUFFER, C.COLOR_ATTACHMENT0, C.TEXTURE_2D, id, 0)
        val status = gl.checkFramebufferStatus(C.FRAMEBUFFER)
        gl.bindFramebuffer(C.FRAMEBUFFER, 0)
        if (status != C.FRAMEBUFFER_COMPLETE) throw GpuException("framebuffer incomplete (0x${status.toString(16)}) for format 0x${internal.toString(16)}")
        return Tex(id, fbo, w, h)
    }

    private fun freeTextures() {
        for (t in textures.values) { gl.deleteFramebuffer(t.fbo); gl.deleteTexture(t.id) }
        textures.clear()
        readBuffer = null
        uploadedLook = null
        uploadedMasks = null
    }

    /**
     * Uploads the render input (after highlight reconstruction) and sizes the work textures.
     * [guide] is the picture before reconstruction, which region masks are refined against.
     */
    fun setImage(img: LinearImage, guide: LinearImage = img) {
        require(guide.width == img.width && guide.height == img.height) { "guide size differs" }
        if (img.width > maxTexture || img.height > maxTexture) throw GpuException("image ${img.width}x${img.height} exceeds GL_MAX_TEXTURE_SIZE $maxTexture")
        if (img.width != width || img.height != height || textures.isEmpty()) {
            freeTextures()
            width = img.width; height = img.height
            val w = width; val h = height
            for (n in listOf("in", "a", "b", "mask")) textures[n] = newTex(w, h, fmt4, C.RGBA, fmtType)
            for (n in listOf("c", "d")) textures[n] = newTex(w, h, fmt2, C.RG, fmtType)
            for (n in listOf("y", "s1", "s2", "s3", "s4", "s5")) textures[n] = newTex(w, h, fmt1, C.RED, fmtType)
            textures["lut"] = newTex(64, 65, fmt1, C.RED, fmtType)
            textures["look"] = newTex(1, 1, C.RGBA8, C.RGBA, C.UNSIGNED_BYTE)
            uploadedLook = null
            textures["out"] = newTex(w, h, C.RGBA8, C.RGBA, C.UNSIGNED_BYTE)
            readBuffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        }
        val buf = ByteBuffer.allocateDirect(img.size * 16).order(ByteOrder.nativeOrder())
        val fb = buf.asFloatBuffer()
        for (i in 0 until img.size) { fb.put(img.r[i]); fb.put(img.g[i]); fb.put(img.b[i]); fb.put(1f) }
        gl.pixelStorei(C.UNPACK_ALIGNMENT, 1)
        gl.bindTexture(C.TEXTURE_2D, tex("in").id)
        gl.texSubImage2D(C.TEXTURE_2D, 0, 0, 0, width, height, C.RGBA, C.FLOAT, buf)
        gl.bindTexture(C.TEXTURE_2D, 0)
        checkError("setImage")
        this.guide = guide
        maskCache.clear()
        uploadedMasks = null
    }

    private fun tex(name: String) = textures[name] ?: throw GpuException("no image loaded")

    /** Frees the image-sized textures but keeps the compiled programs for the next image. */
    fun setImageReleased() {
        freeTextures()
        guide = null
        maskCache.clear()
        width = 0; height = 0
    }

    fun release() {
        freeTextures()
        for (p in programs.values) gl.deleteProgram(p.id)
        programs.clear()
        if (vertexShader != 0) gl.deleteShader(vertexShader)
        if (vao != 0) gl.deleteVertexArray(vao)
        vertexShader = 0; vao = 0; width = 0; height = 0
    }

    // ---- passes -----------------------------------------------------------------------------

    private fun pass(program: String, target: Tex, inputs: List<Pair<String, Tex>>, setup: Program.() -> Unit = {}) {
        val p = programs.getValue(program)
        gl.bindFramebuffer(C.FRAMEBUFFER, target.fbo)
        // Every pass overwrites every pixel. Saying so lets Adreno (and Mali) skip loading the
        // target's previous contents into tile memory before drawing.
        gl.invalidateFramebuffer(C.FRAMEBUFFER, C.COLOR_ATTACHMENT0)
        gl.viewport(0, 0, target.width, target.height)
        gl.useProgram(p.id)
        for ((i, input) in inputs.withIndex()) {
            check(input.second !== target) { "feedback loop in $program" }
            gl.activeTexture(C.TEXTURE0 + i)
            gl.bindTexture(C.TEXTURE_2D, input.second.id)
            gl.uniform1i(p.loc(input.first), i)
        }
        p.setup()
        gl.bindVertexArray(vao)
        gl.drawArrays(C.TRIANGLES, 0, 3)
        // Unbind inputs so a later pass never renders into a texture still bound for sampling;
        // some drivers treat that as a feedback loop even when the shader doesn't read it.
        for (i in inputs.indices.reversed()) {
            gl.activeTexture(C.TEXTURE0 + i)
            gl.bindTexture(C.TEXTURE_2D, 0)
        }
    }

    /** Separable box blur, [passes] times; [src] may equal [dst]. */
    private fun blur(src: Tex, tmp: Tex, dst: Tex, radius: Int, passes: Int) {
        var input = src
        repeat(passes) {
            pass("box", tmp, listOf("uIn" to input)) { gl.uniform2i(loc("uDir"), 1, 0); gl.uniform1i(loc("uR"), radius) }
            pass("box", dst, listOf("uIn" to tmp)) { gl.uniform2i(loc("uDir"), 0, 1); gl.uniform1i(loc("uR"), radius) }
            input = dst
        }
    }

    private fun clearTex(t: Tex) {
        gl.bindFramebuffer(C.FRAMEBUFFER, t.fbo)
        gl.viewport(0, 0, t.width, t.height)
        gl.clearColor(0f, 0f, 0f, 0f)
        gl.clear(C.COLOR_BUFFER_BIT)
    }

    /**
     * Renders the loaded image with [params] and returns packed 0xAARRGGBB pixels, geometry
     * included (equivalent to CPU render followed by [Geometry.apply]).
     */
    fun render(ctx: RenderContext, params: DevelopParams, frame: Frame): IntArray {
        val plan = RenderPlan(ctx, params, frame)
        var cur = tex("a")
        var other = tex("b")
        fun swap() { val t = cur; cur = other; other = t }
        val y = tex("y"); val s1 = tex("s1"); val s2 = tex("s2"); val s3 = tex("s3"); val s4 = tex("s4"); val s5 = tex("s5")
        val m = plan.matrix
        val regions = plan.regions
        if (regions.isNotEmpty()) uploadMasks(ctx, plan, frame)
        fun reg(r: MaskRegion) = regions.firstOrNull { it.region == r && it.colourOn }

        // 1. colour, then the masked regions
        pass("color", cur, listOf("uIn" to tex("in"), "uMask" to tex("mask"))) {
            gl.uniform3f(loc("uMulA"), plan.mulA[0], plan.mulA[1], plan.mulA[2])
            gl.uniform3f(loc("uSubB"), plan.subB[0], plan.subB[1], plan.subB[2])
            gl.uniform3f(loc("uM0"), m[0], m[1], m[2])
            gl.uniform3f(loc("uM1"), m[3], m[4], m[5])
            gl.uniform3f(loc("uM2"), m[6], m[7], m[8])
            gl.uniform1f(loc("uEv"), plan.ev)
            gl.uniform1f(loc("uVig"), plan.vignette)
            gl.uniform2f(loc("uCenter"), plan.centerX, plan.centerY)
            gl.uniform1f(loc("uInvR2"), plan.invR2)
            gl.uniform1f(loc("uInvScale"), plan.invScale)
            gl.uniform2f(loc("uFrame0"), plan.frameX0.toFloat(), plan.frameY0.toFloat())
            gl.uniform1i(loc("uRegOn"), if (regions.any { it.colourOn }) 1 else 0)
            for (r in MaskRegion.entries) {
                val q = reg(r)
                gl.uniform4f(loc("uReg${r.ordinal}"), q?.ev ?: 0f, (q?.warmth ?: 0f) * 0.25f, q?.saturation ?: 0f, if (q != null) 1f else 0f)
            }
        }

        // 2. dehaze
        if (plan.dehazeOn) {
            val atm = plan.atmosphere
            if (plan.dehaze > 0f) {
                pass("dark", s1, listOf("uIn" to cur)) { gl.uniform3f(loc("uAtm"), atm[0], atm[1], atm[2]) }
                pass("min", s2, listOf("uIn" to s1)) { gl.uniform2i(loc("uDir"), 1, 0); gl.uniform1i(loc("uR"), plan.dehazeMinRadius) }
                pass("min", s3, listOf("uIn" to s2)) { gl.uniform2i(loc("uDir"), 0, 1); gl.uniform1i(loc("uR"), plan.dehazeMinRadius) }
                blur(s3, s2, s1, plan.dehazeBlurRadius, 3)
                pass("dhApply", other, listOf("uIn" to cur, "uT" to s1)) {
                    gl.uniform3f(loc("uAtm"), atm[0], atm[1], atm[2]); gl.uniform1f(loc("uOmega"), plan.dehazeOmega)
                }
            } else {
                pass("dhNeg", other, listOf("uIn" to cur)) {
                    gl.uniform3f(loc("uAtm"), atm[0], atm[1], atm[2]); gl.uniform1f(loc("uK"), plan.dehazeNegative)
                }
            }
            swap()
        }

        // 3. noise reduction
        if (plan.lumaOn || plan.chromaOn) {
            pass("luma", y, listOf("uIn" to cur))
            if (plan.chromaOn) {
                val c = tex("c"); val d = tex("d")
                pass("split", c, listOf("uIn" to cur, "uY" to y))
                blur(c, d, c, plan.chromaRadius, 2)
                pass("merge", other, listOf("uY" to y, "uC" to c))
                swap()
            }
            if (plan.lumaOn) {
                var approx = y
                var next = s1
                var accIn = s3
                var accOut = s4
                clearTex(accIn)
                for (j in 0 until plan.nrLevels) {
                    val step = 1 shl j
                    pass("atrous", s2, listOf("uIn" to approx)) { gl.uniform2i(loc("uDir"), 1, 0); gl.uniform1i(loc("uStep"), step) }
                    pass("atrous", next, listOf("uIn" to s2)) { gl.uniform2i(loc("uDir"), 0, 1); gl.uniform1i(loc("uStep"), step) }
                    val finest = j == 0
                    val kk = plan.nrK * RenderPlan.ATROUS_NOISE[j] * (if (finest) RenderPlan.FINEST_BOOST else 1f)
                    pass("shrink", accOut, listOf("uCur" to approx, "uNext" to next, "uAcc" to accIn)) {
                        gl.uniform1f(loc("uK"), kk)
                        gl.uniform1f(loc("uNA"), plan.noiseA)
                        gl.uniform1f(loc("uNB"), plan.noiseB)
                        gl.uniform1i(loc("uFinest"), if (finest) 1 else 0)
                    }
                    val t = accIn; accIn = accOut; accOut = t
                    approx = next
                    next = if (next === s1) s5 else s1
                }
                pass("lumaApply", other, listOf("uIn" to cur, "uY" to y, "uAcc" to accIn, "uCoarse" to approx))
                swap()
            }
        }

        // 4/5. tone + colour
        uploadCurve(plan.curve)
        val look = if (plan.lookOn) plan.look else null
        if (look != null) uploadLook(look)
        pass("tone", other, listOf("uIn" to cur, "uLut" to tex("lut"), "uLook" to tex("look"))) {
            gl.uniform1i(loc("uLookOn"), if (look != null) 1 else 0)
            gl.uniform1i(loc("uLookN"), look?.size ?: 2)
            gl.uniform1f(loc("uLookAmount"), plan.lookAmount)
            gl.uniform1f(loc("uSh"), plan.shadows)
            gl.uniform1f(loc("uHl"), plan.highlights)
            gl.uniform1i(loc("uDoSh"), if (plan.shadowsHighlightsOn) 1 else 0)
            gl.uniform1f(loc("uVib"), plan.vibrance)
            gl.uniform1f(loc("uSat"), plan.saturation)
            gl.uniform1i(loc("uDoSat"), if (plan.saturationOn) 1 else 0)
            gl.uniform1i(loc("uMixOn"), if (plan.mixerOn) 1 else 0)
            for (k in 0 until 8) gl.uniform3f(loc("uMix[$k]"), plan.mix[3 * k], plan.mix[3 * k + 1], plan.mix[3 * k + 2])
            gl.uniform1i(loc("uGradeOn"), if (plan.gradeOn) 1 else 0)
            gl.uniform1f(loc("uGradeExp"), plan.gradeExponent)
            plan.gradeShadow.let { gl.uniform3f(loc("uGradeS"), it[0], it[1], it[2]) }
            plan.gradeMidtone.let { gl.uniform3f(loc("uGradeM"), it[0], it[1], it[2]) }
            plan.gradeHighlight.let { gl.uniform3f(loc("uGradeH"), it[0], it[1], it[2]) }
        }
        swap()

        // 6. clarity + sharpening
        if (plan.detailOn) {
            pass("luma", y, listOf("uIn" to cur))
            if (plan.sharpenOn) blur(y, s2, s1, plan.sharpenRadius, 2)
            val regionClarity = regions.any { it.clarityOn }
            if (plan.clarityOn || regionClarity) blur(y, s2, s3, plan.clarityRadius, 3)
            pass("detail", other, listOf("uIn" to cur, "uY" to y, "uSharpBlur" to s1, "uClarBlur" to s3, "uMask" to tex("mask"))) {
                gl.uniform1i(loc("uSharpOn"), if (plan.sharpenOn) 1 else 0)
                gl.uniform1i(loc("uClarOn"), if (plan.clarityOn) 1 else 0)
                gl.uniform1f(loc("uSharpA"), plan.sharpenAmount)
                gl.uniform1f(loc("uClarA"), plan.clarityAmount)
                gl.uniform1i(loc("uRegClarOn"), if (regionClarity) 1 else 0)
                fun clar(r: MaskRegion) = regions.firstOrNull { it.region == r && it.clarityOn }?.clarity ?: 0f
                gl.uniform4f(loc("uRegClar"), clar(MaskRegion.SKY), clar(MaskRegion.WATER), clar(MaskRegion.SUBJECT), clar(MaskRegion.FOLIAGE))
            }
            swap()
        }

        // 7. grain
        if (plan.grainOn) {
            pass("grain", other, listOf("uIn" to cur)) {
                gl.uniform1f(loc("uGrainA"), plan.grainAmount)
                gl.uniform1f(loc("uCell"), plan.grainCell)
                gl.uniform2f(loc("uFrame0"), plan.frameX0.toFloat(), plan.frameY0.toFloat())
                gl.uniform1f(loc("uInvScale"), plan.invScale)
            }
            swap()
        }

        // quantise + geometry
        val geom = Geometry.create(width, height, params, ctx.lens)?.uniforms()
        val out = tex("out")
        pass("final", out, listOf("uIn" to cur)) {
            gl.uniform1i(loc("uGeom"), if (geom != null) 1 else 0)
            if (geom != null) {
                gl.uniform1i(loc("uTca"), if (geom.hasTca) 1 else 0)
                gl.uniform2f(loc("uC"), geom.cx, geom.cy)
                gl.uniform1f(loc("uRadius"), geom.radius)
                gl.uniform1f(loc("uZoom"), geom.zoom)
                gl.uniform2f(loc("uRot"), geom.cos, geom.sin)
                gl.uniform1f(loc("uK"), geom.k)
                gl.uniform1i(loc("uLens"), if (geom.lensOn) 1 else 0)
                gl.uniform1f(loc("uAmount"), geom.amount)
                for (i in 0 until 7) gl.uniform3f(loc("uP$i"), geom.poly[i][0], geom.poly[i][1], geom.poly[i][2])
            }
        }
        val rb = readBuffer!!
        rb.clear()
        gl.pixelStorei(C.PACK_ALIGNMENT, 1)
        gl.readPixels(0, 0, width, height, C.RGBA, C.UNSIGNED_BYTE, rb)
        gl.bindFramebuffer(C.FRAMEBUFFER, 0)
        checkError("render")
        val px = IntArray(width * height)
        for (i in px.indices) {
            val r = rb.get(4 * i).toInt() and 0xFF
            val g = rb.get(4 * i + 1).toInt() and 0xFF
            val b = rb.get(4 * i + 2).toInt() and 0xFF
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return px
    }

    /**
     * Puts the masks of the regions [plan] uses into the mask texture, one per channel in
     * [MaskRegion] order. They are refined on the CPU, like the CPU renderer's, and only
     * recomputed when the frame changes, so slider drags reuse them.
     */
    private fun uploadMasks(ctx: RenderContext, plan: RenderPlan, frame: Frame) {
        val scene = ctx.scene ?: return
        val img = guide ?: throw GpuException("no image loaded")
        check(MaskRegion.entries.size == 4) { "the mask texture holds four regions" }
        val arrays = MaskRegion.entries.map { region ->
            val mask = if (plan.regions.any { it.region == region }) scene[region] else null
            if (mask == null) null else {
                val key = listOf(System.identityHashCode(img), mask, frame.x0, frame.y0, frame.scale)
                maskCache[region]?.takeIf { it.first == key }?.second
                    ?: mask.forFrame(img, frame, ctx.fullWidth, ctx.fullHeight).also { maskCache[region] = key to it }
            }
        }
        val key = arrays.map { a -> a?.let { System.identityHashCode(it) } }
        if (key == uploadedMasks) return
        val n = width * height
        val buf = ByteBuffer.allocateDirect(n * 16).order(ByteOrder.nativeOrder())
        val fb = buf.asFloatBuffer()
        val (a0, a1, a2, a3) = arrays
        for (i in 0 until n) {
            fb.put(a0?.get(i) ?: 0f); fb.put(a1?.get(i) ?: 0f); fb.put(a2?.get(i) ?: 0f); fb.put(a3?.get(i) ?: 0f)
        }
        gl.pixelStorei(C.UNPACK_ALIGNMENT, 1)
        gl.bindTexture(C.TEXTURE_2D, tex("mask").id)
        gl.texSubImage2D(C.TEXTURE_2D, 0, 0, 0, width, height, C.RGBA, C.FLOAT, buf)
        gl.bindTexture(C.TEXTURE_2D, 0)
        checkError("masks")
        uploadedMasks = key
    }

    private var uploadedLook: Lut? = null

    /** Puts [lut] in the look texture: N*N wide, N tall, texel (r + N*g, b) — the table's own order. */
    private fun uploadLook(lut: Lut) {
        if (uploadedLook === lut) return
        val n = lut.size
        val old = textures.getValue("look")
        gl.deleteFramebuffer(old.fbo); gl.deleteTexture(old.id)
        val t = newTex(n * n, n, C.RGBA8, C.RGBA, C.UNSIGNED_BYTE)
        textures["look"] = t
        val count = n * n * n
        val buf = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until count) {
            buf.put(lut.rgb[3 * i]); buf.put(lut.rgb[3 * i + 1]); buf.put(lut.rgb[3 * i + 2]); buf.put(0xFF.toByte())
        }
        buf.flip()
        gl.pixelStorei(C.UNPACK_ALIGNMENT, 1)
        gl.bindTexture(C.TEXTURE_2D, t.id)
        gl.texSubImage2D(C.TEXTURE_2D, 0, 0, 0, n * n, n, C.RGBA, C.UNSIGNED_BYTE, buf)
        gl.bindTexture(C.TEXTURE_2D, 0)
        uploadedLook = lut
    }

    private val curveBuffer = ByteBuffer.allocateDirect(64 * 65 * 4).order(ByteOrder.nativeOrder())

    private fun uploadCurve(curve: FloatArray) {
        val fb = curveBuffer.clear().asFloatBuffer()
        for (i in 0 until 64 * 65) fb.put(if (i < curve.size) curve[i] else 0f)
        gl.pixelStorei(C.UNPACK_ALIGNMENT, 1)
        gl.bindTexture(C.TEXTURE_2D, tex("lut").id)
        gl.texSubImage2D(C.TEXTURE_2D, 0, 0, 0, 64, 65, C.RED, C.FLOAT, curveBuffer)
        gl.bindTexture(C.TEXTURE_2D, 0)
    }
}

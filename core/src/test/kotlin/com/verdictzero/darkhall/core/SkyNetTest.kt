package com.verdictzero.darkhall.core

import com.verdictzero.darkhall.core.sky.SkyModel
import com.verdictzero.darkhall.core.sky.SkyNet
import com.verdictzero.darkhall.core.sky.Tensor
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Kotlin network against tools/sky/reference.py (itself checked against TensorFlow). */
class SkyNetTest {
    companion object {
        val model: SkyModel by lazy { File("../app/src/main/assets/sky/deeplab_mnv2_ade20k.bin").inputStream().use { SkyModel.read(it) } }
    }

    /** The same deterministic picture as tools/sky/fixture.py. */
    private fun testInput(): Tensor {
        val n = SkyNet.INPUT
        val t = Tensor(3, n, n)
        for (c in 0 until 3) for (y in 0 until n) for (x in 0 until n) {
            val v = if (y < n / 3.0) 0.6 + 0.1 * c + 0.05 * sin(0.01 * x)
            else sin(0.05 * x + 0.07 * y + c) * cos(0.03 * y - 0.02 * x * (c + 1))
            t.data[c * n * n + y * n + x] = v.toFloat()
        }
        return t
    }

    @Test
    fun matchesTheReference() {
        val ref = ByteBuffer.wrap(javaClass.getResourceAsStream("/sky/reference.bin")!!.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val cells = listOf(0 to 0, 10 to 64, 64 to 64, 128 to 100)
        val x = testInput()
        for (os in listOf(16, 8)) {
            val gh = ref.get().toInt(); val gw = ref.get().toInt()
            val t0 = System.nanoTime()
            val logits = SkyNet.logits(model, x, os)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals(gh, logits.h); assertEquals(gw, logits.w)
            val p = SkyNet.skyProbability(logits)
            var worst = 0f
            for (i in 0 until gh * gw) worst = maxOf(worst, abs(p[i] - ref.get()))
            var worstLogit = 0f
            for ((cy, cx) in cells) for (c in 0 until 151) {
                worstLogit = maxOf(worstLogit, abs(logits.data[c * logits.plane + cy * gw + cx] - ref.get()))
            }
            println("output stride $os: $ms ms, sky probability within $worst, logits within $worstLogit")
            assertTrue(worst < 2e-3f, "sky probability off by $worst at output stride $os")
            assertTrue(worstLogit < 2e-2f, "logits off by $worstLogit at output stride $os")
        }
    }

    /** Timing, after warm-up (printed, not asserted). */
    @Test
    fun speed() {
        val x = testInput()
        repeat(2) { SkyNet.logits(model, x, 16) }
        for (os in listOf(16, 8)) {
            val t0 = System.nanoTime()
            repeat(3) { SkyNet.logits(model, x, os) }
            println("output stride $os: ${(System.nanoTime() - t0) / 3_000_000} ms on ${com.verdictzero.darkhall.core.util.Parallel.threads} threads")
        }
    }
}

package com.verdictzero.darkhall.core.sky

import com.verdictzero.darkhall.core.util.Parallel
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** One convolution, batch norm folded in. Weights [cout][cin][k][k], or [c][k][k] when [depthwise]. */
class ConvLayer(val name: String, val depthwise: Boolean, val k: Int, val cin: Int, val cout: Int, val w: FloatArray, val b: FloatArray)

/**
 * The weights of DeepLab v3+ with a MobileNetV2 backbone, trained on ADE20K (TensorFlow Models,
 * Apache 2.0), in the file tools/sky/export_deeplab.py writes.
 */
class SkyModel(val layers: List<ConvLayer>) {
    companion object {
        fun read(input: InputStream): SkyModel {
            val d = DataInputStream(input.buffered(1 shl 16))
            val magic = ByteArray(8).also { d.readFully(it) }
            require(String(magic, 0, 6, Charsets.US_ASCII) == "DHSKY1") { "not a sky model" }
            fun u16() = Integer.reverseBytes(d.readUnsignedShort() shl 16)
            val n = Integer.reverseBytes(d.readInt())
            val layers = ArrayList<ConvLayer>(n)
            repeat(n) {
                val name = ByteArray(u16()).also { d.readFully(it) }.toString(Charsets.UTF_8)
                val depthwise = d.readUnsignedByte() == 1
                val kh = u16(); val kw = u16(); val cin = u16(); val cout = u16()
                require(kh == kw) { "square kernels only" }
                val count = kh * kw * cin * (if (depthwise) 1 else cout)
                val raw = ByteArray(2 * count).also { d.readFully(it) }
                val hb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val w = FloatArray(count) { halfToFloat(hb.get(it)) }
                val braw = ByteArray(4 * cout).also { d.readFully(it) }
                val fb = ByteBuffer.wrap(braw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val b = FloatArray(cout) { fb.get(it) }
                layers += ConvLayer(name, depthwise, kh, cin, cout, w, b)
            }
            return SkyModel(layers)
        }

        private fun halfToFloat(h: Short): Float {
            val bits = h.toInt() and 0xFFFF
            val sign = (bits ushr 15) shl 31
            val exp = (bits ushr 10) and 0x1F
            val mant = bits and 0x3FF
            return when (exp) {
                0 -> if (mant == 0) Float.fromBits(sign) else (if (sign != 0) -1f else 1f) * mant * (1f / (1 shl 24))
                31 -> Float.fromBits(sign or 0x7F800000 or (mant shl 13))
                else -> Float.fromBits(sign or ((exp - 15 + 127) shl 23) or (mant shl 13))
            }
        }
    }
}

/** A feature map, channels first: [c][h][w]. */
class Tensor(val c: Int, val h: Int, val w: Int, val data: FloatArray = FloatArray(c * h * w)) {
    val plane get() = h * w
}

/**
 * DeepLab v3+ inference in plain Kotlin: MobileNetV2 (inverted residual blocks, ReLU6), an
 * image-pooling + 1x1 head, and a decoder that adds back stride-4 detail. Padding follows
 * TensorFlow's SAME rule, so results match TensorFlow's on the same weights (to float rounding).
 *
 * At [OUTPUT_STRIDE] 16 the deep layers run at 1/16 of the input size, MobileNetV2's own
 * strides with dilation 2 in the last three blocks; the exported graph used 8, which costs
 * four times as much in those layers for slightly finer edges, edges the mask refinement
 * recovers from the photo anyway.
 */
object SkyNet {
    /** Input side: pictures are scaled to fit and padded, as the model was trained. */
    const val INPUT = 513
    /** ADE20K class index of "sky". */
    const val SKY = 3
    const val OUTPUT_STRIDE = 16

    private fun blocks(os: Int): Array<IntArray> = when (os) {
        8 -> arrayOf(
            intArrayOf(1, 1, 1), intArrayOf(6, 2, 1), intArrayOf(6, 1, 1), intArrayOf(6, 2, 1), intArrayOf(6, 1, 1), intArrayOf(6, 1, 1),
            intArrayOf(6, 1, 1), intArrayOf(6, 1, 2), intArrayOf(6, 1, 2), intArrayOf(6, 1, 2), intArrayOf(6, 1, 2), intArrayOf(6, 1, 2),
            intArrayOf(6, 1, 2), intArrayOf(6, 1, 2), intArrayOf(6, 1, 4), intArrayOf(6, 1, 4), intArrayOf(6, 1, 4),
        )
        else -> arrayOf(
            intArrayOf(1, 1, 1), intArrayOf(6, 2, 1), intArrayOf(6, 1, 1), intArrayOf(6, 2, 1), intArrayOf(6, 1, 1), intArrayOf(6, 1, 1),
            intArrayOf(6, 2, 1), intArrayOf(6, 1, 1), intArrayOf(6, 1, 1), intArrayOf(6, 1, 1), intArrayOf(6, 1, 1), intArrayOf(6, 1, 1),
            intArrayOf(6, 1, 1), intArrayOf(6, 1, 1), intArrayOf(6, 1, 2), intArrayOf(6, 1, 2), intArrayOf(6, 1, 2),
        )
    }

    private const val NONE = 0
    private const val RELU = 1
    private const val RELU6 = 2

    /** Class scores [151][h4][w4] for [x] (3 channels, -1..1), at a quarter of its size. */
    fun logits(model: SkyModel, x: Tensor, outputStride: Int = OUTPUT_STRIDE): Tensor {
        val layers = model.layers.iterator()
        var t = conv(x, layers.next(), stride = 2, dilation = 1, act = RELU6)
        var low: Tensor? = null
        for ((i, blk) in blocks(outputStride).withIndex()) {
            val (expand, stride, dilation) = Triple(blk[0], blk[1], blk[2])
            val input = t
            if (expand != 1) t = conv(t, layers.next(), 1, 1, RELU6)
            t = conv(t, layers.next(), stride, dilation, RELU6)
            if (i == 2) low = t // the decoder's stride-4 detail
            t = conv(t, layers.next(), 1, 1, NONE)
            if (stride == 1 && input.c == t.c) {
                val a = t.data; val b = input.data
                for (j in a.indices) a[j] += b[j]
            }
        }
        // Image pooling: the whole picture's average, through a 1x1 convolution, everywhere.
        val mean = Tensor(t.c, 1, 1)
        for (c in 0 until t.c) {
            var s = 0.0
            val o = c * t.plane
            for (j in 0 until t.plane) s += t.data[o + j]
            mean.data[c] = (s / t.plane).toFloat()
        }
        val pooled = conv(mean, layers.next(), 1, 1, RELU)
        val aspp0 = conv(t, layers.next(), 1, 1, RELU)
        val both = Tensor(pooled.c + aspp0.c, t.h, t.w)
        for (c in 0 until pooled.c) both.data.fill(pooled.data[c], c * t.plane, (c + 1) * t.plane)
        System.arraycopy(aspp0.data, 0, both.data, pooled.c * t.plane, aspp0.data.size)
        var y = conv(both, layers.next(), 1, 1, RELU)
        val f = conv(low!!, layers.next(), 1, 1, RELU)
        y = resizeAlignCorners(y, f.h, f.w)
        val z0 = Tensor(y.c + f.c, f.h, f.w)
        System.arraycopy(y.data, 0, z0.data, 0, y.data.size)
        System.arraycopy(f.data, 0, z0.data, y.data.size, f.data.size)
        var z = conv(z0, layers.next(), 1, 1, RELU)
        z = conv(z, layers.next(), 1, 1, RELU)
        z = conv(z, layers.next(), 1, 1, RELU)
        z = conv(z, layers.next(), 1, 1, RELU)
        return conv(z, layers.next(), 1, 1, NONE)
    }

    /**
     * For each group of ADE20K classes in [groups], the probability that each cell of [logits]'
     * grid belongs to one of them (the softmax over all classes, summed over the group).
     */
    fun groupProbabilities(logits: Tensor, groups: List<IntArray>): List<FloatArray> {
        val n = logits.plane
        val out = List(groups.size) { FloatArray(n) }
        val d = logits.data
        for (i in 0 until n) {
            var m = Float.NEGATIVE_INFINITY
            for (c in 0 until logits.c) m = max(m, d[c * n + i])
            var s = 0.0
            for (c in 0 until logits.c) s += exp((d[c * n + i] - m).toDouble())
            for ((g, classes) in groups.withIndex()) {
                var e = 0.0
                for (c in classes) e += exp((d[c * n + i] - m).toDouble())
                out[g][i] = (e / s).toFloat()
            }
        }
        return out
    }

    /** Probability of sky at each cell of [logits]' grid (softmax over all classes). */
    fun skyProbability(logits: Tensor): FloatArray {
        val n = logits.plane
        val out = FloatArray(n)
        val d = logits.data
        for (i in 0 until n) {
            var m = Float.NEGATIVE_INFINITY
            for (c in 0 until logits.c) m = max(m, d[c * n + i])
            var s = 0.0
            for (c in 0 until logits.c) s += exp((d[c * n + i] - m).toDouble())
            out[i] = (exp((d[SKY * n + i] - m).toDouble()) / s).toFloat()
        }
        return out
    }

    /** TensorFlow SAME padding for one axis: output size and padding before. */
    private fun same(size: Int, k: Int, stride: Int, dilation: Int): IntArray {
        val keff = (k - 1) * dilation + 1
        val out = (size + stride - 1) / stride
        val total = max((out - 1) * stride + keff - size, 0)
        return intArrayOf(out, total / 2)
    }

    private fun conv(x: Tensor, l: ConvLayer, stride: Int, dilation: Int, act: Int): Tensor {
        require(x.c == l.cin) { "${l.name}: ${x.c} channels in, expects ${l.cin}" }
        return when {
            l.depthwise -> depthwise(x, l, stride, dilation, act)
            l.k == 1 && stride == 1 -> pointwise(x, l, act)
            else -> dense(x, l, stride, dilation, act)
        }
    }

    private fun activate(a: FloatArray, from: Int, to: Int, act: Int) {
        when (act) {
            RELU -> for (i in from until to) if (a[i] < 0f) a[i] = 0f
            RELU6 -> for (i in from until to) a[i] = min(max(a[i], 0f), 6f)
        }
    }

    /**
     * 1x1 convolution, in tiles of [TILE] pixels by eight output channels: the eight partial
     * sums stay in a small buffer the CPU keeps in its first-level cache while every input
     * channel streams past once.
     */
    private fun pointwise(x: Tensor, l: ConvLayer, act: Int): Tensor {
        val hw = x.plane
        val out = Tensor(l.cout, x.h, x.w)
        val o = out.data; val xd = x.data; val w = l.w; val cin = l.cin; val cout = l.cout
        val tiles = (hw + TILE - 1) / TILE
        val groups = (cout + 7) / 8
        Parallel.rows(tiles * groups) { j0, j1 ->
            val acc = FloatArray(8 * TILE)
            for (job in j0 until j1) {
                val g = job % groups
                val t0 = (job / groups) * TILE
                val n = min(TILE, hw - t0)
                val c0 = g * 8
                val m = min(8, cout - c0)
                for (k in 0 until m) acc.fill(l.b[c0 + k], k * TILE, k * TILE + n)
                if (m == 8) {
                    for (c in 0 until cin) {
                        val w0 = w[c0 * cin + c]; val w1 = w[(c0 + 1) * cin + c]; val w2 = w[(c0 + 2) * cin + c]; val w3 = w[(c0 + 3) * cin + c]
                        val w4 = w[(c0 + 4) * cin + c]; val w5 = w[(c0 + 5) * cin + c]; val w6 = w[(c0 + 6) * cin + c]; val w7 = w[(c0 + 7) * cin + c]
                        val xo = c * hw + t0
                        for (i in 0 until n) {
                            val v = xd[xo + i]
                            acc[i] += w0 * v; acc[TILE + i] += w1 * v; acc[2 * TILE + i] += w2 * v; acc[3 * TILE + i] += w3 * v
                            acc[4 * TILE + i] += w4 * v; acc[5 * TILE + i] += w5 * v; acc[6 * TILE + i] += w6 * v; acc[7 * TILE + i] += w7 * v
                        }
                    }
                } else {
                    for (k in 0 until m) {
                        for (c in 0 until cin) {
                            val wv = w[(c0 + k) * cin + c]
                            val xo = c * hw + t0
                            for (i in 0 until n) acc[k * TILE + i] += wv * xd[xo + i]
                        }
                    }
                }
                for (k in 0 until m) {
                    val r = (c0 + k) * hw + t0
                    System.arraycopy(acc, k * TILE, o, r, n)
                    activate(o, r, r + n, act)
                }
            }
        }
        return out
    }

    private const val TILE = 256

    /** Depthwise k x k convolution with stride and dilation. */
    private fun depthwise(x: Tensor, l: ConvLayer, stride: Int, dilation: Int, act: Int): Tensor {
        val (oh, pt) = same(x.h, l.k, stride, dilation).let { it[0] to it[1] }
        val (ow, pl) = same(x.w, l.k, stride, dilation).let { it[0] to it[1] }
        val out = Tensor(x.c, oh, ow)
        val k = l.k
        Parallel.rows(x.c) { c0, c1 ->
            for (c in c0 until c1) {
                val xo = c * x.plane
                val oo = c * oh * ow
                val wo = c * k * k
                for (oy in 0 until oh) {
                    for (ox in 0 until ow) {
                        var s = l.b[c]
                        for (ky in 0 until k) {
                            val iy = oy * stride - pt + ky * dilation
                            if (iy < 0 || iy >= x.h) continue
                            val row = xo + iy * x.w
                            for (kx in 0 until k) {
                                val ix = ox * stride - pl + kx * dilation
                                if (ix < 0 || ix >= x.w) continue
                                s += l.w[wo + ky * k + kx] * x.data[row + ix]
                            }
                        }
                        out.data[oo + oy * ow + ox] = s
                    }
                }
                activate(out.data, oo, oo + oh * ow, act)
            }
        }
        return out
    }

    /** Any other convolution (here only the 3x3, stride 2 stem). */
    private fun dense(x: Tensor, l: ConvLayer, stride: Int, dilation: Int, act: Int): Tensor {
        val (oh, pt) = same(x.h, l.k, stride, dilation).let { it[0] to it[1] }
        val (ow, pl) = same(x.w, l.k, stride, dilation).let { it[0] to it[1] }
        val out = Tensor(l.cout, oh, ow)
        val k = l.k
        Parallel.rows(l.cout) { o0, o1 ->
            for (o in o0 until o1) {
                val oo = o * oh * ow
                for (oy in 0 until oh) for (ox in 0 until ow) {
                    var s = l.b[o]
                    for (c in 0 until l.cin) {
                        val xo = c * x.plane
                        val wo = (o * l.cin + c) * k * k
                        for (ky in 0 until k) {
                            val iy = oy * stride - pt + ky * dilation
                            if (iy < 0 || iy >= x.h) continue
                            for (kx in 0 until k) {
                                val ix = ox * stride - pl + kx * dilation
                                if (ix < 0 || ix >= x.w) continue
                                s += l.w[wo + ky * k + kx] * x.data[xo + iy * x.w + ix]
                            }
                        }
                    }
                    out.data[oo + oy * ow + ox] = s
                }
                activate(out.data, oo, oo + oh * ow, act)
            }
        }
        return out
    }

    /** Bilinear resize with aligned corners, as the model's own upsampling does. */
    fun resizeAlignCorners(x: Tensor, oh: Int, ow: Int): Tensor {
        if (oh == x.h && ow == x.w) return x
        val out = Tensor(x.c, oh, ow)
        val sy = if (oh > 1) (x.h - 1).toFloat() / (oh - 1) else 0f
        val sx = if (ow > 1) (x.w - 1).toFloat() / (ow - 1) else 0f
        Parallel.rows(x.c) { c0, c1 ->
            for (c in c0 until c1) {
                val xo = c * x.plane
                for (oy in 0 until oh) {
                    val fy = oy * sy; val y0 = fy.toInt(); val y1 = min(y0 + 1, x.h - 1); val ty = fy - y0
                    for (ox in 0 until ow) {
                        val fx = ox * sx; val x0 = fx.toInt(); val x1 = min(x0 + 1, x.w - 1); val tx = fx - x0
                        val a = x.data[xo + y0 * x.w + x0]; val b = x.data[xo + y0 * x.w + x1]
                        val cc = x.data[xo + y1 * x.w + x0]; val d = x.data[xo + y1 * x.w + x1]
                        out.data[c * oh * ow + oy * ow + ox] = (a * (1 - tx) + b * tx) * (1 - ty) + (cc * (1 - tx) + d * tx) * ty
                    }
                }
            }
        }
        return out
    }
}

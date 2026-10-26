package com.verdictzero.darkhall.core.sky

import com.verdictzero.darkhall.core.export.Upright
import com.verdictzero.darkhall.core.util.Parallel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The parts of a photo the editor can adjust on their own, each a group of the network's
 * ADE20K classes (numbered as in the dataset's objectInfo150, 0 being "other").
 */
enum class MaskRegion(val title: String, val noun: String, vararg val classes: Int) {
    SKY("Sky", "sky", SkyNet.SKY),
    /** Water, sea, river, swimming pool, waterfall, lake. */
    WATER("Water", "water", 22, 27, 61, 110, 114, 129),
    /** People and animals. */
    SUBJECT("Subject", "people or animals", 13, 127),
    /** Trees, grass, plants, flowers, palms. */
    FOLIAGE("Foliage", "foliage", 5, 10, 18, 67, 73),
}

/** Where each [MaskRegion] is in one photo: one network run, kept per region. */
class SceneMasks(val masks: Map<MaskRegion, RegionMask>) {

    operator fun get(region: MaskRegion): RegionMask? = masks[region]

    /** Share of the picture that is [region]. */
    fun coverage(region: MaskRegion): Float = masks[region]?.coverage ?: 0f

    /** For the on-disk cache. */
    fun toBytes(): ByteArray {
        val size = 8 + masks.values.sumOf { 4 + it.bytes }
        val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(MAGIC).putInt(masks.size)
        for ((r, m) in masks) { bb.putInt(r.ordinal); m.write(bb) }
        return bb.array()
    }

    companion object {
        private const val MAGIC = 0x53434E31 // "SCN1"

        /** A cache written by [toBytes]; null if it isn't one (an older format, a damaged file). */
        fun fromBytes(bytes: ByteArray): SceneMasks? {
            if (bytes.size < 8) return null
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.int != MAGIC) return null
            val count = bb.int
            if (count !in 0..MaskRegion.entries.size) return null
            val masks = LinkedHashMap<MaskRegion, RegionMask>()
            repeat(count) {
                if (bb.remaining() < 4) return null
                val region = MaskRegion.entries.getOrNull(bb.int) ?: return null
                masks[region] = RegionMask.read(bb) ?: return null
            }
            // A cache from before a region was added lacks it: detect again.
            if (masks.size != MaskRegion.entries.size) return null
            return SceneMasks(masks)
        }

        /**
         * Finds every region in [argb], the whole crop rendered at preview size in camera
         * orientation (8-bit, before geometry), whose EXIF [orientation] says how to turn it
         * upright. The network sees the picture upright, as it was trained.
         */
        fun detect(model: SkyModel, argb: IntArray, width: Int, height: Int, orientation: Int): SceneMasks {
            val up = Upright(width, height, orientation)
            val s = SkyNet.INPUT.toFloat() / max(up.width, up.height)
            val nw = min(SkyNet.INPUT, max(1, (up.width * s).roundToInt()))
            val nh = min(SkyNet.INPUT, max(1, (up.height * s).roundToInt()))
            // Area-average down to network size, scale to -1..1; the rest of the square stays 0,
            // the mean grey the model was padded with in training.
            val x = Tensor(3, SkyNet.INPUT, SkyNet.INPUT)
            val plane = SkyNet.INPUT * SkyNet.INPUT
            Parallel.rows(nh) { y0, y1 ->
                for (ny in y0 until y1) {
                    val sy0 = ny * up.height / nh; val sy1 = max(sy0 + 1, (ny + 1) * up.height / nh)
                    for (nx in 0 until nw) {
                        val sx0 = nx * up.width / nw; val sx1 = max(sx0 + 1, (nx + 1) * up.width / nw)
                        var sr = 0; var sg = 0; var sb = 0
                        for (yy in sy0 until sy1) for (xx in sx0 until sx1) {
                            val px = argb[up.srcY(xx, yy) * width + up.srcX(xx, yy)]
                            sr += (px shr 16) and 0xFF; sg += (px shr 8) and 0xFF; sb += px and 0xFF
                        }
                        val k = 1f / ((sy1 - sy0) * (sx1 - sx0) * 127.5f)
                        val i = ny * SkyNet.INPUT + nx
                        x.data[i] = sr * k - 1f; x.data[plane + i] = sg * k - 1f; x.data[2 * plane + i] = sb * k - 1f
                    }
                }
            }
            val logits = SkyNet.logits(model, x)
            val all = SkyNet.groupProbabilities(logits, MaskRegion.entries.map { it.classes })
            val gw = (nw - 1) / 4 + 1; val gh = (nh - 1) / 4 + 1
            val masks = LinkedHashMap<MaskRegion, RegionMask>()
            for ((k, region) in MaskRegion.entries.withIndex()) {
                val prob = FloatArray(gw * gh) { all[k][(it / gw) * logits.w + it % gw] }
                masks[region] = RegionMask(gw, gh, prob, nw, nh, orientation)
            }
            return SceneMasks(masks)
        }
    }
}

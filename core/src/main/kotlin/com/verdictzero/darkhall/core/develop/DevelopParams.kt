package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.raw.RawMetadata
import kotlin.math.ln
import kotlin.math.roundToInt

enum class AdjustmentGroup(val title: String) {
    LOOK("Look"), LIGHT("Light"), COLOR("Color"), DETAIL("Detail"), LENS("Lens")
}

/**
 * Every control the app exposes. The UI is generated from this list, so the whole
 * interface really is sliders only.
 */
enum class Adjustment(
    val label: String,
    val group: AdjustmentGroup,
    val min: Float,
    val max: Float,
    val default: Float,
    val decimals: Int = 0,
    val unit: String = "",
) {
    /** How strongly the chosen look (3D LUT) is applied. */
    LOOK_AMOUNT("Look strength", AdjustmentGroup.LOOK, 0f, 100f, 100f),
    EXPOSURE("Exposure", AdjustmentGroup.LIGHT, -3f, 3f, 0f, 2, " EV"),
    CONTRAST("Contrast", AdjustmentGroup.LIGHT, -100f, 100f, 0f),
    HIGHLIGHTS("Highlights", AdjustmentGroup.LIGHT, -100f, 100f, 0f),
    SHADOWS("Shadows", AdjustmentGroup.LIGHT, -100f, 100f, 0f),
    WHITES("Whites", AdjustmentGroup.LIGHT, -100f, 100f, 0f),
    BLACKS("Blacks", AdjustmentGroup.LIGHT, -100f, 100f, 0f),

    TEMPERATURE("Temperature", AdjustmentGroup.COLOR, -100f, 100f, 0f),
    TINT("Tint", AdjustmentGroup.COLOR, -100f, 100f, 0f),
    /** Underwater colour: removes the blue-green veil and restores the red water absorbs. */
    WATER("Underwater", AdjustmentGroup.COLOR, 0f, 100f, 0f),
    VIBRANCE("Vibrance", AdjustmentGroup.COLOR, -100f, 100f, 0f),
    SATURATION("Saturation", AdjustmentGroup.COLOR, -100f, 100f, 0f),

    CLARITY("Clarity", AdjustmentGroup.DETAIL, -100f, 100f, 0f),
    DEHAZE("Dehaze", AdjustmentGroup.DETAIL, -100f, 100f, 0f),
    SHARPEN("Sharpening", AdjustmentGroup.DETAIL, 0f, 100f, 30f),
    LUMA_NR("Luminance NR", AdjustmentGroup.DETAIL, 0f, 100f, 0f),
    CHROMA_NR("Color NR", AdjustmentGroup.DETAIL, 0f, 100f, 25f),

    /** Strength of the built-in TG lens profile (distortion + colour fringing). */
    LENS_PROFILE("Lens profile", AdjustmentGroup.LENS, 0f, 100f, 100f),
    VIGNETTE("Vignette", AdjustmentGroup.LENS, -100f, 100f, 0f),
    DISTORTION("Distortion", AdjustmentGroup.LENS, -100f, 100f, 0f),
    STRAIGHTEN("Straighten", AdjustmentGroup.LENS, -10f, 10f, 0f, 1, "°"),
    ;

    fun format(v: Float): String {
        val s = if (decimals == 0) v.roundToInt().toString() else "%.${decimals}f".format(v)
        val signed = if (v > 0f && min < 0f) "+$s" else s
        return signed + unit
    }
}

/** Immutable slider state, plus the chosen look (a LUT id, or null for none). */
class DevelopParams private constructor(private val values: FloatArray, val look: String? = null) {

    operator fun get(a: Adjustment): Float = values[a.ordinal]

    fun with(a: Adjustment, v: Float): DevelopParams {
        val c = values.copyOf()
        c[a.ordinal] = v.coerceIn(a.min, a.max)
        return DevelopParams(c, look)
    }

    fun withLook(id: String?): DevelopParams = DevelopParams(values.copyOf(), id)

    fun reset(a: Adjustment, defaults: DevelopParams) = with(a, defaults[a])

    fun isDefault(a: Adjustment, defaults: DevelopParams) = kotlin.math.abs(values[a.ordinal] - defaults[a]) < 1e-4f

    fun toMap(): Map<String, Float> = Adjustment.entries.associate { it.name to values[it.ordinal] }

    override fun equals(other: Any?) = other is DevelopParams && other.values.contentEquals(values) && other.look == look
    override fun hashCode() = values.contentHashCode() * 31 + (look?.hashCode() ?: 0)
    override fun toString() = toMap().toString() + " look=$look"

    companion object {
        val NEUTRAL: DevelopParams = DevelopParams(FloatArray(Adjustment.entries.size) { Adjustment.entries[it].default })

        fun fromMap(m: Map<String, Float>, base: DevelopParams = NEUTRAL, look: String? = base.look): DevelopParams {
            val c = base.values.copyOf()
            for (a in Adjustment.entries) m[a.name]?.let { c[a.ordinal] = it.coerceIn(a.min, a.max) }
            return DevelopParams(c, look)
        }

        /**
         * Starting point for a file.
         *
         * Luminance noise reduction is profiled (its thresholds follow the noise measured in this
         * very file), so one moderate setting suits every ISO: it barely touches a base-ISO shot
         * and works proportionally harder on a noisy one. Colour noise reduction and sharpening
         * still need a level, taken from the measured noise expressed as an ISO ([noise]), or from
         * the EXIF ISO when no measurement is available. Underwater shots start with the
         * underwater correction switched on.
         */
        fun defaultsFor(meta: RawMetadata, noise: NoiseProfile? = null): DevelopParams {
            val iso = noise?.takeIf { it.measured }?.effectiveIso?.toDouble() ?: (if (meta.iso > 0) meta.iso.toDouble() else 100.0)
            val stops = (ln(iso / 100.0) / ln(2.0)).coerceIn(0.0, 7.0) // 0 at ISO 100, 5 at ISO 3200
            val chroma = (25.0 + stops * 12.0).coerceIn(0.0, 90.0).toFloat()
            val sharpen = (30.0 - stops * 3.0).coerceIn(10.0, 30.0).toFloat()
            // The TG-7 logs depth from its pressure sensor. Start the underwater correction there,
            // stronger the deeper the shot (red fades fast).
            val water = meta.estimatedDepth?.let { d -> if (d >= 0.5) (40.0 + 4.0 * d).coerceIn(40.0, 90.0).toFloat() else 0f } ?: 0f
            return NEUTRAL.with(Adjustment.LUMA_NR, DEFAULT_LUMA_NR).with(Adjustment.CHROMA_NR, chroma)
                .with(Adjustment.SHARPEN, sharpen).with(Adjustment.WATER, water)
        }

        /** Measured on simulated ISO 1600 noise: halves the error, keeps edge detail (NoiseTest). */
        const val DEFAULT_LUMA_NR = 25f
    }
}

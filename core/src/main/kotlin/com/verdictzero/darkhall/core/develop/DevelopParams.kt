package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.orf.OrfMetadata
import kotlin.math.ln
import kotlin.math.roundToInt

enum class AdjustmentGroup(val title: String) {
    LIGHT("Light"), COLOR("Color"), DETAIL("Detail"), LENS("Lens")
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
    EXPOSURE("Exposure", AdjustmentGroup.LIGHT, -3f, 3f, 0f, 2, " EV"),
    CONTRAST("Contrast", AdjustmentGroup.LIGHT, -100f, 100f, 0f),
    HIGHLIGHTS("Highlights", AdjustmentGroup.LIGHT, -100f, 100f, 0f),
    SHADOWS("Shadows", AdjustmentGroup.LIGHT, -100f, 100f, 0f),
    WHITES("Whites", AdjustmentGroup.LIGHT, -100f, 100f, 0f),
    BLACKS("Blacks", AdjustmentGroup.LIGHT, -100f, 100f, 0f),

    TEMPERATURE("Temperature", AdjustmentGroup.COLOR, -100f, 100f, 0f),
    TINT("Tint", AdjustmentGroup.COLOR, -100f, 100f, 0f),
    VIBRANCE("Vibrance", AdjustmentGroup.COLOR, -100f, 100f, 0f),
    SATURATION("Saturation", AdjustmentGroup.COLOR, -100f, 100f, 0f),

    CLARITY("Clarity", AdjustmentGroup.DETAIL, -100f, 100f, 0f),
    DEHAZE("Dehaze", AdjustmentGroup.DETAIL, -100f, 100f, 0f),
    SHARPEN("Sharpening", AdjustmentGroup.DETAIL, 0f, 100f, 30f),
    LUMA_NR("Luminance NR", AdjustmentGroup.DETAIL, 0f, 100f, 0f),
    CHROMA_NR("Color NR", AdjustmentGroup.DETAIL, 0f, 100f, 25f),

    VIGNETTE("Vignette", AdjustmentGroup.LENS, -100f, 100f, 0f),
    DISTORTION("Distortion", AdjustmentGroup.LENS, -100f, 100f, 0f),
    STRAIGHTEN("Straighten", AdjustmentGroup.LENS, -10f, 10f, 0f, 1, "°"),
    ;

    fun format(v: Float): String {
        val s = if (decimals == 0) v.roundToInt().toString() else "%.${decimals}f".format(v)
        val signed = if (v > 0f && this != SHARPEN && this != LUMA_NR && this != CHROMA_NR) "+$s" else s
        return signed + unit
    }
}

/** Immutable slider state. */
class DevelopParams private constructor(private val values: FloatArray) {

    operator fun get(a: Adjustment): Float = values[a.ordinal]

    fun with(a: Adjustment, v: Float): DevelopParams {
        val c = values.copyOf()
        c[a.ordinal] = v.coerceIn(a.min, a.max)
        return DevelopParams(c)
    }

    fun reset(a: Adjustment, defaults: DevelopParams) = with(a, defaults[a])

    fun isDefault(a: Adjustment, defaults: DevelopParams) = kotlin.math.abs(values[a.ordinal] - defaults[a]) < 1e-4f

    fun toMap(): Map<String, Float> = Adjustment.entries.associate { it.name to values[it.ordinal] }

    override fun equals(other: Any?) = other is DevelopParams && other.values.contentEquals(values)
    override fun hashCode() = values.contentHashCode()
    override fun toString() = toMap().toString()

    companion object {
        val NEUTRAL: DevelopParams = DevelopParams(FloatArray(Adjustment.entries.size) { Adjustment.entries[it].default })

        fun fromMap(m: Map<String, Float>, base: DevelopParams = NEUTRAL): DevelopParams {
            val c = base.values.copyOf()
            for (a in Adjustment.entries) m[a.name]?.let { c[a.ordinal] = it.coerceIn(a.min, a.max) }
            return DevelopParams(c)
        }

        /**
         * Starting point for a file. The TG-7's small sensor gets noisy quickly, so the
         * default noise reduction follows the ISO the shot was taken at:
         * nothing at base ISO, moderate by ISO 800, strong at ISO 3200+.
         */
        fun defaultsFor(meta: OrfMetadata): DevelopParams {
            val iso = if (meta.iso > 0) meta.iso else 100
            val stops = (ln(iso / 100.0) / ln(2.0)).coerceIn(0.0, 7.0) // 0 at ISO 100, 5 at ISO 3200
            val luma = (stops * 12.0).coerceIn(0.0, 70.0).toFloat()
            val chroma = (25.0 + stops * 12.0).coerceIn(0.0, 90.0).toFloat()
            val sharpen = (30.0 - stops * 3.0).coerceIn(10.0, 30.0).toFloat()
            return NEUTRAL.with(Adjustment.LUMA_NR, luma).with(Adjustment.CHROMA_NR, chroma).with(Adjustment.SHARPEN, sharpen)
        }
    }
}

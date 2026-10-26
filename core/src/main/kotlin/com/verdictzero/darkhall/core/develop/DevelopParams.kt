package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.raw.RawMetadata
import com.verdictzero.darkhall.core.sky.MaskRegion
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Editor sections, in order. [sliders] is false for a section with its own controls instead;
 * [region] is set for the sections that act on one part of the picture through its mask.
 */
enum class AdjustmentGroup(val title: String, val sliders: Boolean = true, val region: MaskRegion? = null) {
    LOOK("Look"), LIGHT("Light"), COLOR("Color"), MIXER("Color mixer", sliders = false), GRADING("Color grading"),
    SKY("Sky", region = MaskRegion.SKY), WATER("Water", region = MaskRegion.WATER),
    SUBJECT("Subject", region = MaskRegion.SUBJECT), FOLIAGE("Foliage", region = MaskRegion.FOLIAGE),
    DETAIL("Detail"), LENS("Lens"), CROP("Crop", sliders = false)
}

/** The colour mixer's eight hue bands, by the hue each is centred on (degrees, HSV). */
enum class MixBand(val label: String, val hue: Float, val swatch: Long) {
    RED("Red", 0f, 0xFFE5484D), ORANGE("Orange", 30f, 0xFFF0883E), YELLOW("Yellow", 60f, 0xFFE9D24A),
    GREEN("Green", 120f, 0xFF46A758), AQUA("Aqua", 180f, 0xFF3DC2C9), BLUE("Blue", 240f, 0xFF3E7BFA),
    PURPLE("Purple", 270f, 0xFF8E5BE8), MAGENTA("Magenta", 300f, 0xFFD658C4),
}

/** What a masked slider does within its region (see the region passes in Developer). */
enum class MaskKind { EXPOSURE, SATURATION, CLARITY, WARMTH }

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
    /** For a slider of a masked section: what it does there. */
    val maskKind: MaskKind? = null,
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
    /** Removes the flash-lit specks of plankton and silt in underwater shots (see Backscatter). */
    BACKSCATTER("Backscatter removal", AdjustmentGroup.COLOR, 0f, 100f, 0f),
    VIBRANCE("Vibrance", AdjustmentGroup.COLOR, -100f, 100f, 0f),
    SATURATION("Saturation", AdjustmentGroup.COLOR, -100f, 100f, 0f),

    // Colour mixer: hue, saturation and luminance of each MixBand (see ToneStage).
    MIX_RED_HUE("Red hue", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_RED_SAT("Red saturation", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_RED_LUM("Red luminance", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_ORANGE_HUE("Orange hue", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_ORANGE_SAT("Orange saturation", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_ORANGE_LUM("Orange luminance", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_YELLOW_HUE("Yellow hue", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_YELLOW_SAT("Yellow saturation", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_YELLOW_LUM("Yellow luminance", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_GREEN_HUE("Green hue", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_GREEN_SAT("Green saturation", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_GREEN_LUM("Green luminance", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_AQUA_HUE("Aqua hue", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_AQUA_SAT("Aqua saturation", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_AQUA_LUM("Aqua luminance", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_BLUE_HUE("Blue hue", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_BLUE_SAT("Blue saturation", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_BLUE_LUM("Blue luminance", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_PURPLE_HUE("Purple hue", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_PURPLE_SAT("Purple saturation", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_PURPLE_LUM("Purple luminance", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_MAGENTA_HUE("Magenta hue", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_MAGENTA_SAT("Magenta saturation", AdjustmentGroup.MIXER, -100f, 100f, 0f),
    MIX_MAGENTA_LUM("Magenta luminance", AdjustmentGroup.MIXER, -100f, 100f, 0f),

    // Colour grading: a tint for the shadows, the midtones and the highlights (see ToneStage).
    GRADE_SHADOW_HUE("Shadow hue", AdjustmentGroup.GRADING, 0f, 360f, 220f, unit = "°"),
    GRADE_SHADOW("Shadow tint", AdjustmentGroup.GRADING, 0f, 100f, 0f),
    GRADE_MIDTONE_HUE("Midtone hue", AdjustmentGroup.GRADING, 0f, 360f, 30f, unit = "°"),
    GRADE_MIDTONE("Midtone tint", AdjustmentGroup.GRADING, 0f, 100f, 0f),
    GRADE_HIGHLIGHT_HUE("Highlight hue", AdjustmentGroup.GRADING, 0f, 360f, 45f, unit = "°"),
    GRADE_HIGHLIGHT("Highlight tint", AdjustmentGroup.GRADING, 0f, 100f, 0f),
    /** Moves the split between shadows and highlights: positive gives the highlight tint more room. */
    GRADE_BALANCE("Balance", AdjustmentGroup.GRADING, -100f, 100f, 0f),

    CLARITY("Clarity", AdjustmentGroup.DETAIL, -100f, 100f, 0f),
    DEHAZE("Dehaze", AdjustmentGroup.DETAIL, -100f, 100f, 0f),
    SHARPEN("Sharpening", AdjustmentGroup.DETAIL, 0f, 100f, 30f),
    LUMA_NR("Luminance NR", AdjustmentGroup.DETAIL, 0f, 100f, 0f),
    CHROMA_NR("Color NR", AdjustmentGroup.DETAIL, 0f, 100f, 25f),
    /** Film grain, the same at any size the photo is shown or exported (see Grain). */
    GRAIN("Grain", AdjustmentGroup.DETAIL, 0f, 100f, 0f),
    GRAIN_SIZE("Grain size", AdjustmentGroup.DETAIL, 0f, 100f, 25f),

    /** Strength of the built-in TG lens profile (distortion + colour fringing). */
    LENS_PROFILE("Lens profile", AdjustmentGroup.LENS, 0f, 100f, 100f),
    VIGNETTE("Vignette", AdjustmentGroup.LENS, -100f, 100f, 0f),
    DISTORTION("Distortion", AdjustmentGroup.LENS, -100f, 100f, 0f),
    STRAIGHTEN("Straighten", AdjustmentGroup.LENS, -10f, 10f, 0f, 1, "°"),

    // Applied through each region's mask (that part of the photo only); see RegionMask.
    SKY_EXPOSURE("Sky exposure", AdjustmentGroup.SKY, -2f, 2f, 0f, 2, " EV", MaskKind.EXPOSURE),
    SKY_SATURATION("Sky saturation", AdjustmentGroup.SKY, -100f, 100f, 0f, maskKind = MaskKind.SATURATION),
    SKY_CLARITY("Sky clarity", AdjustmentGroup.SKY, -100f, 100f, 0f, maskKind = MaskKind.CLARITY),
    SKY_WARMTH("Sky warmth", AdjustmentGroup.SKY, -100f, 100f, 0f, maskKind = MaskKind.WARMTH),
    WATER_EXPOSURE("Water exposure", AdjustmentGroup.WATER, -2f, 2f, 0f, 2, " EV", MaskKind.EXPOSURE),
    WATER_SATURATION("Water saturation", AdjustmentGroup.WATER, -100f, 100f, 0f, maskKind = MaskKind.SATURATION),
    WATER_CLARITY("Water clarity", AdjustmentGroup.WATER, -100f, 100f, 0f, maskKind = MaskKind.CLARITY),
    WATER_WARMTH("Water warmth", AdjustmentGroup.WATER, -100f, 100f, 0f, maskKind = MaskKind.WARMTH),
    SUBJECT_EXPOSURE("Subject exposure", AdjustmentGroup.SUBJECT, -2f, 2f, 0f, 2, " EV", MaskKind.EXPOSURE),
    SUBJECT_SATURATION("Subject saturation", AdjustmentGroup.SUBJECT, -100f, 100f, 0f, maskKind = MaskKind.SATURATION),
    SUBJECT_CLARITY("Subject clarity", AdjustmentGroup.SUBJECT, -100f, 100f, 0f, maskKind = MaskKind.CLARITY),
    SUBJECT_WARMTH("Subject warmth", AdjustmentGroup.SUBJECT, -100f, 100f, 0f, maskKind = MaskKind.WARMTH),
    FOLIAGE_EXPOSURE("Foliage exposure", AdjustmentGroup.FOLIAGE, -2f, 2f, 0f, 2, " EV", MaskKind.EXPOSURE),
    FOLIAGE_SATURATION("Foliage saturation", AdjustmentGroup.FOLIAGE, -100f, 100f, 0f, maskKind = MaskKind.SATURATION),
    FOLIAGE_CLARITY("Foliage clarity", AdjustmentGroup.FOLIAGE, -100f, 100f, 0f, maskKind = MaskKind.CLARITY),
    FOLIAGE_WARMTH("Foliage warmth", AdjustmentGroup.FOLIAGE, -100f, 100f, 0f, maskKind = MaskKind.WARMTH),

    // The crop rectangle in the upright, straightened picture (0..1 of its width and height)
    // and the ratio picked for it (a CropAspect); set with the crop tool, see Crop.
    CROP_LEFT("Crop left", AdjustmentGroup.CROP, 0f, 1f, 0f, 4),
    CROP_TOP("Crop top", AdjustmentGroup.CROP, 0f, 1f, 0f, 4),
    CROP_RIGHT("Crop right", AdjustmentGroup.CROP, 0f, 1f, 1f, 4),
    CROP_BOTTOM("Crop bottom", AdjustmentGroup.CROP, 0f, 1f, 1f, 4),
    CROP_ASPECT("Crop ratio", AdjustmentGroup.CROP, 0f, 5f, 0f),
    ;

    companion object {
        /** The slider of [region]'s section that does [kind]. */
        fun masked(region: MaskRegion, kind: MaskKind): Adjustment =
            entries.first { it.group.region == region && it.maskKind == kind }

        /** Sliders only the CPU renderer implements (the masked ones aside, see [DevelopParams.usesMasks]). */
        val CPU_ONLY: Set<Adjustment> get() = setOf(BACKSCATTER)

        /** The colour mixer's sliders for [band]: hue, saturation, luminance. */
        fun mixer(band: MixBand): List<Adjustment> =
            listOf("HUE", "SAT", "LUM").map { valueOf("MIX_${band.name}_$it") }
    }

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

    /** True when any masked slider (Sky, Water, Subject, Foliage) is in use, so the photo's masks are needed. */
    fun usesMasks(): Boolean = Adjustment.entries.any { it.maskKind != null && kotlin.math.abs(values[it.ordinal]) > 1e-4f }

    /** True when a slider the GPU renderer doesn't implement is in use, so the preview renders on the CPU. */
    fun needsCpu(): Boolean = usesMasks() || Adjustment.CPU_ONLY.any { kotlin.math.abs(values[it.ordinal]) > 1e-4f }

    /** True when a slider of [region]'s section is in use. */
    fun uses(region: MaskRegion): Boolean =
        Adjustment.entries.any { it.group.region == region && kotlin.math.abs(values[it.ordinal]) > 1e-4f }

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

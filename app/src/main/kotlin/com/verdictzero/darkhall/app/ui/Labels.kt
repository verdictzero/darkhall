package com.verdictzero.darkhall.app.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.verdictzero.darkhall.R
import com.verdictzero.darkhall.core.develop.Adjustment
import com.verdictzero.darkhall.core.develop.AdjustmentGroup
import com.verdictzero.darkhall.core.develop.CropAspect
import com.verdictzero.darkhall.core.develop.MixBand
import com.verdictzero.darkhall.core.sky.MaskRegion

/*
 * Display names of the core's enums, from string resources so they can be translated (the
 * core's own labels are the English fallback for code that has no Android context).
 */

@get:StringRes
val Adjustment.titleRes: Int get() = when (this) {
    Adjustment.LOOK_AMOUNT -> R.string.adj_look_amount
    Adjustment.EXPOSURE -> R.string.adj_exposure
    Adjustment.CONTRAST -> R.string.adj_contrast
    Adjustment.HIGHLIGHTS -> R.string.adj_highlights
    Adjustment.SHADOWS -> R.string.adj_shadows
    Adjustment.WHITES -> R.string.adj_whites
    Adjustment.BLACKS -> R.string.adj_blacks
    Adjustment.TEMPERATURE -> R.string.adj_temperature
    Adjustment.TINT -> R.string.adj_tint
    Adjustment.WATER -> R.string.adj_water
    Adjustment.BACKSCATTER -> R.string.adj_backscatter
    Adjustment.VIBRANCE -> R.string.adj_vibrance
    Adjustment.SATURATION -> R.string.adj_saturation
    Adjustment.MIX_RED_HUE -> R.string.adj_mix_red_hue
    Adjustment.MIX_RED_SAT -> R.string.adj_mix_red_sat
    Adjustment.MIX_RED_LUM -> R.string.adj_mix_red_lum
    Adjustment.MIX_ORANGE_HUE -> R.string.adj_mix_orange_hue
    Adjustment.MIX_ORANGE_SAT -> R.string.adj_mix_orange_sat
    Adjustment.MIX_ORANGE_LUM -> R.string.adj_mix_orange_lum
    Adjustment.MIX_YELLOW_HUE -> R.string.adj_mix_yellow_hue
    Adjustment.MIX_YELLOW_SAT -> R.string.adj_mix_yellow_sat
    Adjustment.MIX_YELLOW_LUM -> R.string.adj_mix_yellow_lum
    Adjustment.MIX_GREEN_HUE -> R.string.adj_mix_green_hue
    Adjustment.MIX_GREEN_SAT -> R.string.adj_mix_green_sat
    Adjustment.MIX_GREEN_LUM -> R.string.adj_mix_green_lum
    Adjustment.MIX_AQUA_HUE -> R.string.adj_mix_aqua_hue
    Adjustment.MIX_AQUA_SAT -> R.string.adj_mix_aqua_sat
    Adjustment.MIX_AQUA_LUM -> R.string.adj_mix_aqua_lum
    Adjustment.MIX_BLUE_HUE -> R.string.adj_mix_blue_hue
    Adjustment.MIX_BLUE_SAT -> R.string.adj_mix_blue_sat
    Adjustment.MIX_BLUE_LUM -> R.string.adj_mix_blue_lum
    Adjustment.MIX_PURPLE_HUE -> R.string.adj_mix_purple_hue
    Adjustment.MIX_PURPLE_SAT -> R.string.adj_mix_purple_sat
    Adjustment.MIX_PURPLE_LUM -> R.string.adj_mix_purple_lum
    Adjustment.MIX_MAGENTA_HUE -> R.string.adj_mix_magenta_hue
    Adjustment.MIX_MAGENTA_SAT -> R.string.adj_mix_magenta_sat
    Adjustment.MIX_MAGENTA_LUM -> R.string.adj_mix_magenta_lum
    Adjustment.GRADE_SHADOW_HUE -> R.string.adj_grade_shadow_hue
    Adjustment.GRADE_SHADOW -> R.string.adj_grade_shadow
    Adjustment.GRADE_MIDTONE_HUE -> R.string.adj_grade_midtone_hue
    Adjustment.GRADE_MIDTONE -> R.string.adj_grade_midtone
    Adjustment.GRADE_HIGHLIGHT_HUE -> R.string.adj_grade_highlight_hue
    Adjustment.GRADE_HIGHLIGHT -> R.string.adj_grade_highlight
    Adjustment.GRADE_BALANCE -> R.string.adj_grade_balance
    Adjustment.CLARITY -> R.string.adj_clarity
    Adjustment.DEHAZE -> R.string.adj_dehaze
    Adjustment.SHARPEN -> R.string.adj_sharpen
    Adjustment.LUMA_NR -> R.string.adj_luma_nr
    Adjustment.CHROMA_NR -> R.string.adj_chroma_nr
    Adjustment.GRAIN -> R.string.adj_grain
    Adjustment.GRAIN_SIZE -> R.string.adj_grain_size
    Adjustment.LENS_PROFILE -> R.string.adj_lens_profile
    Adjustment.VIGNETTE -> R.string.adj_vignette
    Adjustment.DISTORTION -> R.string.adj_distortion
    Adjustment.STRAIGHTEN -> R.string.adj_straighten
    Adjustment.SKY_EXPOSURE -> R.string.adj_sky_exposure
    Adjustment.SKY_SATURATION -> R.string.adj_sky_saturation
    Adjustment.SKY_CLARITY -> R.string.adj_sky_clarity
    Adjustment.SKY_WARMTH -> R.string.adj_sky_warmth
    Adjustment.WATER_EXPOSURE -> R.string.adj_water_exposure
    Adjustment.WATER_SATURATION -> R.string.adj_water_saturation
    Adjustment.WATER_CLARITY -> R.string.adj_water_clarity
    Adjustment.WATER_WARMTH -> R.string.adj_water_warmth
    Adjustment.SUBJECT_EXPOSURE -> R.string.adj_subject_exposure
    Adjustment.SUBJECT_SATURATION -> R.string.adj_subject_saturation
    Adjustment.SUBJECT_CLARITY -> R.string.adj_subject_clarity
    Adjustment.SUBJECT_WARMTH -> R.string.adj_subject_warmth
    Adjustment.FOLIAGE_EXPOSURE -> R.string.adj_foliage_exposure
    Adjustment.FOLIAGE_SATURATION -> R.string.adj_foliage_saturation
    Adjustment.FOLIAGE_CLARITY -> R.string.adj_foliage_clarity
    Adjustment.FOLIAGE_WARMTH -> R.string.adj_foliage_warmth
    Adjustment.CROP_LEFT -> R.string.adj_crop_left
    Adjustment.CROP_TOP -> R.string.adj_crop_top
    Adjustment.CROP_RIGHT -> R.string.adj_crop_right
    Adjustment.CROP_BOTTOM -> R.string.adj_crop_bottom
    Adjustment.CROP_ASPECT -> R.string.adj_crop_aspect
}

@get:StringRes
val AdjustmentGroup.titleRes: Int get() = when (this) {
    AdjustmentGroup.LOOK -> R.string.group_look
    AdjustmentGroup.LIGHT -> R.string.group_light
    AdjustmentGroup.COLOR -> R.string.group_color
    AdjustmentGroup.MIXER -> R.string.group_mixer
    AdjustmentGroup.GRADING -> R.string.group_grading
    AdjustmentGroup.SKY -> R.string.group_sky
    AdjustmentGroup.WATER -> R.string.group_water
    AdjustmentGroup.SUBJECT -> R.string.group_subject
    AdjustmentGroup.FOLIAGE -> R.string.group_foliage
    AdjustmentGroup.DETAIL -> R.string.group_detail
    AdjustmentGroup.LENS -> R.string.group_lens
    AdjustmentGroup.CROP -> R.string.group_crop
}

@get:StringRes
val MixBand.titleRes: Int get() = when (this) {
    MixBand.RED -> R.string.band_red
    MixBand.ORANGE -> R.string.band_orange
    MixBand.YELLOW -> R.string.band_yellow
    MixBand.GREEN -> R.string.band_green
    MixBand.AQUA -> R.string.band_aqua
    MixBand.BLUE -> R.string.band_blue
    MixBand.PURPLE -> R.string.band_purple
    MixBand.MAGENTA -> R.string.band_magenta
}

@get:StringRes
val MaskRegion.titleRes: Int get() = when (this) {
    MaskRegion.SKY -> R.string.region_sky
    MaskRegion.WATER -> R.string.region_water
    MaskRegion.SUBJECT -> R.string.region_subject
    MaskRegion.FOLIAGE -> R.string.region_foliage
}

@get:StringRes
val MaskRegion.nounRes: Int get() = when (this) {
    MaskRegion.SKY -> R.string.region_sky_noun
    MaskRegion.WATER -> R.string.region_water_noun
    MaskRegion.SUBJECT -> R.string.region_subject_noun
    MaskRegion.FOLIAGE -> R.string.region_foliage_noun
}

@Composable fun Adjustment.title(): String = stringResource(titleRes)
fun Adjustment.title(context: Context): String = context.getString(titleRes)
@Composable fun AdjustmentGroup.title(): String = stringResource(titleRes)
@Composable fun MixBand.title(): String = stringResource(titleRes)
@Composable fun MaskRegion.title(): String = stringResource(titleRes)
@Composable fun MaskRegion.noun(): String = stringResource(nounRes)
fun MaskRegion.noun(context: Context): String = context.getString(nounRes)

/** Ratios are the same in every language; only Free and Original are words. */
@Composable
fun CropAspect.title(): String = when (this) {
    CropAspect.FREE -> stringResource(R.string.aspect_free)
    CropAspect.ORIGINAL -> stringResource(R.string.aspect_original)
    else -> label
}

package com.verdictzero.darkhall.app.data

import android.content.Context
import androidx.annotation.StringRes
import com.verdictzero.darkhall.R

/**
 * One third-party component bundled in the app: what it is used for, who holds the copyright,
 * and its licence. [use] is a string resource (shown translated); [text] is an asset holding the
 * full licence or notice.
 */
data class OpenSourceComponent(
    val name: String,
    @StringRes val use: Int,
    val copyright: String,
    val license: String,
    val url: String,
    val text: String,
)

/** The third-party notices (NOTICE.md in the repository), with licence texts from assets. */
object OpenSourceLicenses {
    const val SOURCE_URL = "https://github.com/verdictzero/darkhall"

    val components = listOf(
        OpenSourceComponent(
            "LibRaw",
            R.string.licenses_use_libraw,
            "Copyright (C) 2008-2025 LibRaw LLC. Fujifilm compressed and Canon CR3 decoders copyright (C) 2016-2019 Alexey Danilchenko. " +
                "Includes code from dcraw.c, copyright 1997-2018 Dave Coffin (no restricted dcraw code).",
            "Common Development and Distribution License 1.0",
            "https://www.libraw.org",
            "licenses/CDDL-1.0.txt",
        ),
        OpenSourceComponent(
            "Adobe DNG SDK 1.4 (portions, via LibRaw)",
            R.string.licenses_use_dng_sdk,
            "Copyright (c) 2005 Adobe Systems Incorporated",
            "MIT",
            "https://helpx.adobe.com/camera-raw/digital-negative.html",
            "licenses/MIT-Adobe-DNG-SDK.txt",
        ),
        OpenSourceComponent(
            "RawTherapee Film Simulation Collection",
            R.string.licenses_use_film_simulation,
            "Pat David, Pavlov Dmitry, Michael Ezra",
            "CC BY-SA 4.0",
            "https://creativecommons.org/licenses/by-sa/4.0/",
            "looks/LICENSES.txt",
        ),
        OpenSourceComponent(
            "lensfun",
            R.string.licenses_use_lensfun,
            "The lensfun contributors",
            "CC BY-SA 3.0",
            "https://creativecommons.org/licenses/by-sa/3.0/",
            "licenses/lensfun.txt",
        ),
        OpenSourceComponent(
            "DeepLab v3+ (MobileNetV2, ADE20K)",
            R.string.licenses_use_deeplab,
            "Copyright The TensorFlow Authors",
            "Apache License 2.0",
            "https://github.com/tensorflow/models/tree/master/research/deeplab",
            "sky/LICENSE.txt",
        ),
        OpenSourceComponent(
            "AndroidX and Jetpack Compose",
            R.string.licenses_use_androidx,
            "Copyright The Android Open Source Project; Material Icons copyright Google LLC",
            "Apache License 2.0",
            "https://developer.android.com/jetpack/androidx",
            "licenses/Apache-2.0.txt",
        ),
        OpenSourceComponent(
            "Kotlin and kotlinx.coroutines",
            R.string.licenses_use_kotlin,
            "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors",
            "Apache License 2.0",
            "https://kotlinlang.org",
            "licenses/Apache-2.0.txt",
        ),
    )

    fun text(context: Context, component: OpenSourceComponent): String =
        context.assets.open(component.text).bufferedReader().use { it.readText() }
}

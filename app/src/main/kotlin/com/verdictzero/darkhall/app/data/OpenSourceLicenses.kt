package com.verdictzero.darkhall.app.data

import android.content.Context

/**
 * One third-party component bundled in the app: what it is used for, who holds the copyright,
 * and its licence. [text] is an asset holding the full licence or notice.
 */
data class OpenSourceComponent(
    val name: String,
    val use: String,
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
            "Raw decoders, camera colour and Canon model tables",
            "Copyright (C) 2008-2025 LibRaw LLC. Fujifilm compressed decoder copyright (C) 2016-2019 Alexey Danilchenko. " +
                "Includes code from dcraw.c, copyright 1997-2018 Dave Coffin (no restricted dcraw code).",
            "Common Development and Distribution License 1.0",
            "https://www.libraw.org",
            "licenses/CDDL-1.0.txt",
        ),
        OpenSourceComponent(
            "Adobe DNG SDK 1.4 (portions, via LibRaw)",
            "Floating-point DNG decoding",
            "Copyright (c) 2005 Adobe Systems Incorporated",
            "MIT",
            "https://helpx.adobe.com/camera-raw/digital-negative.html",
            "licenses/MIT-Adobe-DNG-SDK.txt",
        ),
        OpenSourceComponent(
            "RawTherapee Film Simulation Collection",
            "Film looks, resampled to 33³ tables",
            "Pat David, Pavlov Dmitry, Michael Ezra",
            "CC BY-SA 4.0",
            "https://creativecommons.org/licenses/by-sa/4.0/",
            "looks/LICENSES.txt",
        ),
        OpenSourceComponent(
            "lensfun",
            "TG-5/6/7 lens distortion and chromatic aberration coefficients",
            "The lensfun contributors",
            "CC BY-SA 3.0",
            "https://creativecommons.org/licenses/by-sa/3.0/",
            "licenses/lensfun.txt",
        ),
        OpenSourceComponent(
            "DeepLab v3+ (MobileNetV2, ADE20K)",
            "Sky detection model",
            "Copyright The TensorFlow Authors",
            "Apache License 2.0",
            "https://github.com/tensorflow/models/tree/master/research/deeplab",
            "sky/LICENSE.txt",
        ),
        OpenSourceComponent(
            "AndroidX and Jetpack Compose",
            "Core, SplashScreen, Activity, Lifecycle, Compose UI, Foundation, Material 3, Material Icons, DocumentFile, ExifInterface, WorkManager",
            "Copyright The Android Open Source Project; Material Icons copyright Google LLC",
            "Apache License 2.0",
            "https://developer.android.com/jetpack/androidx",
            "licenses/Apache-2.0.txt",
        ),
        OpenSourceComponent(
            "Kotlin and kotlinx.coroutines",
            "Language runtime",
            "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors",
            "Apache License 2.0",
            "https://kotlinlang.org",
            "licenses/Apache-2.0.txt",
        ),
    )

    fun text(context: Context, component: OpenSourceComponent): String =
        context.assets.open(component.text).bufferedReader().use { it.readText() }
}

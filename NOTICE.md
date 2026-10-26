# Third-party notices

Darkhall is Copyright (c) 2026 verdictzero, all rights reserved (see `LICENSE`), except for
the components below, which keep their own licences. Licence texts are in `LICENSES/`; the
app shows the same list under ⋮ → Open-source licences.

Source code: <https://github.com/verdictzero/darkhall>

## Raw decoders: LibRaw and dcraw (CDDL 1.0)

The raw decoders in `core/src/main/kotlin/com/verdictzero/darkhall/core/raw/` (`Bits.kt`,
`Canon.kt`, `Dng.kt`, `Nikon.kt`, `OlympusDecoder.kt`, `Panasonic.kt`, `Pentax.kt`,
`Sony.kt`) follow LibRaw's and dcraw's decoding code, and the camera colour and Canon model
tables (`core/src/main/resources/darkhall/camera_colors.tsv`, `canon_models.tsv`) are
extracted from LibRaw 0.22.1 by `tools/colors/`.

- LibRaw: Copyright (C) 2008-2025 LibRaw LLC (<https://www.libraw.org>, info@libraw.org).
  LibRaw is offered under the LGPL 2.1 or the CDDL 1.0; Darkhall uses it under the
  **Common Development and Distribution License 1.0** (`LICENSES/CDDL-1.0.txt`).
- dcraw.c: copyright 1997-2018 Dave Coffin. Only unrestricted dcraw code is used, as in
  LibRaw.
- Portions of LibRaw come from the Adobe DNG SDK 1.4, Copyright (c) 2005 Adobe Systems
  Incorporated, under the MIT licence (`LICENSES/MIT-Adobe-DNG-SDK.txt`).

Each of those files carries a CDDL header. The CDDL covers those files only; it does not
extend to the rest of Darkhall. Their source code is available at the link above.

## Film simulation looks (CC BY-SA 4.0)

The film looks in `app/src/main/assets/looks/` (every `.dlut` except `dh-*`) are adapted
from the RawTherapee Film Simulation Collection, version 2015-09-20, by Pat David, Pavlov
Dmitry and Michael Ezra, licensed under the Creative Commons Attribution-ShareAlike 4.0
International licence (<https://creativecommons.org/licenses/by-sa/4.0/>). Darkhall resampled
the Hald CLUTs to 33×33×33 tables; the adapted tables are likewise CC BY-SA 4.0. Darkhall
shows them under its own descriptive names and is not affiliated with or endorsed by any
film maker. Full notice: `app/src/main/assets/looks/LICENSES.txt`.

Darkhall's own looks (`dh-*.dlut`) are dedicated to the public domain under CC0 1.0.

## Lens profile (CC BY-SA 3.0)

The distortion and lateral chromatic aberration coefficients for the TG-5/6/7 lens in
`core/src/main/kotlin/com/verdictzero/darkhall/core/develop/LensProfile.kt` come from the
lensfun database (<https://lensfun.github.io>, `compact-olympus.xml`), licensed under the
Creative Commons Attribution-ShareAlike 3.0 licence
(<https://creativecommons.org/licenses/by-sa/3.0/>).

## Sky detection model (Apache 2.0)

`app/src/main/assets/sky/deeplab_mnv2_ade20k.bin` is DeepLab v3+ with a MobileNetV2 backbone
trained on ADE20K, from the TensorFlow Models repository
(<https://github.com/tensorflow/models/tree/master/research/deeplab>), Copyright The
TensorFlow Authors, licensed under the Apache License 2.0 (`LICENSES/Apache-2.0.txt`).
Darkhall converted it (batch norm folded into the convolutions, 16-bit floats) with
`tools/sky/export_deeplab.py`. The ADE20K training images (MIT CSAIL) are released for
research and education; the model weights are distributed by Google under Apache 2.0.

## Libraries (Apache 2.0)

Linked into the app, all under the Apache License 2.0 (`LICENSES/Apache-2.0.txt`):

- Kotlin standard library and kotlinx.coroutines: Copyright JetBrains s.r.o. and Kotlin
  Programming Language contributors.
- AndroidX (Core, Core SplashScreen, Activity, Lifecycle, Compose UI, Foundation,
  Material 3, Material Icons, DocumentFile, ExifInterface, WorkManager): Copyright The
  Android Open Source Project.

The undo and redo icons in `app/src/main/kotlin/com/verdictzero/darkhall/app/ui/Glyphs.kt`
are Material Icons paths, Copyright Google LLC, Apache License 2.0.

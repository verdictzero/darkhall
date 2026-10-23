# Darkhall

A sliders-only RAW developer for Android, built around the files an
**Olympus Tough TG-7** writes.

No curves, no masks, no presets: open an `.ORF`, drag sliders, export a JPEG.

## What "tailored to the TG-7" means

* **Native ORF decoding.** The TG-7 writes 4040 × 3016 twelve-bit sensor data with
  Olympus' lossless adaptive-predictive compression. Darkhall decodes it directly
  (a Kotlin port of the public-domain dcraw algorithm) with no native libraries. On both a
  TG-6 and a TG-7 sample the output is bit-identical to LibRaw across all 12 million samples.
* **OM System files.** The TG-7 is sold by OM Digital Solutions and its MakerNote starts
  with `OM SYSTEM` instead of `OLYMPUS`; both layouts are read.
* **Camera metadata drives the defaults.** As-shot white balance, per-channel black
  level, the 4000 × 3000 crop the camera intends, the GRBG pattern and the
  12-bit white point are read from the MakerNote. Colour uses the matrix the
  camera itself embeds in every file, so the starting point matches the TG-7's JPEGs;
  the Adobe matrix for the TG-5/6/7 sensor is the fallback.
* **Underwater correction from the depth gauge.** The TG-7 records water depth (and air
  pressure, from which depth can be derived) in every file. The *Underwater* slider removes
  the blue-green veil and restores the red that water absorbs; for shots taken below 0.5 m it
  starts switched on, stronger the deeper the shot.
* **Built-in lens profile.** Distortion and colour fringing of the 4.5–18 mm lens are
  corrected automatically (the *Lens profile* slider sets the strength).
* **Profiled noise reduction.** The 1/2.33" sensor gets noisy early. Darkhall measures the
  noise in each file's raw data (shot and read noise per colour channel, fitted from the
  flattest patches of the picture), follows it through white balance, the colour matrix and
  exposure, and sets the luminance denoiser's thresholds from it: a multi-scale wavelet
  shrinkage that removes noise-sized detail and leaves real edges. Pushing exposure or
  shooting at high ISO automatically gets proportionally more denoising at the same slider
  value. Colour noise reduction and sharpening start from the measured noise level (shown as
  an equivalent ISO in the editor).
* **Highlight recovery.** Blown channels are rebuilt from the colour of their surroundings
  instead of turning magenta, and a soft shoulder keeps bright skies and surf from clipping
  hard; pull *Highlights* down to bring the recovered detail into range.
* **Field data kept.** Exported JPEGs carry the camera's GPS position and altitude, and the
  depth, temperature and pressure readings go into the image description.
* **Thumbnails from the embedded preview.** The library reads only the first ~1 MB of
  each file to show the camera's own preview JPEG, so a card full of raws browses quickly.

## Sliders

| Light | Color | Detail | Lens |
|---|---|---|---|
| Exposure | Temperature | Clarity | Lens profile |
| Contrast | Tint | Dehaze | Vignette |
| Highlights | Underwater | Sharpening | Distortion |
| Shadows | Vibrance | Luminance NR | Straighten |
| Whites | Saturation | Color NR | |
| Blacks | | | |

Double-tap a slider's label to reset it; the ⋮ menu resets everything.
Hold **Hold to compare** to see the as-shot rendering.
Pinch or double-tap to zoom: once the preview runs out of pixels, the visible area is
re-rendered from the raw at up to full resolution, so sharpening and noise reduction can be
judged properly. Edits are saved automatically per file and restored when you reopen it.

## Using it

1. Copy the `.ORF` files from the TG-7's card (`DCIM/100OLYMP`) to the phone, or plug
   the card in with an OTG reader.
2. **Folder** picks a directory to browse; **Files** opens individual raws. Opening an
   `.ORF` from a file manager also works.
3. Tap a thumbnail, adjust, then **Export**. JPEGs (quality 95, full 12 MP, with the
   shooting data copied into EXIF) land in `Pictures/Darkhall`.
4. To treat a whole dive alike: ⋮ → **Copy settings** on one photo, then in the library
   long-press to select others and tap **Paste** (each photo keeps its own Straighten).
   **Export** on a selection renders the batch.

Exports run in a background job with a progress notification, so they finish even if you
leave the app; several exports queue up and run one after another.

If the app ever crashes, the next launch shows the error with Copy and Share buttons.

## Building

Requires JDK 17+ and the Android SDK (platform 36).

```
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # unsigned; sign with your own key
```

### Tests

The decoder, pipeline and app tests run against a real file. Two have been used:

* the CC0 TG-6 sample from raw.pixls.us (same sensor and file format as the TG-7);
* a TG-7 file shared on the pixls.us forum (not redistributed here).

```
./gradlew :core:test -Pdarkhall.sample=/path/to/file.orf \
    -Pdarkhall.sampleRef=/path/to/raw_u16le.bin \
    -Pdarkhall.outDir=/tmp/darkhall-out      # writes rendered PNGs for inspection

./gradlew :app:testDebugUnitTest -Pdarkhall.sample=/path/to/file.orf
```

The reference dump is the `raw_image` array from rawpy/LibRaw written as little-endian
uint16. Without `-Pdarkhall.sample` the file-based tests are skipped.

What they cover:

* bit-exact decoding against LibRaw, and metadata parsing for both bodies;
* fuzzing: truncated files, random corruption, and every IFD entry rewritten with hostile
  counts, offsets and types — all must fail cleanly, never crash;
* every slider at both extremes; band seams in the full-size export;
* underwater correction on a simulated 8 m dive; highlight recovery on a simulated
  overexposure; zoomed regions matching the full export pixel for pixel;
* noise profiling: recovers known synthetic noise within a few percent, measures noise added
  to a real file, demosaic noise factors; the denoiser halves the error of simulated
  ISO 1600 noise against the clean original while keeping strong-edge detail;
* an end-to-end app run under Robolectric: open the file via a VIEW intent, move sliders,
  pinch to full-resolution zoom, copy settings, export through WorkManager, and check the
  JPEG's size, rotation and EXIF.

## Layout

* `core/` – pure Kotlin/JVM: ORF parser, Olympus decoder, demosaic (superpixel for
  previews, Malvar-He-Cutler for export and zoom), the slider pipeline, lens geometry,
  region rendering for zoom, banded 12 MP export.
* `app/` – Jetpack Compose UI, Storage Access Framework browsing, WorkManager export to
  MediaStore.

## Notes and limits

* Preview edits run on a ~1280 px proxy; export re-renders at full resolution in bands
  so a 12 MP float pipeline fits in a phone's heap.
* Only the TG-series ORF layout is exercised. Other Olympus/OM bodies that use the same
  compression will probably open, but no promises; the lens profile is applied only to
  TG-5, TG-6 and TG-7 files.
* The underwater correction is a global one (veil removal plus grey-world balance). It
  cannot bring back red that is completely gone; very deep natural-light shots stay blue-ish.
* Rendering is on the CPU. Slider updates take roughly 30–150 ms on a desktop-class CPU and
  will be slower on a phone.

## Credits

Lens distortion and TCA coefficients for the TG-5/6/7 lens come from the
[lensfun](https://lensfun.github.io) database (CC BY-SA 3.0).

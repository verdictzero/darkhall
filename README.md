# Darkhall

A sliders-only RAW developer for Android, built around the files an
**Olympus Tough TG-7** writes.

No curves, no masks, no presets: open an `.ORF`, drag sliders, export a JPEG.

## What "tailored to the TG-7" means

* **Native ORF decoding.** The TG-7 writes 4040 × 3016 twelve-bit sensor data with
  Olympus' lossless adaptive-predictive compression. Darkhall decodes it directly
  (a Kotlin port of the public-domain dcraw algorithm) with no native libraries. On a
  TG-6 sample the output is bit-identical to LibRaw across all 12 million samples.
* **Camera metadata drives the defaults.** As-shot white balance, per-channel black
  level, the 4000 × 3000 crop the camera intends, the GRBG pattern and the
  12-bit white point are read from the Olympus MakerNote. Colour uses the matrix the
  camera itself embeds in every file, so the starting point matches the TG-7's JPEGs;
  the Adobe matrix for the TG-5/6/7 sensor is the fallback.
* **Defaults scaled to the small sensor.** The 1/2.33" sensor gets noisy early, so
  luminance and colour noise reduction start at ISO-dependent values (nothing at ISO 100,
  strong by ISO 3200) and sharpening backs off at high ISO.
* **Tough-camera tools.** Dehaze (dark channel prior) and highlight desaturation for
  underwater and beach shots, plus distortion, vignette and straighten sliders for the
  4.5–18 mm lens.
* **Thumbnails from the embedded preview.** The library reads only the first ~1 MB of
  each file to show the camera's own preview JPEG, so a card full of raws browses quickly.

## Sliders

| Light | Color | Detail | Lens |
|---|---|---|---|
| Exposure | Temperature | Clarity | Vignette |
| Contrast | Tint | Dehaze | Distortion |
| Highlights | Vibrance | Sharpening | Straighten |
| Shadows | Saturation | Luminance NR | |
| Whites | | Color NR | |
| Blacks | | | |

Double-tap a slider's label to reset it; the refresh icon resets everything.
Hold **Hold to compare** to see the as-shot rendering. Pinch to zoom, double-tap to reset.
Edits are saved automatically per file and restored when you reopen it.

## Using it

1. Copy the `.ORF` files from the TG-7's card (`DCIM/100OLYMP`) to the phone, or plug
   the card in with an OTG reader.
2. **Folder** picks a directory to browse; **Files** opens individual raws. Opening an
   `.ORF` from a file manager also works.
3. Tap a thumbnail, adjust, then **Export**. JPEGs (quality 95, full 12 MP, with the
   shooting data copied into EXIF) land in `Pictures/Darkhall`.

## Building

Requires JDK 17+ and the Android SDK (platform 36).

```
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # unsigned; sign with your own key
```

### Tests

The decoder and pipeline tests run against a real file. Download the CC0 TG-6 sample
from raw.pixls.us (same sensor and file format as the TG-7) and, optionally, a LibRaw
dump of its raw samples for a bit-exact comparison:

```
./gradlew :core:test -Pdarkhall.sample=/path/to/tg6.orf \
    -Pdarkhall.sampleRef=/path/to/tg6_raw_u16le.bin \
    -Pdarkhall.outDir=/tmp/darkhall-out      # writes rendered PNGs for inspection
```

The reference dump is the `raw_image` array from rawpy/LibRaw written as little-endian
uint16. Without `-Pdarkhall.sample` the file-based tests are skipped.

## Layout

* `core/` – pure Kotlin/JVM: ORF parser, Olympus decoder, demosaic (superpixel for
  previews, Malvar-He-Cutler for export), the slider pipeline, banded 12 MP export.
* `app/` – Jetpack Compose UI, Storage Access Framework browsing, MediaStore export.

## Notes and limits

* Preview edits run on a ~1280 px proxy; export re-renders at full resolution in bands
  so a 12 MP float pipeline fits in a phone's heap.
* Only the TG-series ORF layout is exercised. Other Olympus/OM bodies that use the same
  compression will probably open, but no promises.
* Lens distortion has no built-in profile; the slider is manual.

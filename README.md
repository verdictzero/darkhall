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
* **Auto tone.** One tap sets Exposure, Contrast, Highlights, Shadows, Whites and Blacks,
  judged through the real tone pipeline; see [Auto tone](#auto-tone).
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
* **370 looks (3D LUTs).** The full RawTherapee Film Simulation Collection — Fuji Velvia,
  Provia, Superia and Pro 400H, Kodak Portra, Ektar, Kodachrome and Tri-X, Ilford, Polaroid,
  Agfa, Rollei and more — plus 77 Darkhall originals, including underwater looks for TG-7
  dive shots. See [Looks](#looks).
* **GPU preview rendering, tuned for Adreno.** Slider changes render on the phone's GPU
  (OpenGL ES 3.0 fragment shaders); see [GPU rendering](#gpu-rendering).
* **Thumbnails from the embedded preview.** The library reads only the first ~1 MB of
  each file to show the camera's own preview JPEG, so a card full of raws browses quickly.

## Sliders

| Look | Light | Color | Detail | Lens |
|---|---|---|---|---|
| Look (browser) | Exposure | Temperature | Clarity | Lens profile |
| Look strength | Contrast | Tint | Dehaze | Vignette |
| | Highlights | Underwater | Sharpening | Distortion |
| | Shadows | Vibrance | Luminance NR | Straighten |
| | Whites | Saturation | Color NR | |
| | Blacks | | | |

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

## Auto tone

**Auto** (on the Light section) sets the six tone sliders for the photo in front of you;
the message lists what changed and offers **Undo**. ⋮ → **Auto tone new photos** applies it
whenever a photo without saved edits is opened, and in the library a selection's **Auto**
action tones a whole dive in one go (each photo's other edits and look are kept).

How it decides: the photo is sampled after white balance, the underwater correction and the
camera matrix, so it respects the colour settings already made. A search then tries slider
combinations, rendering each through the same tone stage the editor uses (shadows/highlights,
highlight shoulder, curve, clipping), and scores the result on

* mean brightness close to a balanced 0.44 (display),
* a tonal range reaching from near black (2nd percentile ≈ 0.045) to near white (98th ≈ 0.93),
* few blown or crushed pixels,
* sliders moved no further than needed, in the directions a person would use them
  (Shadows only lifts, Highlights only recovers, moderate Contrast and Blacks).

Exposure starts from the scene's log-average brightness, so a frame two stops too dark gets
two stops more exposure and otherwise the same settings. Dehaze, clarity and noise reduction
are left alone; very hazy water still benefits from the Dehaze slider.

## Looks

Tap **Look → Browse** to open the library: every look is shown as a thumbnail of the photo
you are editing, on four shelves (Darkhall, Film color, Film B&W, Creative) with search. The
**Look strength** slider blends it in. Looks are saved with each photo's edits and travel with
copy/paste.

| Shelf | Looks | Source | License |
|---|---|---|---|
| Film color | 194 | RawTherapee Film Simulation Collection | CC BY-SA 4.0 |
| Film B&W | 66 | RawTherapee Film Simulation Collection | CC BY-SA 4.0 |
| Creative | 33 | RawTherapee Film Simulation Collection (CreativePack-1) | CC BY-SA 4.0 |
| Darkhall | 77 | generated by `tools/looks/build_looks.py` | CC0 |

The Darkhall shelf covers underwater (Reef, Blue Water, Kelp Forest, …), cinematic grades,
split-tones, faded/matte, cross-processing, black & white with colour filters, and duotones.

A look is applied to display-encoded colour after the tone curve and saturation, before
clarity and sharpening, with trilinear interpolation on a 33³ table. The film tables are
resampled from the 144³ Hald CLUTs; on exact grid points Velvia 50 matches its source within
0.3 of an 8-bit level. Each table is stored as 8-bit values with a 3D neighbour predictor and
zlib (about 28 KB per look, 10 MB for the library).

To rebuild the library (e.g. after changing the originals):

```
curl -LO https://rawtherapee.com/shared/HaldCLUT.zip && unzip HaldCLUT.zip
python3 tools/looks/build_looks.py --hald HaldCLUT --out app/src/main/assets/looks
```

## GPU rendering

The live preview runs the whole slider pipeline on the GPU: about 35 full-screen passes of
OpenGL ES 3.0 fragment shaders over float textures, then one read-back. The CPU pipeline is
still the reference: exports and the full-resolution zoom use it, and the GPU must reproduce it.

Written with Qualcomm Adreno in mind:

* all maths in `highp` (Adreno really does run `mediump` at 16 bits, too coarse for linear
  light and noise thresholds);
* only `texelFetch` with explicit edge clamping, so no float-filtering extension is needed;
* no compute shaders, SSBOs or image load/store — plain GLES 3.0, which every Adreno from the
  3xx series on supports;
* 32-bit float render targets via `EXT_color_buffer_float` (Adreno 4xx and later), falling
  back to 16-bit (`EXT_color_buffer_half_float`, Adreno 3xx);
* every pass invalidates its target first, so Adreno's tiled renderer doesn't reload the old
  contents into on-chip memory before overwriting them.

Safety nets, because no GPU driver is tested here:

* the first time a GPU/driver combination is seen, the app renders the same picture on GPU and
  CPU and compares them; a driver that doesn't match is remembered and left off;
* any GL error while editing switches that session to the CPU, with a message;
* the ⋮ menu shows the GPU in use and has a switch to turn GPU rendering off.

The editor shows which engine drew the preview next to the render time.

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

GPU tests need an EGL/GLES 3 library: Mesa (`apt install libegl1 libgles2 libegl-mesa0
libgl1-mesa-dri`) is used by default; for ANGLE pass
`-Pdarkhall.gles=angle -Pdarkhall.angleDir=<dir with libEGL.so, libGLESv2.so, libvk_swiftshader.so>`
(Chromium's install directory has all three). Without either, they are skipped.

The reference dump is the `raw_image` array from rawpy/LibRaw written as little-endian
uint16. Without `-Pdarkhall.sample` the file-based tests are skipped.

What they cover:

* bit-exact decoding against LibRaw, and metadata parsing for both bodies;
* fuzzing: truncated files, random corruption, and every IFD entry rewritten with hostile
  counts, offsets and types — all must fail cleanly, never crash;
* every slider at both extremes; band seams in the full-size export;
* underwater correction on a simulated 8 m dive; highlight recovery on a simulated
  overexposure; zoomed regions matching the full export pixel for pixel;
* auto tone: exposure tracks the scene (−2 EV in → +2 EV out), results are balanced
  (mean, clipping, crushing), hard scenes (underexposed, clipped, hazy) are rescued; batch
  auto through the library keeps other edits; the editor's Auto/Undo round-trip works;
* noise profiling: recovers known synthetic noise within a few percent, measures noise added
  to a real file, demosaic noise factors; the denoiser halves the error of simulated
  ISO 1600 noise against the clean original while keeping strong-edge detail;
* GPU parity: the GLSL pipeline runs on two independent OpenGL ES implementations — Mesa
  (llvmpipe) and Google's ANGLE on SwiftShader, the strict translator Chrome ships — and must
  match the CPU renderer for every slider at both extremes (mean difference under 0.03 of an
  8-bit level); the 16-bit float fallback is measured as well;
* looks: all 370 tables decode and match the index, black & white looks never tint, an
  identity table changes nothing, a film look reproduces its RawTherapee source, and the GPU
  reproduces the CPU with looks applied;
* an end-to-end app run under Robolectric: open the file via a VIEW intent, move sliders,
  pinch to full-resolution zoom, pick a look in the browser, copy settings, export through
  WorkManager, and check the JPEG's size, rotation and EXIF and that the look was saved;
* a screenshot test that renders the editor and the look browser to PNGs for review
  (`-Pdarkhall.outDir=...`).

## Layout

* `core/` – pure Kotlin/JVM: ORF parser, Olympus decoder, demosaic (superpixel for
  previews, Malvar-He-Cutler for export and zoom), the slider pipeline and its shared
  `RenderPlan`, the GLSL ES port behind a small `Gl` interface, lens geometry, region
  rendering for zoom, banded 12 MP export.
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
* GPU rendering covers the live preview. The full-resolution zoom and exports run on the CPU:
  a 12 MP float pipeline would need several hundred MB of GPU memory, and the CPU path is the
  reference the GPU is checked against.
* GPU performance on real Adreno hardware has not been measured here; the shaders have been
  verified for correctness on two software implementations only.

## Credits

Film simulation looks: RawTherapee Film Simulation Collection (2015-09-20) by Pat David,
Pavlov Dmitry and Michael Ezra, CC BY-SA 4.0; resampled to 33³ tables, which are likewise
CC BY-SA 4.0. Film stock names are used only to describe what a look approximates; neither
the collection's authors nor Darkhall are affiliated with the trademark owners. Full notice
in `app/src/main/assets/looks/LICENSES.txt` and in the app (Looks → Credits).

Lens distortion and TCA coefficients for the TG-5/6/7 lens come from the
[lensfun](https://lensfun.github.io) database (CC BY-SA 3.0).

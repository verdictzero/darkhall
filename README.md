# Darkhall

A sliders-only raw developer for Android. Open a raw, drag sliders, export.

Reads ORF, DNG, ARW, NEF, CR2, CR3, RW2, PEF and RAF; see [Formats](#formats).

## Features

* **Raw decoding in Kotlin**, no native libraries. Output is bit-identical to LibRaw on every
  sample tested.
* **Starts from the camera's metadata**: as-shot white balance, black and white levels, crop,
  orientation and colour matrix.
* **Auto tone**: one tap sets Exposure, Contrast, Highlights, Shadows, Whites and Blacks. See
  [Auto tone](#auto-tone).
* **Measured noise**: each file's noise is measured from its raw data and sets the
  denoiser and sharpening. The editor shows it as an equivalent ISO.
* **Highlight recovery**: clipped channels are rebuilt from their surroundings; a soft
  shoulder keeps bright areas from clipping hard.
* **Underwater tools**: *Underwater* removes the blue-green cast and restores red; it starts
  on for files that record a water depth. *Backscatter removal* takes out lit particles.
* **Region sliders**: sky, water, people and foliage, found on the phone. See
  [Sky, water, subject and foliage](#sky-water-subject-and-foliage).
* **Lens correction**: DNG lens corrections, and a built-in profile for the Olympus Tough
  lens.
* **Field data**: depth, water temperature, pressure, altitude and GPS are shown and kept in
  exports.
* **370 looks** (3D LUTs). See [Looks](#looks).
* **GPU preview** (OpenGL ES 3.0). See [GPU rendering](#gpu-rendering).
* **Library**: thumbnails from embedded previews, ratings, filters, grouping by date.
* **Export**: JPEG, PNG, TIFF, WebP, TGA; 8 or 16-bit; resizing; batch; focus stacking.
* **Languages**: English, German, French, Spanish, Italian, Portuguese, Japanese.

## Sliders

| Look | Light | Color | Detail | Lens |
|---|---|---|---|---|
| Look (browser) | Exposure | Temperature | Clarity | Lens profile |
| Look strength | Contrast | Tint | Dehaze | Vignette |
| | Highlights | Underwater | Sharpening | Distortion |
| | Shadows | Vibrance | Luminance NR | Straighten |
| | Whites | Saturation | Color NR | Crop |
| | Blacks | Color mixer | Grain | |
| | | Color grading | Grain size | |

**Color mixer**: pick one of eight hue bands (red, orange, yellow, green, aqua, blue,
purple, magenta) and turn its hue (up to 30° towards a neighbour), saturation and luminance
(up to a stop). Bands blend smoothly into each other, and greys are never touched, so pulling
down aqua and blue or reviving a faded red changes only those colours. A dot under a band
marks it as changed; **Reset** clears all eight. It runs after saturation and before the
look, on the GPU as well as the CPU.

**Backscatter removal** (Color section): the specks of plankton and silt a flash lights up
between the camera and the subject. A morphological opening of the brightness (a minimum
then a maximum filter as wide as the largest speck) shows the picture without anything that
small and bright; what stands well above it, relative to the water around, is a speck, and is
filled from each colour channel's own opening, so the fill follows the water's gradient.
Anything bigger (a fish, a torch) is left alone, and so is texture: specks are scattered, so
where candidates crowd together (foliage, sand, polyps) they are kept. The slider sets both
the largest speck (up to about 1% of the picture's width) and how faint one may be. It runs
on the CPU.

**Color grading**: a tint for the shadows, the midtones and the highlights, each a hue
(with a swatch beside the value) and a strength, plus **Balance**, which moves the split
between shadows and highlights. The tints change colour, not brightness. **Grain** (with
**Grain size**, in the Detail section) is monochrome film grain on a grid of full-resolution
pixels, strongest in the midtones: the preview, a zoomed region and the export all show the
same grain on the same spot.

Double-tap a slider's label to reset it; the ⋮ menu resets everything. **Undo** and
**Redo** in the top bar step through every change made since the photo was opened (a whole
slider drag counts as one step).

To compare with the original (the camera's own rendering, without your edits): press and
hold the photo, or hold **Hold for original**. The three-way switch at the bottom right of the
preview puts the original and the edit **side by side** or **one above the other**; zoom and
pan move both together.
Pinch or double-tap to zoom: once the preview runs out of pixels, the visible area is
re-rendered from the raw at up to full resolution, so sharpening and noise reduction can be
judged properly. Edits are saved automatically per file and restored when you reopen it.

### Straightening

While you hold the **Straighten** slider (and for a moment after it changes) a grid lies
over the photo: a fine square grid from the centre with the thirds drawn brighter, light on
dark so it shows on skies and water alike, and the angle at the top. Two buttons on the Lens
section do the measuring for you:

* **Draw level** – drag along anything that should be level or upright (the horizon, a
  door frame, a pole). Straighten turns the photo so that line becomes exactly horizontal,
  or vertical if you drew it closer to vertical.
* **Auto straighten** – finds the photo's longest clean straight line within ±11° of level
  or upright and levels it, saying whether it went by the horizon or the upright lines.
  Edges are found with the local structure tensor, so foliage, sand and water texture don't
  count, and candidate lines are scored Hough-style by how long and continuous they are;
  uprights near the middle of the frame count most, since perspective tilts those towards
  the sides. Photos with no line that stands out (most underwater shots) are left alone with
  a message rather than a guess. Like any automatic level it can be fooled by a strong line
  that isn't meant to be level (a mat lying askew on a table), so both tools offer **Undo**.

### Crop

The **Crop** section picks a shape (Free, Original, 1:1, 5:4, 3:2, 16:9) and the button
beside them turns it between landscape and portrait (4:5, 2:3, 9:16). Tapping a shape or
**Crop** shows the whole picture with the crop drawn over it: drag a corner or an edge to
resize it, the middle to move it; a fixed shape keeps its proportions, and the label gives
the size the export will have. **Done** (or Back) shows the cropped photo; **Reset** removes
the crop.

The crop is taken from the straightened, lens-corrected picture, so it stays put when
Straighten changes. It is one undo step per drag, applies to every export format, and like
Straighten it belongs to its photo: pasting settings onto other photos leaves their crops
alone. Moving the crop doesn't re-render anything; the last render is simply cut again.

### Sky, water, subject and foliage

Four sections act on one part of the photo each, through a mask: **Sky**, **Water** (sea,
lakes, rivers, pools, waterfalls), **Subject** (people and animals) and **Foliage** (trees,
grass, plants, flowers). Each has exposure, saturation, clarity and warmth, and **Show mask**
paints what it covers over the preview. All four come from one run of the network, so
finding them costs no more than finding the sky alone did; a section whose part isn't in the
photo says so and hides its sliders.

How the regions are found, on the phone, with nothing sent anywhere (the sky as the example):

1. **A neural network marks the sky.** DeepLab v3+ with a MobileNetV2 backbone, trained on
   the ADE20K scene dataset (150 kinds of things, "sky" among them), runs on the photo turned
   upright and scaled to fit 513 px. It gives, for every 4 × 4 px cell, a probability for
   each of its 150 classes; a region's mask is the sum over its classes (sky; or water, sea,
   river, swimming pool, waterfall and lake; or person and animal; or tree, grass, plant,
   flower and palm).
   The network is implemented in plain Kotlin in `core/sky/SkyNet.kt` (no native libraries);
   its 2.3 million weights ship as a 4.6 MB file of 16-bit floats. It runs once per photo,
   the first time one of the four sections comes into view, in a second or two, and the
   result is kept on disk.
2. **The edge is snapped to the picture.** For whatever is being drawn (the preview, a zoomed
   area, a band of the full-size export) the coarse probabilities are scaled up and an
   edge-aware (guided) filter pulls the mask's edge onto the photo's own brightness edges, so
   roof lines and ridges stay crisp at full resolution.
3. **Gaps in trees are filled in.** Where the network is unsure, each pixel is compared with
   the sky colour expected at that spot (the average of nearby certain sky): blue between
   leaves joins the sky, branches stay out.

The sliders are then blended through the mask: exposure, warmth and saturation in linear
light before the tone curve, clarity as local contrast in the detail stage, so the change
fades out smoothly at the region's edge. The GPU preview does the same: the masks are
refined on the CPU once per view (so dragging a slider doesn't redo them) and the shaders
blend through them, matching the CPU render like every other slider.

Limits: a still lake mirroring the sky can be partly marked, dense canopies give a patchy
mask, a dim, plain room can pass for a night sky, and weathered wood can pass for water;
Show mask makes all of these plain. The network learned from photos taken above water, so
underwater it finds little: divers can come out as subjects, fish rarely do.

## Formats

Each decoder is a Kotlin port of the dcraw/LibRaw algorithm, checked **bit for bit against
LibRaw** on real files (see [Tests](#tests)):

| Format | Cameras | Variants decoded | Checked on |
|---|---|---|---|
| DNG | phones (Pixel, Galaxy Expert RAW, iPhone ProRAW), drones, Leica, Pentax, Ricoh, Adobe DNG Converter | uncompressed, packed, lossless JPEG (strips or tiles), linear (already demosaiced) DNGs, linearization tables, 16/24/32-bit floating-point (HDR merges) | Pixel 4a, Pixel 6 Pro, Galaxy S23 Ultra, iPhone 12 Pro, DJI Mini 2, Leica Q2, Pentax K-3 III, an HDRMerge file |
| ORF | Olympus and OM System bodies with Olympus compression, high-res shot mode included | | TG-5, TG-7, E-M10 IV, E-M1 III, E-M1X, E-PL7, OM-1 (normal and 50 MP high-res), OM-5, OM-5 II |
| ARW | Sony Alpha, RX, ZV | compressed (ARW 2), uncompressed, lossless compressed (L size), SR2-encrypted levels on older bodies | A7 III (both), A7 IV (lossless, APS-C), A6400, ZV-E10, RX100 VA, NEX-6 |
| NEF | Nikon D and Z | lossless and lossy compression (12/14-bit, with the split tables), uncompressed, Z-series packed 14-bit | D3500, D610, D750, D7000, D850, Z 6, Z 7, Z 50 |
| CR2 | Canon EOS 2004–2018 | lossless JPEG with Canon's slicing, 2- and 4-component streams, US/Japanese model names | 5D III, 6D II, 80D, 200D, 450D, Rebel T7i |
| CR3 | Canon EOS R and the later DSLRs/M bodies | CRX lossless (RAW) and wavelet (cRAW), both header versions, tiled streams, levels from the timed-metadata track | EOS R, RP, R5, R6, R10, 90D (both), M50 |
| RW2 | Panasonic, Leica rebrands | all five encodings (original, GH5S/G9 high-res, S1/S5 generation, S1R high-res, and the GH6/G9 II/S5 II generation) | G9, GH5, GH5S, GH6, G85, GX7, LX100, S5 |
| PEF | Pentax | Pentax Huffman, uncompressed | K-1, K-70 |
| RAF | Fujifilm X and GFX (X-Trans and Bayer) | uncompressed, packed 14-bit, Fujifilm's compressed format (lossless and lossy) | X-T2 (both), X-T3, X-T4, X-T5, X-S10, X-A5, GFX 50S |

X-Trans sensors (most Fujifilm X bodies) repeat their colours every six pixels instead of two,
so they get their own demosaic: the half-size preview weighs each 2×2 cell's colours from the
4×4 window around it, and full resolution interpolates green along the flatter direction, then
red and blue as differences from that green. Noise is measured on the 36 same-colour sites of
the 6×6 pattern.

From the file: as-shot white balance, per-channel black
level, the camera's crop, orientation and EXIF. Colour comes from the DNG's own matrix or,
for the other formats, Adobe's matrix for that body (the table LibRaw ships, 811 camera entries).
Highlights clip at the level where the camera says its sensor stops being linear (Sony, Canon
and Panasonic record it), not at the converter's full scale, which keeps bright overcast skies
from turning magenta. DNG lens corrections are applied: phones' lens-shading gain maps, radial
vignetting, and the distortion/colour-fringe warp, which drives the *Lens profile* slider;
DNG BaselineExposure is honoured.

Not supported, with a clear message when opened: Fujifilm's older SuperCCD/EXR bodies (S, F
and HS series), Canon sRAW/mRAW, Sony
medium/small lossless ARWs, Nikon High Efficiency RAW and small/medium NEFs, lossy and JPEG XL
DNGs, X-Trans DNGs. Very large sensors (45–60 MP) open and export, but need a phone with a
generous memory allowance.

## Using it

1. Copy raws to the phone, or connect the card with an OTG reader.
2. **Folder** opens a directory; **Files** opens individual raws. Opening a raw from a file
   manager also works.
3. Tap a thumbnail, adjust, **Export**. Files go to `Pictures/Darkhall`.
4. Library chips: **By date**, rating (1+, 3+, 5), **Edited**, **Underwater**, camera.
5. Rate: editor ⋮ → stars, or select photos → ⋮ → **Rate…**.
6. Same settings on several photos: ⋮ → **Copy settings**, then select photos → **Paste**.
   Straighten and crop stay per photo.
7. Presets: ⋮ → **Presets…** saves or applies; on a selection, ⋮ → **Apply preset…**.
8. Batch export: select photos → **Export**.

Landscape: sliders beside the photo, wider on tablets. TalkBack: sliders read their name and
value and have a *Reset* action; thumbnails read as one item; every control has a label and a
48 dp touch area (checked by `AccessibilityTest`).

Language follows the phone, or the app's own setting on Android 13 and later. Look names,
camera data and licence texts are not translated.

### Export formats

| Format | Depth | Notes |
|---|---|---|
| JPEG | 8-bit | quality 60–100 (95 by default); EXIF and GPS |
| PNG | 8 or 16-bit | lossless; EXIF and GPS |
| TIFF | 8 or 16-bit | uncompressed or ZIP (Deflate with the horizontal predictor); EXIF and GPS in their own TIFF directories, so Photoshop, Lightroom, GIMP and darktable read them |
| WebP | 8-bit | lossless; EXIF and GPS |
| TGA | 8-bit | run-length encoded, top-left origin; the format has no room for camera data |

The 16-bit exports come from the same pipeline as the 8-bit ones, packed to 16 bits at the
very end instead of 8 (they match to within rounding), so smooth skies and shadows survive
heavy editing in other software. PNG, TIFF and TGA are written by Darkhall's own encoders,
row by row, so a 16-bit export never holds more than one full-size copy of the picture; JPEG
and WebP use Android's. The dialog remembers the last choice, and a library selection
exports with the same dialog. Formats the gallery doesn't take as pictures on a given device
go to `Download/Darkhall` instead.

**Focus stacking.** For a focus-bracketed series (shot at stepped focus distances): select
it in the library, then ⋮ → **Focus stack…** and
pick a format: the frames are merged into one picture, sharp from front to back, named after
the first (`P0001_stack.tif`) and developed with the first photo's edits. Each frame is
lined up with the one before on its preview, by a Lucas-Kanade fit of scale, rotation and
shift (which covers focus breathing and a hand-held drift), then developed at full size and
folded into the result: every pixel leans towards the frame where it is sharpest (the
Laplacian's strength, pooled locally, to the fourth power), so edges come from the frame
that has them and flat areas average out. Only one raw is in memory at a time.

**Size**: every format can be exported at full size or with its long edge brought down to
4096, 3000, 2048, 1600 or 1080 px (area averaging, so fine detail doesn't alias; never
enlarged). When an export finishes, the banner's **Share** button hands the files to any
app.

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

Tap the heart on any thumbnail to make it a favorite. Favorites get their own shelf at the
top of the browser and a **♥ Favorites** filter, and appear as a strip of small thumbnails
under the Look row in the editor: one tap applies a favorite, a second tap takes it off.

| Shelf | Looks | Source | License |
|---|---|---|---|
| Film color | 194 | RawTherapee Film Simulation Collection | CC BY-SA 4.0 |
| Film B&W | 66 | RawTherapee Film Simulation Collection | CC BY-SA 4.0 |
| Creative | 33 | RawTherapee Film Simulation Collection (CreativePack-1) | CC BY-SA 4.0 |
| Darkhall | 77 | generated by `tools/looks/build_looks.py` | CC0 |

The Darkhall shelf covers underwater (Reef, Blue Water, Kelp Forest, …), cinematic grades,
split-tones, faded/matte, cross-processing, black & white with colour filters, and duotones.

The film looks go by what they look like ("Vivid Slide 50", "Portrait 400 NC 2", "Gritty Pan
400", "Instant Pastel Warm 3"), sorted by kind of film (slide, colour negative, cross process,
instant; classic, modern and special black & white) rather than by the film stocks the
collection imitates, whose names are their makers' trademarks. `tools/looks/build_looks.py`
holds the renaming rules; each look keeps its internal id, so older edits still find it.

A look is applied to display-encoded colour after the tone curve and saturation, before
clarity and sharpening, with trilinear interpolation on a 33³ table. The film tables are
resampled from the 144³ Hald CLUTs; on exact grid points Vivid Slide 50 matches its source within
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

GitHub Actions runs all of the tests below and Android lint on every push
(`.github/workflows/ci.yml`), against ten CC0 raws from raw.pixls.us (TG-6, TG-5, Pixel 6
Pro, EOS 450D, NEX-6, Z 6, LX100, K-70, X-S10, EOS R6) with LibRaw references written by rawpy. To get the
same set locally: `tools/ci/fetch_samples.sh samples` (needs `pip install rawpy numpy`).

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

The other formats are tested on a directory of sample raws (raw.pixls.us has files for most
bodies) plus a LibRaw reference written by `tools/raws/reference.py` (needs `pip install rawpy`):

```
python3 tools/raws/reference.py ~/raws            # writes ~/raws/reference.tsv
./gradlew :core:test --tests '*RawFormatsTest*' -Pdarkhall.rawsDir=$HOME/raws \
    -Pdarkhall.outDir=/tmp/darkhall-out           # optional: default renders of each file
```

Every file must decode to exactly LibRaw's sensor data with matching size, CFA pattern and
black levels; white balance, white level, colour matrix and the embedded preview are checked
too, and truncated or corrupted copies of one file per format must fail cleanly.

The app itself can be driven end to end on any of those files (open from a file manager, wait
for the preview, export, check the JPEG's size and EXIF), one file per run:

```
./gradlew :app:testDebugUnitTest --tests '*OtherFormatsSmokeTest*' \
    -Pdarkhall.otherSample=$HOME/raws/some_camera.nef
```

What they cover:

* bit-exact decoding against LibRaw, and metadata parsing for both bodies; the same for
  52 files from 46 cameras and phones across seven formats;
* fuzzing: truncated files, random corruption, and every IFD entry rewritten with hostile
  counts, offsets and types — all must fail cleanly, never crash;
* every slider at both extremes; band seams in the full-size export;
* underwater correction on a simulated 8 m dive; highlight recovery on a simulated
  overexposure; zoomed regions matching the full export pixel for pixel;
* auto tone: exposure tracks the scene (−2 EV in → +2 EV out), results are balanced
  (mean, clipping, crushing), hard scenes (underexposed, clipped, hazy) are rescued; batch
  auto through the library keeps other edits; the editor's Auto/Undo round-trip works;
* auto straighten: tilted horizons and leaning poles (with ripples and grain) measured to
  within 0.2° and levelled by the measured amount; texture without lines gets no answer;
  on real photos, turning the picture by 3° moves the measurement by 3°; the editor's Draw
  level, Undo and Auto straighten work end to end;
* masks: the Kotlin network matches a NumPy reference (itself matching TensorFlow to 0.0005)
  to 0.00002 in sky probability; sky, water, subjects and foliage found in the samples that
  show them and not in those that don't; each section's sliders change its region and leave
  the rest alone, in the preview and in the full-size export; the editor finds, shows and
  edits them end to end;
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

* `core/` – pure Kotlin/JVM: raw format readers and decoders (`core/raw`: a TIFF reader,
  lossless JPEG, DNG, ORF, ARW, NEF, CR2, CR3, RW2, PEF, RAF), the camera colour table, demosaic
  (superpixel for previews, Malvar-He-Cutler for export and zoom, and X-Trans variants of both), the slider pipeline and its shared
  `RenderPlan`, the GLSL ES port behind a small `Gl` interface, lens geometry, region
  rendering for zoom, banded 12 MP export, and the PNG/TIFF/TGA encoders (`core/export`).
* `app/` – Jetpack Compose UI, Storage Access Framework browsing, WorkManager export to
  MediaStore.

## Notes and limits

* Preview edits run on a ~1280 px proxy; export re-renders at full resolution in bands
  so a 12 MP float pipeline fits in a phone's heap.
* The built-in lens profile is for the TG-5/6/7 lens; other cameras get lens corrections only
  when their DNGs carry them. The noise-reduction and tone defaults were tuned on TG files.
* The underwater correction is a global one (veil removal plus grey-world balance). It
  cannot bring back red that is completely gone; very deep natural-light shots stay blue-ish.
* GPU rendering covers the live preview. The full-resolution zoom and exports run on the CPU:
  a 12 MP float pipeline would need several hundred MB of GPU memory, and the CPU path is the
  reference the GPU is checked against.
* GPU performance on real Adreno hardware has not been measured here; the shaders have been
  verified for correctness on two software implementations only.

## Licence

Darkhall is Copyright (c) 2026 verdictzero, all rights reserved (`LICENSE`). The source is
public so it can be read and audited; that is not a licence to redistribute it. Third-party
parts keep their own licences, listed in [`NOTICE.md`](NOTICE.md) with the texts in
`LICENSES/`, and in the app under ⋮ → Open-source licences (library or editor):

- The raw decoders derived from LibRaw and dcraw (each file carries a CDDL header) and the
  tables extracted from LibRaw: CDDL 1.0. Those files' source must stay available, which
  this public repository does.
- Film looks: CC BY-SA 4.0. Darkhall's own looks: CC0 1.0.
- Lens coefficients from lensfun: CC BY-SA 3.0.
- Sky model: Apache 2.0. Libraries (AndroidX, Compose, Kotlin, coroutines): Apache 2.0.

## Credits

Film simulation looks: RawTherapee Film Simulation Collection (2015-09-20) by Pat David,
Pavlov Dmitry and Michael Ezra, CC BY-SA 4.0; resampled to 33³ tables, which are likewise
CC BY-SA 4.0. Darkhall shows them under its own descriptive names (the internal look ids
keep the collection's file names) and is not affiliated with or endorsed by any film maker.
Full notice in `app/src/main/assets/looks/LICENSES.txt` and in the app (Looks → Credits).

Lens distortion and TCA coefficients for the TG-5/6/7 lens come from the
[lensfun](https://lensfun.github.io) database (CC BY-SA 3.0).

The raw decoders follow the algorithms and code of [dcraw](https://www.dechifro.org/dcraw/)
by Dave Coffin and [LibRaw](https://www.libraw.org) (LibRaw LLC), used under the CDDL 1.0
(LibRaw is offered under LGPL 2.1 or CDDL 1.0); no restricted dcraw code is used, and parts
of the floating-point DNG path come from the Adobe DNG SDK by way of LibRaw (MIT). The
camera colour table (`core/src/main/resources/darkhall/camera_colors.tsv`, Adobe DNG
Converter matrices) and the Canon model table are extracted from LibRaw 0.22.1 by
`tools/colors/`.

Sky detection: DeepLab v3+ with a MobileNetV2 backbone trained on ADE20K, from the TensorFlow
Models repository (Apache 2.0), converted by `tools/sky/export_deeplab.py`
(`app/src/main/assets/sky/LICENSE.txt`). The ADE20K images it was trained on (MIT CSAIL) are
released for research and education.

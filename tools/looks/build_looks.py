#!/usr/bin/env python3
"""
Builds Darkhall's look (LUT) library into app/src/main/assets/looks/.

Sources
  * RawTherapee Film Simulation Collection 2015-09-20 (CC BY-SA 4.0; Pat David,
    Pavlov Dmitry, Michael Ezra): https://rawtherapee.com/shared/HaldCLUT.zip
    Pass the unzipped "HaldCLUT" directory with --hald.
  * Darkhall originals, generated below from colour formulas (CC0 1.0).

Output format (.dlut): "DLUT", version u8 = 1, size u8 = N, 2 reserved bytes, then a zlib
stream of the N^3 RGB table as 8-bit values, channel-planar, each channel stored as residuals
of the 3D Lorenzo predictor (x[r-1] + x[g-1] + x[b-1] - x[r-1,g-1] - x[r-1,b-1] - x[g-1,b-1]
+ x[r-1,g-1,b-1], mod 256), loop order b, g, r. Inputs and outputs are sRGB-encoded.

Usage: python3 build_looks.py --hald /path/to/HaldCLUT --out app/src/main/assets/looks
"""
import argparse
import glob
import json
import os
import re
import zlib

import numpy as np
from PIL import Image

N = 33


# ---------------------------------------------------------------------------------------------
# encoding

def encode(table):
    """table: float array [b][g][r][3] in 0..1 -> .dlut bytes."""
    q = np.clip(np.round(table * 255.0), 0, 255).astype(np.int16)
    p = np.zeros_like(q)
    p[:, :, 1:] += q[:, :, :-1]
    p[:, 1:, :] += q[:, :-1, :]
    p[1:, :, :] += q[:-1, :, :]
    p[:, 1:, 1:] -= q[:, :-1, :-1]
    p[1:, :, 1:] -= q[:-1, :, :-1]
    p[1:, 1:, :] -= q[:-1, :-1, :]
    p[1:, 1:, 1:] += q[:-1, :-1, :-1]
    res = ((q - p) & 0xFF).astype(np.uint8)
    planar = np.ascontiguousarray(res.transpose(3, 0, 1, 2)).tobytes()
    return b"DLUT" + bytes([1, N, 0, 0]) + zlib.compress(planar, 9)


def identity_grid():
    g = np.linspace(0.0, 1.0, N)
    b, gg, r = np.meshgrid(g, g, g, indexing="ij")
    return np.stack([r, gg, b], axis=-1)  # [b][g][r][rgb]


# ---------------------------------------------------------------------------------------------
# RawTherapee Hald CLUTs

def load_hald(path):
    a = np.asarray(Image.open(path).convert("RGB")).reshape(-1, 3).astype(np.float64) / 255.0
    n = round(len(a) ** (1.0 / 3.0))
    assert n ** 3 == len(a), path
    return a.reshape(n, n, n, 3)  # Hald order: r fastest, then g, then b -> [b][g][r]


def resample(lut, size):
    """Trilinear resample of an n^3 table onto a size^3 grid (separable per axis)."""
    n = lut.shape[0]
    x = np.linspace(0, 1, size) * (n - 1)
    i0 = np.clip(np.floor(x).astype(int), 0, n - 2)
    f = x - i0
    out = lut[i0] * (1 - f)[:, None, None, None] + lut[i0 + 1] * f[:, None, None, None]
    out = out[:, i0] * (1 - f)[None, :, None, None] + out[:, i0 + 1] * f[None, :, None, None]
    out = out[:, :, i0] * (1 - f)[None, None, :, None] + out[:, :, i0 + 1] * f[None, None, :, None]
    return out


def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


def pretty(name):
    # "Kodak Portra 160 NC 3 +" -> keep; "BleachBypass1" -> "Bleach Bypass 1"
    name = re.sub(r"(?<=[a-z])(?=[A-Z])", " ", name)
    name = re.sub(r"(?<=[A-Za-z])(?=\d)", " ", name) if " " not in name else name
    return name.strip()


# Film looks get descriptive names instead of the film stocks' trademarks: first match wins,
# the variant suffixes ("3 +", "Cold 2 -") carry over. Group, rule, new name, category.
FILM_NAMES = [
    ("Film color", r"^Agfa Precisa 100$", "Crisp Slide 100", "Slide"),
    ("Film color", r"^Agfa Ultra Color 100$", "Punchy Color 100", "Color negative"),
    ("Film color", r"^Agfa Vista 200$", "Street Color 200", "Color negative"),
    ("Film color", r"^Fuji 160C(.*)$", r"Pro Contrast 160\1", "Color negative"),
    ("Film color", r"^Fuji 400H(.*)$", r"Pro Soft 400\1", "Color negative"),
    ("Film color", r"^Fuji 800Z(.*)$", r"Pro Night 800\1", "Color negative"),
    ("Film color", r"^Fuji Astia 100 Generic$", "Soft Slide 100", "Slide"),
    ("Film color", r"^Fuji Astia 100F$", "Soft Slide 100 F", "Slide"),
    ("Film color", r"^Fuji FP-100c(.*)$", r"Peel-Apart Color\1", "Instant"),
    ("Film color", r"^Fuji Provia (\d+) Generic$", r"Natural Slide \1", "Slide"),
    ("Film color", r"^Fuji Provia (\d+)([FX])$", r"Natural Slide \1 \2", "Slide"),
    ("Film color", r"^Fuji Sensia 100$", "Consumer Slide 100", "Slide"),
    ("Film color", r"^Fuji Superia 200 XPRO$", "Everyday Color 200 Cross", "Cross process"),
    ("Film color", r"^Fuji Superia HG 1600$", "Everyday Color 1600 HG", "Color negative"),
    ("Film color", r"^Fuji Superia Reala 100$", "True Color 100", "Color negative"),
    ("Film color", r"^Fuji Superia X-Tra 800$", "Everyday Color 800 Extra", "Color negative"),
    ("Film color", r"^Fuji Superia (.*)$", r"Everyday Color \1", "Color negative"),
    ("Film color", r"^Fuji Velvia 100 Generic$", "Vivid Slide 100", "Slide"),
    ("Film color", r"^Fuji Velvia 50$", "Vivid Slide 50", "Slide"),
    ("Film color", r"^Kodak E-100 GX Ektachrome 100$", "Cool Slide 100 GX", "Slide"),
    ("Film color", r"^Kodak Ektachrome 100 VS Generic$", "Saturated Slide 100", "Slide"),
    ("Film color", r"^Kodak Ektachrome 100 VS$", "Saturated Slide 100 VS", "Slide"),
    ("Film color", r"^Kodak Ektar 100$", "Fine Grain Color 100", "Color negative"),
    ("Film color", r"^Kodak Elite 100 XPRO$", "Bright Slide 100 Cross", "Cross process"),
    ("Film color", r"^Kodak Elite Chrome (\d+)$", r"Consumer Slide \1", "Slide"),
    ("Film color", r"^Kodak Elite Color (\d+)$", r"Bright Slide \1", "Slide"),
    ("Film color", r"^Kodak Elite Extra Color 100$", "Extra Color Slide 100", "Slide"),
    ("Film color", r"^Kodak Kodachrome (\d+)( Generic)?$", r"Classic Slide \1\2", "Slide"),
    ("Film color", r"^Kodak Portra (.*)$", r"Portrait \1", "Color negative"),
    ("Film color", r"^Lomography Redscale 100$", "Redscale 100", "Cross process"),
    ("Film color", r"^Lomography X-Pro Slide 200$", "Cross Slide 200", "Cross process"),
    ("Film color", r"^Polaroid 669(.*)$", r"Instant Classic\1", "Instant"),
    ("Film color", r"^Polaroid 690(.*)$", r"Instant Bright\1", "Instant"),
    ("Film color", r"^Polaroid PX-100UV\+(.*)$", r"Instant Pale\1", "Instant"),
    ("Film color", r"^Polaroid PX-680(.*)$", r"Instant Square\1", "Instant"),
    ("Film color", r"^Polaroid PX-70(.*)$", r"Instant Pastel\1", "Instant"),
    ("Film color", r"^Polaroid Polachrome$", "Instant Slide", "Instant"),
    ("Film color", r"^Polaroid Time Zero \(Expired\)(.*)$", r"Instant Expired\1", "Instant"),
    ("Film B&W", r"^Agfa APX (\d+)$", r"Classic Pan \1", "Classic"),
    ("Film B&W", r"^Fuji FP-3000b(.*)$", r"Peel-Apart Mono\1", "Instant"),
    ("Film B&W", r"^Fuji Neopan 1600(.*)$", r"Fast Pan 1600\1", "Modern"),
    ("Film B&W", r"^Fuji Neopan Acros 100$", "Fine Pan 100", "Modern"),
    ("Film B&W", r"^Ilford Delta (\d+)(.*)$", r"Modern Pan \1\2", "Modern"),
    ("Film B&W", r"^Ilford FP4 Plus 125$", "Classic Pan 125", "Classic"),
    ("Film B&W", r"^Ilford HP5 Plus 400$", "Street Pan 400", "Classic"),
    ("Film B&W", r"^Ilford HP5 (.*)$", r"Street Pan 400 \1", "Classic"),
    ("Film B&W", r"^Ilford HPS 800$", "Street Pan 800", "Classic"),
    ("Film B&W", r"^Ilford Pan F Plus 50$", "Slow Pan 50", "Classic"),
    ("Film B&W", r"^Ilford XP2$", "Chromogenic Mono 400", "Special"),
    ("Film B&W", r"^Kodak BW 400 CN$", "Chromogenic Mono 400 CN", "Special"),
    ("Film B&W", r"^Kodak HIE \(HS Infra\)$", "Infrared Mono", "Special"),
    ("Film B&W", r"^Kodak T-Max (\d+)$", r"Sharp Pan \1", "Modern"),
    ("Film B&W", r"^Kodak TMAX 3200(.*)$", r"Sharp Pan 3200\1", "Modern"),
    ("Film B&W", r"^Kodak TRI-X 400(.*)$", r"Gritty Pan 400\1", "Classic"),
    ("Film B&W", r"^Polaroid 664$", "Instant Mono 100", "Instant"),
    ("Film B&W", r"^Polaroid 665 Negative(.*)$", r"Instant Mono Fine Negative\1", "Instant"),
    ("Film B&W", r"^Polaroid 665(.*)$", r"Instant Mono Fine\1", "Instant"),
    ("Film B&W", r"^Polaroid 667$", "Instant Mono Fast", "Instant"),
    ("Film B&W", r"^Polaroid 672$", "Instant Mono Fast II", "Instant"),
    ("Film B&W", r"^Rollei IR 400$", "Infrared Pan 400", "Special"),
    ("Film B&W", r"^Rollei Ortho 25$", "Ortho 25", "Special"),
    ("Film B&W", r"^Rollei Retro 100 Tonal$", "Tonal Pan 100", "Special"),
    ("Film B&W", r"^Rollei Retro 80s$", "Vintage Pan 80", "Special"),
]
CATEGORY_ORDER = {
    "Film color": ["Slide", "Color negative", "Cross process", "Instant"],
    "Film B&W": ["Classic", "Modern", "Special", "Instant"],
}


SHELVES = ["Darkhall", "Film color", "Film B&W", "Creative"]  # as LookLibrary.GROUPS


def shelf_order(lk):
    """Sort key: shelves in the browser's order, then categories in their shelf's order."""
    order = CATEGORY_ORDER.get(lk["group"], [])
    return SHELVES.index(lk["group"]), order.index(lk["category"]) if lk["category"] in order else -1


BRANDS = re.compile(r"\b(Agfa|Fuji|Kodak|Ilford|Polaroid|Rollei|Lomography|Velvia|Provia|Astia|Portra|Ektar|Kodachrome|Ektachrome|Superia|Neopan|Acros|Delta|Tri-?X|T-?Max)\b", re.I)


def neutral_name(group, name):
    """Descriptive name and category for a film look's original (trademarked) name."""
    for g, rule, new, category in FILM_NAMES:
        if g == group and re.match(rule, name):
            out = re.sub(r"\s+", " ", re.sub(rule, new, name)).strip()
            assert not BRANDS.search(out), (name, out)
            return out, category
    raise ValueError(f"no descriptive name for {group}: {name}")


def film_looks(hald_dir):
    looks = []
    for path in sorted(glob.glob(os.path.join(hald_dir, "**", "*.png"), recursive=True)):
        rel = os.path.relpath(path, hald_dir)
        parts = rel.split(os.sep)
        if len(parts) != 3:
            continue
        kind, brand, file = parts
        name = os.path.splitext(file)[0]
        if brand == "CreativePack-1":
            group, category = "Creative", "Creative pack"
        elif kind == "Black-and-White":
            group, category = "Film B&W", brand
        else:
            group, category = "Film color", brand
        shown = pretty(name)
        if group != "Creative":
            shown, category = neutral_name(group, shown)
        looks.append({
            # The id keeps the original name, so edits saved before the renaming still find it.
            "id": "rt-" + slug(kind + "-" + brand + "-" + name),
            "name": shown,
            "group": group,
            "category": category,
            "source": "rawtherapee",
            "table": lambda p=path: resample(load_hald(p), N),
        })
    return looks


# ---------------------------------------------------------------------------------------------
# Darkhall originals: small colour operations on the sRGB grid

W = np.array([0.2126, 0.7152, 0.0722])


def lum(c):
    return c @ W


def clamp(c):
    return np.clip(c, 0.0, 1.0)


def s_curve(x, amount):
    """Contrast around mid-grey; amount 0 = identity, 1 = full sine S-curve."""
    s = 0.5 - 0.5 * np.cos(np.pi * np.clip(x, 0, 1))
    return x + amount * (s - x)


def tone(x, black=0.0, white=1.0, gamma=1.0):
    """Lift blacks / lower whites (fade), then gamma on the result."""
    return black + (white - black) * np.power(np.clip(x, 0, 1), gamma)


def saturate(c, k):
    y = lum(c)[..., None]
    return y + (c - y) * k


def hex_rgb(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4)])


def split_tone(c, shadow, highlight, strength=0.18, balance=0.5):
    """Tint shadows towards one hue and highlights towards another, keeping luminance."""
    y = lum(c)[..., None]
    ws = np.clip(1.0 - y / max(balance, 1e-3), 0, 1) ** 1.5
    wh = np.clip((y - balance) / max(1 - balance, 1e-3), 0, 1) ** 1.5
    tint = ws * (hex_rgb(shadow) - lum(hex_rgb(shadow))) + wh * (hex_rgb(highlight) - lum(hex_rgb(highlight)))
    out = c + strength * 2.0 * tint
    return out - (lum(out) - lum(c))[..., None]


def opponent_push(c, axis_rgb, gain, orth_gain=1.0):
    """Scale chroma along the hue axis of axis_rgb by gain, and across it by orth_gain."""
    y = lum(c)[..., None]
    ch = c - y
    a = hex_rgb(axis_rgb) - lum(hex_rgb(axis_rgb))
    a = a / np.linalg.norm(a)
    along = (ch @ a)[..., None] * a
    return y + along * gain + (ch - along) * orth_gain


def mono(c, weights, contrast=0.3, black=0.0, white=1.0, gamma=1.0):
    w = np.array(weights, dtype=float)
    y = np.clip(c @ (w / w.sum()), 0, 1)
    y = tone(s_curve(y, contrast), black, white, gamma)
    return np.repeat(y[..., None], 3, axis=-1)


def duotone(c, dark, light, contrast=0.2, weights=(0.2126, 0.7152, 0.0722)):
    y = s_curve(np.clip(c @ np.array(weights), 0, 1), contrast)[..., None]
    return hex_rgb(dark) * (1 - y) + hex_rgb(light) * y


def channel_curves(c, r=1.0, g=1.0, b=1.0):
    """Per-channel gamma (cross-processing style)."""
    return np.stack([c[..., 0] ** r, c[..., 1] ** g, c[..., 2] ** b], axis=-1)


def original_looks():
    L = []

    def add(group, category, name, fn, id_name=None):
        L.append({
            # id_name: the look's earlier name, so saved edits still find it after a rename.
            "id": "dh-" + slug(category + "-" + (id_name or name)),
            "name": name,
            "group": group,
            "category": category,
            "source": "darkhall",
            "table": lambda f=fn: clamp(f(identity_grid())),
        })

    # Underwater: for TG-7 dive shots, after the Underwater slider has done the heavy lifting.
    U = "Underwater"
    add("Darkhall", U, "Reef", lambda c: saturate(split_tone(s_curve(c, 0.25), "#1E6F7A", "#FFB070", 0.16), 1.18))
    add("Darkhall", U, "Blue Water", lambda c: saturate(opponent_push(s_curve(c, 0.2), "#1F5BFF", 1.15, 0.85), 1.05))
    add("Darkhall", U, "Clear Lagoon", lambda c: saturate(tone(split_tone(c, "#0FA3B1", "#FFF1D6", 0.12), 0.0, 1.0, 0.9), 1.1))
    add("Darkhall", U, "Kelp Forest", lambda c: split_tone(s_curve(c, 0.3), "#0E3B2E", "#E6C36A", 0.2))
    add("Darkhall", U, "Deep", lambda c: saturate(split_tone(s_curve(c, 0.45), "#062A4A", "#9FD3E6", 0.18), 0.95))
    add("Darkhall", U, "Wreck", lambda c: saturate(split_tone(s_curve(c, 0.5), "#123A40", "#D9CBB0", 0.18), 0.7))
    add("Darkhall", U, "Coral Glow", lambda c: saturate(opponent_push(s_curve(c, 0.2), "#FF5A4E", 1.25, 0.95), 1.12))
    add("Darkhall", U, "Night Dive", lambda c: saturate(s_curve(tone(c, 0.0, 1.0, 1.1), 0.55), 1.2))
    add("Darkhall", U, "Snorkel", lambda c: saturate(tone(split_tone(c, "#1BA7C9", "#FFE2A8", 0.14), 0.02, 1.0, 0.92), 1.15))
    add("Darkhall", U, "Freediver", lambda c: mono(c, (0.1, 0.5, 0.4), 0.45))

    # Cinematic grades
    C = "Cinematic"
    for name, k in (("Teal & Orange Soft", 0.5), ("Teal & Orange", 1.0), ("Teal & Orange Bold", 1.6)):
        add("Darkhall", C, name, lambda c, k=k: split_tone(opponent_push(s_curve(c, 0.25 * k), "#FF8A3D", 1 + 0.35 * k, 1 - 0.25 * k), "#0B6E78", "#FFB36B", 0.12 * k))
    add("Darkhall", C, "Bleach Bypass", lambda c: (lambda y: clamp(0.55 * saturate(c, 0.45) + 0.45 * s_curve(np.repeat(y[..., None], 3, -1), 0.8)))(lum(c)))
    add("Darkhall", C, "Silver Retention", lambda c: saturate(s_curve(c, 0.6), 0.55))
    add("Darkhall", C, "Day for Night", lambda c: saturate(split_tone(tone(c, 0.0, 0.62, 1.35), "#06142E", "#7FA6D9", 0.35), 0.45))
    add("Darkhall", C, "Golden Hour", lambda c: saturate(split_tone(s_curve(c, 0.15), "#5A3A1A", "#FFC46B", 0.22), 1.08))
    add("Darkhall", C, "Desert", lambda c: saturate(split_tone(tone(c, 0.03, 0.98, 0.95), "#6B4A2B", "#FFD9A0", 0.2), 0.85))
    add("Darkhall", C, "Arctic", lambda c: saturate(split_tone(tone(c, 0.02, 1.0, 0.9), "#2B4C6F", "#E8F4FF", 0.2), 0.75))
    add("Darkhall", C, "Neo Noir", lambda c: saturate(split_tone(s_curve(c, 0.6), "#1B1035", "#FF4FA3", 0.16), 0.8))
    add("Darkhall", C, "Moonlit", lambda c: saturate(split_tone(tone(c, 0.0, 0.85, 1.15), "#0B1A3A", "#B9C9FF", 0.25), 0.55))
    add("Darkhall", C, "Summer Blockbuster", lambda c: saturate(opponent_push(s_curve(c, 0.4), "#FF9F45", 1.3, 0.8), 1.15))
    add("Darkhall", C, "Code Green", id_name="Matrix", fn=lambda c: saturate(split_tone(s_curve(c, 0.35), "#0A2A12", "#B8FFB0", 0.22), 0.7))
    add("Darkhall", C, "Vaulted Violet", lambda c: saturate(split_tone(s_curve(c, 0.3), "#1A0F33", "#C9B2FF", 0.2), 0.9))

    # Split toning
    S = "Split tone"
    for shadow, high, name in (
        ("#0B6E78", "#FFB36B", "Teal / Amber"), ("#1E3A8A", "#F5D06F", "Navy / Gold"),
        ("#3B1F6B", "#F2C58C", "Violet / Peach"), ("#0F5132", "#F4A7B9", "Forest / Rose"),
        ("#1A4E7A", "#FFE9C7", "Steel / Cream"), ("#5B2A86", "#9EE6D0", "Plum / Mint"),
        ("#7A2E2E", "#CFE8FF", "Oxblood / Ice"), ("#24333D", "#E9C9A1", "Slate / Sand"),
        ("#004E64", "#FF7F50", "Petrol / Coral"), ("#2D2A6E", "#FFD166", "Indigo / Sun"),
        ("#3E5641", "#F2E8CF", "Olive / Linen"), ("#4A1942", "#FFC4A3", "Wine / Apricot"),
    ):
        add("Darkhall", S, name, lambda c, s=shadow, h=high: split_tone(c, s, h, 0.22))

    # Faded & matte
    F = "Faded"
    add("Darkhall", F, "Matte", lambda c: tone(s_curve(c, 0.15), 0.08, 0.97))
    add("Darkhall", F, "Matte Warm", lambda c: split_tone(tone(c, 0.08, 0.96), "#4A3426", "#FFE4C4", 0.12))
    add("Darkhall", F, "Matte Cool", lambda c: split_tone(tone(c, 0.08, 0.96), "#24384D", "#E4F1FF", 0.12))
    add("Darkhall", F, "Pastel", lambda c: saturate(tone(c, 0.1, 0.98, 0.85), 0.65))
    add("Darkhall", F, "Sun Bleached", lambda c: saturate(split_tone(tone(c, 0.06, 1.0, 0.8), "#6A7F8C", "#FFF0D0", 0.14), 0.6))
    add("Darkhall", F, "Washed Out", lambda c: saturate(tone(c, 0.12, 0.92), 0.75))
    add("Darkhall", F, "Vintage Print", lambda c: saturate(split_tone(tone(s_curve(c, 0.2), 0.07, 0.94), "#3A2E4F", "#F3D9A4", 0.2), 0.8))
    add("Darkhall", F, "Polar Fade", lambda c: saturate(split_tone(tone(c, 0.1, 0.97, 0.9), "#2E5C7A", "#F0F8FF", 0.16), 0.7))

    # Cross processing
    X = "Cross process"
    add("Darkhall", X, "Slide as Negative", id_name="E-6 in C-41", fn=lambda c: saturate(channel_curves(s_curve(c, 0.3), 0.92, 0.98, 1.16), 1.1))
    add("Darkhall", X, "Negative as Slide", id_name="C-41 in E-6", fn=lambda c: saturate(channel_curves(tone(c, 0.04, 1.0), 1.08, 0.97, 0.9), 0.92))
    add("Darkhall", X, "Acid Green", lambda c: saturate(channel_curves(s_curve(c, 0.3), 1.1, 0.93, 1.1), 1.06))
    add("Darkhall", X, "Magenta Shift", lambda c: saturate(channel_curves(s_curve(c, 0.25), 0.95, 1.1, 0.95), 1.03))

    # Black & white: filters x contrast
    B = "Black & white"
    filters = (("Neutral", (0.2126, 0.7152, 0.0722)), ("Red Filter", (0.85, 0.15, 0.0)),
               ("Orange Filter", (0.6, 0.35, 0.05)), ("Yellow Filter", (0.45, 0.5, 0.05)),
               ("Green Filter", (0.15, 0.75, 0.1)), ("Blue Filter", (0.05, 0.25, 0.7)))
    for fname, w in filters:
        for cname, k in (("Soft", 0.1), ("", 0.35), ("Hard", 0.75)):
            add("Darkhall", B, (fname + " " + cname).strip(), lambda c, w=w, k=k: mono(c, w, k))
    add("Darkhall", B, "Matte Mono", lambda c: mono(c, (0.3, 0.6, 0.1), 0.2, 0.09, 0.95))
    add("Darkhall", B, "High Key", lambda c: mono(c, (0.3, 0.6, 0.1), 0.2, 0.0, 1.0, 0.7))
    add("Darkhall", B, "Low Key", lambda c: mono(c, (0.3, 0.6, 0.1), 0.5, 0.0, 1.0, 1.45))

    # Duotone
    D = "Duotone"
    for dark, light, name in (
        ("#1A0F33", "#F1E6FF", "Darkhall Violet"), ("#2B1B0E", "#F2DDBA", "Sepia"),
        ("#0B2545", "#D6EAF8", "Cyanotype"), ("#2A2233", "#E8E2D6", "Selenium"),
        ("#1C1C1C", "#EFE6D2", "Platinum"), ("#0D3B66", "#FAF0CA", "Navy & Cream"),
        ("#3D0C11", "#FDE2C9", "Oxblood & Cream"), ("#003844", "#FFB86F", "Petrol & Amber"),
    ):
        add("Darkhall", D, name, lambda c, a=dark, b=light: duotone(c, a, b))
    return L


# ---------------------------------------------------------------------------------------------

LICENSES = """Darkhall looks
==============

1. Film simulations ("Film color", "Film B&W" and "Creative" groups)
   RawTherapee Film Simulation Collection, version 2015-09-20
   https://rawtherapee.com/shared/HaldCLUT.zip
   Copyright: Pat David, Pavlov Dmitry, Michael Ezra
   License: Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)
   https://creativecommons.org/licenses/by-sa/4.0/
   Changes: resampled from Hald CLUTs (level 12 or 16) to 33x33x33 tables with trilinear
   interpolation and stored in the .dlut format. These adapted tables are likewise
   licensed CC BY-SA 4.0.
   Darkhall shows these looks under its own descriptive names (e.g. "Vivid Slide 50") rather
   than the film-stock names the collection uses; neither the collection's authors nor
   Darkhall are affiliated with or endorsed by the makers of those films.

2. Darkhall originals ("Darkhall" group)
   Generated by tools/looks/build_looks.py from colour formulas.
   License: CC0 1.0 Universal (public domain dedication)
   https://creativecommons.org/publicdomain/zero/1.0/
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--hald", required=True, help="unzipped HaldCLUT directory from RawTherapee")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    for f in glob.glob(os.path.join(args.out, "*.dlut")):
        os.remove(f)
    looks = original_looks() + film_looks(args.hald)
    # Shelves in order, categories in order within them, then by name.
    looks.sort(key=shelf_order)
    ids = set()
    index = []
    for lk in looks:
        assert lk["id"] not in ids, lk["id"]
        ids.add(lk["id"])
        data = encode(lk["table"]())
        with open(os.path.join(args.out, lk["id"] + ".dlut"), "wb") as fh:
            fh.write(data)
        index.append({k: lk[k] for k in ("id", "name", "group", "category", "source")})
    with open(os.path.join(args.out, "index.json"), "w") as fh:
        json.dump({"version": 1, "size": N, "looks": index}, fh, indent=1)
    with open(os.path.join(args.out, "LICENSES.txt"), "w") as fh:
        fh.write(LICENSES)
    total = sum(os.path.getsize(os.path.join(args.out, lk["id"] + ".dlut")) for lk in index)
    groups = {}
    for lk in index:
        groups[lk["group"]] = groups.get(lk["group"], 0) + 1
    print(f"{len(index)} looks, {total / 1e6:.1f} MB: {groups}")


if __name__ == "__main__":
    main()

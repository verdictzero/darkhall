#!/usr/bin/env python3
"""Writes reference.tsv for core's RawFormatsTest from LibRaw (via rawpy).

Usage: python3 reference.py DIR      (DIR holds sample raws, e.g. from https://raw.pixls.us)

For each raw: LibRaw's raw dimensions and visible-area margins, the SHA-256 of its decoded
sensor data (raw_image as little-endian uint16), per-channel black levels (R, G, B, G2),
white level, CFA pattern of the visible area and the as-shot white balance.
"""
import hashlib, os, sys
import numpy as np
import rawpy

d = sys.argv[1]
rows = ['#file\ttype\traw_w\traw_h\tleft\ttop\tw\th\tsha\tblack(RGBG)\twhite\tpattern\tcam_wb(RGBG)']
for f in sorted(os.listdir(d)):
    p = os.path.join(d, f)
    if not os.path.isfile(p) or f.endswith(('.tsv', '.json', '.txt', '.log', '.jpg')):
        continue
    try:
        with rawpy.imread(p) as r:
            s = r.sizes
            raw = r.raw_image
            sha = hashlib.sha256(np.ascontiguousarray(raw, dtype='<u2').tobytes()).hexdigest()
            pat = r.raw_pattern.flatten().tolist() if r.raw_pattern is not None else [0, 0, 0, 0]
            rows.append('\t'.join(map(str, [
                f, str(r.raw_type).split('.')[-1], s.raw_width, s.raw_height, s.left_margin, s.top_margin, s.width, s.height, sha,
                ','.join(str(int(x)) for x in r.black_level_per_channel), int(r.white_level), ','.join(map(str, pat)),
                ','.join('%.6g' % x for x in r.camera_whitebalance)])))
    except Exception as e:
        print(f'{f}: {e}', file=sys.stderr)
open(os.path.join(d, 'reference.tsv'), 'w').write('\n'.join(rows) + '\n')
print(f'{len(rows) - 1} files')

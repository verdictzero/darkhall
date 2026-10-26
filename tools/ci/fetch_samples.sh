#!/usr/bin/env bash
# Downloads the CI sample raws listed in samples.tsv into DIR (default: samples), checks each
# against its SHA-256, and writes the LibRaw references the tests compare with:
# DIR/tg6_raw_u16le.bin for -Pdarkhall.sampleRef and DIR/raws/reference.tsv for
# -Pdarkhall.rawsDir. Needs curl and, for the references, Python with rawpy and numpy.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
dir=${1:-samples}
mkdir -p "$dir/raws"
grep -v '^#' "$here/samples.tsv" | while IFS=$'\t' read -r sha name url; do
  [ -n "$sha" ] || continue
  out="$dir/$name"
  if [ -f "$out" ] && echo "$sha  $out" | sha256sum -c --status; then continue; fi
  echo "fetching $name"
  curl -fsSL --retry 4 --retry-delay 5 -o "$out" "$url"
  echo "$sha  $out" | sha256sum -c --quiet
done
python3 - "$dir" <<'PY'
import sys, numpy as np, rawpy
d = sys.argv[1]
with rawpy.imread(f"{d}/tg6.orf") as r:
    np.ascontiguousarray(r.raw_image, dtype="<u2").tofile(f"{d}/tg6_raw_u16le.bin")
PY
python3 "$here/../raws/reference.py" "$dir/raws"

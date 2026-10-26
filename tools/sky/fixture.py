"""Writes core/src/test/resources/sky/reference.bin for SkyNetTest: the NumPy reference's
outputs for a deterministic 513 x 513 input, at output stride 16 and 8.

Layout (little-endian float32): for each stride in (16, 8): grid h, grid w (as floats), the
sky probability grid, then all 151 logits at 4 fixed cells.

  python3 fixture.py model.bin out.bin
"""
import struct, sys
import numpy as np
sys.path.insert(0, __import__("os").path.dirname(__import__("os").path.abspath(__file__)))
import reference as R

def test_input():
    y, x = np.mgrid[0:R_IN, 0:R_IN].astype(np.float64)
    ch = [np.sin(0.05 * x + 0.07 * y + c) * np.cos(0.03 * y - 0.02 * x * (c + 1)) for c in range(3)]
    # A bright smooth band over the top third, as a sky would be.
    for c in range(3):
        ch[c] = np.where(y < R_IN / 3, 0.6 + 0.1 * c + 0.05 * np.sin(0.01 * x), ch[c])
    return np.stack(ch).astype(np.float32)

R_IN = 513
CELLS = [(0, 0), (10, 64), (64, 64), (128, 100)]

if __name__ == '__main__':
    layers = R.load(sys.argv[1])
    x = test_input()
    with open(sys.argv[2], 'wb') as out:
        for blocks in (R.BLOCKS_OS16, R.BLOCKS_OS8):
            l = R.run(layers, x, blocks)
            e = np.exp(l - l.max(0)); p = (e[3] / e.sum(0)).astype(np.float32)
            out.write(struct.pack('<ff', *p.shape))
            out.write(p.astype('<f4').tobytes())
            for (cy, cx) in CELLS:
                out.write(l[:, cy, cx].astype('<f4').tobytes())
            print('sky share', (p > 0.5).mean(), 'shape', p.shape)

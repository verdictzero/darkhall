"""NumPy reference of Darkhall's sky network (the same arithmetic SkyNet.kt does), for
checking the exported weights against TensorFlow and producing test fixtures.

  python3 reference.py model.bin                       # self-check against TF (needs the .pb)
"""
import struct, sys
import numpy as np

def load(path):
    d = open(path, 'rb').read()
    assert d[:6] == b'DHSKY1'
    n = struct.unpack_from('<I', d, 8)[0]; p = 12
    layers = []
    for _ in range(n):
        ln = struct.unpack_from('<H', d, p)[0]; p += 2
        name = d[p:p + ln].decode(); p += ln
        kind, kh, kw, cin, cout = struct.unpack_from('<BHHHH', d, p); p += 9
        count = kh * kw * cin * (1 if kind else cout)
        w = np.frombuffer(d, '<f2', count, p).astype(np.float32); p += 2 * count
        b = np.frombuffer(d, '<f4', cout, p).astype(np.float32); p += 4 * cout
        w = w.reshape((cin, kh, kw) if kind else (cout, cin, kh, kw))
        layers.append((name, kind, w, b))
    return layers

def same_pad(size, k, s, d):
    """TensorFlow SAME padding (before, after) for one axis."""
    keff = (k - 1) * d + 1
    out = -(-size // s)
    total = max((out - 1) * s + keff - size, 0)
    return out, total // 2, total - total // 2

def conv(x, w, b, stride=1, dil=1, depthwise=False):
    # x: [c, h, w]
    if depthwise:
        c, kh, kw = w.shape
    else:
        _, c, kh, kw = w.shape
    _, H, W = x.shape
    oh, pt, pb = same_pad(H, kh, stride, dil)
    ow, pl, pr = same_pad(W, kw, stride, dil)
    xp = np.pad(x, ((0, 0), (pt, pb), (pl, pr)))
    if kh == 1 and kw == 1 and not depthwise:
        xs = xp[:, ::stride, ::stride][:, :oh, :ow]
        return np.einsum('oc,chw->ohw', w[:, :, 0, 0], xs, optimize=True) + b[:, None, None]
    out = np.zeros((w.shape[0] if not depthwise else c, oh, ow), np.float32)
    for i in range(kh):
        for j in range(kw):
            patch = xp[:, i * dil: i * dil + (oh - 1) * stride + 1: stride, j * dil: j * dil + (ow - 1) * stride + 1: stride]
            if depthwise:
                out += w[:, i, j][:, None, None] * patch
            else:
                out += np.einsum('oc,chw->ohw', w[:, :, i, j], patch, optimize=True)
    return out + b[:, None, None]

relu6 = lambda x: np.clip(x, 0, 6)
relu = lambda x: np.maximum(x, 0)

def resize_ac(x, oh, ow):
    """Bilinear resize with align_corners=True."""
    c, h, w = x.shape
    ys = np.linspace(0, h - 1, oh) if oh > 1 else np.zeros(1)
    xs = np.linspace(0, w - 1, ow) if ow > 1 else np.zeros(1)
    y0 = np.floor(ys).astype(int); x0 = np.floor(xs).astype(int)
    y1 = np.minimum(y0 + 1, h - 1); x1 = np.minimum(x0 + 1, w - 1)
    fy = (ys - y0)[None, :, None]; fx = (xs - x0)[None, None, :]
    a = x[:, y0][:, :, x0]; bq = x[:, y0][:, :, x1]; cq = x[:, y1][:, :, x0]; dq = x[:, y1][:, :, x1]
    return a * (1 - fy) * (1 - fx) + bq * (1 - fy) * fx + cq * fy * (1 - fx) + dq * fy * fx

# Expansion factor, stride, dilation per block (block 0 has no expansion): output stride 8,
# as the graph was exported...
BLOCKS_OS8 = [(1, 1, 1), (6, 2, 1), (6, 1, 1), (6, 2, 1), (6, 1, 1), (6, 1, 1), (6, 1, 1),
          (6, 1, 2), (6, 1, 2), (6, 1, 2), (6, 1, 2), (6, 1, 2), (6, 1, 2), (6, 1, 2),
          (6, 1, 4), (6, 1, 4), (6, 1, 4)]
# ...and output stride 16, MobileNetV2's own strides with dilation only after the last one:
# the same weights at a quarter of the cost of the deep layers.
BLOCKS_OS16 = [(1, 1, 1), (6, 2, 1), (6, 1, 1), (6, 2, 1), (6, 1, 1), (6, 1, 1), (6, 2, 1),
               (6, 1, 1), (6, 1, 1), (6, 1, 1), (6, 1, 1), (6, 1, 1), (6, 1, 1), (6, 1, 1),
               (6, 1, 2), (6, 1, 2), (6, 1, 2)]
BLOCKS = BLOCKS_OS16

def run(layers, x, blocks=None):
    """x: [3, h, w] in -1..1. Returns logits [151, h4, w4] at a quarter of the input size."""
    L = iter(layers)
    def nxt(): return next(L)
    _, _, w, b = nxt(); x = relu6(conv(x, w, b, stride=2))
    low = None
    for i, (t, s, d) in enumerate(blocks or BLOCKS):
        inp = x
        if t != 1:
            _, _, w, b = nxt(); x = relu6(conv(x, w, b))
        _, _, w, b = nxt(); x = relu6(conv(x, w, b, stride=s, dil=d, depthwise=True))
        if i == 2: low = x  # decoder's low-level features: block 2's depthwise output, stride 4
        _, _, w, b = nxt(); x = conv(x, w, b)
        if s == 1 and inp.shape == x.shape: x = x + inp
    # Image pooling branch: global average, 1x1 conv, broadcast back.
    _, _, w, b = nxt(); pool = relu(conv(x.mean(axis=(1, 2), keepdims=True), w, b))
    pool = np.broadcast_to(pool, (pool.shape[0],) + x.shape[1:])
    _, _, w, b = nxt(); a0 = relu(conv(x, w, b))
    _, _, w, b = nxt(); y = relu(conv(np.concatenate([pool, a0]), w, b))
    _, _, w, b = nxt(); f = relu(conv(low, w, b))
    y = resize_ac(y, f.shape[1], f.shape[2])
    z = np.concatenate([y, f])
    _, _, w, b = nxt(); z = relu(conv(z, w, b, depthwise=True))
    _, _, w, b = nxt(); z = relu(conv(z, w, b))
    _, _, w, b = nxt(); z = relu(conv(z, w, b, depthwise=True))
    _, _, w, b = nxt(); z = relu(conv(z, w, b))
    _, _, w, b = nxt(); return conv(z, w, b)

if __name__ == '__main__':
    layers = load(sys.argv[1])
    print(len(layers), 'layers,', sum(l[2].size for l in layers), 'weights')

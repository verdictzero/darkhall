"""Exports DeepLab v3+ MobileNetV2 (ADE20K) to Darkhall's sky model file.

Input: frozen_inference_graph.pb from
  https://storage.googleapis.com/download.tensorflow.org/models/deeplabv3_mnv2_ade20k_train_2018_12_03.tar.gz
(TensorFlow Models, Apache 2.0). Batch norm is folded into each convolution; weights are
stored as fp16, biases as fp32. Of the 151 output classes only "sky" (3) and a "not sky"
summary are needed at run time, but the logits layer is kept whole so the softmax is exact.

File layout (little-endian): magic "DHSKY1\\0\\0", u32 layer count, then per layer:
  u16 name length, name (UTF-8), u8 kind (0 conv, 1 depthwise), u16 kh, u16 kw, u16 cin,
  u16 cout, then kh*kw*cin*cout fp16 weights in [cout][cin][kh][kw] order (depthwise:
  [c][kh][kw]), then cout fp32 biases.
Layers appear in the order SkyNet.kt reads them.

Usage: python3 export_deeplab.py frozen_inference_graph.pb out.bin
"""
import struct, sys
import numpy as np
import tensorflow as tf
from tensorflow.python.framework import tensor_util

graph = tf.compat.v1.GraphDef()
nodes = {}

def open_graph(path):
    graph.ParseFromString(open(path, 'rb').read())
    nodes.update({n.name: n for n in graph.node})

def const(name):
    n = nodes[name.split(':')[0]]
    while n.op == 'Identity':
        n = nodes[n.input[0]]
    return tensor_util.MakeNdarray(n.attr['value'].tensor)

def consumers(name):
    return [n for n in graph.node if any(i.split(':')[0] == name for i in n.input)]

def layer(conv_name):
    """Weights and bias of one convolution with its batch norm (or bias add) folded in."""
    conv = nodes[conv_name]
    w = const(conv.input[1]).astype(np.float64)  # [kh, kw, cin, cout] or depthwise [kh, kw, c, 1]
    depthwise = conv.op == 'DepthwiseConv2dNative'
    nxt = consumers(conv_name)
    # Dilated depthwise convs are wrapped in SpaceToBatch/BatchToSpace.
    if len(nxt) == 1 and nxt[0].op == 'BatchToSpaceND':
        nxt = consumers(nxt[0].name)
    cout = w.shape[2] if depthwise else w.shape[3]
    bias = np.zeros(cout)
    if nxt and nxt[0].op == 'FusedBatchNorm':
        bn = nxt[0]
        gamma, beta, mean, var = (const(bn.input[i]).astype(np.float64) for i in range(1, 5))
        scale = gamma / np.sqrt(var + bn.attr['epsilon'].f)
        w = w * (scale if not depthwise else scale[None, None, :, None])
        bias = beta - mean * scale
    elif nxt and nxt[0].op == 'BiasAdd':
        bias = const(nxt[0].input[1]).astype(np.float64)
    if depthwise:
        arr = w[:, :, :, 0].transpose(2, 0, 1)            # [c][kh][kw]
        kh, kw, cin = w.shape[0], w.shape[1], w.shape[2]
    else:
        arr = w.transpose(3, 2, 0, 1)                     # [cout][cin][kh][kw]
        kh, kw, cin = w.shape[0], w.shape[1], w.shape[2]
    return (1 if depthwise else 0), kh, kw, cin, cout, arr, bias

NAMES = ['MobilenetV2/Conv/Conv2D',
         'MobilenetV2/expanded_conv/depthwise/depthwise', 'MobilenetV2/expanded_conv/project/Conv2D']
for i in range(1, 17):
    b = f'MobilenetV2/expanded_conv_{i}'
    NAMES += [f'{b}/expand/Conv2D', f'{b}/depthwise/depthwise', f'{b}/project/Conv2D']
NAMES += ['image_pooling/Conv2D', 'aspp0/Conv2D', 'concat_projection/Conv2D', 'decoder/feature_projection0/Conv2D',
          'decoder/decoder_conv0_depthwise/depthwise', 'decoder/decoder_conv0_pointwise/Conv2D',
          'decoder/decoder_conv1_depthwise/depthwise', 'decoder/decoder_conv1_pointwise/Conv2D',
          'logits/semantic/Conv2D']

if __name__ == '__main__':
    open_graph(sys.argv[1])
    with open(sys.argv[2], 'wb') as out:
        out.write(b'DHSKY1\0\0')
        out.write(struct.pack('<I', len(NAMES)))
        for n in NAMES:
            kind, kh, kw, cin, cout, w, b = layer(n)
            nb = n.encode()
            out.write(struct.pack('<H', len(nb))); out.write(nb)
            out.write(struct.pack('<BHHHH', kind, kh, kw, cin, cout))
            out.write(w.astype('<f2').tobytes()); out.write(b.astype('<f4').tobytes())
    print(len(NAMES), 'layers')

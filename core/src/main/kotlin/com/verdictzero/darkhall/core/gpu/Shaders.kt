package com.verdictzero.darkhall.core.gpu

/**
 * GLSL ES 3.00 sources, one fragment shader per pipeline stage, mirroring
 * [com.verdictzero.darkhall.core.develop.Developer] line by line.
 *
 * Written to be safe on Qualcomm Adreno drivers in particular:
 *  - everything is highp: Adreno honours mediump as real 16-bit floats, which is far too
 *    coarse for linear-light maths and noise thresholds;
 *  - only texelFetch with explicit edge clamping, so no float-texture filtering extension
 *    is needed and the result does not depend on sampler state;
 *  - no compute shaders, image load/store or SSBOs (GLES 3.0 fragment shaders only);
 *  - loop bounds are uniforms with small ranges; no dynamic indexing into arrays;
 *  - looks (3D LUTs) live in an ordinary 2D RGBA8 texture and are interpolated by hand, so
 *    neither 3D-texture support nor the driver's (often 8-bit) filtering weights matter;
 *  - a full-screen triangle from gl_VertexID, no vertex attributes.
 */
object Shaders {
    const val VERTEX = """#version 300 es
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

    private const val HEADER = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
layout(location = 0) out vec4 o;
const vec3 LUM = vec3(0.2126, 0.7152, 0.0722);
ivec2 P() { return ivec2(gl_FragCoord.xy); }
vec4 F(sampler2D s, ivec2 p) { return texelFetch(s, clamp(p, ivec2(0), textureSize(s, 0) - 1), 0); }
"""

    /** WB, underwater, temperature/tint, camera matrix, exposure, vignette. */
    const val COLOR = HEADER + """
uniform sampler2D uIn;
uniform vec3 uMulA;
uniform vec3 uSubB;
uniform vec3 uM0;
uniform vec3 uM1;
uniform vec3 uM2;
uniform float uEv;
uniform float uVig;
uniform vec2 uCenter;
uniform float uInvR2;
uniform float uInvScale;
uniform vec2 uFrame0;
void main() {
    ivec2 p = P();
    vec3 v = texelFetch(uIn, p, 0).rgb;
    vec3 c = max(vec3(0.0), v * uMulA - uSubB);
    vec3 s = vec3(dot(uM0, c), dot(uM1, c), dot(uM2, c));
    float k = uEv;
    if (uVig != 0.0) {
        float fx = uFrame0.x + float(p.x) * uInvScale - uCenter.x;
        float fy = uFrame0.y + float(p.y) * uInvScale - uCenter.y;
        k *= 1.0 + uVig * (fx * fx + fy * fy) * uInvR2;
    }
    o = vec4(s * k, 1.0);
}
"""

    /** Dehaze transmission input: darkest channel relative to the atmospheric light. */
    const val DEHAZE_DARK = HEADER + """
uniform sampler2D uIn;
uniform vec3 uAtm;
void main() {
    vec3 v = texelFetch(uIn, P(), 0).rgb / uAtm;
    o = vec4(min(1.0, max(0.0, min(v.r, min(v.g, v.b)))));
}
"""

    const val DEHAZE_APPLY = HEADER + """
uniform sampler2D uIn;
uniform sampler2D uT;
uniform vec3 uAtm;
uniform float uOmega;
void main() {
    ivec2 p = P();
    vec3 v = texelFetch(uIn, p, 0).rgb;
    float t = max(0.2, 1.0 - uOmega * texelFetch(uT, p, 0).r);
    o = vec4((v - uAtm) * (1.0 / t) + uAtm, 1.0);
}
"""

    const val DEHAZE_NEGATIVE = HEADER + """
uniform sampler2D uIn;
uniform vec3 uAtm;
uniform float uK;
void main() {
    vec3 v = texelFetch(uIn, P(), 0).rgb;
    o = vec4(v + (uAtm - v) * uK, 1.0);
}
"""

    /** Separable minimum over [-uR, uR] along uDir (edge clamped, like the CPU filter). */
    const val MIN_1D = HEADER + """
uniform sampler2D uIn;
uniform ivec2 uDir;
uniform int uR;
void main() {
    ivec2 p = P();
    float m = F(uIn, p).r;
    for (int i = 1; i <= uR; i++) {
        m = min(m, min(F(uIn, p + uDir * i).r, F(uIn, p - uDir * i).r));
    }
    o = vec4(m);
}
"""

    /** Separable box average over [-uR, uR] along uDir (edge clamped), all four channels. */
    const val BOX_1D = HEADER + """
uniform sampler2D uIn;
uniform ivec2 uDir;
uniform int uR;
void main() {
    ivec2 p = P();
    vec4 s = vec4(0.0);
    for (int i = -uR; i <= uR; i++) s += F(uIn, p + uDir * i);
    o = s * (1.0 / float(2 * uR + 1));
}
"""

    /** Linear luminance into .r. */
    const val LUMA = HEADER + """
uniform sampler2D uIn;
void main() { o = vec4(dot(LUM, texelFetch(uIn, P(), 0).rgb)); }
"""

    /** Chroma differences (R - Y, B - Y) for colour noise reduction. */
    const val CHROMA_SPLIT = HEADER + """
uniform sampler2D uIn;
uniform sampler2D uY;
void main() {
    ivec2 p = P();
    vec3 v = texelFetch(uIn, p, 0).rgb;
    float y = texelFetch(uY, p, 0).r;
    o = vec4(v.r - y, v.b - y, 0.0, 0.0);
}
"""

    const val CHROMA_MERGE = HEADER + """
uniform sampler2D uY;
uniform sampler2D uC;
void main() {
    ivec2 p = P();
    float y = texelFetch(uY, p, 0).r;
    vec2 c = texelFetch(uC, p, 0).rg;
    float r = c.x + y;
    float b = c.y + y;
    float g = (y - LUM.r * r - LUM.b * b) / LUM.g;
    o = vec4(r, g, b, 1.0);
}
"""

    /** One separable "à trous" B3-spline smoothing pass with holes of uStep pixels. */
    const val ATROUS_1D = HEADER + """
uniform sampler2D uIn;
uniform ivec2 uDir;
uniform int uStep;
void main() {
    ivec2 p = P();
    ivec2 d = uDir * uStep;
    float l2 = F(uIn, p - 2 * d).r;
    float l1 = F(uIn, p - d).r;
    float r1 = F(uIn, p + d).r;
    float r2 = F(uIn, p + 2 * d).r;
    o = vec4((l2 + r2 + 4.0 * (l1 + r1) + 6.0 * F(uIn, p).r) * 0.0625);
}
"""

    /** Shrinks one wavelet detail level against the profiled noise and accumulates it. */
    const val SHRINK = HEADER + """
uniform sampler2D uCur;
uniform sampler2D uNext;
uniform sampler2D uAcc;
uniform float uK;
uniform float uNA;
uniform float uNB;
uniform int uFinest;
void main() {
    ivec2 p = P();
    float nx = texelFetch(uNext, p, 0).r;
    float d = texelFetch(uCur, p, 0).r - nx;
    float t = uK * sqrt(uNA * max(nx, 0.0) + uNB);
    float add;
    if (uFinest == 1) {
        add = d > t ? d - t : (d < -t ? d + t : 0.0);
    } else {
        float t2 = t * t;
        add = d * d > t2 ? d - t2 / d : 0.0;
    }
    o = vec4(texelFetch(uAcc, p, 0).r + add);
}
"""

    /** Adds the luminance change (denoised - original) to all channels. */
    const val LUMA_APPLY = HEADER + """
uniform sampler2D uIn;
uniform sampler2D uY;
uniform sampler2D uAcc;
uniform sampler2D uCoarse;
void main() {
    ivec2 p = P();
    vec3 v = texelFetch(uIn, p, 0).rgb;
    float add = texelFetch(uAcc, p, 0).r + texelFetch(uCoarse, p, 0).r - texelFetch(uY, p, 0).r;
    o = vec4(v + add, 1.0);
}
"""

    /** Shadows/highlights, shoulder, sRGB encode, tone curve, vibrance/saturation, colour mixer, look. */
    const val TONE = HEADER + """
uniform sampler2D uIn;
uniform sampler2D uLut;
uniform float uSh;
uniform float uHl;
uniform int uDoSh;
uniform float uVib;
uniform float uSat;
uniform int uDoSat;
uniform sampler2D uLook;
uniform int uLookOn;
uniform int uLookN;
uniform float uLookAmount;
uniform int uMixOn;
uniform vec3 uMix[8];
uniform int uGradeOn;
uniform float uGradeExp;
uniform vec3 uGradeS;
uniform vec3 uGradeM;
uniform vec3 uGradeH;
const float SHOULDER = 0.85;
const float DESAT_END = 3.0;
const float DESAT_MAX = 0.85;
// Look table: N*N wide, N tall; texel (r + N*g, b). Manual trilinear, same arithmetic as the CPU.
vec3 lk(int r, int g, int b) { return texelFetch(uLook, ivec2(r + g * uLookN, b), 0).rgb; }
vec3 applyLook(vec3 c) {
    int n1 = uLookN - 1;
    vec3 p = c * float(n1);
    ivec3 i = clamp(ivec3(p), ivec3(0), ivec3(n1 - 1));
    vec3 f = p - vec3(i);
    vec3 c00 = lk(i.x, i.y, i.z) * (1.0 - f.x) + lk(i.x + 1, i.y, i.z) * f.x;
    vec3 c01 = lk(i.x, i.y + 1, i.z) * (1.0 - f.x) + lk(i.x + 1, i.y + 1, i.z) * f.x;
    vec3 c10 = lk(i.x, i.y, i.z + 1) * (1.0 - f.x) + lk(i.x + 1, i.y, i.z + 1) * f.x;
    vec3 c11 = lk(i.x, i.y + 1, i.z + 1) * (1.0 - f.x) + lk(i.x + 1, i.y + 1, i.z + 1) * f.x;
    vec3 c0 = c00 * (1.0 - f.y) + c01 * f.y;
    vec3 c1 = c10 * (1.0 - f.y) + c11 * f.y;
    return c0 * (1.0 - f.z) + c1 * f.z;
}
// Colour mixer: as ToneStage.mix.
const float CENTRES[9] = float[9](0.0, 30.0, 60.0, 120.0, 180.0, 240.0, 270.0, 300.0, 360.0);
vec3 mixer(vec3 d) {
    float mx = max(d.r, max(d.g, d.b));
    float c = mx - min(d.r, min(d.g, d.b));
    if (c < 1e-5) return d;
    float h;
    if (mx == d.r) h = (d.g - d.b) / c;
    else if (mx == d.g) h = (d.b - d.r) / c + 2.0;
    else h = (d.r - d.g) / c + 4.0;
    h *= 60.0;
    if (h < 0.0) h += 360.0;
    int i = 0;
    while (i < 7 && h >= CENTRES[i + 1]) i++;
    float t = (h - CENTRES[i]) / (CENTRES[i + 1] - CENTRES[i]);
    t = t * t * (3.0 - 2.0 * t);
    vec3 m = uMix[i] * (1.0 - t) + uMix[(i + 1) % 8] * t;
    vec3 o = d;
    if (m.x != 0.0) {
        float co = cos(m.x);
        float si = sin(m.x) * 0.57735026;
        float a = co + (1.0 - co) / 3.0;
        float p = (1.0 - co) / 3.0 - si;
        float q = (1.0 - co) / 3.0 + si;
        o = vec3(a * d.r + p * d.g + q * d.b, q * d.r + a * d.g + p * d.b, p * d.r + q * d.g + a * d.b);
    }
    if (m.y != 0.0) {
        float y = dot(LUM, o);
        o = y + (o - y) * (1.0 + m.y);
    }
    if (m.z != 0.0) o *= exp2(m.z * min(1.0, 2.0 * c / mx));
    return clamp(o, 0.0, 1.0);
}
float enc(float v) {
    float x = clamp(v, 0.0, 1.0);
    return x <= 0.0031308 ? 12.92 * x : 1.055 * pow(x, 1.0 / 2.4) - 0.055;
}
float lut(float v) {
    int i = int(enc(v) * 4096.0);
    return texelFetch(uLut, ivec2(i % 64, i / 64), 0).r;
}
void main() {
    vec3 c = texelFetch(uIn, P(), 0).rgb;
    if (uDoSh == 1) {
        float l = dot(LUM, c);
        float yd = l <= 1.0 ? enc(l) : 1.0 + 0.5 * log2(l);
        float ys = min(yd, 1.0);
        c *= exp(uSh * (1.0 - ys) * (1.0 - ys) + uHl * yd * yd);
    }
    float mx = max(c.r, max(c.g, c.b));
    if (mx > SHOULDER) {
        float f = SHOULDER + (1.0 - SHOULDER) * (1.0 - exp(-(mx - SHOULDER) / (1.0 - SHOULDER)));
        c *= f / mx;
        float t = clamp((mx - SHOULDER) / (DESAT_END - SHOULDER), 0.0, 1.0);
        float d = t * t * (3.0 - 2.0 * t) * DESAT_MAX;
        c += (dot(LUM, c) - c) * d;
    }
    vec3 d = vec3(lut(c.r), lut(c.g), lut(c.b));
    if (uDoSat == 1) {
        float yv = dot(LUM, d);
        float s = max(d.r, max(d.g, d.b)) - min(d.r, min(d.g, d.b));
        float k = (1.0 + uVib * (1.0 - s)) * (1.0 + uSat);
        d = clamp(yv + (d - yv) * k, 0.0, 1.0);
    }
    if (uMixOn == 1) d = mixer(d);
    if (uGradeOn == 1) {
        float t = pow(max(clamp(dot(LUM, d), 0.0, 1.0), 1e-6), uGradeExp);
        float ws = (1.0 - t) * (1.0 - t);
        float wh = t * t;
        float wm = 2.0 * t * (1.0 - t);
        d = clamp(d + ws * uGradeS + wm * uGradeM + wh * uGradeH, 0.0, 1.0);
    }
    if (uLookOn == 1) {
        d += (applyLook(d) - d) * uLookAmount;
    }
    o = vec4(d, 1.0);
}
"""

    /** Film grain: as Grain.apply. */
    const val GRAIN = HEADER + """
uniform sampler2D uIn;
uniform float uGrainA;
uniform float uCell;
uniform vec2 uFrame0;
uniform float uInvScale;
float hash(int ix, int iy) {
    uint h = uint(ix) * 374761393u + uint(iy) * 668265263u;
    h = (h ^ (h >> 13u)) * 1274126177u;
    h = h ^ (h >> 16u);
    return float(h & 0xFFFFFFu) / 16777216.0;
}
void main() {
    ivec2 p = P();
    vec3 v = texelFetch(uIn, p, 0).rgb;
    vec2 f = uFrame0 + (vec2(p) + 0.5) * uInvScale;
    vec2 q = f / uCell;
    vec2 i0 = floor(q);
    vec2 t = q - i0;
    t = t * t * (3.0 - 2.0 * t);
    int ix = int(i0.x);
    int iy = int(i0.y);
    float n = (hash(ix, iy) * (1.0 - t.x) + hash(ix + 1, iy) * t.x) * (1.0 - t.y) + (hash(ix, iy + 1) * (1.0 - t.x) + hash(ix + 1, iy + 1) * t.x) * t.y;
    float l = clamp(dot(LUM, v), 0.0, 1.0);
    float add = uGrainA * (2.0 * n - 1.0) * 2.0 * sqrt(l * (1.0 - l));
    o = vec4(clamp(v + add, 0.0, 1.0), 1.0);
}
"""

    /** Clarity and sharpening from blurred display luminance. */
    const val DETAIL_APPLY = HEADER + """
uniform sampler2D uIn;
uniform sampler2D uY;
uniform sampler2D uSharpBlur;
uniform sampler2D uClarBlur;
uniform int uSharpOn;
uniform int uClarOn;
uniform float uSharpA;
uniform float uClarA;
void main() {
    ivec2 p = P();
    vec3 v = texelFetch(uIn, p, 0).rgb;
    float y = texelFetch(uY, p, 0).r;
    float t = 0.0;
    if (uSharpOn == 1) {
        float d = y - texelFetch(uSharpBlur, p, 0).r;
        float d2 = d * d;
        t += uSharpA * d * (d2 / (d2 + 0.000036));
    }
    if (uClarOn == 1) {
        float d = y - texelFetch(uClarBlur, p, 0).r;
        t += uClarA * d / (1.0 + 3.0 * abs(d));
    }
    float ny = clamp(y + t, 0.0, 1.0);
    if (y > 1e-4) {
        v = clamp(v * (ny / y), 0.0, 1.0);
    } else {
        v = clamp(v + (ny - y), 0.0, 1.0);
    }
    o = vec4(v, 1.0);
}
"""

    /**
     * 8-bit quantisation plus geometry (straighten, distortion, lens profile with lateral CA),
     * sampled bilinearly from the quantised values exactly like the CPU Geometry pass.
     */
    const val FINAL = HEADER + """
uniform sampler2D uIn;
uniform int uGeom;
uniform int uTca;
uniform vec2 uC;
uniform float uRadius;
uniform float uZoom;
uniform vec2 uRot;
uniform float uK;
uniform int uLens;
uniform float uAmount;
// Lens correction f(r) = sum uPi r^i, per channel (r, g, b).
uniform vec3 uP0;
uniform vec3 uP1;
uniform vec3 uP2;
uniform vec3 uP3;
uniform vec3 uP4;
uniform vec3 uP5;
uniform vec3 uP6;
float q(float v) { return floor(clamp(v, 0.0, 1.0) * 255.0 + 0.5); }
// One channel, bilinear on quantised values; -1 when outside the frame.
float sampleCh(vec2 s, int ch) {
    vec2 sz = vec2(textureSize(uIn, 0));
    if (s.x < -0.5 || s.y < -0.5 || s.x > sz.x - 0.5 || s.y > sz.y - 0.5) return -1.0;
    vec2 c = clamp(s, vec2(0.0), sz - 1.0);
    ivec2 i0 = ivec2(c);
    ivec2 i1 = min(i0 + 1, ivec2(sz) - 1);
    vec2 f = c - vec2(i0);
    vec4 a = texelFetch(uIn, i0, 0);
    vec4 b = texelFetch(uIn, ivec2(i1.x, i0.y), 0);
    vec4 cc = texelFetch(uIn, ivec2(i0.x, i1.y), 0);
    vec4 d = texelFetch(uIn, i1, 0);
    float va = q(ch == 0 ? a.r : (ch == 1 ? a.g : a.b));
    float vb = q(ch == 0 ? b.r : (ch == 1 ? b.g : b.b));
    float vc = q(ch == 0 ? cc.r : (ch == 1 ? cc.g : cc.b));
    float vd = q(ch == 0 ? d.r : (ch == 1 ? d.g : d.b));
    float v = va * (1.0 - f.x) * (1.0 - f.y) + vb * f.x * (1.0 - f.y) + vc * (1.0 - f.x) * f.y + vd * f.x * f.y;
    return clamp(floor(v + 0.5), 0.0, 255.0);
}
void main() {
    ivec2 p = P();
    if (uGeom == 0) {
        vec3 v = texelFetch(uIn, p, 0).rgb;
        o = vec4(q(v.r), q(v.g), q(v.b), 255.0) / 255.0;
        return;
    }
    vec2 dd = (vec2(p) - uC) / uRadius;
    vec2 qv = vec2(dd.x * uRot.x - dd.y * uRot.y, dd.x * uRot.y + dd.y * uRot.x) * uZoom;
    if (uK != 0.0) qv *= 1.0 + uK * dot(qv, qv);
    float fg = 1.0;
    float fr = 1.0;
    float fb = 1.0;
    if (uLens == 1) {
        float r = length(qv);
        vec3 f = uP6;
        f = f * r + uP5; f = f * r + uP4; f = f * r + uP3; f = f * r + uP2; f = f * r + uP1; f = f * r + uP0;
        vec3 fa = vec3(1.0) + uAmount * (f - vec3(1.0));
        fr = fa.x; fg = fa.y; fb = fa.z;
    }
    vec2 sg = uC + qv * fg * uRadius;
    if (uTca == 0) {
        float r = sampleCh(sg, 0);
        if (r < 0.0) { o = vec4(0.0, 0.0, 0.0, 1.0); return; }
        o = vec4(r, sampleCh(sg, 1), sampleCh(sg, 2), 255.0) / 255.0;
    } else {
        o = vec4(max(sampleCh(uC + qv * fr * uRadius, 0), 0.0),
                 max(sampleCh(sg, 1), 0.0),
                 max(sampleCh(uC + qv * fb * uRadius, 2), 0.0), 255.0) / 255.0;
    }
}
"""
}

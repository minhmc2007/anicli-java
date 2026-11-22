#version 450

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D uPrev;
layout(binding = 1) uniform sampler2D uNext;
layout(binding = 2) uniform sampler2D uTiles;

layout(push_constant) uniform Push {
    vec2 lumaSize;
    vec2 tileSize;
    vec2 grid;
} pc;

vec2 decodeFlow(vec4 e) {
    return vec2(e.r * 255.0 + e.g, e.b * 255.0 + e.a) * (384.0 / 255.0) - 192.0;
}

float box(vec2 p, vec2 s) {
    float sad = 0.0;
    for (int j = -1; j <= 1; ++j) {
        for (int i = -1; i <= 1; ++i) {
            vec2 q = p + vec2(float(i), float(j));
            sad += abs(texture(uPrev, q / pc.lumaSize).r
                    - texture(uNext, (q + s) / pc.lumaSize).r);
        }
    }
    return sad;
}

vec2 tileFlow(vec2 cell) {
    return decodeFlow(texture(uTiles, (cell + 0.5) / pc.grid));
}

void main() {
    vec2 p = vUv * pc.lumaSize;
    vec2 t = p / pc.tileSize - 0.5;
    vec2 base = floor(t);
    vec2 f = t - base;
    vec2 v00 = tileFlow(base);
    vec2 v10 = tileFlow(base + vec2(1.0, 0.0));
    vec2 v01 = tileFlow(base + vec2(0.0, 1.0));
    vec2 v11 = tileFlow(base + vec2(1.0, 1.0));
    float s00 = box(p, v00);
    float s10 = box(p, v10);
    float s01 = box(p, v01);
    float s11 = box(p, v11);
    vec2 v = v00;
    float s = s00;
    if (s10 < s) {
        v = v10;
        s = s10;
    }
    if (s01 < s) {
        v = v01;
        s = s01;
    }
    if (s11 < s) {
        v = v11;
        s = s11;
    }
    // Interpolate only while every neighbour fits the pixel equally well. Where
    // they disagree one of them is the silhouette's real motion, so take the
    // winner instead of mixing two objects into a rubber edge.
    float lo = min(min(s00, s10), min(s01, s11));
    float hi = max(max(s00, s10), max(s01, s11));
    vec2 blend = v00 * (1.0 - f.x) * (1.0 - f.y)
            + v10 * f.x * (1.0 - f.y)
            + v01 * (1.0 - f.x) * f.y
            + v11 * f.x * f.y;
    v = mix(blend, v, smoothstep(0.5, 2.5, hi - lo));
    float c0 = box(p, v);
    float l = box(p, v + vec2(-1.0, 0.0));
    float r = box(p, v + vec2(1.0, 0.0));
    float u = box(p, v + vec2(0.0, -1.0));
    float d = box(p, v + vec2(0.0, 1.0));
    float ax = l - 2.0 * c0 + r;
    float ay = u - 2.0 * c0 + d;
    vec2 frac = vec2(ax > 1e-5 ? clamp(0.5 * (l - r) / ax, -0.5, 0.5) : 0.0,
                     ay > 1e-5 ? clamp(0.5 * (u - d) / ay, -0.5, 0.5) : 0.0);
    vec2 e = clamp((v + frac + 192.0) * (255.0 / 384.0), 0.0, 255.0);
    fragColor = vec4(floor(e.x) / 255.0, fract(e.x),
                     floor(e.y) / 255.0, fract(e.y));
}

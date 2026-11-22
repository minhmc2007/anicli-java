#version 450

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D uSearch;
layout(binding = 1) uniform sampler2D uBlocks;
layout(binding = 2) uniform sampler2D uPick;

layout(push_constant) uniform Push {
    float pickRange;
    float stride;
} pc;

float sad(vec2 cell) {
    vec4 t = texture(uSearch, (cell + 0.5) / 49.0);
    return floor(t.b * 255.0 + 0.5) + t.a;
}

void main() {
    float best = 2.0;
    vec2 bestCell = vec2(24.0);
    for (int y = 0; y < 7; ++y) {
        for (int x = 0; x < 7; ++x) {
            vec4 b = texture(uBlocks, (vec2(float(x), float(y)) + 0.5) / 7.0);
            if (b.b < best) {
                best = b.b;
                bestCell = floor(b.rg * 49.0);
            }
        }
    }
    float c0 = sad(bestCell);
    float l = sad(bestCell + vec2(-1.0, 0.0));
    float r = sad(bestCell + vec2(1.0, 0.0));
    float u = sad(bestCell + vec2(0.0, -1.0));
    float d = sad(bestCell + vec2(0.0, 1.0));
    float ax = l - 2.0 * c0 + r;
    float ay = u - 2.0 * c0 + d;
    vec2 frac = vec2(ax > 1e-5 ? clamp(0.5 * (l - r) / ax, -0.5, 0.5) : 0.0,
                     ay > 1e-5 ? clamp(0.5 * (u - d) / ay, -0.5, 0.5) : 0.0);
    vec2 center = (texture(uPick, vec2(0.5)).rg * 2.0 - 1.0) * pc.pickRange;
    vec2 shift = center + (bestCell - 24.0 + frac) * pc.stride;
    vec2 e = (shift + 192.0) * (255.0 / 384.0);
    fragColor = vec4(floor(e.x) / 255.0, e.x - floor(e.x),
                     floor(e.y) / 255.0, e.y - floor(e.y));
}

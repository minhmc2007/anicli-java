#version 450

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D uBlocks;

layout(push_constant) uniform Push {
    float stride;
    float range;
} pc;

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
    vec2 shift = (bestCell - 24.0) * pc.stride;
    fragColor = vec4((shift + pc.range) / (2.0 * pc.range), best, 1.0);
}

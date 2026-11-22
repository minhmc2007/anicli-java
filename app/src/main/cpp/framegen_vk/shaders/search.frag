#version 450

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D uPrev;
layout(binding = 1) uniform sampler2D uNext;
layout(binding = 2) uniform sampler2D uPick;

layout(push_constant) uniform Push {
    vec2 step;
    float pickRange;
    float stride;
    float inset;
} pc;

void main() {
    vec2 idx = floor(vUv * 49.0);
    vec2 center = (texture(uPick, vec2(0.5)).rg * 2.0 - 1.0) * pc.pickRange;
    vec2 shift = center + (idx - 24.0) * pc.stride;
    float sad = 0.0;
    for (int y = 0; y < 9; ++y) {
        for (int x = 0; x < 16; ++x) {
            vec2 uv = pc.inset + (1.0 - 2.0 * pc.inset)
                    * (vec2(float(x), float(y)) + 0.5) / vec2(16.0, 9.0);
            sad += abs(texture(uPrev, uv).r - texture(uNext, uv + shift * pc.step).r);
        }
    }
    float packed = sad * (255.0 / 144.0);
    float coarse = floor(packed);
    fragColor = vec4((idx + 0.5) / 49.0, coarse / 255.0, packed - coarse);
}

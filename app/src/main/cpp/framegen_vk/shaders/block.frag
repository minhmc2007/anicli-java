#version 450

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D uSearch;

float full(vec4 s) {
    return floor(s.b * 255.0 + 0.5) + s.a;
}

void main() {
    vec2 cell = floor(vUv * 7.0) * 7.0;
    float best = 1e9;
    vec2 bestCell = vec2(24.0);
    for (int y = 0; y < 7; ++y) {
        for (int x = 0; x < 7; ++x) {
            vec4 s = texture(uSearch, (cell + vec2(float(x), float(y)) + 0.5) / 49.0);
            if (full(s) < best) {
                best = full(s);
                bestCell = floor(s.rg * 49.0);
            }
        }
    }
    fragColor = vec4((bestCell + 0.5) / 49.0, best / 255.0, 1.0);
}

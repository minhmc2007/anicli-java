#version 450

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D uPrev;
layout(binding = 1) uniform sampler2D uNext;
layout(binding = 2) uniform sampler2D uFlow;

layout(push_constant) uniform Push {
    vec2 lumaSize;
} pc;

vec2 decodeFlow(vec4 e) {
    return vec2(e.r * 255.0 + e.g, e.b * 255.0 + e.a) * (384.0 / 255.0) - 192.0;
}

void main() {
    vec2 p = vUv * pc.lumaSize;
    vec2 s = decodeFlow(texture(uFlow, vUv));
    float sad = 0.0;
    for (int j = -2; j <= 2; ++j) {
        for (int i = -2; i <= 2; ++i) {
            vec2 q = p + vec2(float(i), float(j));
            sad += abs(texture(uPrev, q / pc.lumaSize).r
                    - texture(uNext, (q + s) / pc.lumaSize).r);
        }
    }
    fragColor = vec4(sad / 25.0, 1.0, 1.0, 1.0);
}

#version 450

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D uSource;

layout(push_constant) uniform Push {
    vec2 texel;
} pc;

float luma(vec2 uv) {
    return dot(texture(uSource, uv).rgb, vec3(0.299, 0.587, 0.114));
}

void main() {
    float y = 0.25 * (luma(vUv + vec2(-pc.texel.x, -pc.texel.y))
            + luma(vUv + vec2(pc.texel.x, -pc.texel.y))
            + luma(vUv + vec2(-pc.texel.x, pc.texel.y))
            + luma(vUv + vec2(pc.texel.x, pc.texel.y)));
    fragColor = vec4(y, 0.0, 0.0, 1.0);
}

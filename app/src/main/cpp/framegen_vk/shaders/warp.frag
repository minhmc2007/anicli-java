#version 450

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D uPrev;
layout(binding = 1) uniform sampler2D uNext;
layout(binding = 2) uniform sampler2D uFlow;
layout(binding = 3) uniform sampler2D uSad;

layout(push_constant) uniform Push {
    vec2 step;
    vec2 bad;
    vec2 dis;
    float timestep;
    float disW;
} pc;

vec2 decodeFlow(vec4 e) {
    return vec2(e.r * 255.0 + e.g, e.b * 255.0 + e.a) * (384.0 / 255.0) - 192.0;
}

void main() {
    vec2 shift = decodeFlow(texture(uFlow, vUv));
    vec2 shiftStep = shift * 4.0 * pc.step;
    vec3 a = texture(uPrev, vUv - shiftStep * pc.timestep).rgb;
    vec3 b = texture(uNext, vUv + shiftStep * (1.0 - pc.timestep)).rgb;
    vec3 warped = mix(a, b, pc.timestep);
    vec3 nearest = pc.timestep < 0.5 ? texture(uPrev, vUv).rgb
                                     : texture(uNext, vUv).rgb;
    float bad = smoothstep(pc.bad.x, pc.bad.y, texture(uSad, vUv).r);
    float dis = abs(dot(a - b, vec3(0.299, 0.587, 0.114)));
    float occ = smoothstep(pc.dis.x, pc.dis.y, dis) * pc.disW;
    fragColor = vec4(mix(warped, nearest, max(bad, occ)), 1.0);
}

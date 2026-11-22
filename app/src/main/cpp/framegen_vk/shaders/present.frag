#version 450

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D uSource;

void main() {
    // Imported decoder slots keep GL's bottom-up rows, so flip on the way out.
    fragColor = vec4(texture(uSource, vec2(vUv.x, 1.0 - vUv.y)).rgb, 1.0);
}

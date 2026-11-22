#version 450

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D uSource;

void main() {
    fragColor = texture(uSource, vUv);
}

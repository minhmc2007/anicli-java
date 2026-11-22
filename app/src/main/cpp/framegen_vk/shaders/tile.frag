#version 450

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D uPrev;
layout(binding = 1) uniform sampler2D uNext;
layout(binding = 2) uniform sampler2D uFlow;
layout(binding = 3) uniform sampler2D uPrevTile;
layout(binding = 4) uniform sampler2D uPrevGlobal;

layout(push_constant) uniform Push {
    vec2 lumaSize;
    vec2 tileSize;
    vec2 grid;
    float reg;
} pc;

vec2 decodeFlow(vec4 e) {
    return vec2(e.r * 255.0 + e.g, e.b * 255.0 + e.a) * (384.0 / 255.0) - 192.0;
}

float eval(vec2 origin, vec2 shift) {
    float sad = 0.0;
    for (int j = 0; j < 5; ++j) {
        for (int i = 0; i < 5; ++i) {
            vec2 uv = origin + pc.tileSize * (0.2 + (vec2(float(i), float(j)) + 0.5)
                    * 0.6 / 5.0);
            sad += abs(texture(uPrev, uv / pc.lumaSize).r
                    - texture(uNext, (uv + shift) / pc.lumaSize).r);
        }
    }
    return sad * (255.0 / 25.0);
}

void main() {
    vec2 cell = floor(vUv * pc.grid);
    vec2 origin = cell * pc.tileSize;
    vec2 global = decodeFlow(texture(uFlow, vec2(0.5)));
    // The tile keeps its residual over the global vector from the previous pair,
    // so a character can hold its own velocity instead of the camera's.
    vec2 pred = clamp(global
            + decodeFlow(texture(uPrevTile, (cell + 0.5) / pc.grid))
            - decodeFlow(texture(uPrevGlobal, vec2(0.5))),
            global - vec2(96.0), global + vec2(96.0));
    float best = 1e9;
    vec2 at = pred;
    // +-24 luma at stride 4, then +-4 at stride 2, then +-1 at stride 1.
    for (int dy = -6; dy <= 6; ++dy) {
        for (int dx = -6; dx <= 6; ++dx) {
            vec2 s = pred + vec2(float(dx), float(dy)) * 4.0;
            float score = eval(origin, s) + pc.reg * dot(s - pred, s - pred);
            if (score < best) {
                best = score;
                at = s;
            }
        }
    }
    best = 1e9;
    vec2 coarse = at;
    for (int dy = -2; dy <= 2; ++dy) {
        for (int dx = -2; dx <= 2; ++dx) {
            vec2 s = coarse + vec2(float(dx), float(dy)) * 2.0;
            float score = eval(origin, s) + pc.reg * dot(s - pred, s - pred);
            if (score < best) {
                best = score;
                at = s;
            }
        }
    }
    best = 1e9;
    vec2 mid = at;
    for (int dy = -1; dy <= 1; ++dy) {
        for (int dx = -1; dx <= 1; ++dx) {
            vec2 s = mid + vec2(float(dx), float(dy));
            float score = eval(origin, s) + pc.reg * dot(s - pred, s - pred);
            if (score < best) {
                best = score;
                at = s;
            }
        }
    }
    float c0 = eval(origin, at);
    float l = eval(origin, at + vec2(-1.0, 0.0));
    float r = eval(origin, at + vec2(1.0, 0.0));
    float u = eval(origin, at + vec2(0.0, -1.0));
    float d = eval(origin, at + vec2(0.0, 1.0));
    float ax = l - 2.0 * c0 + r;
    float ay = u - 2.0 * c0 + d;
    vec2 frac = vec2(ax > 1e-5 ? clamp(0.5 * (l - r) / ax, -0.5, 0.5) : 0.0,
                     ay > 1e-5 ? clamp(0.5 * (u - d) / ay, -0.5, 0.5) : 0.0);
    vec2 e = clamp((at + frac + 192.0) * (255.0 / 384.0), 0.0, 255.0);
    fragColor = vec4(floor(e.x) / 255.0, fract(e.x),
                     floor(e.y) / 255.0, fract(e.y));
}

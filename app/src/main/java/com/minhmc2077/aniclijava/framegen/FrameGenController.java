package com.minhmc2077.aniclijava.framegen;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.minhmc2077.aniclijava.util.Settings;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Owns the video SurfaceView: decodes into a two-frame ring, estimates a per-pixel
 * motion field between the pair, and presents a warp at the display rate.
 */
public final class FrameGenController implements Choreographer.FrameCallback, SurfaceHolder.Callback {
    public interface Listener {
        void onInputSurface(@NonNull Surface surface);

        void onFailure(@NonNull String reason);
    }

    private static final String VERTEX_SHADER =
            "uniform mat4 uTexMatrix;\n"
                    + "attribute vec4 aPosition;\n"
                    + "attribute vec2 aTexCoord;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main() {\n"
                    + "    gl_Position = aPosition;\n"
                    + "    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n"
                    + "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "uniform samplerExternalOES uTexture;\n"
                    + "void main() {\n"
                    + "    gl_FragColor = texture2D(uTexture, vTexCoord);\n"
                    + "}\n";

    private static final String SIMPLE_VERTEX_SHADER =
            "attribute vec4 aPosition;\n"
                    + "attribute vec2 aTexCoord;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main() {\n"
                    + "    gl_Position = aPosition;\n"
                    + "    vTexCoord = aTexCoord;\n"
                    + "}\n";

    private static final String SIMPLE_FRAGMENT_SHADER =
            "precision mediump float;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "uniform sampler2D uTexture;\n"
                    + "void main() {\n"
                    + "    gl_FragColor = texture2D(uTexture, vTexCoord);\n"
                    + "}\n";

    private static final String GLSL_HEADER =
            "#version 300 es\n"
                    + "precision highp float;\n"
                    + "in vec2 vTexCoord;\n"
                    + "out vec4 fragColor;\n";

    private static final String GLSL_VERTEX_SHADER =
            "#version 300 es\n"
                    + "in vec4 aPosition;\n"
                    + "in vec2 aTexCoord;\n"
                    + "out vec2 vTexCoord;\n"
                    + "void main() {\n"
                    + "    gl_Position = aPosition;\n"
                    + "    vTexCoord = aTexCoord;\n"
                    + "}\n";

    private static final String LUMA_FRAGMENT_SHADER = GLSL_HEADER
            + "uniform sampler2D uSource;\n"
            + "uniform vec2 uTexel;\n"
            + "float luma(vec2 uv) {\n"
            + "    vec3 c = texture(uSource, uv).rgb;\n"
            + "    return dot(c, vec3(0.299, 0.587, 0.114));\n"
            + "}\n"
            + "void main() {\n"
            + "    float y = 0.25 * (luma(vTexCoord + vec2(-uTexel.x, -uTexel.y))\n"
            + "            + luma(vTexCoord + vec2(uTexel.x, -uTexel.y))\n"
            + "            + luma(vTexCoord + vec2(-uTexel.x, uTexel.y))\n"
            + "            + luma(vTexCoord + vec2(uTexel.x, uTexel.y)));\n"
            + "    fragColor = vec4(y, 0.0, 0.0, 1.0);\n"
            + "}\n";

    private static final String SEARCH_FRAGMENT_SHADER = GLSL_HEADER
            + "uniform sampler2D uPrev;\n"
            + "uniform sampler2D uNext;\n"
            + "uniform sampler2D uPick;\n"
            + "uniform vec2 uStep;\n"
            + "uniform float uPickRange;\n"
            + "uniform float uStride;\n"
            + "uniform float uInset;\n"
            + "void main() {\n"
            + "    vec2 idx = floor(vTexCoord * 49.0);\n"
            + "    vec2 center = (texture(uPick, vec2(0.5)).rg * 2.0 - 1.0) * uPickRange;\n"
            + "    vec2 shift = center + (idx - 24.0) * uStride;\n"
            + "    float sad = 0.0;\n"
            + "    for (int y = 0; y < 9; ++y) {\n"
            + "        for (int x = 0; x < 16; ++x) {\n"
            + "            vec2 uv = uInset + (1.0 - 2.0 * uInset)\n"
            + "                    * (vec2(float(x), float(y)) + 0.5) / vec2(16.0, 9.0);\n"
            + "            sad += abs(texture(uPrev, uv).r - texture(uNext, uv + shift * uStep).r);\n"
            + "        }\n"
            + "    }\n"
            + "    float packed = sad * (255.0 / 144.0);\n"
            + "    float coarse = floor(packed);\n"
            + "    fragColor = vec4((idx + 0.5) / 49.0, coarse / 255.0, packed - coarse);\n"
            + "}\n";

    private static final String BLOCK_FRAGMENT_SHADER = GLSL_HEADER
            + "uniform sampler2D uSearch;\n"
            + "float full(vec4 s) {\n"
            + "    return floor(s.b * 255.0 + 0.5) + s.a;\n"
            + "}\n"
            + "void main() {\n"
            + "    vec2 cell = floor(vTexCoord * 7.0) * 7.0;\n"
            + "    float best = 1e9;\n"
            + "    vec2 bestCell = vec2(24.0);\n"
            + "    for (int y = 0; y < 7; ++y) {\n"
            + "        for (int x = 0; x < 7; ++x) {\n"
            + "            vec4 s = texture(uSearch, (cell + vec2(float(x), float(y)) + 0.5) / 49.0);\n"
            + "            if (full(s) < best) {\n"
            + "                best = full(s);\n"
            + "                bestCell = floor(s.rg * 49.0);\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "    fragColor = vec4((bestCell + 0.5) / 49.0, best / 255.0, 1.0);\n"
            + "}\n";

    private static final String PICK_FRAGMENT_SHADER = GLSL_HEADER
            + "uniform sampler2D uBlocks;\n"
            + "uniform float uStride;\n"
            + "uniform float uRange;\n"
            + "void main() {\n"
            + "    float best = 2.0;\n"
            + "    vec2 bestCell = vec2(24.0);\n"
            + "    for (int y = 0; y < 7; ++y) {\n"
            + "        for (int x = 0; x < 7; ++x) {\n"
            + "            vec4 b = texture(uBlocks, (vec2(float(x), float(y)) + 0.5) / 7.0);\n"
            + "            if (b.b < best) {\n"
            + "                best = b.b;\n"
            + "                bestCell = floor(b.rg * 49.0);\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "    vec2 shift = (bestCell - 24.0) * uStride;\n"
            + "    fragColor = vec4((shift + uRange) / (2.0 * uRange), best, 1.0);\n"
            + "}\n";

    private static final String REFINE_FRAGMENT_SHADER = GLSL_HEADER
            + "uniform sampler2D uSearch;\n"
            + "uniform sampler2D uBlocks;\n"
            + "uniform sampler2D uPick;\n"
            + "uniform float uPickRange;\n"
            + "uniform float uStride;\n"
            + "float sad(vec2 cell) {\n"
            + "    vec4 t = texture(uSearch, (cell + 0.5) / 49.0);\n"
            + "    return floor(t.b * 255.0 + 0.5) + t.a;\n"
            + "}\n"
            + "void main() {\n"
            + "    float best = 2.0;\n"
            + "    vec2 bestCell = vec2(24.0);\n"
            + "    for (int y = 0; y < 7; ++y) {\n"
            + "        for (int x = 0; x < 7; ++x) {\n"
            + "            vec4 b = texture(uBlocks, (vec2(float(x), float(y)) + 0.5) / 7.0);\n"
            + "            if (b.b < best) {\n"
            + "                best = b.b;\n"
            + "                bestCell = floor(b.rg * 49.0);\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "    float c0 = sad(bestCell);\n"
            + "    float l = sad(bestCell + vec2(-1.0, 0.0));\n"
            + "    float r = sad(bestCell + vec2(1.0, 0.0));\n"
            + "    float u = sad(bestCell + vec2(0.0, -1.0));\n"
            + "    float d = sad(bestCell + vec2(0.0, 1.0));\n"
            + "    float ax = l - 2.0 * c0 + r;\n"
            + "    float ay = u - 2.0 * c0 + d;\n"
            + "    vec2 frac = vec2(ax > 1e-5 ? clamp(0.5 * (l - r) / ax, -0.5, 0.5) : 0.0,\n"
            + "                     ay > 1e-5 ? clamp(0.5 * (u - d) / ay, -0.5, 0.5) : 0.0);\n"
            + "    vec2 center = (texture(uPick, vec2(0.5)).rg * 2.0 - 1.0) * uPickRange;\n"
            + "    vec2 shift = center + (bestCell - 24.0 + frac) * uStride;\n"
            + "    vec2 e = (shift + 192.0) * (255.0 / 384.0);\n"
            + "    fragColor = vec4(floor(e.x) / 255.0, e.x - floor(e.x),\n"
            + "                     floor(e.y) / 255.0, e.y - floor(e.y));\n"
            + "}\n";

    private static final String FLOW_DECODE_GLSL =
            "vec2 decodeFlow(vec4 e) {\n"
                    + "    return vec2(e.r * 255.0 + e.g, e.b * 255.0 + e.a)\n"
                    + "            * (384.0 / 255.0) - 192.0;\n"
                    + "}\n";

    private static final String WARP_FRAGMENT_SHADER = GLSL_HEADER
            + FLOW_DECODE_GLSL
            + "uniform sampler2D uPrev;\n"
            + "uniform sampler2D uNext;\n"
            + "uniform sampler2D uFlow;\n"
            + "uniform sampler2D uSad;\n"
            + "uniform float uTimestep;\n"
            + "uniform vec2 uStep;\n"
            + "uniform vec2 uBad;\n"
            + "uniform vec2 uDis;\n"
            + "uniform float uDisW;\n"
            + "void main() {\n"
            + "    vec2 shift = decodeFlow(texture(uFlow, vTexCoord));\n"
            + "    vec2 shiftStep = shift * 4.0 * uStep;\n"
            + "    vec3 a = texture(uPrev, vTexCoord - shiftStep * uTimestep).rgb;\n"
            + "    vec3 b = texture(uNext, vTexCoord + shiftStep * (1.0 - uTimestep)).rgb;\n"
            + "    vec3 warped = mix(a, b, uTimestep);\n"
            + "    vec3 nearest = uTimestep < 0.5 ? texture(uPrev, vTexCoord).rgb\n"
            + "                                   : texture(uNext, vTexCoord).rgb;\n"
            + "    float bad = smoothstep(uBad.x, uBad.y, texture(uSad, vTexCoord).r);\n"
            + "    float dis = abs(dot(a - b, vec3(0.299, 0.587, 0.114)));\n"
            + "    float occ = smoothstep(uDis.x, uDis.y, dis) * uDisW;\n"
            + "    fragColor = vec4(mix(warped, nearest, max(bad, occ)), 1.0);\n"
            + "}\n";

    private static final String COPY_FRAGMENT_SHADER = GLSL_HEADER
            + "uniform sampler2D uTex;\n"
            + "void main() {\n"
            + "    fragColor = texture(uTex, vTexCoord);\n"
            + "}\n";

    private static final String TILE_SEARCH_FRAGMENT_SHADER = GLSL_HEADER
            + FLOW_DECODE_GLSL
            + "uniform sampler2D uPrev;\n"
            + "uniform sampler2D uNext;\n"
            + "uniform sampler2D uFlow;\n"
            + "uniform sampler2D uPrevTile;\n"
            + "uniform sampler2D uPrevGlobal;\n"
            + "uniform vec2 uLumaSize;\n"
            + "uniform vec2 uTileSize;\n"
            + "uniform vec2 uGrid;\n"
            + "uniform float uReg;\n"
            + "float eval(vec2 origin, vec2 shift) {\n"
            + "    float sad = 0.0;\n"
            + "    for (int j = 0; j < 5; ++j) {\n"
            + "        for (int i = 0; i < 5; ++i) {\n"
            + "            vec2 uv = origin + uTileSize * (0.2 + (vec2(float(i), float(j)) + 0.5)\n"
            + "                    * 0.6 / 5.0);\n"
            + "            sad += abs(texture(uPrev, uv / uLumaSize).r\n"
            + "                    - texture(uNext, (uv + shift) / uLumaSize).r);\n"
            + "        }\n"
            + "    }\n"
            + "    return sad * (255.0 / 25.0);\n"
            + "}\n"
            + "void main() {\n"
            + "    vec2 cell = floor(vTexCoord * uGrid);\n"
            + "    vec2 origin = cell * uTileSize;\n"
            + "    vec2 global = decodeFlow(texture(uFlow, vec2(0.5)));\n"
            + "    // The tile keeps its residual over the global vector from the previous pair,\n"
            + "    // so a character can hold its own velocity instead of the camera's.\n"
            + "    vec2 pred = clamp(global\n"
            + "            + decodeFlow(texture(uPrevTile, (cell + 0.5) / uGrid))\n"
            + "            - decodeFlow(texture(uPrevGlobal, vec2(0.5))),\n"
            + "            global - vec2(96.0), global + vec2(96.0));\n"
            + "    float best = 1e9;\n"
            + "    vec2 at = pred;\n"
            + "    // +-24 luma at stride 4, then +-4 at stride 2, then +-1 at stride 1.\n"
            + "    for (int dy = -6; dy <= 6; ++dy) {\n"
            + "        for (int dx = -6; dx <= 6; ++dx) {\n"
            + "            vec2 s = pred + vec2(float(dx), float(dy)) * 4.0;\n"
            + "            float score = eval(origin, s) + uReg * dot(s - pred, s - pred);\n"
            + "            if (score < best) {\n"
            + "                best = score;\n"
            + "                at = s;\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "    best = 1e9;\n"
            + "    vec2 coarse = at;\n"
            + "    for (int dy = -2; dy <= 2; ++dy) {\n"
            + "        for (int dx = -2; dx <= 2; ++dx) {\n"
            + "            vec2 s = coarse + vec2(float(dx), float(dy)) * 2.0;\n"
            + "            float score = eval(origin, s) + uReg * dot(s - pred, s - pred);\n"
            + "            if (score < best) {\n"
            + "                best = score;\n"
            + "                at = s;\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "    best = 1e9;\n"
            + "    vec2 mid = at;\n"
            + "    for (int dy = -1; dy <= 1; ++dy) {\n"
            + "        for (int dx = -1; dx <= 1; ++dx) {\n"
            + "            vec2 s = mid + vec2(float(dx), float(dy));\n"
            + "            float score = eval(origin, s) + uReg * dot(s - pred, s - pred);\n"
            + "            if (score < best) {\n"
            + "                best = score;\n"
            + "                at = s;\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "    float c0 = eval(origin, at);\n"
            + "    float l = eval(origin, at + vec2(-1.0, 0.0));\n"
            + "    float r = eval(origin, at + vec2(1.0, 0.0));\n"
            + "    float u = eval(origin, at + vec2(0.0, -1.0));\n"
            + "    float d = eval(origin, at + vec2(0.0, 1.0));\n"
            + "    float ax = l - 2.0 * c0 + r;\n"
            + "    float ay = u - 2.0 * c0 + d;\n"
            + "    vec2 frac = vec2(ax > 1e-5 ? clamp(0.5 * (l - r) / ax, -0.5, 0.5) : 0.0,\n"
            + "                     ay > 1e-5 ? clamp(0.5 * (u - d) / ay, -0.5, 0.5) : 0.0);\n"
            + "    vec2 e = clamp((at + frac + 192.0) * (255.0 / 384.0), 0.0, 255.0);\n"
            + "    fragColor = vec4(floor(e.x) / 255.0, fract(e.x),\n"
            + "                     floor(e.y) / 255.0, fract(e.y));\n"
            + "}\n";

    private static final String PIXEL_FLOW_FRAGMENT_SHADER = GLSL_HEADER
            + FLOW_DECODE_GLSL
            + "uniform sampler2D uPrev;\n"
            + "uniform sampler2D uNext;\n"
            + "uniform sampler2D uTiles;\n"
            + "uniform vec2 uLumaSize;\n"
            + "uniform vec2 uTileSize;\n"
            + "uniform vec2 uGrid;\n"
            + "float box(vec2 p, vec2 s) {\n"
            + "    float sad = 0.0;\n"
            + "    for (int j = -1; j <= 1; ++j) {\n"
            + "        for (int i = -1; i <= 1; ++i) {\n"
            + "            vec2 q = p + vec2(float(i), float(j));\n"
            + "            sad += abs(texture(uPrev, q / uLumaSize).r\n"
            + "                    - texture(uNext, (q + s) / uLumaSize).r);\n"
            + "        }\n"
            + "    }\n"
            + "    return sad;\n"
            + "}\n"
            + "vec2 tileFlow(vec2 cell) {\n"
            + "    return decodeFlow(texture(uTiles, (cell + 0.5) / uGrid));\n"
            + "}\n"
            + "void main() {\n"
            + "    vec2 p = vTexCoord * uLumaSize;\n"
            + "    vec2 t = p / uTileSize - 0.5;\n"
            + "    vec2 base = floor(t);\n"
            + "    vec2 f = t - base;\n"
            + "    vec2 v00 = tileFlow(base);\n"
            + "    vec2 v10 = tileFlow(base + vec2(1.0, 0.0));\n"
            + "    vec2 v01 = tileFlow(base + vec2(0.0, 1.0));\n"
            + "    vec2 v11 = tileFlow(base + vec2(1.0, 1.0));\n"
            + "    float s00 = box(p, v00);\n"
            + "    float s10 = box(p, v10);\n"
            + "    float s01 = box(p, v01);\n"
            + "    float s11 = box(p, v11);\n"
            + "    vec2 v = v00;\n"
            + "    float s = s00;\n"
            + "    if (s10 < s) {\n"
            + "        v = v10;\n"
            + "        s = s10;\n"
            + "    }\n"
            + "    if (s01 < s) {\n"
            + "        v = v01;\n"
            + "        s = s01;\n"
            + "    }\n"
            + "    if (s11 < s) {\n"
            + "        v = v11;\n"
            + "        s = s11;\n"
            + "    }\n"
            + "    // Interpolate only while every neighbour fits the pixel equally well. Where they\n"
            + "    // disagree one of them is the silhouette's real motion, so take the winner instead\n"
            + "    // of mixing two objects into a rubber edge.\n"
            + "    float lo = min(min(s00, s10), min(s01, s11));\n"
            + "    float hi = max(max(s00, s10), max(s01, s11));\n"
            + "    vec2 blend = v00 * (1.0 - f.x) * (1.0 - f.y)\n"
            + "            + v10 * f.x * (1.0 - f.y)\n"
            + "            + v01 * (1.0 - f.x) * f.y\n"
            + "            + v11 * f.x * f.y;\n"
            + "    v = mix(blend, v, smoothstep(0.5, 2.5, hi - lo));\n"
            + "    float c0 = box(p, v);\n"
            + "    float l = box(p, v + vec2(-1.0, 0.0));\n"
            + "    float r = box(p, v + vec2(1.0, 0.0));\n"
            + "    float u = box(p, v + vec2(0.0, -1.0));\n"
            + "    float d = box(p, v + vec2(0.0, 1.0));\n"
            + "    float ax = l - 2.0 * c0 + r;\n"
            + "    float ay = u - 2.0 * c0 + d;\n"
            + "    vec2 frac = vec2(ax > 1e-5 ? clamp(0.5 * (l - r) / ax, -0.5, 0.5) : 0.0,\n"
            + "                     ay > 1e-5 ? clamp(0.5 * (u - d) / ay, -0.5, 0.5) : 0.0);\n"
            + "    vec2 e = clamp((v + frac + 192.0) * (255.0 / 384.0), 0.0, 255.0);\n"
            + "    fragColor = vec4(floor(e.x) / 255.0, fract(e.x),\n"
            + "                     floor(e.y) / 255.0, fract(e.y));\n"
            + "}\n";

    private static final String PIXEL_SAD_FRAGMENT_SHADER = GLSL_HEADER
            + FLOW_DECODE_GLSL
            + "uniform sampler2D uPrev;\n"
            + "uniform sampler2D uNext;\n"
            + "uniform sampler2D uFlow;\n"
            + "uniform vec2 uLumaSize;\n"
            + "void main() {\n"
            + "    vec2 p = vTexCoord * uLumaSize;\n"
            + "    vec2 s = decodeFlow(texture(uFlow, vTexCoord));\n"
            + "    float sad = 0.0;\n"
            + "    for (int j = -2; j <= 2; ++j) {\n"
            + "        for (int i = -2; i <= 2; ++i) {\n"
            + "            vec2 q = p + vec2(float(i), float(j));\n"
            + "            sad += abs(texture(uPrev, q / uLumaSize).r\n"
            + "                    - texture(uNext, (q + s) / uLumaSize).r);\n"
            + "        }\n"
            + "    }\n"
            + "    fragColor = vec4(sad / 25.0, 1.0, 1.0, 1.0);\n"
            + "}\n";

    private static final float[] QUAD = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
    private static final float[] TEX_COORDS = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};

    private final SurfaceView view;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread thread;
    private final Handler handler;
    private final FloatBuffer quadBuffer = buffer(QUAD);
    private final FloatBuffer texCoordBuffer = buffer(TEX_COORDS);
    private final float[] texMatrix = new float[16];

    private Choreographer choreographer;
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglPbuffer = EGL14.EGL_NO_SURFACE;
    private EGLSurface eglWindow = EGL14.EGL_NO_SURFACE;
    private EGLConfig eglConfig;

    private int program;
    private int positionHandle;
    private int texCoordHandle;
    private int texMatrixHandle;
    private int textureHandle;
    private int textureId;
    private int surfaceWidth;
    private int surfaceHeight;

    private SurfaceTexture inputTexture;
    private Surface inputSurface;
    private boolean glReady;
    private boolean released;
    private boolean hasFrame;
    private boolean newFrame;
    private boolean paused;
    private int targetFps = 60;
    private long nextDrawNs;

    // Neural engine selection (Settings.FRAME_GEN_ENGINE_*). "gl" keeps the
    // shipped shader pipeline untouched; "fastflownet" replaces only the flow
    // source: ncnn estimates the pair flow and the GL occlusion-aware
    // warp/blend still renders the frame.
    private String engine = Settings.FRAME_GEN_ENGINE_GL;
    private NcnnFrameEngine ncnnEngine;
    private float[] ncnnFlow;
    private float[] ncnnSad;
    private HandlerThread ncnnThread;
    private Handler ncnnHandler;
    private boolean ncnnBusy;
    private boolean busyWarned;
    private long busyStartNs;

    private volatile TextView debugView;
    private String deviceName = "";
    private float inferLastMs;
    private float inferAvgMs;
    private float readbackMs;
    private long inferFail;
    private long inferStale;
    private int netWidth;
    private int netHeight;
    private long statsStartNs;
    private long winDecoded;
    private long winDrawn;
    private long winWarp;
    private long winSkip;
    private ByteBuffer readbackA;
    private ByteBuffer readbackC;
    private ByteBuffer flowUpload;
    private ByteBuffer sadUpload;

    private static final int RING = 2;
    private static final int SEARCH_RANGE = 24;
    private static final int SEARCH_SIZE = SEARCH_RANGE * 2 + 1;
    private static final int MOTION_SCALE = 4;
    // Coarse pass spans +-48 luma px (2 luma px steps), fine pass recentres on it and
    // resolves 0.5 luma px before the parabola. Total reach +-60 luma px = +-240 full px.
    private static final float COARSE_STRIDE = 2f;
    private static final float COARSE_RANGE = SEARCH_RANGE * COARSE_STRIDE;
    private static final float COARSE_INSET = 0.20f;
    private static final float FINE_STRIDE = 0.5f;
    private static final float FINE_INSET = 0.24f;

    // Per-tile motion field: a wide three-level search per tile around a temporal
    // predictor, then a per-pixel winner-take-all pass that resolves silhouettes.
    private static final int TILE_TARGET = 16;      // luma px per tile
    private static final float TILE_REG = 0.002f;   // tie-break toward the predictor
    private static final float BAD_LO = 0.05f;
    private static final float BAD_HI = 0.14f;
    private static final float DIS_LO = 0.03f;
    private static final float DIS_HI = 0.10f;
    private static final float DIS_W = 0.85f;

    private int lumaProgram;
    private int searchProgram;
    private int blockProgram;
    private int pickProgram;
    private int refineProgram;
    private int tileSearchProgram;
    private int pixelFlowProgram;
    private int pixelSadProgram;
    private int warpProgram;
    private int lumaSourceHandle;
    private int lumaTexelHandle;
    private int searchPrevHandle;
    private int searchNextHandle;
    private int searchPickHandle;
    private int searchPickRangeHandle;
    private int searchStrideHandle;
    private int searchInsetHandle;
    private int searchStepHandle;
    private int blockSearchHandle;
    private int pickBlocksHandle;
    private int pickStrideHandle;
    private int pickRangeHandle;
    private int refineSearchHandle;
    private int refineBlocksHandle;
    private int refinePickHandle;
    private int refinePickRangeHandle;
    private int refineStrideHandle;
    private int tilePrevHandle;
    private int tileNextHandle;
    private int tileFlowHandle;
    private int tilePrevTileHandle;
    private int tilePrevGlobalHandle;
    private int tileLumaSizeHandle;
    private int tileTileSizeHandle;
    private int tileGridHandle;
    private int tileRegHandle;
    private int pixelPrevHandle;
    private int pixelNextHandle;
    private int pixelTilesHandle;
    private int pixelLumaSizeHandle;
    private int pixelTileSizeHandle;
    private int pixelGridHandle;
    private int pixelSadPrevHandle;
    private int pixelSadNextHandle;
    private int pixelSadFlowHandle;
    private int pixelSadLumaSizeHandle;
    private int warpPrevHandle;
    private int warpNextHandle;
    private int warpFlowHandle;
    private int warpSadHandle;
    private int warpTimestepHandle;
    private int warpStepHandle;
    private int warpBadHandle;
    private int warpDisHandle;
    private int warpDisWHandle;
    private final int[] lumaTexture = new int[RING];
    private int searchTexture;
    private int blockTexture;
    private int pickTexture;
    private int flowTexture;
    private int tileTexture;
    private int pixelFlowTexture;
    private int pixelSadTexture;
    private int prevTileTexture;
    private int prevGlobalTexture;
    private int tileGridW;
    private int tileGridH;
    private float tileW;
    private float tileH;
    private int lumaWidth;
    private int lumaHeight;
    private int flowPrev = -1;
    private int flowNext = -1;
    private boolean flowValid;

    private int simpleProgram;
    private int copyProgram;
    private int copyTexHandle;
    private int simplePositionHandle;
    private int simpleTexCoordHandle;
    private int simpleTextureHandle;
    private final int[] frameTexture = new int[RING];
    private final int[] frameFbo = new int[1];
    private int writeIndex = RING - 1;
    private int frameCount;
    private int videoWidth;
    private int videoHeight;
    private long intervalNs = 41_666_667L;
    private long lastPtsNs;
    private long pairStartNs;

    public FrameGenController(@NonNull SurfaceView view, @NonNull Listener listener) {
        this.view = view;
        this.listener = listener;
        engine = NcnnFrameEngine.modelBundled(view.getContext())
                ? Settings.frameGenEngine(view.getContext())
                : Settings.FRAME_GEN_ENGINE_GL;
        thread = new HandlerThread("framegen");
        thread.start();
        handler = new Handler(thread.getLooper());
        if (!Settings.FRAME_GEN_ENGINE_GL.equals(engine)) {
            ncnnThread = new HandlerThread("ffn");
            ncnnThread.start();
            ncnnHandler = new Handler(ncnnThread.getLooper());
        }
        view.getHolder().addCallback(this);
    }

    public void start() {
        handler.post(this::initGl);
    }

    public void setPaused(boolean value) {
        handler.post(() -> paused = value);
    }

    public void setTargetFps(int fps) {
        handler.post(() -> targetFps = fps >= 120 ? 120 : 60);
    }

    public void setDebugOverlay(@NonNull TextView debug) {
        mainHandler.post(() -> {
            debugView = debug;
            debug.setVisibility(View.VISIBLE);
        });
        handler.post(() -> statsStartNs = 0);
    }

    public void release() {
        view.getHolder().removeCallback(this);
        CountDownLatch detached = new CountDownLatch(1);
        if (!handler.post(() -> {
            released = true;
            if (choreographer != null) {
                choreographer.removeFrameCallback(this);
            }
            if (debugView != null) {
                mainHandler.post(() -> debugView.setVisibility(View.GONE));
            }
            destroyGl();
            if (ncnnHandler != null) {
                ncnnHandler.post(() -> {
                    if (ncnnEngine != null) {
                        ncnnEngine.close();
                        ncnnEngine = null;
                    }
                    ncnnThread.quitSafely();
                });
            }
            detached.countDown();
            thread.quitSafely();
        })) {
            return;
        }
        try {
            detached.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        handler.post(this::createWindowSurface);
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        handler.post(() -> {
            surfaceWidth = width;
            surfaceHeight = height;
        });
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        // The framework recycles the surface as soon as this returns, so wait for the
        // render thread to detach it instead of queueing the teardown behind a swap.
        CountDownLatch detached = new CountDownLatch(1);
        if (!handler.post(() -> {
            destroyWindowSurface();
            detached.countDown();
        })) {
            return;
        }
        try {
            detached.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private void initGl() {
        if (released) {
            return;
        }
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            fail("eglGetDisplay");
            return;
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            fail("eglInitialize");
            return;
        }

        int[] configAttribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, count, 0)
                || count[0] == 0) {
            fail("eglChooseConfig");
            return;
        }
        eglConfig = configs[0];

        int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
                contextAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            fail("eglCreateContext");
            return;
        }

        int[] pbufferAttribs = {
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
        };
        eglPbuffer = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0);
        if (eglPbuffer == EGL14.EGL_NO_SURFACE
                || !EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext)) {
            fail("eglPbufferSurface");
            return;
        }

        if (!buildProgram()) {
            fail("shader");
            return;
        }
        if (!buildSimpleProgram()) {
            fail("shader");
            return;
        }
        if (!buildMotionPrograms()) {
            fail("shader");
            return;
        }
        GLES20.glGenFramebuffers(1, frameFbo, 0);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        inputTexture = new SurfaceTexture(textureId);
        inputTexture.setOnFrameAvailableListener(texture -> onFrameAvailable(), handler);
        inputSurface = new Surface(inputTexture);
        Matrix.setIdentityM(texMatrix, 0);
        glReady = true;
        if (videoWidth > 0 && videoHeight > 0) {
            resizeFrames(videoWidth, videoHeight);
        }

        mainHandler.post(() -> {
            if (!released) {
                listener.onInputSurface(inputSurface);
            }
        });

        if (view.getHolder().getSurface() != null
                && view.getHolder().getSurface().isValid()) {
            createWindowSurface();
        }

        choreographer = Choreographer.getInstance();
        choreographer.postFrameCallback(this);
    }

    private boolean buildProgram() {
        program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (program == 0) {
            return false;
        }
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord");
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix");
        textureHandle = GLES20.glGetUniformLocation(program, "uTexture");
        return true;
    }

    private boolean buildSimpleProgram() {
        simpleProgram = linkProgram(SIMPLE_VERTEX_SHADER, SIMPLE_FRAGMENT_SHADER);
        copyProgram = linkProgram(GLSL_VERTEX_SHADER, COPY_FRAGMENT_SHADER);
        if (simpleProgram == 0 || copyProgram == 0) {
            return false;
        }
        simplePositionHandle = GLES20.glGetAttribLocation(simpleProgram, "aPosition");
        simpleTexCoordHandle = GLES20.glGetAttribLocation(simpleProgram, "aTexCoord");
        simpleTextureHandle = GLES20.glGetUniformLocation(simpleProgram, "uTexture");
        copyTexHandle = GLES20.glGetUniformLocation(copyProgram, "uTex");
        return true;
    }

    private boolean buildMotionPrograms() {
        lumaProgram = linkProgram(GLSL_VERTEX_SHADER, LUMA_FRAGMENT_SHADER);
        searchProgram = linkProgram(GLSL_VERTEX_SHADER, SEARCH_FRAGMENT_SHADER);
        blockProgram = linkProgram(GLSL_VERTEX_SHADER, BLOCK_FRAGMENT_SHADER);
        pickProgram = linkProgram(GLSL_VERTEX_SHADER, PICK_FRAGMENT_SHADER);
        refineProgram = linkProgram(GLSL_VERTEX_SHADER, REFINE_FRAGMENT_SHADER);
        tileSearchProgram = linkProgram(GLSL_VERTEX_SHADER, TILE_SEARCH_FRAGMENT_SHADER);
        pixelFlowProgram = linkProgram(GLSL_VERTEX_SHADER, PIXEL_FLOW_FRAGMENT_SHADER);
        pixelSadProgram = linkProgram(GLSL_VERTEX_SHADER, PIXEL_SAD_FRAGMENT_SHADER);
        warpProgram = linkProgram(GLSL_VERTEX_SHADER, WARP_FRAGMENT_SHADER);
        if (lumaProgram == 0 || searchProgram == 0 || blockProgram == 0
                || pickProgram == 0 || refineProgram == 0 || tileSearchProgram == 0
                || pixelFlowProgram == 0 || pixelSadProgram == 0 || warpProgram == 0) {
            return false;
        }
        lumaSourceHandle = uniform(lumaProgram, "uSource");
        lumaTexelHandle = uniform(lumaProgram, "uTexel");
        searchPrevHandle = uniform(searchProgram, "uPrev");
        searchNextHandle = uniform(searchProgram, "uNext");
        searchPickHandle = uniform(searchProgram, "uPick");
        searchPickRangeHandle = uniform(searchProgram, "uPickRange");
        searchStrideHandle = uniform(searchProgram, "uStride");
        searchInsetHandle = uniform(searchProgram, "uInset");
        searchStepHandle = uniform(searchProgram, "uStep");
        blockSearchHandle = uniform(blockProgram, "uSearch");
        pickBlocksHandle = uniform(pickProgram, "uBlocks");
        pickStrideHandle = uniform(pickProgram, "uStride");
        pickRangeHandle = uniform(pickProgram, "uRange");
        refineSearchHandle = uniform(refineProgram, "uSearch");
        refineBlocksHandle = uniform(refineProgram, "uBlocks");
        refinePickHandle = uniform(refineProgram, "uPick");
        refinePickRangeHandle = uniform(refineProgram, "uPickRange");
        refineStrideHandle = uniform(refineProgram, "uStride");
        tilePrevHandle = uniform(tileSearchProgram, "uPrev");
        tileNextHandle = uniform(tileSearchProgram, "uNext");
        tileFlowHandle = uniform(tileSearchProgram, "uFlow");
        tilePrevTileHandle = uniform(tileSearchProgram, "uPrevTile");
        tilePrevGlobalHandle = uniform(tileSearchProgram, "uPrevGlobal");
        tileLumaSizeHandle = uniform(tileSearchProgram, "uLumaSize");
        tileTileSizeHandle = uniform(tileSearchProgram, "uTileSize");
        tileGridHandle = uniform(tileSearchProgram, "uGrid");
        tileRegHandle = uniform(tileSearchProgram, "uReg");
        pixelPrevHandle = uniform(pixelFlowProgram, "uPrev");
        pixelNextHandle = uniform(pixelFlowProgram, "uNext");
        pixelTilesHandle = uniform(pixelFlowProgram, "uTiles");
        pixelLumaSizeHandle = uniform(pixelFlowProgram, "uLumaSize");
        pixelTileSizeHandle = uniform(pixelFlowProgram, "uTileSize");
        pixelGridHandle = uniform(pixelFlowProgram, "uGrid");
        pixelSadPrevHandle = uniform(pixelSadProgram, "uPrev");
        pixelSadNextHandle = uniform(pixelSadProgram, "uNext");
        pixelSadFlowHandle = uniform(pixelSadProgram, "uFlow");
        pixelSadLumaSizeHandle = uniform(pixelSadProgram, "uLumaSize");
        warpPrevHandle = uniform(warpProgram, "uPrev");
        warpNextHandle = uniform(warpProgram, "uNext");
        warpFlowHandle = uniform(warpProgram, "uFlow");
        warpSadHandle = uniform(warpProgram, "uSad");
        warpTimestepHandle = uniform(warpProgram, "uTimestep");
        warpStepHandle = uniform(warpProgram, "uStep");
        warpBadHandle = uniform(warpProgram, "uBad");
        warpDisHandle = uniform(warpProgram, "uDis");
        warpDisWHandle = uniform(warpProgram, "uDisW");
        return true;
    }

    private static int uniform(int program, String name) {
        return GLES20.glGetUniformLocation(program, name);
    }

    private int linkProgram(String vertexSource, String fragmentSource) {
        int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (vertex == 0 || fragment == 0) {
            return 0;
        }
        int linked = GLES20.glCreateProgram();
        GLES20.glBindAttribLocation(linked, 0, "aPosition");
        GLES20.glBindAttribLocation(linked, 1, "aTexCoord");
        GLES20.glAttachShader(linked, vertex);
        GLES20.glAttachShader(linked, fragment);
        GLES20.glLinkProgram(linked);
        int[] status = new int[1];
        GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        if (status[0] == 0) {
            GLES20.glDeleteProgram(linked);
            return 0;
        }
        return linked;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private void createWindowSurface() {
        if (!glReady || eglWindow != EGL14.EGL_NO_SURFACE || released) {
            return;
        }
        Surface surface = view.getHolder().getSurface();
        if (surface == null || !surface.isValid()) {
            return;
        }
        int[] attribs = {EGL14.EGL_NONE};
        eglWindow = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0);
        if (eglWindow == EGL14.EGL_NO_SURFACE) {
            fail("eglCreateWindowSurface");
            return;
        }
        int[] value = new int[1];
        EGL14.eglQuerySurface(eglDisplay, eglWindow, EGL14.EGL_WIDTH, value, 0);
        surfaceWidth = value[0];
        EGL14.eglQuerySurface(eglDisplay, eglWindow, EGL14.EGL_HEIGHT, value, 0);
        surfaceHeight = value[0];
        if (EGL14.eglMakeCurrent(eglDisplay, eglWindow, eglWindow, eglContext)) {
            EGL14.eglSwapInterval(eglDisplay, 0);
        }
        EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext);
        newFrame = true;
    }

    private void destroyWindowSurface() {
        if (eglWindow == EGL14.EGL_NO_SURFACE) {
            return;
        }
        EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext);
        EGL14.eglDestroySurface(eglDisplay, eglWindow);
        eglWindow = EGL14.EGL_NO_SURFACE;
    }

    private void destroyGl() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            return;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (inputTexture != null) {
            inputTexture.release();
            inputTexture = null;
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        if (simpleProgram != 0) {
            GLES20.glDeleteProgram(simpleProgram);
            simpleProgram = 0;
        }
        if (copyProgram != 0) {
            GLES20.glDeleteProgram(copyProgram);
            copyProgram = 0;
        }
        int[] programs = {lumaProgram, searchProgram, blockProgram, pickProgram, refineProgram,
                tileSearchProgram, pixelFlowProgram, pixelSadProgram, warpProgram};
        for (int id : programs) {
            if (id != 0) {
                GLES20.glDeleteProgram(id);
            }
        }
        lumaProgram = 0;
        searchProgram = 0;
        blockProgram = 0;
        pickProgram = 0;
        refineProgram = 0;
        tileSearchProgram = 0;
        pixelFlowProgram = 0;
        pixelSadProgram = 0;
        warpProgram = 0;
        if (frameTexture[0] != 0) {
            GLES20.glDeleteTextures(RING, frameTexture, 0);
            GLES20.glDeleteTextures(RING, lumaTexture, 0);
            GLES20.glDeleteTextures(1, new int[]{searchTexture}, 0);
            GLES20.glDeleteTextures(1, new int[]{blockTexture}, 0);
            GLES20.glDeleteTextures(1, new int[]{pickTexture}, 0);
            GLES20.glDeleteTextures(1, new int[]{flowTexture}, 0);
            GLES20.glDeleteTextures(5, new int[]{tileTexture, pixelFlowTexture, pixelSadTexture,
                    prevTileTexture, prevGlobalTexture}, 0);
        }
        if (frameFbo[0] != 0) {
            GLES20.glDeleteFramebuffers(1, frameFbo, 0);
            frameFbo[0] = 0;
        }
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
            textureId = 0;
        }
        if (eglWindow != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglWindow);
            eglWindow = EGL14.EGL_NO_SURFACE;
        }
        if (eglPbuffer != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(eglDisplay, eglPbuffer);
            eglPbuffer = EGL14.EGL_NO_SURFACE;
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            eglContext = EGL14.EGL_NO_CONTEXT;
        }
        EGL14.eglTerminate(eglDisplay);
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        glReady = false;
    }

    private void onFrameAvailable() {
        if (released || inputTexture == null) {
            return;
        }
        try {
            inputTexture.updateTexImage();
            inputTexture.getTransformMatrix(texMatrix);
        } catch (RuntimeException error) {
            return;
        }
        long pts = inputTexture.getTimestamp();
        if (pts > 0) {
            trackTiming(pts);
        }
        if (pts > 0 && videoWidth > 0 && videoHeight > 0) {
            winDecoded++;
            writeIndex = (writeIndex + 1) % RING;
            copyToFrame(writeIndex);
            if (frameCount < RING) {
                frameCount++;
            }
            if (frameCount >= 2) {
                if (Settings.FRAME_GEN_ENGINE_GL.equals(engine)) {
                    estimateMotion((writeIndex - 1 + RING) % RING, writeIndex);
                } else {
                    requestNcnnMotion((writeIndex - 1 + RING) % RING, writeIndex);
                }
            }
            advancePair();
        }
        hasFrame = true;
        newFrame = true;
    }

    private void advancePair() {
        long now = System.nanoTime();
        if (pairStartNs == 0 || now - pairStartNs > intervalNs + intervalNs / 2) {
            pairStartNs = now;
        } else {
            // Ideal clock: one interval per frame even when a frame lands late, so the
            // pair boundary never repeats the frame that was already on screen.
            pairStartNs += intervalNs;
        }
    }

    private void trackTiming(long pts) {
        if (lastPtsNs > 0) {
            long delta = pts - lastPtsNs;
            if (delta < 0 || delta > 2_000_000_000L) {
                // Seek or track change: both ring slots are from before the jump.
                resetStream();
            } else if (delta > 1_000_000L && delta < 500_000_000L) {
                intervalNs += (delta - intervalNs) / 8;
            }
        }
        lastPtsNs = pts;
    }

    private void resetStream() {
        frameCount = 0;
        writeIndex = RING - 1;
        flowPrev = -1;
        flowNext = -1;
        flowValid = false;
        pairStartNs = 0;
    }

    private void copyToFrame(int index) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameFbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, frameTexture[index], 0);
        GLES20.glViewport(0, 0, videoWidth, videoHeight);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        drawQuad(program, positionHandle, texCoordHandle,
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId, textureHandle,
                texMatrixHandle, texMatrix);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        renderLuma(index);
    }

    private void renderLuma(int index) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameFbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, lumaTexture[index], 0);
        GLES20.glViewport(0, 0, lumaWidth, lumaHeight);
        GLES20.glUseProgram(lumaProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTexture[index]);
        GLES20.glUniform1i(lumaSourceHandle, 0);
        GLES20.glUniform2f(lumaTexelHandle, 1f / videoWidth, 1f / videoHeight);
        drawFullQuad();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void estimateMotion(int prev, int next) {
        renderSearch(prev, next, 0f, COARSE_STRIDE, COARSE_INSET);
        renderBlocks();
        renderPick(COARSE_STRIDE, COARSE_RANGE);
        renderSearch(prev, next, COARSE_RANGE, FINE_STRIDE, FINE_INSET);
        renderBlocks();
        renderRefine(COARSE_RANGE, FINE_STRIDE);
        renderTileFlow(prev, next);
        renderPixelFlow(prev, next);
        renderPixelSad(prev, next);
        copyTexture(tileTexture, prevTileTexture, tileGridW, tileGridH);
        copyTexture(flowTexture, prevGlobalTexture, 1, 1);
        flowPrev = prev;
        flowNext = next;
        flowValid = true;
    }

    /** Read back on the GL thread, infer on the ffn thread. One run in flight. */
    private void requestNcnnMotion(int prev, int next) {
        if (ncnnHandler == null) {
            return;
        }
        if (ncnnBusy) {
            winSkip++;
            final long busyMs = (System.nanoTime() - busyStartNs) / 1_000_000L;
            if (busyMs > 2000 && !busyWarned) {
                busyWarned = true;
                Log.w("framegen", "inference busy " + busyMs + " ms");
            }
            return;
        }
        flowValid = false;
        final int w = videoWidth;
        final int h = videoHeight;
        final int lumaW = w / 4;
        final int lumaH = h / 4;
        final int flowLen = lumaW * lumaH * 2;
        if (ncnnFlow == null || ncnnFlow.length != flowLen) {
            ncnnFlow = new float[flowLen];
            ncnnSad = new float[lumaW * lumaH];
        }
        final float[] flow = ncnnFlow;
        final float[] sad = ncnnSad;
        final long readbackStart = System.nanoTime();
        readbackTexture(prev, true);
        readbackTexture(next, false);
        readbackMs = (System.nanoTime() - readbackStart) / 1_000_000f;
        ncnnBusy = true;
        busyStartNs = System.nanoTime();
        if (!ncnnHandler.post(() -> {
            final long started = System.nanoTime();
            boolean ok = false;
            try {
                ok = ensureNcnn()
                        && ncnnEngine.ffnFlow(readbackA, readbackC, w, h, flow, sad);
            } catch (Throwable error) {
                Log.e("framegen", "inference crashed", error);
            }
            final boolean result = ok;
            final float attemptMs = (System.nanoTime() - started) / 1_000_000f;
            final int[] net = result ? ncnnEngine.netSize() : null;
            final String dev = ncnnEngine != null ? ncnnEngine.deviceName() : "";
            Log.i("framegen", String.format(Locale.US, "infer %s %.1f ms",
                    result ? "ok" : "failed", attemptMs));
            handler.post(() -> applyNcnnMotion(prev, next, w, h, flow, sad, result,
                    attemptMs, net, dev));
        })) {
            ncnnBusy = false;
        }
    }

    /** Publish a result only if it still matches the current pair. */
    private void applyNcnnMotion(int prev, int next, int w, int h,
                                 float[] flow, float[] sad, boolean ok,
                                 float ms, int[] net, String dev) {
        ncnnBusy = false;
        busyWarned = false;
        if (released) {
            return;
        }
        if (!dev.isEmpty()) {
            deviceName = dev;
        }
        inferLastMs = ms;
        inferAvgMs = inferAvgMs == 0f ? ms : inferAvgMs * 0.9f + ms * 0.1f;
        if (net != null && net.length == 2) {
            netWidth = net[0];
            netHeight = net[1];
        }
        if (!ok) {
            inferFail++;
        }
        if (ncnnEngine == null || !ncnnEngine.available) {
            engine = Settings.FRAME_GEN_ENGINE_GL;
            flowValid = false;
            return;
        }
        final int lumaW = w / 4;
        final int lumaH = h / 4;
        final int currentPrev = (writeIndex - 1 + RING) % RING;
        if (!ok || prev != currentPrev || next != writeIndex
                || w != videoWidth || h != videoHeight
                || flow.length != lumaW * lumaH * 2) {
            if (ok) {
                inferStale++;
            }
            return;
        }
        // Pack with the encoding the GL warp shader decodes.
        ByteBuffer packed = uploadBuffer(flowUpload, lumaW * lumaH * 4);
        flowUpload = packed;
        for (int i = 0; i < lumaW * lumaH; i++) {
            packed.put((byte) encodeChannel(flow[i * 2]));
            packed.put((byte) encodeFrac(flow[i * 2]));
            packed.put((byte) encodeChannel(flow[i * 2 + 1]));
            packed.put((byte) encodeFrac(flow[i * 2 + 1]));
        }
        packed.position(0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pixelFlowTexture);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, lumaW, lumaH,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, packed);
        ByteBuffer sadBuf = uploadBuffer(sadUpload, lumaW * lumaH * 4);
        sadUpload = sadBuf;
        for (int i = 0; i < lumaW * lumaH; i++) {
            int v = (int) Math.max(0f, Math.min(1f, sad[i]) * 255f);
            sadBuf.put((byte) v).put((byte) v).put((byte) v).put((byte) 255);
        }
        sadBuf.position(0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pixelSadTexture);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, lumaW, lumaH,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, sadBuf);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        flowPrev = prev;
        flowNext = next;
        flowValid = true;
    }

    private static ByteBuffer uploadBuffer(ByteBuffer buffer, int size) {
        if (buffer == null || buffer.capacity() < size) {
            return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
        }
        buffer.clear();
        return buffer;
    }

    /** Encode a full-res pixel displacement into the packed 0-255 channel. */
    private static int encodeChannel(float displacementPx) {
        float e = (displacementPx / 4f + 192f) * (255f / 384f);
        return (int) Math.max(0f, Math.min(255f, (float) Math.floor(e)));
    }

    private static int encodeFrac(float displacementPx) {
        float e = (displacementPx / 4f + 192f) * (255f / 384f);
        float frac = e - (float) Math.floor(e);
        return (int) Math.max(0f, Math.min(255f, frac * 255f));
    }

    /** Lazy-load the ncnn engine on the inference thread. */
    private boolean ensureNcnn() {
        if (ncnnEngine == null) {
            final long started = System.nanoTime();
            ncnnEngine = new NcnnFrameEngine(view.getContext());
            Log.i("framegen", String.format(Locale.US, "engine init %s in %.0f ms",
                    ncnnEngine.available ? "ok" : "failed",
                    (System.nanoTime() - started) / 1e6f));
        }
        return ncnnEngine.available;
    }

    /** glReadPixels of a ring frame; first call fills buffer A, second C. */
    private void readbackTexture(int index, boolean intoA) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameFbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D,
                frameTexture[index], 0);
        int bytes = videoWidth * videoHeight * 4;
        if (intoA) {
            readbackA = readbackBuffer(readbackA, bytes);
        } else {
            readbackC = readbackBuffer(readbackC, bytes);
        }
        GLES20.glReadPixels(0, 0, videoWidth, videoHeight, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, intoA ? readbackA : readbackC);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private static ByteBuffer readbackBuffer(ByteBuffer buffer, int bytes) {
        if (buffer == null || buffer.capacity() != bytes) {
            return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        }
        buffer.clear();
        return buffer;
    }

    private void renderSearch(int prev, int next, float pickRange, float stride, float inset) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameFbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, searchTexture, 0);
        GLES20.glViewport(0, 0, SEARCH_SIZE, SEARCH_SIZE);
        unbindUnits();
        GLES20.glUseProgram(searchProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lumaTexture[prev]);
        GLES20.glUniform1i(searchPrevHandle, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lumaTexture[next]);
        GLES20.glUniform1i(searchNextHandle, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pickTexture);
        GLES20.glUniform1i(searchPickHandle, 2);
        GLES20.glUniform1f(searchPickRangeHandle, pickRange);
        GLES20.glUniform1f(searchStrideHandle, stride);
        GLES20.glUniform1f(searchInsetHandle, inset);
        GLES20.glUniform2f(searchStepHandle, 1f / lumaWidth, 1f / lumaHeight);
        drawFullQuad();
    }

    private void renderBlocks() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameFbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, blockTexture, 0);
        GLES20.glViewport(0, 0, 7, 7);
        unbindUnits();
        GLES20.glUseProgram(blockProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, searchTexture);
        GLES20.glUniform1i(blockSearchHandle, 0);
        drawFullQuad();
    }

    private void renderPick(float stride, float range) {
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, pickTexture, 0);
        GLES20.glViewport(0, 0, 1, 1);
        unbindUnits();
        GLES20.glUseProgram(pickProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blockTexture);
        GLES20.glUniform1i(pickBlocksHandle, 0);
        GLES20.glUniform1f(pickStrideHandle, stride);
        GLES20.glUniform1f(pickRangeHandle, range);
        drawFullQuad();
    }

    private void renderRefine(float pickRange, float stride) {
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, flowTexture, 0);
        GLES20.glViewport(0, 0, 1, 1);
        unbindUnits();
        GLES20.glUseProgram(refineProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, searchTexture);
        GLES20.glUniform1i(refineSearchHandle, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blockTexture);
        GLES20.glUniform1i(refineBlocksHandle, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pickTexture);
        GLES20.glUniform1i(refinePickHandle, 2);
        GLES20.glUniform1f(refinePickRangeHandle, pickRange);
        GLES20.glUniform1f(refineStrideHandle, stride);
        drawFullQuad();
    }

    /** One fragment per tile: local motion search around the global vector. */
    private void renderTileFlow(int prev, int next) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameFbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, tileTexture, 0);
        GLES20.glViewport(0, 0, tileGridW, tileGridH);
        unbindUnits();
        GLES20.glUseProgram(tileSearchProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lumaTexture[prev]);
        GLES20.glUniform1i(tilePrevHandle, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lumaTexture[next]);
        GLES20.glUniform1i(tileNextHandle, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, flowTexture);
        GLES20.glUniform1i(tileFlowHandle, 2);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTileTexture);
        GLES20.glUniform1i(tilePrevTileHandle, 3);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevGlobalTexture);
        GLES20.glUniform1i(tilePrevGlobalHandle, 4);
        GLES20.glUniform2f(tileLumaSizeHandle, lumaWidth, lumaHeight);
        GLES20.glUniform2f(tileTileSizeHandle, tileW, tileH);
        GLES20.glUniform2f(tileGridHandle, tileGridW, tileGridH);
        GLES20.glUniform1f(tileRegHandle, TILE_REG);
        drawFullQuad();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    /** Per pixel: pick the neighbouring tile vector that actually fits, then refine it. */
    private void renderPixelFlow(int prev, int next) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameFbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, pixelFlowTexture, 0);
        GLES20.glViewport(0, 0, lumaWidth, lumaHeight);
        unbindUnits();
        GLES20.glUseProgram(pixelFlowProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lumaTexture[prev]);
        GLES20.glUniform1i(pixelPrevHandle, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lumaTexture[next]);
        GLES20.glUniform1i(pixelNextHandle, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tileTexture);
        GLES20.glUniform1i(pixelTilesHandle, 2);
        GLES20.glUniform2f(pixelLumaSizeHandle, lumaWidth, lumaHeight);
        GLES20.glUniform2f(pixelTileSizeHandle, tileW, tileH);
        GLES20.glUniform2f(pixelGridHandle, tileGridW, tileGridH);
        drawFullQuad();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    /** Mean luma error of the chosen per-pixel vector, used as the fallback mask. */
    private void renderPixelSad(int prev, int next) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameFbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, pixelSadTexture, 0);
        GLES20.glViewport(0, 0, lumaWidth, lumaHeight);
        unbindUnits();
        GLES20.glUseProgram(pixelSadProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lumaTexture[prev]);
        GLES20.glUniform1i(pixelSadPrevHandle, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lumaTexture[next]);
        GLES20.glUniform1i(pixelSadNextHandle, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pixelFlowTexture);
        GLES20.glUniform1i(pixelSadFlowHandle, 2);
        GLES20.glUniform2f(pixelSadLumaSizeHandle, lumaWidth, lumaHeight);
        drawFullQuad();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void copyTexture(int src, int dst, int width, int height) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameFbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, dst, 0);
        GLES20.glViewport(0, 0, width, height);
        unbindUnits();
        GLES20.glUseProgram(copyProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src);
        GLES20.glUniform1i(copyTexHandle, 0);
        drawFullQuad();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private static void unbindUnits() {
        for (int unit = 0; unit < 5; unit++) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
    }

    private void drawFullQuad() {
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quadBuffer);
        GLES20.glEnableVertexAttribArray(1);
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(0);
        GLES20.glDisableVertexAttribArray(1);
    }

    public void setVideoSize(int width, int height) {
        handler.post(() -> resizeFrames(width, height));
    }

    private void resizeFrames(int width, int height) {
        if (width == videoWidth && height == videoHeight && frameTexture[0] != 0) {
            return;
        }
        if (frameTexture[0] != 0) {
            GLES20.glDeleteTextures(RING, frameTexture, 0);
            GLES20.glDeleteTextures(RING, lumaTexture, 0);
            GLES20.glDeleteTextures(1, new int[]{searchTexture}, 0);
            GLES20.glDeleteTextures(1, new int[]{blockTexture}, 0);
            GLES20.glDeleteTextures(1, new int[]{pickTexture}, 0);
            GLES20.glDeleteTextures(1, new int[]{flowTexture}, 0);
            GLES20.glDeleteTextures(5, new int[]{tileTexture, pixelFlowTexture, pixelSadTexture,
                    prevTileTexture, prevGlobalTexture}, 0);
        }
        videoWidth = width;
        videoHeight = height;
        frameCount = 0;
        writeIndex = RING - 1;
        lastPtsNs = 0;
        pairStartNs = 0;
        flowValid = false;
        flowPrev = -1;
        flowNext = -1;
        if (width <= 0 || height <= 0 || !glReady) {
            return;
        }
        lumaWidth = Math.max(1, width / MOTION_SCALE);
        lumaHeight = Math.max(1, height / MOTION_SCALE);
        tileGridW = Math.max(1, (lumaWidth + TILE_TARGET - 1) / TILE_TARGET);
        tileGridH = Math.max(1, (lumaHeight + TILE_TARGET - 1) / TILE_TARGET);
        tileW = lumaWidth / (float) tileGridW;
        tileH = lumaHeight / (float) tileGridH;
        GLES20.glGenTextures(RING, frameTexture, 0);
        for (int texture : frameTexture) {
            bindSimpleTexture(texture);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height,
                    0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        }
        GLES20.glGenTextures(RING, lumaTexture, 0);
        for (int texture : lumaTexture) {
            bindSimpleTexture(texture);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R8, lumaWidth, lumaHeight,
                    0, GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, null);
        }
        int[] generated = new int[1];
        GLES20.glGenTextures(1, generated, 0);
        searchTexture = generated[0];
        bindSimpleTexture(searchTexture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, SEARCH_SIZE, SEARCH_SIZE,
                0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glGenTextures(1, generated, 0);
        blockTexture = generated[0];
        bindSimpleTexture(blockTexture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 7, 7,
                0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glGenTextures(1, generated, 0);
        pickTexture = generated[0];
        bindSimpleTexture(pickTexture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1,
                0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glGenTextures(1, generated, 0);
        flowTexture = generated[0];
        bindSimpleTexture(flowTexture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1,
                0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glGenTextures(1, generated, 0);
        tileTexture = generated[0];
        bindSimpleTexture(tileTexture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, tileGridW, tileGridH,
                0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glGenTextures(1, generated, 0);
        pixelFlowTexture = generated[0];
        bindSimpleTexture(pixelFlowTexture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, lumaWidth, lumaHeight,
                0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glGenTextures(1, generated, 0);
        pixelSadTexture = generated[0];
        bindSimpleTexture(pixelSadTexture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, lumaWidth, lumaHeight,
                0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glGenTextures(1, generated, 0);
        prevTileTexture = generated[0];
        bindSimpleTexture(prevTileTexture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, tileGridW, tileGridH,
                0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glGenTextures(1, generated, 0);
        prevGlobalTexture = generated[0];
        bindSimpleTexture(prevGlobalTexture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1,
                0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        clearTexture(prevGlobalTexture, 1, 1);
        clearTexture(prevTileTexture, tileGridW, tileGridH);
    }

    private void clearTexture(int texture, int width, int height) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameFbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texture, 0);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
    }

    private static void bindSimpleTexture(int texture) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (released) {
            return;
        }
        if (eglWindow != EGL14.EGL_NO_SURFACE && hasFrame && !paused) {
            // Once the pair has played out, the endpoint is already on screen; keep it
            // there instead of warping it again every vsync.
            boolean live = newFrame || frameTimeNanos < pairStartNs + intervalNs;
            if (live && due(frameTimeNanos)) {
                newFrame = false;
                draw(frameTimeNanos);
            }
        }
        choreographer.postFrameCallback(this);
    }

    private boolean due(long frameTimeNanos) {
        if (nextDrawNs == 0 || frameTimeNanos - nextDrawNs > 1_000_000_000L) {
            nextDrawNs = frameTimeNanos;
        }
        long interval = 1_000_000_000L / targetFps;
        if (frameTimeNanos + 1_000_000L < nextDrawNs) {
            return false;
        }
        nextDrawNs += interval;
        if (nextDrawNs < frameTimeNanos) {
            nextDrawNs = frameTimeNanos + interval;
        }
        return true;
    }

    private void draw(long frameTimeNanos) {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglWindow, eglWindow, eglContext)) {
            return;
        }
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        boolean drawn = false;
        if (frameCount > 0) {
            if (flowValid) {
                float timestep = (frameTimeNanos - pairStartNs) / (float) intervalNs;
                drawWarp(flowPrev, flowNext, Math.max(0f, Math.min(1f, timestep)));
            } else {
                drawQuad(simpleProgram, simplePositionHandle, simpleTexCoordHandle,
                        GLES20.GL_TEXTURE_2D, frameTexture[writeIndex], simpleTextureHandle,
                        -1, null);
            }
            drawn = true;
        }
        if (!drawn) {
            drawQuad(program, positionHandle, texCoordHandle, GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    textureId, textureHandle, texMatrixHandle, texMatrix);
        }

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglWindow, frameTimeNanos);
        if (!EGL14.eglSwapBuffers(eglDisplay, eglWindow)
                && EGL14.eglGetError() == EGL14.EGL_CONTEXT_LOST) {
            fail("EGL context lost");
            return;
        }
        winDrawn++;
        if (flowValid) {
            winWarp++;
        }
        updateDebug(frameTimeNanos);

        EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext);
    }

    /** Afterburner-style overlay: proves what the engine is actually doing. */
    private void updateDebug(long nowNs) {
        final TextView view = debugView;
        if (view == null) {
            return;
        }
        if (statsStartNs == 0) {
            statsStartNs = nowNs;
            return;
        }
        final long elapsed = nowNs - statsStartNs;
        if (elapsed < 500_000_000L) {
            return;
        }
        final double sec = elapsed / 1e9;
        final long drawn = winDrawn, warp = winWarp, decoded = winDecoded;
        final long skipped = winSkip, fail = inferFail, stale = inferStale;
        winDrawn = 0;
        winWarp = 0;
        winDecoded = 0;
        winSkip = 0;
        inferFail = 0;
        inferStale = 0;
        statsStartNs = nowNs;
        final int outFps = (int) Math.round(drawn / sec);
        final int inFps = (int) Math.round(decoded / sec);
        final int warpPct = drawn == 0 ? 0 : (int) (warp * 100 / drawn);
        final String busy = ncnnBusy
                ? String.format(Locale.US, "%.1fs",
                        (System.nanoTime() - busyStartNs) / 1e9)
                : "-";
        final String text = String.format(Locale.US,
                "%s · %s\nsrc %dx%d · net %dx%d\n"
                        + "infer %.1f ms avg %.1f · readback %.1f\n"
                        + "fail %d stale %d skip %d · busy %s\n"
                        + "out %d fps · in %d fps · warp %d%%\n"
                        + "target %d",
                Settings.FRAME_GEN_ENGINE_GL.equals(engine) ? "GL" : "FFN",
                deviceName.isEmpty() ? "no GPU" : deviceName,
                videoWidth, videoHeight, netWidth, netHeight,
                inferLastMs, inferAvgMs, readbackMs,
                fail, stale, skipped, busy,
                outFps, inFps, warpPct, targetFps);
        mainHandler.post(() -> view.setText(text));
    }

    private void drawQuad(int programId, int posHandle, int coordHandle, int target,
                          int texture, int textureHandle, int matrixHandle, float[] matrix) {
        GLES20.glUseProgram(programId);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(target, texture);
        GLES20.glUniform1i(textureHandle, 0);
        if (matrixHandle >= 0) {
            GLES20.glUniformMatrix4fv(matrixHandle, 1, false, matrix, 0);
        }
        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quadBuffer);
        GLES20.glEnableVertexAttribArray(coordHandle);
        GLES20.glVertexAttribPointer(coordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(posHandle);
        GLES20.glDisableVertexAttribArray(coordHandle);
    }

    private void drawWarp(int prev, int next, float timestep) {
        GLES20.glUseProgram(warpProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTexture[prev]);
        GLES20.glUniform1i(warpPrevHandle, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTexture[next]);
        GLES20.glUniform1i(warpNextHandle, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pixelFlowTexture);
        GLES20.glUniform1i(warpFlowHandle, 2);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pixelSadTexture);
        GLES20.glUniform1i(warpSadHandle, 3);
        GLES20.glUniform1f(warpTimestepHandle, timestep);
        GLES20.glUniform2f(warpStepHandle, 1f / videoWidth, 1f / videoHeight);
        GLES20.glUniform2f(warpBadHandle, BAD_LO, BAD_HI);
        GLES20.glUniform2f(warpDisHandle, DIS_LO, DIS_HI);
        GLES20.glUniform1f(warpDisWHandle, DIS_W);
        drawFullQuad();
    }

    private static FloatBuffer buffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    private void fail(String reason) {
        String message = String.format(Locale.US, "%s failed", reason);
        mainHandler.post(() -> {
            if (!released) {
                listener.onFailure(message);
            }
        });
    }
}

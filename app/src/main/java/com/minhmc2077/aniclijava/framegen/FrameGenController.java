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
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;

/** Video output surface and presentation clock. Stage 1: pass-through, one draw per vsync. */
public final class FrameGenController implements Choreographer.FrameCallback, SurfaceHolder.Callback {
    public interface Listener {
        void onInputSurface(@NonNull Surface surface);

        void onFailure(@NonNull String reason);
    }

    public static final class Stats {
        public volatile int outputFps;
        public volatile int sourceFps;
        public volatile int vsyncFps;
        public volatile int width;
        public volatile int height;
        public volatile float frameMs;
        public volatile int warpPct;
        public volatile long sourceFrames;
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
            + "uniform vec2 uStep;\n"
            + "void main() {\n"
            + "    vec2 idx = floor(vTexCoord * 49.0);\n"
            + "    vec2 shift = idx - 24.0;\n"
            + "    float sad = 0.0;\n"
            + "    for (int y = 0; y < 9; ++y) {\n"
            + "        for (int x = 0; x < 16; ++x) {\n"
            + "            vec2 uv = 0.12 + 0.76 * (vec2(float(x), float(y)) + 0.5) / vec2(16.0, 9.0);\n"
            + "            sad += abs(texture(uPrev, uv).r - texture(uNext, uv + shift * uStep).r);\n"
            + "        }\n"
            + "    }\n"
            + "    float packed = sad * (255.0 / 144.0);\n"
            + "    float coarse = floor(packed);\n"
            + "    fragColor = vec4((shift + 24.0) / 48.0, coarse / 255.0, packed - coarse);\n"
            + "}\n";

    private static final String BLOCK_FRAGMENT_SHADER = GLSL_HEADER
            + "uniform sampler2D uSearch;\n"
            + "void main() {\n"
            + "    vec2 cell = floor(vTexCoord * 7.0) * 7.0;\n"
            + "    float best = 2.0;\n"
            + "    vec2 bestShift = vec2(0.5);\n"
            + "    for (int y = 0; y < 7; ++y) {\n"
            + "        for (int x = 0; x < 7; ++x) {\n"
            + "            vec2 p = (cell + vec2(float(x), float(y)) + 0.5) / 49.0;\n"
            + "            vec4 s = texture(uSearch, p);\n"
            + "            if (s.b < best) {\n"
            + "                best = s.b;\n"
            + "                bestShift = s.xy;\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "    fragColor = vec4(bestShift, best, 1.0);\n"
            + "}\n";

    private static final String REFINE_FRAGMENT_SHADER = GLSL_HEADER
            + "uniform sampler2D uSearch;\n"
            + "uniform sampler2D uBlocks;\n"
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
            + "                bestCell = floor(b.xy * 48.0 + 0.5);\n"
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
            + "    vec2 shift = bestCell + frac - 24.0;\n"
            + "    vec2 e = (shift + 32.0) * 4.0;\n"
            + "    fragColor = vec4(floor(e.x) / 255.0, e.x - floor(e.x),\n"
            + "                     floor(e.y) / 255.0, e.y - floor(e.y));\n"
            + "}\n";

    private static final String SAD_FRAGMENT_SHADER = GLSL_HEADER
            + "uniform sampler2D uBlocks;\n"
            + "void main() {\n"
            + "    float best = 1.0;\n"
            + "    for (int y = 0; y < 7; ++y) {\n"
            + "        for (int x = 0; x < 7; ++x) {\n"
            + "            vec2 p = (vec2(float(x), float(y)) + 0.5) / 7.0;\n"
            + "            best = min(best, texture(uBlocks, p).b);\n"
            + "        }\n"
            + "    }\n"
            + "    fragColor = vec4(best, 0.0, 0.0, 1.0);\n"
            + "}\n";

    private static final String WARP_FRAGMENT_SHADER = GLSL_HEADER
            + "uniform sampler2D uPrev;\n"
            + "uniform sampler2D uNext;\n"
            + "uniform sampler2D uFlow;\n"
            + "uniform sampler2D uSad;\n"
            + "uniform float uTimestep;\n"
            + "uniform vec2 uStep;\n"
            + "void main() {\n"
            + "    vec4 flow = texture(uFlow, vec2(0.5));\n"
            + "    vec2 lumaShift = vec2(flow.r * 255.0 + flow.g, flow.b * 255.0 + flow.a) / 4.0 - 32.0;\n"
            + "    vec2 shift = lumaShift * 4.0 * uStep;\n"
            + "    vec3 warped = mix(texture(uPrev, vTexCoord - shift * uTimestep).rgb,\n"
            + "                      texture(uNext, vTexCoord + shift * (1.0 - uTimestep)).rgb,\n"
            + "                      uTimestep);\n"
            + "    vec3 nearest = uTimestep < 0.5 ? texture(uPrev, vTexCoord).rgb\n"
            + "                                   : texture(uNext, vTexCoord).rgb;\n"
            + "    float bad = smoothstep(0.08, 0.20, texture(uSad, vec2(0.5)).r);\n"
            + "    fragColor = vec4(mix(warped, nearest, bad), 1.0);\n"
            + "}\n";

    private static final float[] QUAD = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
    private static final float[] TEX_COORDS = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};

    private final SurfaceView view;
    private final Listener listener;
    private final Stats stats = new Stats();
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
    private boolean drawEveryVsync;
    private boolean paused;

    private static final int RING = 2;
    private static final int SEARCH_RANGE = 24;
    private static final int SEARCH_SIZE = SEARCH_RANGE * 2 + 1;
    private static final int MOTION_SCALE = 4;

    private int lumaProgram;
    private int searchProgram;
    private int blockProgram;
    private int refineProgram;
    private int sadProgram;
    private int warpProgram;
    private int lumaSourceHandle;
    private int lumaTexelHandle;
    private int searchPrevHandle;
    private int searchNextHandle;
    private int searchStepHandle;
    private int blockSearchHandle;
    private int refineSearchHandle;
    private int refineBlocksHandle;
    private int sadBlocksHandle;
    private int warpPrevHandle;
    private int warpNextHandle;
    private int warpFlowHandle;
    private int warpSadHandle;
    private int warpTimestepHandle;
    private int warpStepHandle;
    private final int[] lumaTexture = new int[RING];
    private int searchTexture;
    private int blockTexture;
    private int flowTexture;
    private int flowSadTexture;
    private int lumaWidth;
    private int lumaHeight;
    private int flowPrev = -1;
    private int flowNext = -1;
    private boolean flowValid;

    private int simpleProgram;
    private int simplePositionHandle;
    private int simpleTexCoordHandle;
    private int simpleTextureHandle;
    private final int[] frameTexture = new int[RING];
    private final int[] frameFbo = new int[1];
    private final long[] framePts = new long[RING];
    private int writeIndex = RING - 1;
    private int frameCount;
    private int videoWidth;
    private int videoHeight;
    private long clockOffsetNs;
    private long intervalNs = 41_666_667L;
    private long lastPtsNs;

    private long draws;
    private long warped;
    private long vsyncs;
    private long sourceCount;
    private long lastStatsNs;
    private long lastDraws;
    private long lastWarped;
    private long lastVsyncs;
    private long lastSource;

    public FrameGenController(@NonNull SurfaceView view, @NonNull Listener listener) {
        this.view = view;
        this.listener = listener;
        thread = new HandlerThread("framegen");
        thread.start();
        handler = new Handler(thread.getLooper());
        view.getHolder().addCallback(this);
    }

    public void start() {
        handler.post(this::initGl);
    }

    public void setProcessingEnabled(boolean enabled) {
        handler.post(() -> drawEveryVsync = enabled);
    }

    public void setPaused(boolean value) {
        handler.post(() -> paused = value);
    }

    public Stats stats() {
        return stats;
    }

    public void release() {
        view.getHolder().removeCallback(this);
        handler.post(() -> {
            released = true;
            if (choreographer != null) {
                choreographer.removeFrameCallback(this);
            }
            destroyGl();
            thread.quitSafely();
        });
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
            stats.width = width;
            stats.height = height;
        });
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        handler.post(this::destroyWindowSurface);
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
        if (simpleProgram == 0) {
            return false;
        }
        simplePositionHandle = GLES20.glGetAttribLocation(simpleProgram, "aPosition");
        simpleTexCoordHandle = GLES20.glGetAttribLocation(simpleProgram, "aTexCoord");
        simpleTextureHandle = GLES20.glGetUniformLocation(simpleProgram, "uTexture");
        return true;
    }

    private boolean buildMotionPrograms() {
        lumaProgram = linkProgram(GLSL_VERTEX_SHADER, LUMA_FRAGMENT_SHADER);
        searchProgram = linkProgram(GLSL_VERTEX_SHADER, SEARCH_FRAGMENT_SHADER);
        blockProgram = linkProgram(GLSL_VERTEX_SHADER, BLOCK_FRAGMENT_SHADER);
        refineProgram = linkProgram(GLSL_VERTEX_SHADER, REFINE_FRAGMENT_SHADER);
        sadProgram = linkProgram(GLSL_VERTEX_SHADER, SAD_FRAGMENT_SHADER);
        warpProgram = linkProgram(GLSL_VERTEX_SHADER, WARP_FRAGMENT_SHADER);
        if (lumaProgram == 0 || searchProgram == 0 || blockProgram == 0
                || refineProgram == 0 || sadProgram == 0 || warpProgram == 0) {
            return false;
        }
        lumaSourceHandle = uniform(lumaProgram, "uSource");
        lumaTexelHandle = uniform(lumaProgram, "uTexel");
        searchPrevHandle = uniform(searchProgram, "uPrev");
        searchNextHandle = uniform(searchProgram, "uNext");
        searchStepHandle = uniform(searchProgram, "uStep");
        blockSearchHandle = uniform(blockProgram, "uSearch");
        refineSearchHandle = uniform(refineProgram, "uSearch");
        refineBlocksHandle = uniform(refineProgram, "uBlocks");
        sadBlocksHandle = uniform(sadProgram, "uBlocks");
        warpPrevHandle = uniform(warpProgram, "uPrev");
        warpNextHandle = uniform(warpProgram, "uNext");
        warpFlowHandle = uniform(warpProgram, "uFlow");
        warpSadHandle = uniform(warpProgram, "uSad");
        warpTimestepHandle = uniform(warpProgram, "uTimestep");
        warpStepHandle = uniform(warpProgram, "uStep");
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
        stats.width = surfaceWidth;
        stats.height = surfaceHeight;
        if (EGL14.eglMakeCurrent(eglDisplay, eglWindow, eglWindow, eglContext)) {
            EGL14.eglSwapInterval(eglDisplay, 0);
        }
        EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext);
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
        int[] programs = {lumaProgram, searchProgram, blockProgram, refineProgram, sadProgram,
                warpProgram};
        for (int id : programs) {
            if (id != 0) {
                GLES20.glDeleteProgram(id);
            }
        }
        lumaProgram = 0;
        searchProgram = 0;
        blockProgram = 0;
        refineProgram = 0;
        sadProgram = 0;
        warpProgram = 0;
        if (frameTexture[0] != 0) {
            GLES20.glDeleteTextures(RING, frameTexture, 0);
            GLES20.glDeleteTextures(RING, lumaTexture, 0);
            GLES20.glDeleteTextures(1, new int[]{searchTexture}, 0);
            GLES20.glDeleteTextures(1, new int[]{blockTexture}, 0);
            GLES20.glDeleteTextures(1, new int[]{flowTexture}, 0);
            GLES20.glDeleteTextures(1, new int[]{flowSadTexture}, 0);
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
            trackClock(pts);
        }
        if (drawEveryVsync && pts > 0 && videoWidth > 0 && videoHeight > 0) {
            writeIndex = (writeIndex + 1) % RING;
            copyToFrame(writeIndex);
            framePts[writeIndex] = pts;
            if (frameCount < RING) {
                frameCount++;
            }
            if (frameCount >= 2) {
                flowValid = false;
                estimateMotion((writeIndex - 1 + RING) % RING, writeIndex);
            }
        }
        hasFrame = true;
        newFrame = true;
        sourceCount++;
    }

    private void trackClock(long pts) {
        long offset = System.nanoTime() - pts;
        if (clockOffsetNs == 0 || Math.abs(offset - clockOffsetNs) > 500_000_000L) {
            clockOffsetNs = offset;
        } else {
            clockOffsetNs += (offset - clockOffsetNs) / 32;
        }
        if (lastPtsNs > 0) {
            long delta = pts - lastPtsNs;
            if (delta > 1_000_000L && delta < 500_000_000L) {
                intervalNs += (delta - intervalNs) / 8;
            }
        }
        lastPtsNs = pts;
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
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameFbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, searchTexture, 0);
        GLES20.glViewport(0, 0, SEARCH_SIZE, SEARCH_SIZE);
        GLES20.glUseProgram(searchProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lumaTexture[prev]);
        GLES20.glUniform1i(searchPrevHandle, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lumaTexture[next]);
        GLES20.glUniform1i(searchNextHandle, 1);
        GLES20.glUniform2f(searchStepHandle, 1f / lumaWidth, 1f / lumaHeight);
        drawFullQuad();

        GLES20.glUseProgram(blockProgram);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, blockTexture, 0);
        GLES20.glViewport(0, 0, 7, 7);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, searchTexture);
        GLES20.glUniform1i(blockSearchHandle, 0);
        drawFullQuad();

        GLES20.glUseProgram(refineProgram);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, flowTexture, 0);
        GLES20.glViewport(0, 0, 1, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, searchTexture);
        GLES20.glUniform1i(refineSearchHandle, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blockTexture);
        GLES20.glUniform1i(refineBlocksHandle, 1);
        drawFullQuad();

        GLES20.glUseProgram(sadProgram);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, flowSadTexture, 0);
        GLES20.glViewport(0, 0, 1, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blockTexture);
        GLES20.glUniform1i(sadBlocksHandle, 0);
        drawFullQuad();

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        flowPrev = prev;
        flowNext = next;
        flowValid = true;
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
            GLES20.glDeleteTextures(1, new int[]{flowTexture}, 0);
            GLES20.glDeleteTextures(1, new int[]{flowSadTexture}, 0);
        }
        videoWidth = width;
        videoHeight = height;
        frameCount = 0;
        writeIndex = RING - 1;
        lastPtsNs = 0;
        flowValid = false;
        flowPrev = -1;
        flowNext = -1;
        if (width <= 0 || height <= 0 || !glReady) {
            return;
        }
        lumaWidth = Math.max(1, width / MOTION_SCALE);
        lumaHeight = Math.max(1, height / MOTION_SCALE);
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
        flowTexture = generated[0];
        bindSimpleTexture(flowTexture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1,
                0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glGenTextures(1, generated, 0);
        flowSadTexture = generated[0];
        bindSimpleTexture(flowSadTexture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1,
                0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
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

    private int frameForTime(long contentNs) {
        int best = -1;
        long bestDelta = Long.MAX_VALUE;
        int valid = Math.min(frameCount, RING);
        for (int n = 0; n < valid; n++) {
            int index = (writeIndex - n + RING) % RING;
            long delta = Math.abs(framePts[index] - contentNs);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = index;
            }
        }
        return best;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (released) {
            return;
        }
        vsyncs++;
        if (eglWindow != EGL14.EGL_NO_SURFACE && hasFrame && !paused
                && (drawEveryVsync || newFrame)) {
            newFrame = false;
            draw(frameTimeNanos);
        }
        updateStats(frameTimeNanos);
        choreographer.postFrameCallback(this);
    }

    private void draw(long frameTimeNanos) {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglWindow, eglWindow, eglContext)) {
            return;
        }
        long started = SystemClock.elapsedRealtimeNanos();
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        boolean drawn = false;
        if (drawEveryVsync && frameCount > 0 && clockOffsetNs != 0) {
            long contentNs = frameTimeNanos - clockOffsetNs - delayNs();
            int before = -1;
            int after = -1;
            int valid = Math.min(frameCount, RING);
            for (int n = 0; n < valid; n++) {
                int index = (writeIndex - n + RING) % RING;
                long pts = framePts[index];
                if (pts <= contentNs && (before < 0 || pts > framePts[before])) {
                    before = index;
                }
                if (pts >= contentNs && (after < 0 || pts < framePts[after])) {
                    after = index;
                }
            }
            if (before >= 0 && after >= 0 && after != before && flowValid
                    && before == flowPrev && after == flowNext) {
                long span = framePts[after] - framePts[before];
                if (span > 0) {
                    float timestep = (contentNs - framePts[before]) / (float) span;
                    timestep = Math.max(0f, Math.min(1f, timestep));
                    drawWarp(before, after, timestep);
                    warped++;
                    drawn = true;
                }
            }
            if (!drawn) {
                int frame = before >= 0 ? before : frameForTime(contentNs);
                if (frame >= 0) {
                    drawQuad(simpleProgram, simplePositionHandle, simpleTexCoordHandle,
                            GLES20.GL_TEXTURE_2D, frameTexture[frame], simpleTextureHandle,
                            -1, null);
                    drawn = true;
                }
            }
        }
        if (!drawn) {
            drawQuad(program, positionHandle, texCoordHandle, GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    textureId, textureHandle, texMatrixHandle, texMatrix);
        }

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglWindow, frameTimeNanos);
        EGL14.eglSwapBuffers(eglDisplay, eglWindow);

        float ms = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000f;
        stats.frameMs = stats.frameMs == 0f ? ms : stats.frameMs * 0.9f + ms * 0.1f;
        draws++;

        EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext);
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
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, flowTexture);
        GLES20.glUniform1i(warpFlowHandle, 2);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, flowSadTexture);
        GLES20.glUniform1i(warpSadHandle, 3);
        GLES20.glUniform1f(warpTimestepHandle, timestep);
        GLES20.glUniform2f(warpStepHandle, 1f / videoWidth, 1f / videoHeight);
        drawFullQuad();
    }

    private long delayNs() {
        return intervalNs + 4_000_000L;
    }

    private static FloatBuffer buffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    private void updateStats(long frameTimeNanos) {
        if (lastStatsNs == 0) {
            lastStatsNs = frameTimeNanos;
            lastDraws = draws;
            lastWarped = warped;
            lastVsyncs = vsyncs;
            lastSource = sourceCount;
            return;
        }
        long elapsed = frameTimeNanos - lastStatsNs;
        if (elapsed < 1_000_000_000L) {
            return;
        }
        double seconds = elapsed / 1_000_000_000d;
        stats.outputFps = (int) Math.round((draws - lastDraws) / seconds);
        stats.vsyncFps = (int) Math.round((vsyncs - lastVsyncs) / seconds);
        stats.sourceFps = (int) Math.round((sourceCount - lastSource) / seconds);
        long drawnDelta = draws - lastDraws;
        stats.warpPct = drawnDelta > 0
                ? (int) Math.round(100d * (warped - lastWarped) / drawnDelta) : 0;
        stats.sourceFrames = sourceCount;
        lastStatsNs = frameTimeNanos;
        lastDraws = draws;
        lastWarped = warped;
        lastVsyncs = vsyncs;
        lastSource = sourceCount;
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

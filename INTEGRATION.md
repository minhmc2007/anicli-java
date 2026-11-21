# Frame Generation: FastFlowNet engine integration

Adds FastFlowNet as a selectable frame-generation engine **without touching the
shipped GL architecture** (it remains the default). Switchable in
**Settings → Frame generation → Engine**.

## Engines

| Engine | Setting key | What it does | Weights | Benchmark (854x480, 180 real-anime triplets) |
|---|---|---|---|---|
| GPU warp (built-in) | `gl` | the existing GLSL block-match pipeline, unchanged | none | 33.01 dB |
| **FastFlowNet** | `fastflownet` | 1.37M-param ncnn network estimates bidirectional flow A→C/C→A; symmetric flow `f = (f_AC − f_CA)/2` is uploaded into the **existing GL occlusion-aware warp/blend**, which still renders every pixel | 3.3 MB (`ffn.ncnn.param/bin`) | **33.26 dB overall, 41.2 dB low-motion** — best engine measured |

FastFlowNet ships as a **standard ncnn graph** (the CDDC `index_select` was
converted to a fixed one-hot 1×1 conv), so there is no custom-layer porting
hazard. ncnn static libs (20261020, android-vulkan) are downloaded by Gradle
for arm64-v8a, armeabi-v7a, x86 and x86_64.

## Model files

The patch did not include the converted weights. Drop them in:

```
app/src/main/assets/framegen/ffn.ncnn.param
app/src/main/assets/framegen/ffn.ncnn.bin
```

The **Engine** row stays hidden while either file is missing, and playback
falls back to the GL engine if the native library or weights fail to load.
Once the files are in the APK the row appears automatically.

## What was added / changed

```
app/src/main/cpp/framegen/framegen_ncnn.cpp   JNI engine: FFN flow (2 passes,
                                              pair centralization, /64 resize,
                                              symmetric flow on the quarter-
                                              luma grid in FULL-RES px) +
                                              per-grid alignment residual
app/src/main/cpp/framegen/CMakeLists.txt      links the ncnn static release
app/build.gradle                              ncnn download + externalNativeBuild
framegen/NcnnFrameEngine.java                 JNI bridge, safe fallback if the
                                              native lib or assets are missing
framegen/FrameGenController.java              engine selection, estimateMotionNcnn()
                                              (GL readback → JNI → RGBA8-packed
                                              flow upload into pixelFlowTexture/
                                              pixelSadTexture, exactly the
                                              encoding the warp shader decodes)
util/Settings.java                            frameGenEngine() + engine constants
ui/SettingsActivity.java                      engine row + picker dialog
res/layout/activity_settings.xml              frame_gen_engine_row
res/values/strings.xml                        engine labels + note
```

## Data flow

```
decode → frameTexture ring (unchanged)
  → glReadPixels pair (RGBA) → JNI → ncnn FastFlowNet ×2 directions
  → symmetric flow (full-res px, quarter-luma grid) + SAD residual
  → GLTexSubImage2D into pixelFlowTexture / pixelSadTexture
  → existing warp shader renders with occlusion-aware pull + nearest fallback
```

The flow encoding is byte-compatible with the GL pipeline's own
`decodeFlow()·4 = displacement px` convention, so the classical renderer,
quality thresholds (BAD_LO/HI, DIS_LO/HI, DIS_W) and timing logic all work
unchanged.

## Building

Requires Android Studio with NDK 30.0.16248370 + CMake 3.22.1.
`./gradlew :app:assembleDebug` as usual; Gradle downloads ncnn into
`app/build/ncnn/current` before the C++ build.

## Source

- FastFlowNet: Kong et al., ICRA 2021 (`fastflownet_ft_mix.pth`, official repo)
- Benchmark/visual evidence: see the VFI redesign report + `ffn-visuals/`
  (FFN hybrid 69.6 dB vs RIFE 38.7 dB on the low-motion showpiece; RATER
  cross-engine Spearman ρ = 0.954).

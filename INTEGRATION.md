# FastFlowNet frame generation

Optional engine. Enable it in Settings > Frame generation > Engine.

Weights live in `app/src/main/assets/framegen/` and are stored with Git LFS.
If they or the native library fail to load, playback uses the GL engine.

Gradle downloads ncnn during the native build. CI installs the NDK and CMake.

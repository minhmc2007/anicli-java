#!/usr/bin/env bash
# Compiles the Vulkan shaders into app/src/main/assets/shaders/*.spv.
# Needs glslangValidator in PATH; output is committed, so CI never runs this.
set -euo pipefail
cd "$(dirname "$0")"
out=../../../main/assets/shaders
mkdir -p "$out"
for src in shaders/*.frag shaders/*.vert; do
    name=$(basename "$src")
    glslangValidator -V --target-env vulkan1.0 "$src" -o "$out/${name%.*}.spv" >/dev/null
    echo "${name%.*}.spv"
done

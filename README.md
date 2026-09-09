# Imagine

AI image enhancer for Android — skeuomorphic pastel UI, on-device Vulkan GPU inference, optional cloud mode.

**Package:** `com.kaminari.imagine`

## Features

- **On-device GPU engine** — Real-ESRGAN via [ncnn](https://github.com/Tencent/ncnn) with Vulkan compute. No network needed, runs on device GPU for speed.
- **Cloud engine (optional)** — Replicate API models: Real-ESRGAN, CodeFormer, GFPGAN. Paste your API token in cloud mode.
- **Models bundled:** realesr-animevideov3 ×2/×3/×4 (fast, small). Photo-grade `realesrgan-x4plus` (64 MB) and `x4plus-anime` (17 MB) are bundled at build time by CI.
- **Before/after comparison** — draggable slider over the result.
- **Scale options** — multiplier (2×/3×/4×) or exact target resolution (e.g. `1920x1080`).
- **Save** to `Pictures/Imagine` via MediaStore.

## Building

Built entirely on GitHub Actions (no local Android SDK required):

1. Push to `main` — the [Android CI](.github/workflows/android-ci.yml) workflow:
   - Downloads ncnn `20260526` Android Vulkan shared libraries
   - Downloads Real-ESRGAN ncnn models from the official release
   - Compiles SPIR-V shaders with glslangValidator
   - Builds the release APK with NDK 26
2. Download the `imagine-release` artifact from the Actions run.

Requirements when building locally: JDK 17, Android SDK 34, NDK 26.1.10909125, CMake 3.22.1, glslangValidator.

## Notes

- Requires a Vulkan-capable device for on-device mode (most modern Android phones). Falls back to cloud mode otherwise.
- Cloud mode requires a [Replicate API token](https://replicate.com/account/api-tokens); the token stays on the device.

## Credits

- [Real-ESRGAN-ncnn-vulkan](https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan) (MIT) — inference core, vendored in `app/src/main/cpp/`
- [ncnn](https://github.com/Tencent/ncnn) — neural network inference framework

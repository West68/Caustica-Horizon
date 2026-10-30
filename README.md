# Caustica Enhanced

An enhanced fork of [Caustica](https://github.com/ComfyFluffy/Caustica) by ComfyFluffy — a Vulkan hardware
path-traced renderer for Minecraft 26.2. Maintained by **Ruiwen68**.

This fork is based on Caustica 0.1.1 and does not include upstream changes made after that release.

[中文说明](#中文说明)

## What this fork adds

- **Distant Horizons 3.3 support**: far LODs are traced as hardware AABB geometry, with per-block PBR materials,
  block-light glow, metal/emissive classes, rain wetness and early path termination for distant hits.
- **DLSS Multi Frame Generation** via Streamline (up to 6× on RTX 50 series) alongside DLSS Ray Reconstruction.
- **Look closer to classic shader packs**: sky, clouds, End sky, fog and parallax tuned toward an ITRP/SEUS-style look,
  with an in-game preset to switch back to the original Caustica look.
- **Performance work**: radiance cache, cheaper secondary bounces (parallax and relief shadows only on the
  camera-visible surface), distance-scaled parallax steps.
- **Native settings screen** with pages, per-setting reset and presets, independent of Sodium.
- **Compatibility**: Litematica, Xaero's maps, Entity Culling, Continuity, Better Player Model, Sodium animations.
- Translations for 11 languages.

## Requirements

- Minecraft 26.2, Fabric Loader, Fabric API, Vulkan graphics backend
- A GPU and driver with Vulkan ray tracing; NVIDIA RTX for DLSS features
- Optional: Distant Horizons 3.3.x, a LabPBR resource pack

## Installation

Put the single release jar in your `mods` folder and launch with the Vulkan backend. No extra files are needed.

## Building

```
./gradlew build
```

Requires JDK 25 and `slangc` / `spirv-val` from the Vulkan SDK. NVIDIA Streamline/DLSS runtime DLLs are not in this
repository; release jars bundle them under NVIDIA's license (see `THIRD_PARTY_NOTICES.md`).

## Credits

- **ComfyFluffy** — original Caustica renderer.
- Visual style references: **ITRP** and **SEUS PTGI** shader packs. No GLSL source from those packs is included;
  the Slang shaders here are written for Caustica. See `THIRD_PARTY_NOTICES.md` for the noise textures.
- **NVIDIA** — DLSS, Streamline.
- **Distant Horizons** team.

## License

LGPL-3.0-or-later, same as upstream. See `LICENSE.md`, `COPYING` and `COPYING.LESSER`.
NVIDIA SDK components are not covered by the LGPL.

---

## 中文说明

Caustica Enhanced 是 ComfyFluffy 的 [Caustica](https://github.com/ComfyFluffy/Caustica) 的增强分支，作者 **Ruiwen68**。
它基于 Caustica 0.1.1，为 Minecraft 26.2 提供 Vulkan 硬件路径追踪。

主要新增：

- 遥远地平线（Distant Horizons）LOD 光追，远景方块带 PBR 材质、自发光和雨天湿润效果
- DLSS 光线重建 + 多帧生成（50 系最高 6×）
- 接近 ITRP / SEUS 风格的天空、云、雾和视差，可一键切回原版风格
- 性能优化、独立的设置界面、多个常用模组的兼容

安装：把发布页里的单个 jar 放进 `mods` 文件夹，用 Vulkan 后端启动即可。

许可证：LGPL-3.0-or-later，与原版一致。

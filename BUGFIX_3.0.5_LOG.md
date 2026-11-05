# 3.0.5 修改记录

## 内容
- 移植上游 ComfyFluffy/Caustica #35（330acd2d）的 ACES 2.0 SDR 色调：`sdr_aces2_rec709.bin` 输出变换 LUT + `looks/default/lmt.bin` 默认调色（LMT），均为上游离线烘焙的 65³ RGBA16F 3D LUT。
- 新增 `RtToneLut`（上游原样），`RtDisplayPipeline` 增加 binding 9/10 与 push 偏移 52/56/60。
- 我们的路径追踪工作色域为线性 Rec.709，进入 LUT 前用 Rec.709→ACEScg（Bradford）矩阵转换。
- 新设置 `look.aces2`（默认开）：仅作用于 "Caustica 原版画面"（ITRP 关闭时），替换原 AgX。关闭即回到旧 AgX。ITRP 色调完全不变。HDR 输出路径未改。
- 亮度/对比度/饱和度/色温/Gamma 等调色滑条照旧生效（作用在色调映射之后）。

## 验证
- 离线计算：中灰 0.18 经 ACES+LMT 显示值 0.475，原 AgX 0.497，整体亮度一致，无需曝光补偿。
- `compileShaders compileJava releaseJar` 成功；295 个单测 1 个失败（FirstPersonReceiverTest，检查世界光追 shader，本次未改动，属既有问题）。
- 本机 Prism 实例 CE-bench 存档实测：ACES 开/关两种模式均正常启动出图，日志无本模组错误。
- jar 内 DLSS 原生库与 3.0.4 一致（本机无 DLSS SDK，使用 3.0.4 已发布的同一份 DLL 打包）。

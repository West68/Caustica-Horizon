# 3.0.4 修复记录

## 日志证据

读取 Prism 测试实例最新 crash-2026-11-03_20.15.04-client.txt 及 logs/2026-11-03-7.log.gz。20:09:04 的首次清理失败发生在 VanillaRenderController.releaseRasterTerrain 调用 Sodium reload 时，异常为 Cannot wait on a fence for the current submit，调用链经过 MappedStagingBuffer.delete。Sodium 已关闭构建线程，但 deleteRendererState 尚未清空 renderSectionManager。20:15:03 重生处理再次清理，随后渲染重载也清理，最终报告 Worker threads are not running。

## 修改

- VanillaRenderController 在调用 Sodium reload 前提交 Minecraft 命令编码器，确保 staging-buffer 栅栏指向已提交的工作。使用已有提交 API，保留正常的清理和异常报告。
- LitematicaCompat 补充 piecewisePrepareBlockEntities 与 piecewiseRenderBlockEntities，使用独立 SubmitNodeStorage 和已有 FeatureRenderDispatcher，把箱子等绘制到现有合成层。OutputTarget 重定向只在该阶段开启，并在 finally 恢复。
- RtPortalCapture 对实际 ChestBlockEntity 绕过普通提取器的遮挡门，保留 renderer.shouldRender、类型、世界有效性检查。玩家截图无法单独判定缺失的是实际箱子还是投影箱子，本轮分别覆盖这两条路径。
- 版本升级为 3.0.4。其他 3.0.3 的设置和修复保留。

## 静态验证

开发源码离线 compileShaders compileJava releaseJar 成功。未启动游戏，未连接服务器。原始游戏存档和第三方模组未修改。尚未实测复杂投影、双箱、透明投影配置与帧率。不宣称性能零变化。新增渲染工作对应原本缺失的投影方块实体。

## 上游源码对照

- https://github.com/CaffeineMC/sodium/blob/26.2/stable/common/src/main/java/net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer.java
- https://github.com/sakura-ryoko/litematica/tree/LTS/26.2

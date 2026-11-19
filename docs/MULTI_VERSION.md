# 多版本构建（Stonecutter）

项目用 [Stonecutter](https://stonecutter.kikugie.dev/) 0.9.8 管理多个 Minecraft 版本。一份源码，每个版本单独出 jar。

## 结构

| 位置 | 作用 |
|---|---|
| `settings.gradle` | 声明版本列表 `versions "26.2", ...` |
| `stonecutter.gradle` | 根控制器；`stonecutter.active` = `src/` 当前对应的版本 |
| `build.gradle` | 应用到每个 `versions/<mc>` 子项目；仓库内文件一律用 `rootProject.file(...)` |
| `versions/<mc>/gradle.properties` | 每版本的值：`minecraft_version`、`minecraft_compat`（写进 fabric.mod.json）、`fabric_api_version`、`archives_base_name` |
| `gradle.properties` | 共用：mod 版本、loader、loom |
| `src/`、`shaders/`、`native/` | 共用；着色器和原生库与 MC 版本无关 |

产物：`versions/<mc>/build/release/Caustica-Horizon-<mc>-fabric-<ver>.jar`（不再是根目录 `build/release/`）。
原生库（ngx_shim、streamline_bridge、mfg_agent）仍输出到根目录 `build/native/`，各版本共用。

## 构建

```bash
DLSS_SDK=$PWD/third_party/original-sdk JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot" ./gradlew.bat --no-daemon --offline build -x test
```

只编某一版：`./gradlew.bat :26.3:build -x test`。

## 加一个新版本（例：26.3）

1. `settings.gradle`：`versions "26.2", "26.3"`。
2. 新建 `versions/26.3/gradle.properties`，填 `minecraft_version=26.3`、`minecraft_compat=~26.3`、对应的 `fabric_api_version`、`archives_base_name=Caustica-Horizon-26.3-fabric`。必要时在根 `gradle.properties` 升级 loom/loader。
3. 第一次需联网：`./gradlew.bat :26.3:build -x test`，编译器会列出所有对不上的类/方法。
4. 修差异时**保留旧版本代码**，用 Stonecutter 条件注释分开：

   ```java
   //? if >=26.3 {
   newApiCall();
   //?} else {
   /*oldApiCall();
   *///?}
   ```

   Mixin 注入点、渲染管线相关类是主要改动区（约 61 个 Mixin、120 个引用 MC 的类）。
5. 切换 `src/` 的活动版本用 Gradle 任务 `"Set active project to 26.3"`（Stonecutter 自动生成），它会改写条件注释；切完再改代码，改完切回去确认两边都能编。提交前跑 `"Reset active project"` 回到 26.2。
6. 在独立测试实例里验证光追、帧生成、DH 后再发布。

## 版本差异的四种写法

1. **纯改名/搬包**：写进 `build.gradle` 末尾的 `renderpearlMoves` 表或 `stonecutter.replacements`（字符串/正则），源码保持 26.2 写法，生成 26.3 源码时自动替换（含 mixin 描述符里的 `L…/…;` 形式）。
2. **逻辑不同**：源码里用 `//? if <26.3 { … //?} else { /*…*///?}` 分开写。
3. **整个 mixin 不适用**：类整体包在 `//? if <26.3 {` 里，并在 `versions/<mc>/gradle.properties` 的 `mixin_exclude=` 列出类名，打包时从 mixins.json 删除。
4. **资源（着色器）**：`src/main/resources-<mc>/` 同路径文件覆盖主资源。26.3 的 GLSL 由 `python tools/port/make_263_shaders.py` 从 26.2 源生成，改着色器只改 26.2 源再重新生成。

## 移植后必做的核对

- `tools/port/MixinAudit.java`：静态核对所有 mixin 的目标类/方法/注入点/@Shadow/访问器在目标版本 jar 里存在（`perf-cut/audit263.sh`）。字符串目标编译器不检查，漏了就是启动崩溃或功能悄悄失效。
- `tools/port/bcdiff.py`：旧版本 jar 去掉行号后逐类比字节码，证明旧版本没被改坏。

## 注意

- Stonecutter 0.10 需要 Gradle ≥ 9.7；项目当前 Gradle 9.5，所以固定 0.9.8。以后升级 Gradle 时可一起升。
- 迁移前的构建脚本备份：`C:\Projects\mc\perf-cut\backup-build-pre-stonecutter\`。
- 验证基准：迁移后 26.2 jar 与迁移前 529 个文件逐一 SHA-256 相同，仅 `MANIFEST.MF` 多了 4 行 Stonecutter 标记。

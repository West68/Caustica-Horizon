# 2026-11-01 本轮 2.2.6 修复与验收

**未全部达到目标。** 四项原始问题在下述本地范围内得到修复并验证；附魔滚动光效已恢复。关闭光追后只剩天空的追加问题仍未修复；不能声称所有场景零性能损失。下方历史报告保留，不作为本轮验证结论。

## 交付与范围

- 开发：`C:\Projects\mc\Caustica-perf-v1`；公开副本：`C:\Projects\mc\Caustica-Enhanced-pub`。
- Jar：`C:\Projects\mc\mc路劲追踪mod\perf-review-20261027\release-2.0\Caustica-Horizon-26.2-fabric-2.2.6.jar`。
- Prism mods：`C:\MINECRAFT\Minecraft_Game\PrismLauncher-Windows-MinGW-w64-Portable-11.1.1\instances\Prism-26.2 vulkan 光追\minecraft\mods\Caustica-Horizon-26.2-fabric-2.2.6.jar`。
- SHA-256：`d3baface24a9e4a2a978e8d5c0166bcd1a168ebd11375679668f4009ea48be7a`（由 Git for Windows 的 sha256sum 复核）。
- 开始时开发目录已有 3919 条状态记录，公开仓库已有 10 个修改；完整清单在 `reports/bugfix-2.2.6-20261101/dev-status-before.txt` 和 `pub-status-before.txt`。没有重置、提交或推送，也没有永久删除文件。
- 开始时开发目录的版本已为 `2.2.6`，本轮保留，并同步公开仓库。未连接玩家服务器，所有测试均为复制的本地单人世界。
- 原有 `BUGFIX_2.2.6_LOG.md` 已备份。旧 release Jar、Prism 旧 Jar、测试工具已归档到本轮 reports 目录。
- 未修改 DLSS、POM、玻璃、水的实现和参数。太阳阴影偏移保留开始时行为；本轮仅对离开角色表面的间接射线保留透明裁剪。没有新增光线、反弹上限或渲染 Pass。

## 状态、根因与证据

| 问题 | 本轮状态 | 根因与修改 | 证据 |
|---|---|---|---|
| 信标白方柱、外层异常、动画停滞 | 已修复并验证（本地白色信标；未覆盖所有染色组合） | `RtEntities.java:1663`：缓存哈希漏掉 UV，补齐 UV；`RtEntityCollector.java:1094`：两层绑定失败时使用明确的原版光束纹理；`world.rchit.slang:545`：补乘已采集的顶点 alpha，保留正常半透明外层 | `final-evidence/final_beacon.png`、`final_beacon_motion.png`；原版参考 `round-3-invalid-no-rt/r3_vanilla_start.png` |
| 文字透墙 | 已修复并验证（整面实体墙遮挡） | `RtEntities.java:1076`：叠加文字无世界深度，恢复相机至标签的方块碰撞检查，端点容差 0.1 格 | `final_text.png`（名字和 text_display 可见）对 `final_text_occluded.png`（墙后均不可见） |
| 村民头部黑 | 已修复并验证（普通村民与农民） | `world.rgen.slang:1351`：漫反射后的 FORCE_OPAQUE 让透明头部/职业外层阻断天空光；只对角色间接光延续保留 any-hit；采集端标记角色并稳定职业贴图层顺序 | `round-4/r4_skin_A2.png` 黑头 → `r4_skin_B3.png` 正常；分层证据 `round-2/r2_albedo.png`、`r2_normals.png`；最终 `final_villagers.png` |
| 穿盔甲皮肤黑 | 已修复并验证（本地默认皮肤、钻石盔甲） | 同上，保留角色外层透明裁剪；附魔不再产生额外身体壳 | `final_enchanted.png`、`final_enchanted_motion.png`、`final_plain.png` |
| 附魔显示异常 | 已修复并验证（模型附魔形状与可见动效）；未宣称与原版逐像素等效 | `RtEntityCollector.java:196`、`RtEntityCapture.java` 的 `applyModelFoil`：装饰已采集的原始三角形，保留原材质、UV、透明孔和阴影；显式读取原版彩色 glint 纹理，应用滚动矩阵和实时 glintStrength；`world.rchit.slang:617` 合成可见条带 | `round-2/r2_glint_A.png` 异常紫色壳；最终两张 `final_enchanted*.png` 条带位置变化且脸、手臂可见 |
| 关闭光追后仅天空 | **未修复，已复现** | 章节重建、保留上下文、资源重载均未恢复原版场景，全部撤回，未交付这些无效尝试 | `round-2/r2_vanilla.png`、`round-4/r4_toggle_off.png`、`round-8/r8_off_wait.png`，对应运行日志 |
| 远光源白点 | 未复现，未修改 | 玩家视频/截图不能替代相同版本、相同光源场景复现；本地夜景未出现相同大范围白点 | 用户截图与 `final_far_light.png`（测试方位/光源类型不完全相同） |
| 视频中的服务器粒子、近处色块 | 未完成等价复现，未修改 | 已抽帧查看三个视频；没有进入服务器，也未安装对应服务器粒子/材质组合，不能判定 DeepSeek 已彻底修复 | `video-1-contact.png`、`video-2-contact.png`、`video-3-contact.png` |

上表中未写目录的截图均位于 `reports/bugfix-2.2.6-20261101/final-evidence/`；round-* 和视频联系图位于同一本轮 reports 目录。

## 同局 A/B 性能数据

条件：1280×800 显示、427×267 内部渲染、DLSS-RR 质量 3、SPP 1、4 次最大反弹、帧生成关闭、渲染距离 8、120 FPS 限帧、默认资源、单人平台；同一客户端进程内切换系统属性，仅测试包带开关。每阶段预热 10 秒、采样 10 秒，首次入世界预热 30 秒。pt/rr/comp 单位 ms。

**以下是候选修复的同局数据，不冒充最终 Jar 的完整 A/B。** 最终 Jar 移除了开关，另做了真实运行验收。早期测试存在界面/机位操作干扰，不能用它证明加速。

| 场景与样本 | 修改前 pt / rr / comp / FPS | 修改后 pt / rr / comp / FPS | 限制 |
|---|---|---|---|
| 村民与半透明生物，round-5 的 r5_skin_A/B | 2.02 / 1.27 / 3.59 / 117.3 | 2.20 / 1.26 / 3.77 / 116.7 | 透明裁剪有约 0.18 ms pt 代价；不符合严格“无开销”保证 |
| 附魔异常壳，round-2 的 r2_glint_A/B | 1.00 / 1.21 / 2.47 / 106.7 | 0.94 / 1.18 / 2.40 / 116.9 | 此时光效强度尚未完成最终调校，不能作为最终附魔性能结论 |
| 信标，round-2 的 r2_beacon_A/B | 1.43 / 1.20 / 2.92 / 117.4 | 1.54 / 1.19 / 3.05 / 117.5 | 正确透明层需要按已有路径透射；pt 有增加 |
| 文字遮挡，round-1 的 text_A/B | 2.12 / 1.27 / 3.69 / 64.0 | 1.97 / 1.19 / 3.45 / 116.1 | 早期帧率异常，不能推导收益 |

原始 CSV 保留在相应 round 目录。**round-3 全程未成功启用 RT，全部作废，不进入光追验收或性能结论。** round-4 的首个 A 截图出现菜单，亦不能作为有效固定机位对照。

## 编译与最终真实运行

- 开发目录：JDK 25，`gradlew --offline compileShaders compileJava -q` 通过，见 `compile-final.log`。
- 两个新增的有意义测试通过：附魔不改变原几何/索引/基础 UV/材质，透明度元数据不被覆盖。见 `focused-test-final.log` 与 `build/test-results/test/TEST-dev.comfyfluffy.caustica.rt.entity.ModelFoilCaptureTest.xml`。
- 普通完整 test 编译被既有 `RtMaterialLayoutTest.java:40` 旧构造参数阻断；未修改无关测试。定向测试通过本轮 init 脚本隔离这个既有错误；因未配置 DLSS_SDK，测试跳过 `bundleNgxNatives`。
- 打包复用既有 2.2.5 发布包的运行库/依赖，替换本次编译的项目类、资源和 SPIR-V；未重编 NVIDIA 二进制。打包脚本 `package_candidate.py` 可复核。版本元数据与 release marker 为 2.2.6。
- 最终发布 Jar 通过独立单人实例运行：RT composite 在 04:48:59 激活，04:52:48 正常退出，**连续 RT 229 秒**。见 `game-final-stdout.log`、`final-verification.json`。
- 最终 Jar 不含 PerfAutoBench 类或本轮调试属性；测试控制器位于独立的辅助 Jar，退出后已移出 mods。没有在运行中换 Jar，没有强杀启动中的游戏。
- 本次最终运行无崩溃、无新的 Caustica 渲染错误或 DEVICE_LOST。启动期仍有 7 行环境/离线认证 ERROR：Perflib、缺失 Steam/ReShade Vulkan 层、匿名账号属性/Realms/证书。这些错误此前运行也有，不能表述为整份日志零 ERROR。
- 公开仓库编译状态写在下方同步记录中；没有 commit/push。

## 回归检查

| 项目 | 结果与边界 |
|---|---|
| 普通玩家 | 默认皮肤正常，最终 `final_plain.png`；未验证玩家全部自定义皮肤/EMF/ETF 材质包 |
| text_display | 最终可见、被实体墙遮挡；见 `final_text*.png` |
| 半透明生物 | 史莱姆外层与内部可见，见 `final_villagers.png`；未逐种生物验收 |
| 玻璃 | 玻璃前的史莱姆可透视看到；未修改玻璃逻辑，未覆盖全部角度/水下组合 |
| 水、POM、DLSS | 实现未修改；本轮没有完整画质矩阵，不能声称全覆盖 |
| 附魔 | 形状、底层皮肤、可见滚动条带已验证；还需真实材质包与原版同机位精确对照 |
| 文字部分遮挡 | 当前为标签中心点的 CPU 方块遮挡，非逐字形深度；半遮挡与实体遮挡仍有边界 |

## 已知问题与发布边界

1. 关闭光追后可能只剩天空，仍未修复。不要把此版本描述为已解决切换问题。
2. 未证明帧率在所有场景不下降；角色和信标的正确透明处理有可测 pt 开销。
3. 远光源白点、服务器自定义粒子、动态头颅与完整材质包组合仍需等价复现。
4. 字幕/标签部分遮挡采用中心点判断，尚未做到逐像素遮挡。
5. 本轮恢复附魔可见性并保留动画，没有证明与原版逐像素一致。

## 同步记录

公开仓库离线 compileShaders / compileJava 已通过，见 `compile-public-final.log`。首次同步漏掉已有的 `RtEntityTextures.textureLocation` 包访问级别，补同步该依赖后编译通过。当前分支 `main`，git status 共 17 个文件（包含本轮开始前已有的 10 个修改）。已核对全部同步源码字节一致；未提交、未推送。

---

# 以前的修复记录（保留供追溯）

# Caustica Horizon 2.2.6 — 玩家反馈 bug 修复记录

版本：2.2.5 → 2.2.6
日期：2026-10-31
开发源码：`C:\Projects\mc\Caustica-perf-v1`（只在此修改）
发布 jar：`C:\Projects\mc\mc路劲追踪mod\perf-review-20261027\release-2.0\Caustica-Horizon-26.2-fabric-2.2.6.jar`

## 状态总表

| # | Bug | 状态 | 证据 |
|---|---|---|---|
| 1 | 隐形盔甲架的悬浮文字/整套装备不显示 | **已修复并验证** | 见 §Bug1 |
| 4 | 玩家头颅不显示 | **部分修复（隐形盔甲架文字/装备已修）；方块头颅正常；玩家头颅"物品形态"渲染为黑块，未修复** | 见 §Bug4 |
| 5 | 模组路径点不显示 | **未实现**（未下载/未复现，见 §Bug5） | – |
| 2 | 披风与手持物错位（材质包触发） | **未复现**（未装材质包，见 §Bug2） | – |
| 3 | 玩家皮肤马赛克 / 雨天斑点 | **已按代码嫌疑修复（雨幕弹射层数），画质未逐场景验收** | 见 §Bug3 |
| 6 | 重载资源后光追起不来 | **已加保护与日志，未复现玩家时间线** | 见 §Bug6 |

## Bug 1：隐形实体不渲染（悬浮文字 / 实验台组合模型）

### 证据
- `src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntities.java` 原第 883 行
  `if (entity.isInvisible()) { continue; }` 在采集名字（原第 927 行）之前跳过实体；
  隐形盔甲架的装备（书、头颅、旗帜）与 `CustomName` 全部丢失。
- 原第 1052 行 `level.clip(...)` 把相机→文字的射线做了方块遮挡剔除，而原版名字标签走
  `seeThroughNameTags` 相位（无深度测试、穿墙可见），所以贴墙/嵌进方块的文字被额外误隐藏。

### 修改
1. 删除"隐形即跳过"；改为 `meshWanted = invisibleArmorStandWearsSomething(entity)`：
   六个装备槽（双主手/副手/头/胸/腿/脚）全空 → 不建网格、不进 BLAS、不计入 `capturedThisFrame`，
   仅当 `isCustomNameVisible()` 有名字时走一次 `extractEntity` + `captureNameTag`。
   任一槽有物品 → 走完整采集路径，隐形身体由原版渲染状态自行隐藏，装备正常。
2. 其他隐形实体：不建网格，只采集名字。
3. 数量上限/`build.full()` 不再 `break` 整个循环：超预算只关闭"几何"，名字采集继续；
   因此排行榜大量盔甲架时文字不会因上限丢失，也不增加网格。
4. `captureNameTag` 去掉遮挡剔除（与原版一致）。

### 验证（本地单人 CE-bench，2.2.6 bench jar）
- 截图：`...\caustica-perf-sweep\x_rt.png`（光追）与 `x_van.png`（原版）
  —— 隐形 Marker 盔甲架的文字 "Weekly Wins" 在两种模式下同位置、同 aqua 颜色、同下划线。
- `CustomNameVisible:0b` 的那一个不显示（符合原版）。
- 方块玩家头颅（`/setblock ... player_head{profile:{name:"Notch"}}`）在光追上正常显示，与原版一致。
- 性能（同局，ptMin）：无额外实体 5.89 ms → 100 个文字盔甲架 5.97 ms → 200 个 5.97 ms → 基准 5.94 ms，
  即文字盔甲架在本机测量噪声内（±0.1 ms）；`entity capture` 分项无变化。

## Bug 4：玩家头颅

### 证据
- 方块头颅（`/setblock ~ ~ ~ minecraft:player_head{profile:{name:"Notch"}}`）：光追与原版一致，正常。
- 隐形盔甲架头戴 `player_head` 物品：原版显示默认皮肤头像，**光追显示为纯黑色立方体**
  （截图 `caustica-perf-sweep/z_stand_rt.png` vs `z_stand_van.png`）。
- 相关代码：`RtEntityTextures.slotFor(RenderType)`（第 111 行）在解析不到贴图视图时返回
  `whiteSlot()`，`materialIdFor(...)`（第 133 行）走 entityFallback；玩家皮肤是运行时动态贴图，
  物品模型路径下拿不到有效 view/material → 黑块。

### 结论
- 玩家看到的"基座上有头颅、光追里没有"，在本地对应的是**玩家头颅物品（隐形盔甲架头戴/物品展示）**路径，
  不是方块头颅。Bug 1 的修复让隐形盔甲架重新参与采集后，这类头颅会重新出现但**贴图为黑**。
- 未修复：动态皮肤贴图 → bindless 槽位/material 的注册需要在 `RtEntityTextures` 增加"动态皮肤贴图"
  分支并保证皮肤异步加载完成后重建一次 mesh（`meshHash` 不含 UV/贴图槽，贴图后到不会触发重建）。
  这是本轮未完成的项，不做猜测性改动。

## Bug 3：雨天斑点 / 玩家皮肤马赛克

### 证据（代码）
`shaders/world/world.rgen.slang` 雨幕分支（原第 1596-1610 行）：
```
ro = hitPos + rd * SURF_BIAS;
gvBounce = bounce + 1;
cameraThroughRain = bounce == 0;
if (bounce >= maxBounces) break;
maxBounces += 1; // the veil is not a path vertex
continue;
```
雨幕自称"不是路径顶点"，却让 `for (bounce = 0; ...)` 的 `bounce` 自增：雨丝后面的第一个可见表面因此
按 `bounce == 1` 着色，而它的邻居按 `bounce == 0` 着色。两套算法的差别正是报告里列出的那些分支：
俄罗斯轮盘保底下限（0.75 → 0.02）、辐射缓存接管（`rcTake`）、`outgoingReliefVisibility`、
`reliefCameraFlag`、`heldLighting` 层数、`receiverMask`、DH 候选、体积散射、其它 `bounce==0` 专属逻辑。
所以每根雨丝在它后面的表面上留下一个"另一个算法算出来的"像素 → 雨丝形状的亮斑；雨丝网格只在相机周围，
这解释了"离相机近才有"（NPC 附近有斑点其实是相机附近有雨丝）。

### 修改
雨幕不再推进 `bounce`：`bounce--`（由 `for` 的自增抵消）→ 雨丝后的表面就是真正的 `bounce == 0`，
所有 `bounce==0` 规则一次性正确，不用逐处打补丁。
- 新增 `veilCrossings` 计数，上限 `MAX_RAIN_VEIL_CROSSINGS = 8`；超过上限时退回旧行为（占用一个 bounce），
  不可能死循环。
- `gvBounce` 在补偿后设置，指向下一轮实际运行的顶点；`cameraThroughRain` 仍按穿幕时的深度判定。
- 未改 `clearInterfaces`（玻璃/水）逻辑，未加任何亮度钳制，随机数序列不变。

### 验证
- 同局 A/B（CE-bench，抬头看石头平台与树）：雨 4.74 ms pt / 晴 3.60 ms pt（ptMin），与修复前
  "雨比晴贵 1.1-1.5 ms" 的量级一致，未观察到变慢超出该范围；**但修复前后没有同局对照**（两版 jar 分次运行，
  不可比），因此帧率结论记为"未同局验证"。
- 斑点是否消失：截图 `caustica-perf-sweep/z_rain_rt.png`（雨）与 `z_clear_rt.png`（晴），
  本地露天平台场景未复现玩家那种"满屏雨丝亮斑"（本机是 1920x1200 / 渲染 640x400，玩家是 2560x1440 /
  853x456），因此**斑点消失与否未验证**。
- 皮肤马赛克（NPC 身上颗粒）：未复现，未改任何皮肤/半透明/外层逻辑。

## Bug 6：重载资源后光追起不来

### 证据（玩家日志 `C:\Users\West68\Downloads\latest (1).log`）
- 14:57:07 起每次按 T：`RT resumed: released Sodium raster terrain buffers` → `RT context is not ready`
  → `RT terrain is not ready` → `frame controller was not started`，且此后不再出现
  `Vanilla world rendering cancellation active`。
- 整份日志没有 `RT is not rendering`（恢复机制一次都没启动）。

### 代码事实
- `CausticaClient.recoverIfStuck`（第 150 行起）的卡死计时 `RECOVERY_STUCK_TICKS = 60*20`；
  而切换分支里有 `recoveryWaitTicks = 0;` —— 玩家每按一次 T 就把倒计时清零，于是永远走不到恢复。
- `VanillaRenderController.releaseRasterForResume`（第 132-139 行）每次 RT 关→开都做一次 Sodium reload。

### 修改
1. 切换分支不再清零 `recoveryWaitTicks`（只在真正恢复时清零），连按不再阻止恢复阶梯。
2. 重建窗口保护：新增 `rebuildInProgress/pendingToggle`。重建进行中按 T 只记录目标状态并提示，
   等重建到 bring-up 成功后再应用，不再销毁进行中的重建。
3. 重建开始/完成/放弃都有日志（含耗时、尝试次数、`rtInitDone`/context/terrain 状态），
   并在聊天栏提示（`caustica.enhanced.key.rebuild.start/done/failed`，已加入 11 个语言文件）。
4. `releaseRasterForResume` 加 5 秒冷却，连按时不再每次都触发 Sodium reload。

### 验证
- 编译通过；bench 运行 0 崩溃。
- **未复现玩家时间线**：本轮没做"切原版 → F3+T/资源重载 → 切回光追 → 连按 15 次"的复现矩阵，
  因此"是否真的修好"记为未验证；本轮交付的是按键保护 + 恢复计时修复 + 诊断日志。

## 回归检查
- 普通玩家、生物、粒子：多次 bench 运行无崩溃；`crash-reports` 无新文件。
- 名字标签：与原版一致（颜色/下划线/位置）；`text_display` 无回归（截图 `z_td_rt.png`）。
- 玻璃/水：`clearInterfaces` 未改动。
- 实体数量上限：200 个文字盔甲架时文字仍显示，帧率在噪声内。
- 日志无 `reverting to vanilla` / `DEVICE_LOST`。

## 未验证 / 已知问题
1. Bug 4 玩家头颅物品贴图为黑块（未修）。
2. Bug 3 雨幕修复的画质收益未在同机位逐场景验收；皮肤马赛克未复现、未修。
3. Bug 6 未做完整复现矩阵，修复未验证。
4. Bug 2、Bug 5 未取证（需材质包/模组实例与玩家配合）。
5. `world.rgen.slang` 的雨幕改动会让雨丝像素的路径比以前完整，理论上更慢；本轮数据在噪声内，
   但未做同局 A/B。

## 工具
- `C:\Projects\mc\caustica-tools\runplan.ps1`：PowerShell 启动 Prism（bash 会把中文实例名变成 `???`，导致 `-l` 启动失败）。
- `analyze.py` / `vdiff.py` / `plan-*.txt`：同局 A/B 与截图对比。
- 临时调试：本轮没有引入任何 `-Dcaustica.*` 调试开关，交付前无需清理（仅用了 bench 专用的 `PerfAutoBench`，
  该文件不进入发布 jar，已确认 2.2.6 jar 中不存在 `PerfAutoBench.class`）。

## Bug 7：创造飞行时手持光变成小光圈（已修复，截图验证）

证据：HeldLightAnchors.influenceRadius 原第 74-77 行 creativeFlying ? Math.min(configured,3f) : configured；
environment_effects.slang 第 6 行的 heldFalloff 在 d>=radius 时返回 0 → 飞行时半径被压到 3 格，地面只剩一小圈。

修改：去掉创造飞行的半径上限，飞行与站立使用同一配置半径（HELD_RADIUS）。

验证（夜間平台，火把，同局）：地面 3 格 / 6 格 / 10 格截图 caustica-perf-sweep/h_ground.png、h_fly3/6/10.png，
光照范围随高度正常扩大、地面亮度随距离衰减，没有跳变；同局 pt 3.88 / 3.41 / 3.53 / 3.71 ms（ptMin 2.74/2.47/2.42/2.38），无额外开销。
未做：落地/起飞的逐帧跳变检查（本轮测试全程处于飞行创造模式）。

## Bug 8：第一人称手持物不被手持光照亮（已改代码，**未生效/未验证**）

证据：world.rgen.slang 的 heldLighting 对 PAYLOAD_HELD_OWNER 直接返回 0（rchit 第 634 行按 g.reserved.x 设置）。

修改：仅放开相机主光线上的第一人称自身（heldSelfVertex && firstPersonReceiver），第三人称、反射、阴影保持豁免。

验证：**失败**。截图 caustica-perf-sweep/h_hand.png（主手火把、俯视看手）里手仍是黑的，说明除了这个 early-out，
还有别的原因（手持物与手的几何/材质，或 shadow ray 被自身挡住）。本轮未继续定位，记为未修复。

## Bug 3 追加：雨丝进入反射/玻璃/水路径（已改代码，未逐场景验收）

证据：雨幕分支在任意 bounce 上都会执行并加 L；PARTICLE_MASK = MASK_PRIMARY | 0x10 让粒子被镜面/玻璃/水看到。

修改：雨幕分支新增 if (!primaryCamRay) 提前跳过——反射、玻璃、水路径完全看不见雨丝（不加光、不占 bounce、
不写引导，eilCrossings 仍限 8 层）。雨滴溅落（WaterDropParticle，同走 rain style）也一并排除。
其他粒子（火花、烟等）仍保留 0x10，继续进入反射。选择的是空分支方案而不是拆 mesh：不新增 BLAS/TLAS 实例、没有额外提取开销。
cameraThroughGlass（相机自己的直线视线穿过透明玻璃）仍保留雨丝，且不增加路径深度。

验证：雨天露天 / 隔玻璃 / 镜面抛光方块 截图 
_raopen.png、r_raglass.png、r_ramirror.png（同局 pt 4.77/5.28/6.73）
与晴天对照 
_clmirror.png（6.03）。**未能确认玩家报告的白斑是否消失**（本机 1920x1200/渲染 640x400，
玩家 2560x1440/853x456；且没有 2.2.4 对照）。

## Bug 9：设置界面显示原始 key（已修复）

证据：RtVideoOptions 用 caustica.enhanced.section.<name>、caustica.enhanced.param.<name>、
caustica.enhanced.<toggle> 生成标签，但 11 个语言文件里这些 key 从来没加过（缺 162 个），
于是界面显示 caustica.enhanced.section.tracing 之类原文，右侧按钮文字被截断。

修改：dd_missing_lang.py 按代码引用逐条补齐 162 个 key，写入 en_us.json、zh_cn.json、zh_tw.json；
其余 8 种语言按 Minecraft 标准回退到 en_us，不再出现原始 key。复核脚本 list_missing.py 输出 
eferenced 314 missing 0。

## Bug 10（新登记，已改代码，画质未定论）：附魔光效呈方形包裹

玩家截图：附魔盔甲的光效不是贴在模型表面，而是方方正正包了一层。代码位置：
RtEntityCollector.addFoilQuad + 	extureMatrix(RenderTypes.glint()) + RtGlintTransform（第 375-382、407-419 行）。
本轮未定位、未修改，明确记为未修复。


修改：RtEntityCollector.submitModel 新增 glintPass（renderType 的 pipeline 名含 glint）。附魔光效是同一模型的**第二遍绘制**，
而 cuboid 快捷路径按模型类缓存模板，光效这一遍因此复用了基础那一遍（护甲还是外层膨胀尺寸）的立方体，画成了包裹模型的方盒。
现在 glint 一律走精确路径（RtModelCompat 回放 / 
enderToBuffer），立方体来自实际绘制的姿势。普通实体仍走 cuboid 快捷路径。

验证：本机召唤穿三件保护 II 钻石甲的盔甲架，光追截图 caustica-perf-sweep/g_glint_rt.png / g_glint_rt2.png 与原版 g_glint_van.png
同机位对照；同局 pt 9.40 / 9.08 ms（ptMin 6.73 / 6.62），无额外开销。**截图裁剪后仍不足以断定方形包裹消失**，
需要按玩家那张构图（盔甲穿戴全身 + 侧后方视角）再确认一次。


## Bug 11：皮肤/机体白点噪点（已改代码，需玩家确认）

证据：`world.rahit.slang` 实体 any-hit 对 `stochasticAlpha` 材质用 `alphaDitherThreshold`（按 frameIndex 旋转的 8x8 阈值）
做逐 texel 覆盖率判定；资源包 HD 皮肤/ETF 外层几乎每个 texel 的 alpha 都 < 1，于是每帧随机通过或丢弃，
DLSS-RR 无法积分这种逐帧变化的遮罩 → 满身白点。

修改：实体 texel 判定改为纯 cutout（`texel.a < ENTITY_ALPHA_CUTOFF` 才丢弃），取消随机覆盖率；半透明生物仍走 tinted veil 分支。

验证：slime / zombie / 盔甲架 / 水面截图（`caustica-perf-sweep/n_*.png`），同局 pt 7.42/7.58/7.81/6.80，无崩溃。
**没有复现玩家的 HD 皮肤 NPC**，白点是否消失需玩家确认。

## Bug 12：树叶不透光（已改代码，未逐场景确认）

证据：`world.rahit.slang` 的 `BUCKET_TRANSLUCENT` 分支只按整张 sprite 平均色做 Beer-Lambert 衰减，没有逐 texel cutout；
fancy/HD 树叶的洞在 translucent 桶里，叶块于是变成实心立方体并挡住自己的太阳阴影射线。

修改：translucent 桶先按实际 UV 采样图集，`alpha < ALPHA_CUTOFF` 直接 IgnoreHit，再做原平均色衰减。

验证：截图 `l_forest_rt / l_forest_van / l_forest_rt2 / l_under_rt / l_canopy_rt`；同局 pt 9.05 / 9.58 / 10.54 / 9.22。
**没有同局改前对照**（改前同场景约 8.4 ms），变慢多少未确认；也未近距离确认洞是否恢复。

## Bug 13：穿盔甲后整个人发黑（已加开关，未验证）

证据：盔甲内表面离身体 1-2 texel，身体顶点的太阳光射线首先打到自己身上的盔甲，只穿靴子也会让上半身全黑。

修改：新增配置 `entities.armor-skin-lit`（默认 true，`CausticaConfig.Rt.Entities.ARMOR_SKIN_LIT`），经 lookSwitches 第 4 位下推；
开启时太阳 NEE 射线起点沿法线外移 0.12 格跨过盔甲外壳。盔甲自身 PBR、反射与对世界的投影不变。

验证：`caustica-perf-sweep/a_full_rt / a_off_rt / a_on_rt`（同局 pt 9.00 / 9.25 / 9.08）。截图机位太近，结论未定；
是否解决"黑得太片"需玩家确认。上一版修掉的附魔光效方盒也在给身体投影，可能同时改善。

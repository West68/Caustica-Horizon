# Caustica Horizon 2.2.8 修复与验收记录

> 本文件为累计记录。前半部分是首轮验收；本轮追加结果、最终 Jar 与校验值见文末。

结论：本轮三个主要问题——物品玻璃/RR 引导、RT 长间隔开关失效、已定位的雨夜矿石亮斑——均已在本地验证处理；不能据此宣称所有白点、服务器 NPC 或手部穿模问题全部解决。原有信标、文字遮挡、村民/盔甲皮肤问题通过本轮回归。严格的“所有场景帧率不下降”尚未达到证明标准。

## 版本与操作边界

- 开始时开发目录已为 2.2.8，公开仓库 main 为 2.2.7（08bb53da）。保留现有版本，未降级为历史要求的 2.2.6。
- 只在开发目录修改代码；最后逐文件同步公开目录。未提交、未推送、未永久删除文件。
- 初始状态记录：`reports/rain-night-20261101/dev-status-before.txt` 与 `pub-status-before.txt`。开发目录大量既有改动未回滚；公开目录初始干净。
- 仅离线账号、本地单人、复制世界 CE-natural；没有连接玩家服务器，没有修改正式存档或 PCL。
- 所有 Jar 替换在测试进程正常退出后进行；旧 Jar、Prism 配置保存在 `reports/rain-night-20261101/delivery-backups/`。
- 原来的 REMIX_PORT_LOG.md 未修改。本轮临时 shader/探针开关未进入交付源码；外部测试辅助 Jar 已移出测试 mods。既有其他开发工具不作为本轮新增调试代码删除。

## 根因、证据与修改

|问题|代码证据|处理|
|---|---|---|
|物品玻璃不透光|`src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntityCollector.java:405`|识别 solid/cutout 管线里的玻璃，保留真实图集 UV；透射模型进入 any-hit 阴影桶。|
|玻璃后 RR 模糊|`shaders/world/world.rchit.slang:658`|将平面玻璃物品纳入既有薄板延续路径，给 RR 背景深度、法线和运动引导，而非只给前玻璃面。|
|RT 关闭后仅剩天空|`src/main/java/dev/comfyfluffy/caustica/client/RasterChunkUpdates.java:8`|暂存净区块可见性通知，在恢复原版渲染时一次重放；同时恢复裁剪状态，按实际取消状态决定实体提取。|
|雨夜白色矿石斑|`src/main/java/dev/comfyfluffy/caustica/rt/material/RtMaterialRegistry.java:50`|新增矿石附加自发光开关；仅屏蔽非发光矿石材质附加发光，保留火把和点亮红石矿。默认保留材质包效果，本地实例设为关闭。|
|雨天涟漪过慢/稀疏|`shaders/world/environment_effects.slang:14`|加入速度、密度、波环频率三项设置，每轮随机落点；保持九格采样，不增加图像 Pass。|
|树叶荧光描边|`shaders/world/world.rgen.slang:2016`|同局将 look.sun-rims 从 1 改为 0 后描边消失。属于既有艺术轮廓光效果；只将本地 Prism 设置关闭，未删除正常 SSS。|
|手部穿模|`src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntities.java:825`|原有碰撞回缩逻辑保留。补测 EMF/ETF 后近墙、侧墙、低头、持物未重现用户的截断，未声称修复。|

实测证据目录：`C:/Projects/mc/Caustica-perf-v1/reports/rain-night-20261101/`。

- `clean-ab/clean_glass_A1.png`、`clean_glass_B1.png`：同一玻璃后的支架细节；新路径更清楚。
- `clean-ab/clean_white_A1.png`、`clean_white_B1.png`：矿石附加自发光开启/关闭，非关闭全局发光。
- `final2/leaf_rim_on.png`、`leaf_rim_off.png`：树叶亮边来源对照。
- `final2/final_glass_head.png`、`final_glass_hand.png`、`final_glass_drop.png`：头戴、手持、掉落玻璃透射。
- `final2/final_regression.png`、`final_text_occluded.png`：村民正常、史莱姆保留透明性、text_display 可见且被实体墙遮挡。
- `final2/final_enchant.png`、`final_enchant_motion.png`：盔甲皮肤正常，保留变化的紫色附魔效果。
- `final2/final_beacon.png`、`final_beacon_motion.png`：信标光束仍有动画和低透明外层；未简单删除外层。
- `final2/final_water.png`、`final_water_motion.png`：速度 2、密度 2、波环频率 1.5 的雨面动画。参考用户视频视觉目标独立实现，未引入其代码或贴图；浅黄底池塘仍明显受底色影响，不宣称复刻参考光影。
- `hand-emf/hand_emf_06.png`、`hand_emf_03.png`、`hand_emf_side.png`、`hand_emf_ground.png`、`hand_emf_wall_item.png`：EMF/ETF 的近墙和持物补测；本次未复现手截断，非已修复声明。近墙局部大黑影仍可见，未在本轮处理，见已知问题。

## 性能：同局 A/B

唯一用于最终结论的数据：`clean-ab/autobench-result.csv`、`clean-ab-stdout.log`、`clean-ab-plan.txt`。早先 ab/ab3 与编译重叠，不用来宣称性能结果。

条件：同一 Java 进程、同一复制世界、同一材质包、输出 1280×800、内部 427×267、RR quality=3/preset=6、FOV=70、1 SPP、4 最大反弹、8 区块、120 帧上限；每步稳定 10 秒、采样 10 秒。玻璃与白点各两对，涟漪三对，固定视点和动画相位；平均数使用所有成对样本。帧生成设置实际为开启，但日志明确报告没有插帧，不能写“帧生成关闭”。

涟漪行的普通实体预算为 0，以隔离雨水着色器；其他行预算 1024。天气、粒子、方块实体保持启用。B 为本轮路径/功能，A 为旧引导/旧涟漪或矿石发光开启。

|场景|修改前 pt/rr/comp(ms)/FPS|修改后 pt/rr/comp(ms)/FPS|
|---|---|---|
|物品玻璃 RR|2.29/1.34/3.97/115.60|2.08/1.24/3.63/113.05|
|雨夜矿石发光开/关|4.09/1.31/5.73/104.35|4.04/1.31/5.65/109.25|
|雨天涟漪|6.76/1.39/8.48/101.53|6.74/1.32/8.38/103.53|

comp 是整体复合 GPU 区段，包含 pt 与 RR，不能把三列相加。玻璃 GPU 时间降低，但平均 FPS 115.60→113.05；因此不承诺“帧率绝不下降”。本地结果不能替代其他显卡与整合包测试。

## 编译、自动检查与真实游戏

- 开发源码：`./gradlew.bat --offline compileShaders compileJava -q` 成功；记录 `compile-final-2.2.8.log`。
- 定向 JUnit：9 个测试，0 失败（玻璃材质 3、区块通知 4、附魔捕获 2）。记录 `tests-delivery-v2.log`、build/test-results/test。
- 全量 test 因既有 RtMaterialLayoutTest 旧构造签名而未通过编译，未将定向测试表述为全套通过。
- 标准 Jar 原生打包依赖当前未配置的 DLSS SDK；交付使用新编译 Java/SPIR-V/当前资源，以及 2.2.7 已有原生库与内嵌依赖重新组包；脚本 `package_release.py` 逐字节验证原生文件未变。没有从旧 2.2.5 继承项目代码。发布元数据为 2.2.8，开发辅助类及临时 A/B 开关不在最终 Jar。
- `final2-stdout.log`：光追/RR 06:20:16 启用；06:28:49 关闭，06:31:49 再开（关闭整 180 秒）；随后又关闭/开启两轮；06:34:30 正常退出。这里时间为日志主机本地时间，报告日期按 America/Guatemala 2026-11-01。
- `final2/final_off_170s.png` 显示原版地形与实体仍在；`final_resume_wait.png`、`final_stable.png` 显示重开后 RT 世界正常。连续 RT 测试超过 3 分钟。
- 初始化有既有离线账号/Realms、Perflib、Steam/ReShade 缺失层错误，以及 MFG 缺少本地第三方组件警告；进入世界后的本轮测试未出现新渲染 ERROR、崩溃或设备丢失。不能称整个日志“没有错误”。
- 公开目录同样的离线 compileShaders/compileJava 编译成功，记录 compile-public-2.2.8.log；git diff --check 无空白错误，当前 main 的 git status 共 22 个文件。未提交、未推送。

## 已知问题与未验证项

1. 手部穿模未复现；未测试玩家截图中全部客户端模组、模型及特定动画。0.3 格贴墙时可见局部大黑影，需独立定位，不作为手穿模已解决。
2. 已确认矿石附加发光亮斑可关闭；普通湿面偶发采样噪点/其他远处亮点不能保证全部消除。
3. 服务器 NPC 间歇消失未连接原服务器验证；仅验证本地实体和开关恢复。
4. 不保证其他画面逐像素不变或所有场景 FPS 不下降；涟漪和玻璃修改本身会改变目标区域。
5. 本轮不是整个仓库的版权合规审计，不能据此证明原项目全部来源已获授权。

## 本地安装与设置

- Jar：`C:\Projects\mc\mc路劲追踪mod\perf-review-20261027\release-2.0\Caustica-Horizon-26.2-fabric-2.2.8.jar`
- 同一 Jar 已安装：`C:\MINECRAFT\Minecraft_Game\PrismLauncher-Windows-MinGW-w64-Portable-11.1.1\instances\Prism-26.2 vulkan 光追\minecraft\mods\Caustica-Horizon-26.2-fabric-2.2.8.jar`
- SHA-256（交付时 sha256sum 现算）：`7053ee0bb59a78a871a180af097210f5a47288bcf9fd3808ed5057e4acf051b8`
- Prism 保留原配置，只设置：矿石附加发光关、阳光轮廓光 0、涟漪速度 2/密度 2/频率 1.5。模组默认值仍保留原材质包发光和艺术效果；其他玩家可自行调节。


## 2026-11-01 追加：自身模型透射、骷髅、第一人称与手持照明

版本仍为 2.2.8。相对上一轮交付，只追加上述五个代码文件；保留公开目录已有 22 项未提交改动。本次新增问题未全部宣称解决：水面圆形分层/靠近水面严重掉帧，本地未复现。

### 状态与根因

|问题|证据|修改|
|---|---|---|
|透明表面后看到自身模型内部|`shaders/world/world.rgen.slang:1328`|连续透射保留相机可见性；反射或不透明散射后恢复完整场景；折射 RR 引导使用同一规则。|
|洞穴骷髅头发黑|`src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntityCollector.java:309`|角色透明层保留标记扩到 Humanoid；间接光不再强制把骷髅等角色的透明帽层当成实心遮挡。|
|First Person Model 身体缺失|`src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntities.java:1024`|追加只供相机的头部隐藏模型；完整模型仍供反射和阴影；复用原始相机偏移与姿态。|
|第一人称头部错误遮挡|`src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntityCollector.java:502`|相机身体捕获期间临时隐藏 head/hat，结束后恢复原可见性；反射模型不受影响。|
|手持物品不受手持灯照亮|`src/main/java/dev/comfyfluffy/caustica/rt/entity/RtFirstPerson.java:28`|取消只有 Punchy/EMF 才捕获手部的门槛；原版手和持物也参与既有 RT 手持照明。|

- 自身透射空壳：已修复并验证。正常相机隐藏的模型不再因为穿过透明界面就重新显露；水面 RR 折射 albedo 也不再包含该模型。没有全局删除玩家、阴影或正常反射。
- 骷髅/流浪者头部：已修复并验证；同一洞穴双侧海晶灯，修复前头部整黑、albedo 正常，修复后头部正常受光。
- First Person Model：已修复并验证基本兼容。第一人称低头可见身体/双手但不被自己的头遮挡；F5 完整头部和盔甲仍可见。未覆盖全部泳姿、飞行姿势、YSM 等自定义模型与整合包。
- 原版手持照明：已修复并验证。不安装 FPM、EMF、ETF 时，同局切换旧栅格手/新 RT 手，主手石头由全黑变为可见受光纹理，副手火把正常发亮。背光的面仍可能较暗，未通过强行提高全局亮度处理。
- 水面分层/近水掉帧：未复现。浅池和 51×51、8 格深池、第一/第三人称、RR 与引导模式对照，未看到报告中的固定圆形分界；未据此否定原报告，未擅自改动水的 Fresnel、反射权重、吸收和波浪逻辑。

### 可追溯证据

目录 `reports/self-transmission-2.2.8/`：用户视频抽帧 video-contact.png；baseline/self_water_down.png、baseline/self_glass_down.png 可见原空壳；ab2/water_A1.png、water_B1.png 及 angle_A.png、angle_B.png 同局切换可见性规则。

目录 `reports/extra-bugs-2.2.8/`：

- baseline/skeleton.png 与 candidate1/fixed_skeleton.png：相同位置、灯光的骷髅头部前后；baseline/skeleton_albedo.png 证明贴图正常。
- baseline/first_person_down.png 原来无身体；fp-final/final_fp_body.png 可见身体且头部隐藏；fp-final/final_fp_f5.png 完整头部仍在。
- bare-final/hand_A1.png、hand_B1.png：不安装第三方第一人称/EMF 模组的手持照明同局对照。
- bare-final/bare_water_albedo.png：水下背景引导恢复为池底，没有自身空壳。bare_water.png 中仍有自身投在池底的阴影，未为了抹掉阴影而禁用正常遮挡。
- bare-final/bare_item_glass.png、bare_entities.png：物品玻璃、村民、史莱姆与 text_display 回归。
- candidate2/pool_deep.png、pool_deep_no_psr.png、pool_deep_rr_off.png、pool_deep_rr_on.png：深水诊断。关闭 RR 会改变内部渲染分辨率，不能用其帧率作为同分辨率性能对比。
- fp-final-stdout.log：最终同一 Jar 带 First Person Model 连续运行超过 3 分钟，正常退出；bare-final-stdout.log 为无附加模组回归。两次均实际启用 RR，而非仅原版测试。

First Person Model 2.7.3 / mc26.2 与必需依赖 Not Enough Animations 1.12.7 / mc26.2，按用户要求从 Modrinth 作者项目下载；仅用于独立实例，未加入 Prism 正式 mods、公开仓库或交付 Jar。下载文件保留在本报告 downloads/；API 来源 https://modrinth.com/mod/first-person-model 与 https://github.com/tr7zw/FirstPersonModel 。桥接为独立 API 调用实现，未复制其渲染实现。

### 本轮性能

同局 A/B，每步稳定 10 秒、测量 10 秒；1280×800 输出、427×267 内部、RR quality 3、preset 6、1 SPP、4 最大反弹、FOV 70、固定位置/相位。没有并发编译。列为 pt/rr/comp 毫秒/FPS，comp 已含 pt 与 RR。

|场景|旧规则/旧手|新规则/RT 手|
|---|---|---|
|低头水面，两对|3.69/1.35/5.44/103.15|3.13/1.26/4.71/109.50|
|斜看水面，一对|3.82/1.26/5.40/110.20|3.39/1.22/4.94/106.70|
|无附加模组的手持照明，两对|1.50/1.29/3.06/111.75|1.54/1.31/3.12/113.25|

透射数据来自 self-transmission-2.2.8/ab2/autobench-result.csv；手部来自 extra-bugs-2.2.8/bare-final/autobench-result.csv。前者在无 FPM 条件下；后者只有 Fabric API 与本模组。FP 相机模型会追加几何，未完成其独立性能 A/B。斜看水面的平均 FPS 略降，原版手 RT 化也有约 0.06 ms 复合成本，不能承诺所有场景不掉帧。

本轮未新增追踪光线、采样数、反弹或图像 Pass；复用已有透射/引导光线，FP 模式追加相机专用几何。

### 构建与最终交付

开发源码离线 compileShaders/compileJava 成功：compile-delivery.log；原有三个定向测试组共 9 个测试通过：tests-delivery.log。不将这表述成全量测试通过。打包方式沿用首轮已说明的编译产物加现有原生库组包，临时 selfTransmissionBaseline 和测试辅助类未进入最终 Jar。

- Jar：`C:\Projects\mc\mc路劲追踪mod\perf-review-20261027\release-2.0\Caustica-Horizon-26.2-fabric-2.2.8.jar`
- Prism mods：`C:\MINECRAFT\Minecraft_Game\PrismLauncher-Windows-MinGW-w64-Portable-11.1.1\instances\Prism-26.2 vulkan 光追\minecraft\mods\Caustica-Horizon-26.2-fabric-2.2.8.jar`
- 最终 SHA-256（sha256sum 现算）：`7053ee0bb59a78a871a180af097210f5a47288bcf9fd3808ed5057e4acf051b8`
- 旧版同名 Jar 和覆盖前公开源码备份：`reports/extra-bugs-2.2.8/delivery-backups/`。
- 公开源码同样的离线 compileShaders/compileJava 成功，记录 compile-public.log；git diff --check 无空白错误；main 当前 25 个待提交文件。未提交、未推送。
- 初始化仍有本机既有离线账号、Perflib、Steam/ReShade/MFG 环境警告，未改驱动或第三方原生库。进入世界后未发现新的渲染 ERROR、设备丢失或崩溃。

未验证/已知问题：玩家原世界的圆形水面分层与严重掉帧；全部第一人称姿态/模型；其他类型雨夜噪点与原先未复现的手部截断；服务器 NPC。全场景 FPS 不下降的目标仍未达到证明标准。

最终复核：相机头部隐藏限定为 Humanoid 角色部件，不误删手中骷髅头等物品模型。最终同一 Jar 的 review-stdout.log 启用 RR 连续运行超过 3 分钟后正常退出；review/review_body.png 与 review_f5.png 验证头部按视角隐藏/保留。首轮历史行号对应首轮源码；追加表行号对应当前源码。最终公开编译已通过，25 个文件逐个与开发目录比对一致。

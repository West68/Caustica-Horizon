# Caustica Horizon 3.1.15

## 新增

- 视频设置首页新增「ITRP 精选」按钮。把调好的 ITRP Alpha 0.8.x 光影配置搬进 Caustica，一键切换；原有的「ITRP 推荐画面」和「Caustica 原版画面」保留不变。
- 新增 ITRP 色调曲线选项：PTGI（原有曲线）或 GT（ITRP Alpha 的 Gran Turismo 曲线）。
- 新增 ITRP 暗角强度：0 为原有的 PTGI 暗角，大于 0 时使用 ITRP Alpha 的暗角算法。
- 新增 ITRP 火把颜色（绿、蓝分量），开启「ITRP 光源颜色」时生效。
- 云的阳光强度上限从 2 放宽到 5。
- 新增「末地星云」开关（世界页面）。ITRP 精选预设默认关闭末地星云。
- 新增「体积云（ITRP Alpha）」云模式：真正的立体云层，带云影，14 个参数与 ITRP 体积云选项一一对应（高度、厚度、云量、密度、尺寸、阳光/天空光、噪声频率、细节强度、质量等）。ITRP 精选预设默认使用体积云。

## 修复

- 修复使用 Fresh Animations / EMF 时，怪物身上的盔甲（头盔、胸甲等）不跟随动画、与身体脱节的问题。
- 体积云：修复太阳透过云层、云被拉成斜条纹、阴天时云层与天空同色等问题。

## 说明

- ITRP 精选预设只修改画面、云、雾、曝光等外观设置，不会改动反弹次数、采样数、DLSS、帧生成等性能设置。
- 体积云按公开的 Nubis 技术自行实现，噪声在加载时程序生成，未使用 ITRP 源码或贴图。
- ITRP 的植物摆动、灯笼体素化等选项在 Caustica 里没有对应功能，未移植。
- 透过玻璃（含彩色玻璃）看到的远处现在也有雾，之前玻璃后面完全没有雾气（两个 ITRP 预设都受影响）。
- ITRP 精选：修复远处 LOD 区块发黑（预设把雾的天空散射设成了 0，雾只吸光）；云量与厚度调低，不再盖满天空；泛光恢复默认强度，太阳有光晕。
- 新增「团雾层」（雾气页面）：在两个高度之间铺一层成团的白雾，站在高处能看到云海，可调底部/顶部高度、衰减、覆盖率、环境光、阳光、吸收率。默认关闭。
- ITRP 精选改为按 ITRP 体积雾设置开启团雾层（Y30~100，顶部最浓，环境光 3，全天有雾），不再把雾的整体浓度调高。
- 体积云：云的浓度改为按云层厚度计算，与视线角度无关。之前按视线走过的距离累计，地平线方向的云被叠成一整片挡住天空；ITRP 精选恢复为原预设的厚度 1700、覆盖率 0.34、密度 0.1。
- 团雾层改为以中心高度为准上下对称变淡，雾团更小更碎，更接近 ITRP 团雾模式。
- ITRP 精选：曝光过渡时长改为对应自动曝光的适应速度（之前误设成曝光亮度）；开启 ITRP 画面时「曝光基准」作为 ITRP 的曝光曲线 AE_CURVE。
- ITRP 画面的水：吸收改为 ITRP 原值 WATER_ATTENUATION (.35,.13,.11)，水中散射改用 ITRP 的青绿色 WATER_SCATTERING (.1,.6,1)，不再用之前偏清、偏蓝的近似值。
- ITRP 画面的水按 ITRP WaterFog 重写：水上看是随深度变浓的固定水色（0.2/格），水下才有光束；新增水吸收 RGB、水散射色 RGB、水散射浓度、水波强度/尺寸/速度共 10 个参数。
- 新增「基础雾浓度」（ITRP 的 VFOG_DENSITY_BASE），ITRP 精选设为 0：不再有一直升到天上的整片底雾，只保留远处地表大气和团雾层。
- 团雾层在天空方向只算到 视距×1.4（同 ITRP），地平线雾不再叠到天上。
- 体积云密度改用 ITRP 的形状曲线与 .4/.4/.2 噪声权重。
- 团雾层：噪声改为不重复的格点噪声（原纹理每 40 格重复一次，出现规律条纹）；玩家周围 12–64 格内逐渐变清晰；ITRP 精选中心高度 100→72、衰减 40→16，雾贴近地面。
- 新增雨天云密度、雨天云缩放（ITRP 的 CLOUD_RAIN_DENSITY / CLOUD_RAIN_SCALE）；ITRP 精选设为 1.0 / 0.6，下雨时云是晴天的 10 倍浓，天会阴下来。
- 云噪声纹理按 ITRP 原纹理的统计重新生成（主通道均值/对比度/频率 0.670/0.136/8.3，ITRP 为 0.672/0.133/8.6；原来是 0.738/0.070/4.1）：云团缩小一半、起伏更明显，不再连成一整片。
- 团雾层新增「远景薄雾」（ITRP 精选 0.06）：远处景物融进淡雾；近处清晰范围扩大到 24–112 格。
- 云覆盖图加一层更细的噪声，下雨不再从一个方向整齐推进、放晴不再整齐退回。
- ITRP 画面下团雾和水的环境光改用实际显示的 ITRP 参考天空（之前读 Caustica 天空表，偏暗偏暖，远处雾发褐发黑）。
- ITRP 精选远景薄雾 0.3。
- 雨后湿地按 ITRP：湿度过半时法线贴图拉平成镜面，地面倒影更完整。
- DH 远景树叶让阳光穿过（每层约 55%），远处树冠内部不再是黑方块。
- 团雾噪声分布对齐 ITRP 的 noise.png（均值 0.654、标准差 0.109）：平均雾量从 ITRP 的一半提到相同（0.277 对 0.273），远处自然形成均匀淡雾；ITRP 精选远景薄雾改回 0（预设 VFOG_DENSITY_BASE=0）。
- 去掉 ITRP 远景大气多出的透射衰减（ITRP 只叠加散射光）：远处不再发褐发黑，下雨时那条约 2000 格的分界线消失。去掉云覆盖的第二层噪声，云不再碎。
- 植物摆动（按 ITRP WavingPlants）：树叶、藤蔓、草、花、高草、农作物随风摆动，下雨幅度加大，被遮挡照不到天光的不动；玩家 64 格内生效（可调）。光追实现：附近的植物区块保留顶点，每帧计算着色器变形后原地 refit BLAS，并输出逐顶点运动矢量给 DLSS-RR。新增「植物摆动」「植物摆动范围」设置。
- 兼容 Fancy World Animations：灯笼与锁链摇晃、门/活板门/栅栏门/拉杆/钟等方块动画在光追中显示（之前静止）。
- 修复 FWA 动画在光追下不动：FWA 的时钟只在原版世界渲染里推进，光追取消了它，现在由光追推进。光追时关闭 EntityCulling 的方块实体剔除（它按原版画面判断遮挡，导致附魔书等时隐时现）。
- FWA 动画方块使用与地形相同的完整 PBR（法线/高光/自发光，按方块状态解析，摇晃的灯笼照常发光）。动画结束时等光追区块把静止方块发布后再移除动画模型，不再出现方块短暂消失。
- 植物摆动噪声改用三次 B 样条平滑（同 ITRP 的双三次模糊噪声），消除每隔约半秒的跳动和 DLSS 残影造成的重叠感。
- 植物摆动按每个面自己的贴图中心判断根部（同 ITRP 的 mc_midTexCoord），修复资源包立体草叶一半摆动一半静止、看起来两层重叠。
- 植物摆幅改为按顶点在方块内的高度（根部 0、顶端 1），资源包像素立体草的各个小面同点同动，不再撕成两层；原版十字草效果与 ITRP 相同。
- 镂空层（草、花、树叶、作物）不再做视差贴图：资源包高度图让单面草叶显示出错开的第二层。DH LOD 的覆盖剔除移到相交着色器：漫反射之后的光线强制不透明、不执行 any-hit，和真实树冠重叠的 LOD 方块挡住了间接光，树冠里出现暗块。
- 末地吸积盘光线步进加入逐像素逐帧抖动（同参考实现），放大后不再出现阶梯状方块切片。
- 植物/树叶的背光透射（次表面散射）阴影光线从离开自身方块处开始：交叉草另一张面片的草叶不再被投到这张面片上，修复「两层草」。
- Cross plants: a cutout face and its coincident back face are now one-sided in any-hit (the mirrored back face no longer shows through as a second layer of blades).
- Waving: data sections outside the waving range get one rest pass so their motion buffer is never read uninitialized (black blocks smeared by RR in grass); hit shader rejects non-finite or oversized terrain motion.
- DH LODs: sections leaving the wanted set stay cached 600 frames, so a section that comes straight back is drawn at once (intermittent distant flicker); [DH-RT] log splits evictions by cause.
- Cross plants: same-sprite front/back pairs keep only the front card (two-sided) instead of an any-hit facing test, which darkened grass clump centres; fewer triangles, full OMM.
- Perf: fog layer returns early for segments ending within 24 blocks (profile is zero there); volumetric clouds on bounce rays march 4 steps (was 8); camera-ray cloud light march skipped for steps under 0.1% contribution or behind 95% opacity.
- Litematica overlay depth skips clear glass on the camera's straight line, so projections behind windows are no longer hidden where terrain/DH lies behind.

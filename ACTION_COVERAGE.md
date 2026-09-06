# 玩家动作接入核对（Minecraft 26.3-pre-1）

本表区分代码调用链、数值验证和游戏内验收。资源存在、预览命令可播放、数值正常，均不代表动作已在真实游戏画面中验收。

| 状态 | 片段数 | 含义 |
| --- | ---: | --- |
| wired_not_playtested | 337 | 已有输入与渲染代码路径，未进行游戏内动作视觉验收 |
| asset_only | 110 | 只有片段或预览能力，没有自动输入到渲染的接线 |
| vanilla_fallback | 2 | fp_use_spyglass 及左手版本因开镜被遮蔽，不计入手臂绘制覆盖 |

完整的 449 项清单见 `tools/motion/action_coverage.tsv`。运行 `python tools/motion/audit_coverage.py` 可按本轮明确核对的入口重新生成；该脚本不是运行时遥测，新增输入逻辑时必须同步审核对应映射。

## 本轮接入

- 移动与上半身：`END_CLIENT_TICK -> PlayerRenderManager.sample -> PlayerActionController.Playback -> PlayerInstance -> play(base) + playOverlay(upper/) -> native AnimationController -> RenderFeature`。上半身 VMD 在资源生成时去除根、腰、腿和足 IK 轨道；原生层只对掩码内骨骼淡入淡出，底层移动时钟继续运行。受伤、死亡、睡眠等全身动作按原优先级处理。
- 战斗输入：`MultiPlayerGameMode.attack RETURN -> PlayerInteractionMixin -> ClientBootstrap.attack -> CombatActionController -> pendingActions -> tick sample`。七类武器接地面三段、空中两段连击，按主手左右镜像；换武器、落地/离地、tick 回退及超过 `tools/motion/playback.json` 中的 `combo_reset_ticks` 时重置。仅本地实体攻击有这条连击入口；空挥与远端玩家仍走原有 swing 采样。它只改变表现，不增加伤害、位移、重击或弹反规则。
- 第一人称：`GameRenderer -> FirstPersonHandsAndItemsRenderer.submitHandsWithItems -> FirstPersonHandsMixin -> PlayerRenderManager.submitHands -> FirstPersonInstance -> RenderFeature`。动作从相同 tick 播放状态映射到 `fp_` 片段；没有对应片段时用 `fp_use_item` 持握姿态。独立实例及索引缓冲只提交手臂权重占多数的三角形，沿用手部选择、主手方向及视图矩阵。成功提交后才抑制原版空手绘制。

## 明确未接通或有限制的部分

- 重击与蓄力、方向闪避、guard/parry/counter/lock_on：没有可靠的对应输入或游戏机制信号，仍为资源片段。原版盾牌使用走 `use_block`。
- `attack_mace_smash`、`attack_sword_sweep`：未接实际重锤下砸/横扫结果；不能以普通挥手等同命中效果。原有 critical 选择仍是下降状态推断。
- `interact_pickup/feed/equip/swap_hands`、`use_map/fishing_hold/crossbow_hold/two_hands`、`attack_swing` 及对应镜像/第一人称变体：没有自动入口。
- 第一人称地图整条路径回退原版；开镜不提交自定义手臂；缺少可识别的头部、双臂骨架或网格时回退原版。
- 第一人称持有物仍由原版渲染，尚未挂接 MMD 手骨；握持对齐、镜头裁剪、袖口边界与不同模型的视觉效果待游戏内验收。
- 上半身遮罩与手臂识别针对标准日文 MMD 骨骼名；非标准命名模型的覆盖不作保证。

## 本轮实际验证

- `./gradlew.bat build '-Plibmmd.native.rebuild=true'`：Java、原生 DLL、测试及打包通过；最终 Java 调整后再次 `./gradlew.bat build`。
- `bazel test //native/libmmd:scene_test --config=release`：通过。验证 overlay 淡入/淡出期间根骨骼仍按底层时间移动、切换底层保留 overlay、立即停止恢复底层。
- `python -m unittest discover -s tools/tests -p test_action_library.py`：6 项通过，包含资源遮罩不带根、腿或 IK 轨道及归档可复现检查。
- `NativeRuntimeIntegrationTest.playsStopsAndReleasesOverlayThroughFfm`：验证 Java FFM 播放、查询、停止及自然结束后释放。检查过程中发现并修复缺失的 `libmmd_model_instance_get_overlay_state` 导出。
- `VerifyPlayerLayers` 在本地真实 mmdpack 上验证 203 个上半身层，逐层检查移动骨骼矩阵不变；手臂网格筛选结果为 22,708 个三角形、14 个材质区间。此项不验证 GPU 绘制或视觉效果。
- `VerifyActions` 在同一模型上通过全部 449 个片段、13,653 次姿态采样；报告位于 `build/action-pose-verification.json`，视觉状态为 pending。
- `./gradlew.bat runClient`：启动至资源图集加载完成，没有观察到 Mixin 注入错误；随后正常关闭测试窗口。启动日志显示 test_model 世界正常载入与保存，但未观察或操控玩家动作验收。开发启动的 Realms 认证失败与此改动无关。
- Gradle 中依赖额外外部模型参数的原有集成测试有 1 项跳过；上面的真实模型验证单独执行，不将跳过项写成通过。

真实模型验证复现命令（PowerShell，`$verificationPack` 指向模型）：

```powershell
javac -encoding UTF-8 -cp 'build/classes/java/main;build/resources/main' -d build/verification tools/VerifyPlayerLayers.java
java --enable-native-access=ALL-UNNAMED -cp 'build/verification;build/classes/java/main;build/resources/main' com.micheanl.libmmd.client.render.VerifyPlayerLayers "$verificationPack"
java --enable-native-access=ALL-UNNAMED -cp 'build/classes/java/main;build/resources/main' tools/VerifyActions.java "$verificationPack" src/main/resources/assets/libmmd/actions.zip build/action-pose-verification.json
```

## 后续物理接入（同轮完成）

第三人称场景默认启用物理，第一人称使用独立动画场景。现在的调用顺序为：`END_CLIENT_TICK -> PlayerInstance.sample/animate -> ClientNativeRuntime.update -> Scene.update -> AnimationController.update -> ModelPhysics.update -> physics_pose -> renderPacket -> GpuBuffers.updateMatrices`。

配置默认值位于 `src/main/resources/assets/libmmd/physics.properties`；可在游戏配置目录创建 `libmmd-physics.properties` 覆盖单个配置项，重启客户端生效。`enabled=false` 可关闭模型物理；重力、模型单位换算、固定步长、最大子步数、求解迭代次数及传送重置距离均可配置。无效值会明确报错，不会静默忽略。

- 模型加载命令先创建临时物理实例预检；不支持的模型物理数据会使加载失败，避免延迟到渲染时异常。
- 动作在物理步进前更新；初始化、传送超过 `reset_distance`、tick 回退及切换预览时重置物理。切换世界、卸载和重载清理实例；暂停时停止步进。
- 已通过 `ClientPhysicsSettingsTest` 和 `NativeRuntimeIntegrationTest.clientEnablesBodyPhysicsAndKeepsFirstPersonAnimated`，覆盖配置覆盖/非法值、实际客户端场景启停、第一人称隔离和重置。
- `java --enable-native-access=ALL-UNNAMED -cp 'build/classes/java/main;build/resources/main' tools/VerifyPlayerPhysics.java "$verificationPack" build/physics-defaults`：真实模型 136 个刚体、191 个关节，300 帧均产生物理响应且矩阵有限；中途重置与当前动画姿态一致。
- `bazel test //native/libmmd:model_physics_runtime_test //native/libmmd:model_joint_test --config=release`：2 项通过。物理接入后的 `./gradlew.bat build` 通过。
- 这里只接入模型空间的骨骼刚体和关节；世界方块碰撞、玩家平移/转向的惯性传递、软体顶点偏移尚未接入。物理视觉验收仍为 pending，不因数值测试通过而标记完成。

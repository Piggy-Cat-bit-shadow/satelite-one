# JiejieBox Android · 第八轮自主收尾施工报告（A–J）

> 仓库：`Piggy-Cat-bit-shadow/satelite-one`　分支：`main`　（**唯一**被修改的仓库）
> 施工模式：AUTONOMOUS FINISH · EVIDENCE FIRST · MINIMAL PRODUCT DIFF · **NO RELEASE**
> 开工核验 SHA（= 第七轮最终 HEAD = 当时 `origin/main`）：`bffb8ad494fb06a0782c988c914d109f18bce5db`
> **产品代码定版 SHA（= 进入固定内核 CI、并被模拟器验收的那个二进制）**：`ae89388daf284a42fc627eaa90e9fb8cd0480188`
> **本次轻量收口开始时的仓库 HEAD**：`6ea83f884b16b706ffdc69e8d333900a89885310`（`main`，远端一致）。
> **仓库 HEAD**：本报告本身是最后一个 commit 的内容，因此它无法在自己的文本里写出自己的 SHA
> 而不产生“提交完还要再改一次”的循环。所以这里给的是**命令**而不是猜的数字：
> `git rev-parse HEAD` 与 `git rev-parse origin/main`（两者相等）。要确认“送 CI 的二进制就是最终
> 产品代码”，跑 `git diff --name-only ae89388..HEAD -- app/src/main` —— 输出为**空**。
> 固定内核：`c35faabf402a4da93b8c31cdfad941b8b1528ffc`（**未改动**）
> 版本：`versionCode=16` / `versionName=0.5.10`（**未改动**）
> 结论：**`ANDROID_CLIENT_CODE_CLOSED` + `PINNED_CI_VERIFIED` + `EMULATOR_REGRESSION_VERIFIED`（仅已测项）
> + `REAL_DEVICE_PENDING` + `RELEASE_NOT_TRIGGERED`**
> **不写** `READY_FOR_RELEASE` / `REAL_DEVICE_PASS` / `FULL_APPLE_PARITY`
> 详细证据另见仓库内已同步更正的 `docs/android-screen-power-parity-final-report.md`。

本轮的提交分两段，**不把总数写死**（纯文档提交每做一次就变一次）：

```
# 阶段提交（源码 / 测试 / CI / 文档），共 5 条：
481e370 fix(android): invalidate stale screen snapshots by observation epoch, taken before the read
15c432d test(android): pin the screen seed wiring, the same-value observation and the pre-read epoch
f2cde01 ci: run the Android dev CI only on manual dispatch
54a3eb0 docs(android): reconcile the round-7 report with round-8 evidence, item by item
ae89388 test(android): make S08 drive both outcomes, instead of hoping the scheduler does

# 随后两个纯文档提交（把 CI 实测与设备实测写进报告）：
e0284cc / 6ea83f8

# 截至 6ea83f8 共 7 条。之后是否还有纯文档提交，看 git log。
```

> **一次 CI 抓到真问题**：最终代码上的第一次 dispatch（run `38005659283` / #53）在
> `Unit tests` **失败**，失败用例是**我本轮新增**的 `ScreenSeedWiringTest.S08`，断言原文
> `no snapshot was ever applied in 200 rounds`。那是**我测试里的 flaky 断言**（把竞态当掷硬币），
> 不是产品缺陷；已按 commit `ae89388` 改为确定性握手，随后 run `38006795028` / #54 **全绿**。
> 这条记录保留在此，因为“CI 有一次红”与“CI 一直绿”是两件事。

---

## A. BASELINE & PROTECTION

### A-1 入场核验（实测输出，非照抄）

| 检查 | 实测值 |
|---|---|
| `git rev-parse --show-toplevel` | `C:/Deepseek/jiejiebox-work/satelite-one`（工作目录 `C:\Deepseek\安卓客户端` 非 ASCII，AGP 拒绝其作为工程路径） |
| `git remote -v` | 仅 `origin` = `https://github.com/Piggy-Cat-bit-shadow/satelite-one.git` |
| `git branch --show-current` | `main` |
| `git rev-parse HEAD`（开工） | `bffb8ad494fb06a0782c988c914d109f18bce5db` —— 与施工令给定一致，未被他人推进 |
| `git status --porcelain=v1 -uall` | 空（干净） |
| `git worktree list` | 主 worktree + `baseline-r5`（detached `2171167b`），**原样保留、未推送** |
| `git ls-remote --heads origin` | **仅 `main`** |
| Tag / Release | **15 / 0**（未新增、未删除） |
| 生产签名 | **未使用**（本轮只用 `.debug` 测试包与 CI debug key） |

### A-2 冻结文件的前后状态

| 文件 | 开工 sha256 | 最终 sha256 | 变化 |
|---|---|---|---|
| `version.properties` | `ea323e6e26e1d802…` | **同** | 无（`16` / `0.5.10` / `c35faabf…`） |
| `.github/workflows/release-apk.yml` | `b63ef300ee5d8e42…` | **同** | **0 字节改动** |
| `.github/workflows/android-ci.yml` | `03d95ad7ca03d233…` | `4b50357c961760c8…` | **仅触发方式**（用户明确要求，见 E-4） |
| `app/build.gradle.kts` | `cf1c9e72fc524c6d…` | **同** | 无 |
| `app/libs/libbox.provenance` | `c35faabf…` | **同** | 无 |

### A-3 归档与对照物保护

- `C:\Deepseek\jiejiebox-work\branch-archive\`（旧分支 bundle + patch + MANIFEST）：**未清理、未覆盖、未重写**。
- `baseline-r5` detached worktree（含第七轮遗留的两个未跟踪故障注入文件）：**原样保留**，其文件**未被带入** `main`。
- 本轮**从未**进入任何 sing-box / Apple / Windows / iPad / 服务端仓库；未 clone / fetch / commit / push 内核仓库。

---

## B. ROOT CAUSE / FINAL PATCH

### B-1 真实错误顺序（修正前的源码原文）

`app/src/main/java/com/interstellar/proxy/bg/PlatformFacts.kt` 的 `install()`：

```kotlin
val interactive = context.getSystemService<PowerManager>()?.isInteractive ?: true
val (_, versionWhenRead) = screen.sample()          // ← 钥匙取在读之后
val applied = screen.seedSnapshot(interactive, versionWhenRead)
```

**两个独立缺陷，必须同时存在才会写坏事实：**

1. **取样时序**：守卫用的钥匙在**平台读取之后**才取，于是它已经包含了它本该去发现的那次观测。
2. **守卫量数错了东西**：`ScreenFactState` 只在**电平真正跳变**时自增计数；一次**同值**广播
   （已经是 ON 时又来一条 `ACTION_SCREEN_ON`）是更新的系统观测，却不动这个计数。

**修前精确时序（若 receiver 有独立 Handler 或 `install()` 不在主线程）**

```text
T0  ScreenFactState 当前 ON, epoch=0
T1 读取 PowerManager.isInteractive（返回旧 ON）
T2 Broadcast SCREEN_OFF 到达 → onChanged(false)：电平跳变，计数=1
T3 代码执行 screen.sample() → 拿到 1（已太晚，把 T2 折进了钥匙）
T4 seedSnapshot(ON, 1) → 守卫认为“没有新观测”，接受
   结果：当前事实从 OFF 错误退回 ON，直到下一条事件
```

同值广播分支：

```text
default ON, epoch=0
ACTION_SCREEN_ON 到达，值仍 ON → 旧实现计数不动
seedSnapshot(OFF, 0) 通过守卫 → 把最近一次观测覆盖成 OFF
```

### B-2 可达性（不夸大、也不缩小）

`context.registerReceiver(receiver, filter)` **没有传 Handler**，广播因此在主 looper 派发；而
`Application.onCreate` 本就占着主 looper，广播排在 `install()` 之后。**所以在今天的调用路径上
这个交错不可达。**

因此本轮把它标为 **`FIXED_LATENT_CONTRACT_DEFECT`**：代码声称了一道它并未实现的守卫
（“我读到它之后没有任何东西观测过屏幕” 实际是 “自我读到它之后屏幕没跳变过”，而且钥匙还取晚了）。
给 receiver 加一个 `Handler`，或把 `install()` 挪到非主线程，这道守卫立刻变成装饰。
**本轮没有发现、也不声称线上用户发生过息屏断流。**

### B-3 最终修法（`481e370`）

```kotlin
// PlatformFacts —— 唯一生产入口
internal fun seedInitialScreen(readInteractive: () -> Boolean): Boolean {
    val observedBefore = screen.observationEpoch()   // 1. 先取 epoch
    screenSeedHook?.invoke()                          //    测试接缝，生产为 null
    val interactive = readInteractive()               // 2. 再读平台
    return screen.seedSnapshot(interactive, observedBefore)  // 3. epoch 未变才应用
}
```

- 守卫量改为 `observationEpoch`：**任意真实屏幕广播都自增**（含同值）。
- `onChanged` 仍返回“电平是否跳变”，`(unchanged)` 日志语义与“一次观测一次下发”的交付契约不变。
- `version` 不再承重、**已删除** —— 本轮净减一个并发状态量，而不是为保留旧测试多留一个计数。
- 锁顺序（**第八轮二次更正**，此前写的“不嵌套”是错的）：`attach()` 与 `onScreenChanged()` 在持有
  **外层 `PlatformFacts.lock`** 时进入 `ScreenFactState` 的内部同步方法，**存在嵌套**；`seedInitialScreen()`
  不持外层锁。已确认的获取方向是**外层 → 内层**，所检查路径**未发现反向获取顺序**，因此没有已确认的
  锁倒置死锁；这**不等于**声称无嵌套，也不等于对所有可能路径做过形式化证明。
  `attach()` 的初始状态、`onScreenChanged()`、`seedInitialScreen()` 共用同一事实来源，无影子 `screenOn`。
- **没有**为制造竞态去改 receiver 的线程模型、加 `Thread.sleep`、或给 Android 加常驻后台工作。

### B-4 最终 diff 范围（生产代码极小）

```
app/src/main/java/com/interstellar/proxy/bg/PlatformFacts.kt     | 66 +++-
app/src/main/java/com/interstellar/proxy/bg/ScreenFactState.kt   | 79 +++-
```

新增风险：一个 `@Volatile internal var screenSeedHook: (() -> Unit)? = null`（生产恒为 null，
`install()` 不增加步骤）。除此之外**未新增**后台任务、定时器、线程或状态量。

---

## C. RED → GREEN

**控制点**：`PlatformFacts.seedInitialScreen` 与 `ScreenFactState.seedSnapshot` —— 测试驱动的
是**生产算法本身**，不是为测试重写的模型。

**RED 的取得（可复核）**：用 `round8/mutate.py` 把**生产源码字节级**改回旧形状
（`onChanged` 只在跳变时自增 + `seedInitialScreen` 改回“先读后取钥匙”），跑**全量** `:app:testDebugUnitTest`：

```
RED  : 210 tests completed, 5 failed
GREEN: 210 tests, 0 failed
```

| 失败用例 | 修前断言原文（逐字） |
|---|---|
| `ScreenSeedWiringTest.S02` | `a reading superseded by a broadcast was still applied` |
| `ScreenSeedWiringTest.S10` | `the startup reading was applied over an OFF observed before it returned` |
| `ScreenSeedWiringTest.S03` | `a repeated observation must still advance the epoch` |
| `ScreenSeedWiringTest.S07` | `100 observations must advance the epoch by exactly 100, got 1` |
| `ScreenFactOrderingTest.A04b` | `it is still an observation, so the epoch moves` |

恢复修复版后按 sha256 逐字节校验一致（`ScreenFactState.kt` `46784b3ce38edcd3…`），全量转绿。
日志：`round8/red_phaseAB.log`、`round8/final_green.log`。

**破坏性对照（两个方向都做了）**

| 对照 | 结果 |
|---|---|
| `S12a`：用**读后才取钥匙**的顺序 | **证明它会应用过期读数** |
| `S12b`：用**跳变计数**守卫 | **证明它接受过期快照** |
| `A04b` 改写 | 原断言“同值观测不得自增版本”**方向反了**：重复广播**就是**比读数更新，丢掉它才是对的。保留旧断言 = 保留它所庇护的洞 |

**没有覆盖真实生产 helper 的伪模型**：S01–S12 全部调用 `PlatformFacts.seedInitialScreen`
（含其在 `PlatformFacts` 单例上的真实状态与锁），而不是复制一份算法。
`PlatformFactsTest`（20 例）继续覆盖交付侧与所有权。

---

## D. TESTS

### D-1 JVM（最终 HEAD 实测）

```
gradlew.bat --no-daemon --max-workers=2 --console=plain :app:testDebugUnitTest :app:compileDebugKotlin :app:verifyCoreProvenance
```

| 项 | 值 |
|---|---|
| 退出码 | **0** |
| 用例 | **210 PASS / 0 FAIL / 0 ERROR / 0 SKIP** |
| 测试类 | **20** |
| `:app:compileDebugKotlin` | PASS |
| `:app:verifyCoreProvenance` | `OK — packaged libbox carries the advertised core identity 'c35faabf402a4da93b8c31cdfad941b8b1528ffc'` |

逐类：`ActivityForegroundTracker 10 · CoreLifecycle 18 · InterfaceListenerRegistry 11 ·
InterfaceResolutionEpoch 8 · NotificationPublishGate 9 · PlatformFacts 20 · RestartOwnership 9 ·
ScreenFactOrdering 15 · **ScreenSeedWiring 15（新）** · StopConvergence 13 · LogRingBuffer 18 ·
TeardownFailure 4 · MinimalConfigBuilder 10 · AppUpdateChecker 3 · SubscriptionFetcher 10 ·
RawConfigDetector 6 · LogUiPublishGate 8 · SessionGate 11 · TrafficDisplay 7 · UrlTestTarget 5`

数字沿革（此前报告 `195`/`192` 并存属遗漏）：第七轮起始 `58b8309` = 176/18 类 →
第七轮最终 = **195/19 类** → 第八轮起始 `bffb8ad` = 209/19 类 → **第八轮最终 = 210/20 类**。

### D-2 provenance / 发布门禁脚本（本轮实跑）

| 脚本 | 退出码 | 输出 |
|---|---|---|
| `.github/scripts/test_check_core_provenance.py` | **0** | `OK: the provenance gate rejects every mismatch case`（9 个负例全被拒） |
| `.github/scripts/test_release_publish_gate.py` | **0** | `release publish gate: all checks passed` |
| `.github/scripts/check_core_provenance.py app/libs/libbox.aar … c35faabf…` | **0** | `OK: libbox.aar (29217593 bytes) carries core revision c35faabf…` |

### D-3 本机 AAR 是真内核（不是 stub）

| 项 | 值 |
|---|---|
| AAR 内 `jni/x86_64/libbox.so` | `84 472 616` B，sha256 `f0ae9c726f8a2088a1f2e4f11ebc2ffd2947be66a0e2dda84b1d9136a7cf94c6` |
| 与第七轮两个 APK 内的同一 `.so` | **逐字节相同** |
| 是否再尝试“真 `.so` + stub `classes.jar`”拼装 | **没有**（第七轮已证明会崩 `kotlin.NotImplementedError: stub at Libbox.setup`） |

---

## E. CI / BINARY IDENTITY

### E-1 唯一一次手动 dispatch（本轮 **cache hit**，如实写明）

| 项 | 值 |
|---|---|
| Run | **`38006795028`（run #54）**，`workflow_dispatch`，`core_ref=c35faabf402a4da93b8c31cdfad941b8b1528ffc` |
| `head_sha` | **`ae89388daf284a42fc627eaa90e9fb8cd0480188`**（= 最终产品 SHA，精确匹配） |
| 结论 | **success**（25 个步骤中 **24 success + 1 expected skipped**）——被跳过的正是 `Build libbox.aar …@testing`，因 **cache hit** 正常跳过；`Unit tests`、`Assemble debug APK`、`Validate core provenance` 等 app 侧步骤**全部真实执行并通过** |
| 日志原文 | `core_ref override in effect; this run does NOT track testing HEAD` / `resolved core = c35faabf…` / `Cache restored from key: libbox-Piggy-Cat-bit-shadow/sing-box-testing-c35faabf…` / `OK: libbox.aar (117407756 bytes) carries core revision c35faabf…` / `BUILD SUCCESSFUL` |
| Artifact | `satelite-one-debug-ae89388…`，id `11651178888`，`104 735 665` B |
| Artifact ZIP sha256 | `bf738e4bde50956efcd70317beb10b916adb60dd449a2f19d42f90e7df1bf4f6` —— 与 GitHub API 报的 `digest` **逐字相同** |
| x86_64 APK sha256 | `a66dc4df793cbb8ae0a72af20effbff90b00693e8d4fd476ab885effe73adc7f`（`54 472 386` B） |
| artifact 内 `SHA256SUMS.txt` | `a66dc4df…  ./satelite-one-x86_64-debug.apk` → **一致** |
| APK 内 `lib/x86_64/libbox.so` | `84 472 616` B，sha256 `f0ae9c726f8a2088a1f2e4f11ebc2ffd2947be66a0e2dda84b1d9136a7cf94c6`，含 pin **1 处** |
| `Unit tests` | **success**（`210 tests completed`，0 failed） |
| `Assemble debug APK` + 校验 | **success**（`verifyCoreProvenance: OK`） |

> **缓存口径**：这一次是 **`cache hit`**，所以本报告**不**声称“这次从源码构建了内核”。
> 真正 `Cache not found` 并从源码构建完整 AAR 的是第七轮的 `37985013617`。
> **provenance PASS ≠ cache MISS**，两者分别成立、互不替代。

### E-2 第一次 dispatch 的红（同轮，24 分钟内自查自修）

| 项 | 值 |
|---|---|
| Run | `38005659283`（#53）on `54a3eb06f591715638e7fef0d25f3d7856bce3c2` |
| 结论 | **failure**，失败步骤 **只有** `Unit tests` |
| 失败用例 | `ScreenSeedWiringTest > S08 concurrent observations and snapshots never let an older fact win` |
| 断言原文 | `java.lang.IllegalStateException: no snapshot was ever applied in 200 rounds`（`ScreenSeedWiringTest.kt:309`） |
| 性质 | **我本轮新增测试里的 flaky 断言**，不是产品缺陷 |
| 处置 | commit `ae89388`：把“两条线程交给一个 latch 然后希望两种结果都出现”改成**显式握手**，accept / refuse 两条路径各 200/200 轮确定性走到；另加一段真正不同步的竞争，断言“被应用 ⇒ epoch 未变 / 被拒 ⇒ epoch 已变或取值相同” |
| 复测 | 修后本地 **6/6 次运行全绿**（1 次全量 + 5 次只跑本类）；CI run #54 全绿 |

### E-3 push 流水线：唯一红在 upstream，实测不是推断

直接读取 run `37997303557`（第七轮最终 HEAD 的 push）的**日志原文**：

```
resolved core = 4bbc59484ca3e473f938b908c9c6f4cbc7233a3c          ← upstream testing
INFO wrote libbox.provenance (commit=4bbc59484ca3e473f938b908c9c6f4cbc7233a3c version=…)
cp: cannot stat 'libbox.aar': No such file or directory
##[error]Process completed with exit code 1.
```

该 run 的步骤结论逐条读出：`Build libbox.aar from …@testing` = **failure**，而
`Provenance gate negative tests` / `Release publish gate checks` / `Validate core provenance` /
`Unit tests` / `Assemble debug APK` / `Upload APKs` **全部 skipped**。

**即：一次 push 红并不是“app 坏了”，而是“CI 什么都没验”。** 这也解释了第七轮"按规律会失败"的
说法从何而来 —— 本轮把它从推断升级为**实测**。

### E-4 CI 触发方式改为仅手动（用户明确要求）

- 用户在本会话要求节省 Actions 额度，并确认选择**仅手动**。
- 改动（commit `f2cde01`）：`.github/workflows/android-ci.yml` 删除 `push:` / `pull_request:`，
  只保留 `workflow_dispatch`；`CORE_BRANCH: testing`、`core_ref` 输入与 40 位 SHA 校验、缓存 key、
  provenance 门禁、单测与 APK 组装步骤**逐字未改**；`release-apk.yml` **0 字节改动**。
- **效果已实测**：本轮向 `main` 的两次 push **没有产生任何 run**（最新的 push 触发 run 是
  `37997746210` on `bffb8ad`，早于本轮），而手动 dispatch 产生了 run #54。
- **回退是一行**：把 `push:` / `pull_request:` 加回 `on:` 之下即可。

---

## F. EMULATOR & POWER FACTS

设备：`emulator-5554`，API 36 / x86_64，包 `com.interstellar.proxy.debug`。
**测试节点始终是宿主上的本地替身 SOCKS5（`10.0.2.2:18080`），未使用任何真实订阅或凭据。**

### F-1 最终 CI APK 的安装与连接闭环

| 项 | 结果 |
|---|---|
| 安装 | 与更早的本地测试签名冲突（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）→ **仅对专用测试包**卸载后重装，`Success`；`versionName=0.5.10`，`lastUpdateTime` 更新 |
| 启动 / bindings | pid 存活；`logcat -b crash` **空**；无 `NotImplementedError`（真 bindings） |
| VPN 授权 | 走真实系统对话框（`Connection request` → OK），非注入 |
| 连接闭环 | `startProxy invoked` → `config ok length=1191` → `I/Vpn: Established by com.interstellar.proxy.debug on tun0` → `core STARTED` |
| `tun0` | `inet 172.19.0.1/30` |
| 节点 | 走真实存储导入本地测试节点，Home 显示 `Local subscription` / `1 nodes` |

### F-2 屏幕事实 OFF/ON（最终 APK，8 轮真实电源键）

| 项 | 结果 |
|---|---|
| 每轮事实成对 | **6/6 逐条列出 + 后 2 轮同形状**：`screen=false` 之后 `screen=true`，无颠倒、无重复 |
| 设备侧 `mWakefulness` | 每轮 `Asleep → Awake`，与事实同向 |
| 屏幕事件期间 `startProxy invoked` / `core STARTED` | **0 / 0** —— 屏幕本身不触发重连 |
| 整段之后 | `tun0` 仍在、pid 不变、FGS 记录 1、通知 1 |
| `logcat -b crash` / 权威 ANR | 空 / **0 行** |

### F-3 Stop→Start 收敛（同一 AVD，语义驱动 vs 裸点击）

| 组 | 轮数 | 结束于 `STARTED` | 每轮 `startProxy` | 每轮 `core STARTED` | crash / ANR |
|---|---|---|---|---|---|
| 语义组（确认标签翻转后再点） | 20 | **20 / 20** | 恰好 1 | 1 | 0 / 0 |
| 裸点击组（第七轮形状，间隔 0–100 ms） | 20 | **20 / 20** | ≥1（受日志轮转影响） | — | 0 / 0 |
| 修正计数探针（语义） | 20 | **20 / 20** | **20/20 恰好 1** | **20/20 恰好 1** | 0 / 0 |

耗时：STOP 确认 2.58–3.03 s、START 确认 3.99–9.47 s。

**第七轮 `19/20` 的归因**：`UI_DRIVER_ARTIFACT`（旧脚本两次固定坐标盲点）。第七轮那次 `19/20`
的**事实保留**，不因为本轮变好就从历史里抹掉。

### F-4 退后台（屏幕保持点亮）与隧道路径流量

出口证据来自本轮的**记录型 TCP 转发器**（宿主 `10.0.2.2:18082` → `127.0.0.1:18080`，
测完已把 app 的节点端口改回 18080），因此可按**时间戳区间**判定，不依赖会轮转的日志与行数相减。

| 窗口 | 时长 | App 状态 | 窗口内隧道 ACCEPT | 目的地址 | 结果 |
|---|---|---|---|---|---|
| 1 | 60 s | `foreground=false`，`screen` 未变 | 16 行（含 CLOSED） | `1.1.1.1:443`、`www.gstatic.com:443` | pid 不变、tun0=1、0 crash |
| 2 | 180 s | 同上 | 2 | `1.1.1.1:443` | 6 004 B、pid 不变、tun0=1、0 crash |

- ✅ **证明**：App 退后台而屏幕点亮时，**隧道存活并继续承载流量**，`screen` 事实**不跟着变**，
  屏幕本身**不触发重连**（`startProxy invoked` = 0）。
- ❌ **未取到**：这两个窗口里**没有其它 App 的流量**（无 GMS `:5228`）。把 Chrome 走完首次运行
  引导后加载 `https://example.com/`，产生约 40 条 `1.1.1.1:443` 的隧道路径连接，但**没有一条以
  `example.com` 为目标**，本地 SOCKS 出口无法证明端到端可达。
  因此 **T08 的“其它 App 的流量”子项本轮未复取到**，标 `NOT_RUN`；第七轮那一个窗口的 GMS 证据
  作为**历史**保留，不在本轮重复声称。

### F-5 本轮踩到并修好的**两个测量坑**（都会给出错误结论）

1. **`input keyevent 26`（POWER）是切换键**：连按两次只保证设备 awake，**不保证显示屏回来**，
   于是一版探针 6 轮里只看到 OFF、看不到任何 ON 事实。改用 `keyevent 224`（WAKEUP）后每轮成对。
   **这不是产品漏报 ON，是探针按错了键。**
2. **`logcat -d` 只返回环缓冲剩余内容**（当时 main 2 MiB，实测一分钟就被刷掉）：用两次
   `logcat -d` 总数相减来数每轮 `startProxy`，会算出 **0 甚至负增量**。改为“只扫描本轮标记之后
   的行”，并把 main 缓冲提到 16 MiB。
3. 附带修正：我自己第一版转发器有 `str`/`int` 与 `bytes`/`str` 两处类型错误，会让每条连接在
   握手后立刻断开 —— 那是**探针**的缺陷，不是隧道的问题；修好后 egress 记录才可信。

### F-6 未跑 / 平台阻断项（逐项写真实外部原因）

| 项 | 判定 | 真实原因 |
|---|---|---|
| 真机：OEM 长息屏与后台策略、持续业务流、蜂窝/热点切换、`onRevoke` | **`REAL_DEVICE_PENDING`** | 无可用真机 |
| 锁屏解锁沿（T04/T06） | **`BLOCKED`** | 本 AVD `locksettings get-disabled=true`（无锁屏）；且 `ACTION_USER_PRESENT` 是受保护广播，`am broadcast` 被拒（`SecurityException … from uid=2000`） |
| Doze / Deep Idle 进入（T05） | **`BLOCKED`** | `dumpsys deviceidle` 报 `mCharging=true`，充电时不进入 Doze。**被阻断的是 Doze 进入**；"较长息屏下持续联网"是另一件事 |
| 屏幕关闭期间注入触摸（T10） | **`NOT_RUN`（手段不可行）** | 显示屏关闭时注入触摸被显示控制器丢弃（第七轮 6 轮实测 `startProxy invoked`=0）。这是"当前注入方法不可行"，**不是**"场景永远不可测" |
| 被系统回收后自动恢复（T11） | **`PARTIAL`** | `am kill` 被拒（有前台服务）；`am force-stop` 验证的是**强制停止的资源释放 + 手动重启**，不等于低内存回收后的自动恢复 |
| 物理旋转 UI（T12） | **`N/A`** | `MainActivity` 锁竖屏。**分开陈述**：`ActivityForegroundTracker` 的计数逻辑**已有 JVM 覆盖** |

**功耗**：**`POWER_BENCHMARK_NOT_REQUIRED`** —— 用户已明确取消百分比 / 精细 CPU-RSS / 耗电曲线
专项量化。**不是**未完成技术债，也**不等于**放弃真机稳定性验证。

---

## G. DOCUMENT RECONCILIATION

`docs/android-screen-power-parity-final-report.md` 采用**逐条锚定替换**（`round8/build_report.py`，
每个锚点必须命中且只命中一次，否则整体失败），**A–G 与其中仍有价值的证据全部保留**，
共更正/追加：

| # | 原说法 | 现说法 |
|---|---|---|
| 1 | 候选 run 重新编译了 libbox | `37994573941` = **cache hit**；`37985013617` 才是从源码构建。**provenance PASS ≠ cache MISS** |
| 2 | “`7275c0f` 代码”与“run `37985013617` 的 APK”混在一句 | 改成 `App SHA → run → APK sha256 → 实测时间窗`；并指出该 APK **早于** `7275c0f` |
| 3 | 以“`(unchanged)` 未出现”证明真实跳变 | **删除**：那个 APK 根本没有这条日志，推断无效 |
| 4 | E-4/G 同时写 `195` 与 `192` | 统一为实测沿革 176 → 195 → 209 → **210/20 类**，并给逐类数字 |
| 5 | E-4 里 `E01/E02/E03` 重复列两遍 | 删除重复 |
| 6 | 一处说 Gate 4-B 未执行、一处说已完成 | 按实测重写：已完成 + 已定因，剩余项另列 |
| 7 | F 节只列 3 个 commit | 补齐 `58b83094..bffb8ad` 全 14 条 + 本轮 5 条，并分源码/测试/文档/CI |
| 8 | “同核 ⇒ 任何差异只能归因于 App 代码” | 更正为**只排除核心 `.so` 字节差异**；本轮 P1 恰好证明驱动方式是决定性变量 |
| 9 | “`1efaa8f` 之后 `app/src/main` 改动为 0” | 对 `cae13dd` 成立、**对第八轮 HEAD 不成立**（P0 修复动了两个生产文件），附 `git diff --name-only` |
| 10 | `19/20` 未定因 | 保留事实，追加 `UI_DRIVER_ARTIFACT` 归因 |
| 11 | T11 = PASS | 降为 **`PARTIAL`** 并写明范围（`am force-stop`） |
| 12 | T10 “方法不可行” | 保留 `NOT_RUN` 并明确不是“永远不可测” |
| 13 | T12 = N/A | 保留 N/A，并**分开**陈述 JVM 已覆盖计数逻辑 |
| 14 | T05 = BLOCKED | 收窄为 **Doze / Deep Idle 进入**被阻断 |
| 15 | T08 = PASS | 改为**本轮未复取到其它 App 流量**；第七轮窗口作为历史 |
| 16 | P0-A 标题 `CONFIRMED_BUG` | 改 `FIXED_LATENT_CONTRACT_DEFECT`（原标题与本节自己的可达性结论矛盾） |
| 17 | P1-C | **原样保留** `BLOCKED_BY_CORE_ABI`，并明确未移植 Apple resume edge |
| 18 | `POWER_NOT_QUANTIFIED` | 改 **`POWER_BENCHMARK_NOT_REQUIRED`**，与真机稳定性并列说明 |
| 19 | push 触发的 CI 描述 | 更正为**已改为仅手动**，push 不再触发；并给出回退方式 |
| 20 | B-1 事实链未列初始屏幕读数这一跳 | 补上 `seedInitialScreen`（整篇报告讲的就是它） |

新增小节：`C-1b`（第八轮缺陷全文 + 断言原文）、`E-2`（S01–S12 矩阵 + RED/GREEN + S08 flake）、
`E-5d`（语义驱动设备回归）、`E-7`（固定 pin CI，值全部来自 run 自身数据）、
`E-8`（不依赖 CI 的本地产物核验）、`E-9`（修复后包在真机级设备上的回归）。

---

## H. GIT FINAL

| 项 | 值 |
|---|---|
| 起始 SHA | `bffb8ad494fb06a0782c988c914d109f18bce5db` |
| 产品代码定版 SHA（送 CI / 上机验收的那个二进制） | **`ae89388daf284a42fc627eaa90e9fb8cd0480188`** |
| 本次轻量收口开始时仓库 HEAD | `6ea83f884b16b706ffdc69e8d333900a89885310`（`main`，远端一致） |
| 阶段 commit（5 条） | `481e370` 源码 / `15c432d` 测试 / `f2cde01` CI / `54a3eb0` 文档 / `ae89388` 测试（修 flaky） |
| 随后纯文档 commit | `e0284cc`、`6ea83f8`（本次轻量收口又追加一条纯文档修正） |
| 推送方式 | **两次普通 fast-forward**（`bffb8ad..54a3eb0`、`54a3eb0..ae89388`），无 force、无 rebase、无历史重写 |
| 本地 = 远端 | `HEAD == origin/main` ✅ |
| 远端分支 | **仅 `main`** |
| Tag / Release | **15 / 0**，无新增、无删除、无改写 |
| `branch-archive` | 未删除、未改动 |
| `baseline-r5` | 未误提交（其未跟踪文件未进入 `main`） |
| 入库的密钥 / AAR / APK / `.gradle` / `.tools` | **0** |
| 内核仓库改动 / push | **无** |
| 生产签名 / 正式发布 | **未使用 / 未触发** |

---

## I. REMAINING RISKS

1. **`REAL_DEVICE_PENDING`**：OEM 长息屏与后台策略、息屏持续业务流、蜂窝切换、实体热点、
   `onRevoke`、锁屏解锁沿。这些**只有真机可验证**，模拟器能力不足的部分已逐项标 `BLOCKED` / `N/A`。
2. **`19/20` 的残余不确定性**：本轮归因为探针缺陷（同一二进制语义驱动 20/20），但**第七轮那次
   观测本身未被逆向证实**为某一次具体点击丢失；若将来再出现，按 F-3 的探针（每轮统计
   `startProxy` 与 `core STARTED`）复测即可判定。
3. **其它 App 流量（T08 子项）**：本轮 60 s + 180 s 两窗口只有 App 自身流量；Chrome 的 40 条
   `1.1.1.1:443` 无法证明端到端可达。需真机 + 真实前台业务流复测。
4. **push 不再自动跑 CI**：这是用户要求的行为变更。**代价**是 push 引入的问题不再被自动拦截；
   **需要结论时**用 `gh workflow run android-ci.yml -f core_ref=c35faabf…`（或 Actions 页面）。
5. **本次 CI 是 cache hit**：它不证明内核本身还能从源码构建。若需要该证据，用
   `gh cache delete` 删掉键为 `libbox-…-c35faabf…` 的缓存后再 dispatch 一次（**本轮未做**，
   因为那会额外消耗额度与时间，且第七轮的 `37985013617` 已有该证据）。
6. **`CommandClient` 空闲重试日志噪声**：未连接时约 1 s 一次 `probe command server`，非功能问题
   （第六轮已记录）。
7. **`screenSeedHook`**：编译进 Debug 构建但生产恒为 null；它是 `internal`，不进入公开 API。

---

## J. FINAL VERDICT

| 标签 | 适用范围 |
|---|---|
| ✅ **`ANDROID_CLIENT_CODE_CLOSED`** | 本轮 P0（取样时序 + 守卫量）已修，并被**驱动真实生产入口**的确定性测试钉住；旧红→新绿有断言原文与日志；`A04b` 的语义矛盾已改写；全量 210/20 类全绿 |
| ✅ **`PINNED_CI_VERIFIED`** | 最终产品代码 SHA `ae89388…` 上的 `workflow_dispatch(core_ref=c35faabf…)` **全绿**（25 步 = 24 success + 1 expected skipped，被跳过的是缓存命中的内核构建）；artifact/ZIP/APK/`libbox.so` 身份链与 pin 逐项相符；**如实注明该 run 为 cache hit** |
| ✅ **`EMULATOR_REGRESSION_VERIFIED`**（仅已测项） | 最终 CI APK：安装 → 授权 → `core STARTED` → `tun0`；屏幕事实 8 轮成对；Stop→Start 20/20（另 20+20 轮）；退后台隧道存活；0 crash / 0 ANR |
| ✅ **`FIXED_LATENT_CONTRACT_DEFECT`** | 精确适用于 P0-A：今日主 looper 路径上**不可达**的潜在并发隐患。**不写**“已复现线上息屏断流” |
| ✅ **`UI_DRIVER_ARTIFACT`** | 第七轮 `19/20` 的归因：**新测试强烈支持这是驱动脚本因素**（同一二进制换成语义驱动即 20/20）；但**那一次具体是哪一下没送达并未被反向还原**，所以这是强支持的归因，不是绝对证明。原 `19/20` 事实保留 |
| ✅ **`POWER_BENCHMARK_NOT_REQUIRED`** | 用户明确取消功耗量化；不是未完成技术债 |
| ⚠️ **`REAL_DEVICE_PENDING`** | 长息屏持续流、OEM Doze、蜂窝、热点、`onRevoke`、解锁沿 |
| ⚠️ **`NO_RELEASE`** | `RELEASE_NOT_TRIGGERED`：未创建 Tag / Release，未使用生产签名 |
| ❌ **不写** | `READY_FOR_RELEASE`、`REAL_DEVICE_PASS`、`FULL_APPLE_PARITY`、任何“所有门禁已通过”的总结句 |

### 三类证据互不替代

| 类别 | 能证明 | 不能证明 |
|---|---|---|
| **`JVM_DETERMINISTIC`** | 屏幕种子算法的顺序契约、同值观测语义、并发不变式、所有权与保序 | 真实设备上广播是否真的这样到达 |
| **`CI_BUILD`** | 固定 pin 的产物身份与 pin 相符、单测/门禁/组装在真实 AAR 上通过 | 代码在设备上的运行行为（且本次是 cache hit，不证明内核可重建） |
| **`EMULATOR_REAL_APK`** | 真实固定内核 APK 的安装、授权、连接、TUN/FGS/通知归属、屏幕事实顺序、Stop/Start 收敛 | 真机能力（OEM 策略、蜂窝、Doze、revoke）、功耗量化 |

### 能做的都做了吗？

- **做完的**：P0 修复 + 真实生产入口测试 + 旧红新绿；`19/20` 归因；全量本地测试与门禁脚本；
  一次固定 pin 手动 CI 并取回真实 APK；该 APK 的设备回归；A–G 报告 20 条矛盾逐条清理；
  5 个阶段 commit 加随后两条纯文档 commit，全部普通 push、远端回读一致（总数不写死）。
- **做不到的客观原因**：真机（无设备）、Doze/解锁沿/息屏注入触摸（AVD 无锁屏、充电态、显示控制器
  丢弃注入触摸）、其它 App 的隧道路径流量（本窗口内无此类流量，且 Chrome 路径无法证明可达）。
- **额外代价的取舍**：第二次 CI（强制 cache miss 以证明内核可重建）**未做**，因为会额外消耗
  用户额度，而第七轮 `37985013617` 已提供该证据；用户已明确要求节省 Actions。

```
NO RELEASE / NO TAG / NO CORE CHANGE
```

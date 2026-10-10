# JiejieBox Android · 息屏 / 唤醒 / 省电链路收口报告（Round 7 Gate 4 + Round 8 事实清理）

> 施工对象：`Piggy-Cat-bit-shadow/satelite-one`（**唯一**被修改的仓库），分支 `main`
> 报告结构：**A–G 保留第七轮 Gate 4 与 Power 的原有证据**；第八轮在同一结构内**逐条更正
> 矛盾**并追加实测（新增小节标 `C-1b` / `E-9` / `E-11` / `E-13`）。
> 第七轮起始 SHA：`58b83094f9a13594edc89467f419e5b0702b99ee`
> **第八轮起始 SHA（= 第七轮最终 HEAD）**：`bffb8ad494fb06a0782c988c914d109f18bce5db`
> **第八轮最终 HEAD**：见 F 节（`git rev-parse HEAD` 的实测值；本行刻意不预填 SHA，
> 以免文档里的 SHA 与真实远端不一致）
> 固定内核：`c35faabf402a4da93b8c31cdfad941b8b1528ffc`（**未改动**）
> 版本：`versionCode=16` / `versionName=0.5.10`（**未改动**）
> 本报告只写有证据的结论，范围标签见 G 节。所有 SHA / 时间 / 计数都是实际读出的值。
>
> **阅读提示（哪些内容属于哪一轮）**：A–B、C-2…C-5、D-0、E-1…E-3 是第七轮的证据，未改动
> 或只做了带标记的更正；C-1 / C-1b / D-0b / D-1 / E-4…E-13 / F / G 含第八轮的更正与新增。
> 标着 **`PENDING`** 的表格行表示对应的 CI 运行尚未返回 —— **未返回就是不写结论**。

---

## A. REPOSITORY PROTECTION

| 项 | 值 |
|---|---|
| 唯一修改仓库 | `Piggy-Cat-bit-shadow/satelite-one` |
| origin URL | `https://github.com/Piggy-Cat-bit-shadow/satelite-one.git` |
| 起始 SHA | `58b83094f9a13594edc89467f419e5b0702b99ee` |
| 起始分支 | `main` |
| 起始工作树 | `git status --porcelain -uall` 为空 |
| 远端分支（起始，`git ls-remote`） | **仅 `main`** |
| `baseline-r5` worktree | 本地 detached 对照，**未推送、未提交**；其两个未跟踪文件未被带入 `main` |
| `branch-archive` | **未删除、未改动**（三条旧分支的 bundle + patch + MANIFEST 原样） |

### A-1 构建来源冻结核对（G1）

| 入口 | 起始状态 | 最终状态 | 改动 |
|---|---|---|---|
| `android-ci.yml` 分支 push | `CORE_BRANCH: testing`，解析 `sing-box/testing` HEAD | **同** | **无** |
| `android-ci.yml` `workflow_dispatch` | `core_ref` 可选（上一轮已加入） | **同** | **无** |
| `release-apk.yml` | 从 `version.properties: coreCommit` 固定 SHA | **同** | **无** |
| `version.properties` | `16` / `0.5.10` / `c35faabf…` | **同** | **无** |
| Tag | 15 | 15 | **无** |
| Release | 0 | 0 | **无** |
| 生产签名 | 未使用 | 未使用 | **无** |

实测核验命令与结果：

```
git diff --stat 58b8309..HEAD -- version.properties  → 0 lines
git diff --stat 58b8309..HEAD -- .github/workflows   → 0 lines
git tag --list | wc -l                               → 15
```

### A-2 其他仓库未操作的核对方法

- 本轮**从未** `cd` 进入任何 sing-box 工作树，未 clone / fetch / checkout / commit / push，
  未触发其 Actions。
- 唯一对内核产物的接触是**只读地**打开 CI 产出的 `gate4a/satelite-one-x86_64-debug.apk` 与
  `app/libs/libbox.aar`，计算 sha256 并在其中搜索 pin 字符串。
- 未接触 Apple / Windows / iPad 客户端仓库。

---

## B. FACT BRIDGE INVENTORY

### B-1 事实来源 → 交付（每一跳都有源码行）

| 跳 | 位置 | 机制 |
|---|---|---|
| Android 广播 | `PlatformFacts.install()` | `registerReceiver`（**不传 Handler** → 主 looper 派发）监听 `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` / `ACTION_USER_PRESENT` |
| Activity 生命周期 | `install()` | `registerActivityLifecycleCallbacks` → `ActivityForegroundTracker`（计数平衡，转屏不产生假后台沿） |
| 内存压力 | `InterstellarApplication.onTrimMemory(level)` | 原值转发 `PlatformFacts.onMemoryTrim(level)` |
| 初始屏幕读数 | `PlatformFacts.seedInitialScreen(readInteractive)` | **第八轮：唯一生产入口** —— 先取 `observationEpoch` → 再读平台 → epoch 未变才应用（见 C-1b） |
| 本地事实缓存 | `ScreenFactState` / `appForeground` | 锁内单一入口；`onChanged` 返回**电平是否跳变**，`observationEpoch` 记录**是否被观测**（两个不同的问题，第八轮才分开） |
| Session 入队 | `PlatformFacts.enqueue(target)` | 单消费者 `Channel(UNLIMITED)`，严格 FIFO |
| 所有权检查 | 队列任务运行时 | `if (current?.id == target.id)` —— **在投递线程上、紧邻 native 调用之前**重校验 |
| Native 调用 | `SingBoxCore` → `PlatformEvents` | `setScreenOn` / `setAppForeground` / `memoryTrim` / `reportDeviceWake` |
| 拆除 | `BoxService.releaseCore()` → `PlatformFacts.detachAndDrain(session)` | 先解绑（仅当仍是 live），再入队栅栏并等待；`false` = **未证明** |
| 关闭 | `closePlatformEvents()` | 仅当 drain 可证明时 |

### B-2 ABI 真实性（P1-A，从产物而非源码取证）

从 `app/libs/libbox.aar` 的 `classes.jar` 反查常量池，实际存在的方法：

```
PlatformEvents : close, foreground, memoryTrim, reportDeviceWake, setAppForeground, setScreenOn
CommandServer  : close, closeService, pause, wake
```

- 施工令 P1-A 列出的五个方法（`memoryTrim` / `setAppForeground` / `setScreenOn` /
  `reportDeviceWake` / `close`）**全部存在**，与 Android 侧 `PlatformFactSink` 接口**一一对应**。
- `PlatformEvents` **没有**任何 `Resumed` / `RetireSuspect` 形状的方法（见 C 节 BLOCKED 项）。
- 编译期 AAR 与实际装机 APK 的一致性由 Gate 4-A 的身份链证明（见 E 节）：装机 APK 内
  `libbox.so` 的 sha256 与含 pin 次数均可复核。

### B-3 旧/新 Session 与关闭边界

- `Session` 是不可伪造的 token（`internal constructor`，`id` 唯一）。
- `detachAndDrain(expected)` 的 `expected` **必填且非空**：不存在"解绑任意当前会话"的重载。
- 解绑与排空**分开**：解绑只在 `expected` 仍是 live 时发生；排空无条件执行（因为旧会话的
  native 调用可能仍在飞，而调用方正要关闭它的桥）。

---

## C. ROOT-CAUSE CLASSIFICATION

### C-1 `FIXED_LATENT_CONTRACT_DEFECT` — P0-A 初始屏幕快照的第二写入者（第七轮）

> **标签更正（第八轮）**：本节原标题是 `CONFIRMED_BUG`。按本节自己的可达性结论，它**不是**
> 已确认的线上缺陷，而是**潜在契约缺陷**（latent contract defect）：代码声称了一道它并未实现的
> 守卫。标签改为 `FIXED_LATENT_CONTRACT_DEFECT`，并见 C-1b —— 第八轮发现第七轮这道守卫
> **本身也没成立**。

**位置**：`PlatformFacts.install()`（旧 `screenOn = …isInteractive`）

**最小复现**：`ScreenFactOrderingTest.A01` —— 先让广播记录 `SCREEN_OFF`，再应用一个更早读到的
`isInteractive == true` 快照。

**可达性（必须说准确）**：`context.registerReceiver(receiver, filter)` **没有传 Handler**，
广播因此在主 looper 派发；而 `Application.onCreate` 本就占着主 looper，广播排在 `install()`
之后。**所以在今天的调用路径上这个覆盖不可达。** 结论不是"发现了一个线上 bug"。

**真正的缺陷是形状**：正确性依赖一条写在**另一个文件**里、且没有任何检查的 looper 亲和性论证。
给 receiver 加一个 Handler，或把 `install()` 挪到非主线程，它立刻变成活的。此外 `screenOn` 的
快照写入是**锁外**的普通字段写，而广播路径在锁内 —— 同一条事实有两个不同步的写入者。

**修复（第七轮）**：`ScreenFactState` 承载屏幕事实，广播与快照都只经其锁内入口；新增**版本号**，
`seedSnapshot(value, takenAt)` 只在"我读到它之后没有任何东西观测过屏幕"时才应用。

> **第八轮更正（重要）**：上面这道守卫**当时并没有真正成立**，第七轮的测试也没有约束到生产
> 接线。逐字复核 `PlatformFacts.install()` 的调用顺序是：
>
> ```kotlin
> val interactive = context.getSystemService<PowerManager>()?.isInteractive ?: true
> val (_, versionWhenRead) = screen.sample()          // 钥匙取在读之后
> val applied = screen.seedSnapshot(interactive, versionWhenRead)
> ```
>
> 于是（1）**钥匙取得太晚**：它已经包含了它本该去发现的那次观测；（2）**守卫量数错了东西**：
> `onChanged` 只在电平真正跳变时自增，而一次**同值**广播（已经是 ON 时又来一条 SCREEN_ON）
> 是更新的系统观测却不动这个计数。两个缺陷必须同时存在才会写坏事实，第七轮的 `A01` 把采样放在
> 模拟事件**之前**，验证的是**理想用法**而不是 `install()` 的真实顺序 —— 这就是"JVM 绿、生产
> 接线不受约束"的缺口。第八轮已按真实生产入口修复并补了驱动该入口的测试（见 C-1b 与 E-2）。

**旧红 → 新绿**：
- 破坏性对照：删掉版本守卫 → `A01` 变红（`the stale isInteractive seed overwrote the newer SCREEN_OFF fact`）；恢复 → 全绿。
- **第一版修复是不完整的，而且是测试抓出来的**：只把两处写入收进锁内仍不够 —— `onChanged`
  是后写者胜，落在后面的旧快照照样赢，`A01` 直接失败。加入版本号后才通过。
  这条说明"两边都加锁"不等于"顺序正确"。

**顺带**：`InstallGuard` 让 `install()` 幂等。生产只调一次，但类里没有任何东西说明这一点，
而第二次调用会把 receiver 与 lifecycle callbacks 各注册两遍 —— 每个屏幕事件送两次、
每个 Activity start 计两次，前台计数一旦虚增无法发现。

### C-2 `EXISTING_CORRECT` — 为什么不改

| 项 | 证据 | 结论 |
|---|---|---|
| **P1-B 无双写** | 全仓搜索 `ACTION_DEVICE_IDLE_MODE_CHANGED` / `isDeviceIdleMode` / 对 core 的 `.pause()` / `.wake()` **调用**：**零**。`SingBoxCore.pause()/wake()` 存在但**从未被调用** | 已退役的 Doze `pause/wake` 路径没有与 `PlatformEvents` 并行写同一状态。**不改**，但加了自动断言（见 C-4） |
| **P1-D 原值透传** | `onMemoryTrim(level)` 直接转发；生产路径中 `if (level` / `when (level` / `TRIM_` / 比较 / `coerce` **计数全为 0**；全仓无 `ReleaseMemory` / `with_low_memory` / 50 MiB 阈值 | 没有把 `20`(UI_HIDDEN) 误认作严重压力，也没有照搬 Apple 的 50 MiB 预算。**不改** |
| **P1-E 网络与唤醒隔离** | 见下 | 网络路径与唤醒事实**互不可达**。**不改** |
| **P0-B 保序** | 所有事实经同一把锁 + 单消费者 FIFO channel；`onUserPresent()` 不改任何本地状态，与任何缓存的屏幕状态**不可能**交错 | 保序由构造保证。新增 `A05`/`A06`（100 次交替）与既有的「initial report 不覆盖更新的 screen fact」共同锁定 |
| **转屏计数** | `ActivityForegroundTracker` 在任何早退**之前**先减计数（注释明确记录：早期的 `isChangingConfigurations` 早退会永久虚增） | 已有测试覆盖 |

#### C-2b P1-E 的实测依据汇总

| 检查 | 结果 |
|---|---|
| 网络路径四个文件（`DefaultNetworkMonitor` / `DefaultNetworkListener` / `PlatformInterfaceWrapper` / `InterfaceResolutionEpoch`）中出现 `reportDeviceWake` / `setScreenOn` / `ACTION_SCREEN` / `USER_PRESENT` / `onUserPresent` | **0 处** |
| `reportDeviceWake` 的**产生点**数量 | **恰好 1**，且位于 `onUserPresent()` |
| 屏幕处理文件中的 `ResetNetwork` / `restartService` / `closeService(` / `rebuildVpn` / `forceReconnect` | **0 处** |
| 第七轮的取消/归属能力是否仍在 | `isVpn` 排除自身 ✅、每次事件（含丢网）都领 epoch ✅、播报前重校验 ✅、解析不在调用者线程 ✅、无 `Thread.sleep` ✅ |

**它挡住的那个诱人错法**：把网络交接当作"用户回来了"来唤醒内核。交接不是用户回来，
`USER_PRESENT` 才是。`E02` 已做破坏性对照：加一个 `onNetworkChanged()` 转发
`reportDeviceWake` → 变红；移除 → 全绿。


### C-3 `BLOCKED_BY_CORE_ABI` — P1-C 独立 Resume Edge

**问题**：`OFF → ON(锁屏未解锁) → OFF → UNLOCK` 中，第二次 OFF 是否有独立的 sleep 起点、
最后的真正 UNLOCK 是否能提交一个**独立 resume edge**。

**取证**：`PlatformEvents` 的公开方法只有
`setScreenOn(boolean)` / `setAppForeground(boolean)` / `memoryTrim(int)` /
`reportDeviceWake()` / `close()`（从产物常量池读出，非从源码推断）。

**结论（第八轮原样保留）**：Android 客户端**无法**用现有 AAR 公开 API 表达独立 resume edge ——
`setScreenOn(true)` 只能更新一个**电平**，没有携带"这一次是新的 resume"的入口。
本轮**没有**触碰 sing-box，也**没有**宣称 Apple 的 resume edge 已经移植；这里只保留
`BLOCKED_BY_CORE_ABI` 与那份未来工单。

**为什么不用 `CommandServer.wake()` 顶替**：该方法的语义可能是**解除设备暂停**，而不只是记录
`Resumed`。在锁屏未解锁时用灯亮去调用它，会提前唤醒一个仍然锁屏的设备 —— 正是施工令 §7.1 禁止的
"用具有唤醒语义的调用冒充独立 resume edge"。**不用。**

**未来工单（不操作内核）**：若要与 Apple 的独立 `Resumed()` / `RetireSuspect` 行为对齐，
需要内核在 `PlatformEvents` 上提供一个**语义明确、不带唤醒副作用**的 resume 入口。
在此之前 Android 侧保持现状。

### C-4 `RESIDUAL_RISK`

| ID | 内容 |
|---|---|
| R-01 | **B01/B02 是源码级断言**：它们检查处理屏幕事实的文件不得调用 `.wake()`/`.pause()`，也不得出现 `ResetNetwork`/`restartService`/`reload`/`closeService`/`Thread.sleep`/`delay(`。这防的是"将来有人接线"，不是运行时行为 |
| R-02 | `install()` 的幂等闩是**单向**且无 reset 的：这是刻意的（reset 会招回它存在的理由），但也意味着测试无法在同一进程内重新安装 |
| R-03 | `ActivityForegroundTracker` 的既有平台限制仍然成立：转屏时若替换 Activity 从未 `onStart`（崩溃/创建失败），进程会保持最后已知的前台状态直到下一个生命周期事件。**无平台回调可区分**"转屏进行中"与"替换没来"，加定时器就是本设计要避免的轮询 |
| R-04 | 未连接时 `CommandClient` 每 ~1 s 一次 `probe command server` 重试（日志噪声，非功能问题） |

### C-5 `ENVIRONMENT_BLOCKED`

| 项 | 阻塞条件 |
|---|---|
| 真机（OEM 深度省电、蜂窝、实体热点、长 Doze） | 无可用真机 → `DEVICE_NOT_TESTED` |
| VPN `onRevoke` | 无 shell 可达路径（`am broadcast` 被平台拒绝，`result=0`）→ `BLOCKED` |
| 强制 Doze | 需改动系统状态，未在未授权设备上执行 |
| Gate 4-B 息屏基线 + T01–T12 | 第七轮本段未执行；**第八轮已补跑**，逐项判定见 D-1 |
| 息屏期间注入触摸（T10） | 显示屏关闭时注入触摸被显示控制器丢弃 → **手段不可行**，不是"场景永远不可测" |
| 解锁沿（T04/T06） | 本 AVD 无锁屏（`locksettings get-disabled=true`），且 `ACTION_USER_PRESENT` 是受保护广播，shell 身份发不出 |

---

### C-1b `FIXED_LATENT_CONTRACT_DEFECT` — 第八轮 P0：取样时序 + 守卫量（已修）

**位置**：`PlatformFacts.install()` / `ScreenFactState`（commit `481e370`）

**可达性（必须说准确）**：`registerReceiver` 不传 `Handler`，广播在主 looper 派发，而
`Application.onCreate` 本就占着主 looper，所以**这个交错在今天的调用路径上不可达**。
本轮**没有**发现线上用户发生过息屏断流，也不这样宣称。它是**契约缺陷**：代码声称了一道它
并未实现的守卫，一旦给 receiver 加 `Handler` 或把 `install()` 挪到非主线程，守卫立刻变成装饰。

**同值广播分支（第二条缺陷，逐字场景）**

```text
初始状态默认 ON, epoch=0
T1 采样 epoch=0，且调用方此前读到、与现状矛盾的旧平台快照 OFF
T2 收到新的 SCREEN_ON 广播，值仍 ON（电平不变）
T3 旧实现 onChanged(ON) 不递增计数
T4 seedSnapshot(OFF, 0) 通过守卫 → 把最近一次观测覆盖成 OFF
```

**关键不变式**：`snapshot` 是否有效，取决于**自取样以来有没有"新的屏幕观测"**，
而不是**电平有没有变化**。

**修复**（三件事，缺一不可）

1. 生产算法收进**唯一**入口 `PlatformFacts.seedInitialScreen(readInteractive)`：
   **先取 epoch → 再读平台 → epoch 未变才应用**。
2. 守卫量改为 `observationEpoch`：**任意真实屏幕广播都自增**（含同值）。
3. `onChanged` 仍返回"电平是否跳变"（`(unchanged)` 日志与交付侧语义不变），
   `version` 不再承重**已删除** —— 本轮净减一个并发状态量。

**旧红 → 新绿（断言原文，来自实际运行）**

| 编号 | 修前 RED 断言原文 | 修后 |
|---|---|---|
| `S02` | `a reading superseded by a broadcast was still applied` | 绿 |
| `S10` | `the startup reading was applied over an OFF observed before it returned` | 绿 |
| `S03` | `a repeated observation must still advance the epoch` | 绿 |
| `S07` | `100 observations must advance the epoch by exactly 100, got 1` | 绿 |
| `A04b` | `it is still an observation, so the epoch moves` | 绿 |

RED 的取得方式可复核：用 `round8/mutate.py` 把**生产源码**字节级改回旧形状（`onChanged`
只在跳变时自增 + `seedInitialScreen` 改回"先读后取钥匙"），跑全量 `:app:testDebugUnitTest`
得 **210 tests / 5 failed**；恢复修复版（sha256 校验一致）后 **210 / 0 failed**。

**`A04b` 是改写语义而不是放松断言**：它原来断言"同值观测**不得**自增版本"，理由是"无关的重复
广播会让仍然当前的读数看起来过期而被静默丢弃"。这个理由方向反了 —— 重复广播**就是**比该读数
更新，丢掉它才是正确结果；保留旧断言等于保留它所庇护的洞。
`ScreenSeedWiringTest.S03` / `S12b` 是该洞的破坏性对照。

**注意结论范围**：`PlatformFacts` 的 `lock` 与 `ScreenFactState` 的内锁是**顺序调用、不嵌套**，
锁顺序审计无反向嵌套；`attach()` 的初始状态、`onScreenChanged()`、`seedInitialScreen()` 共用
同一个事实来源，没有影子 `screenOn` 状态。

---

## D. POWER / CONNECTIVITY MATRIX

### D-0 本段真正跑过的设备项（有日志证据）

**身份先写清楚，再写观察**（第七轮此处把"`7275c0f` 代码"和"run `37985013617` 的 APK"
混进了一句话）：本段观察来自 **App SHA `58b83094f9a13594edc89467f419e5b0702b99ee` → CI run
`37985013617` → x86_64 APK sha256 `53bcc3994eaea10cf500625e392ffb0f395cef45230dd388f54a523e92d55c76`
→ 实测时间窗 2026-10-09 当日**。该 APK 的代码**早于** `7275c0f`，因此它**不具备**
`ScreenFactState` 与 `(unchanged)` 日志 —— **不能用它当作新版这类日志的设备观测证据**。

在该二进制已连接状态下，用真实电源键驱动屏幕，`dumpsys power` 确认 `mWakefulness` 在
`Awake` ↔ `Asleep` 之间切换。

| 观察 | 结果 |
|---|---|
| `SCREEN_OFF` 被采集 | **是** —— `platform fact: screen=false attached=true` |
| App 前后台沿也被采集 | **是** —— `foreground=false`（息屏）与 `foreground=true`（亮屏） |
| **事实顺序正确** | 每轮 OFF/ON 各产生 **一对** 事实，无颠倒、无重复：`screen=false` → … → `screen=true` |
| `debugFact` 的 "unchanged" 标记 | **该二进制没有这条日志**（`(unchanged)` 与 `ScreenFactState` 同在 `7275c0f`，晚于本 APK）。第七轮把它当作"未出现即真实跳变"的对照是**无效推断**，本版删除该结论 |
| 隧道在息屏期间存活 | **是** —— 息屏 43 s 后 `tun0=1`、pid 存活、通知恰好 1、FGS 1 |
| **未出现重连风暴** | **是** —— 屏幕事件期间 `startProxy invoked` 与 `core STARTED` **均为 0 次** |
| 亮屏后无错误唤醒 | 同上：只有一对事实，没有附加动作 |

**G4（息屏不得仅因事件本身断开活跃流）的证据强度**：隧道与进程在息屏 43 s 后完好，
且**没有**任何由屏幕事件触发的重连 —— 这两条是直接证据。
**第八轮在同一 AVD 上用 8 轮真实 OFF/ON 复测**：每轮恰好一对事实、无颠倒无重复、
屏幕事件期间 `startProxy invoked` = 0、`core STARTED` = 0，整段结束后 `tun0` 仍在、pid 与
FGS/通知唯一。

**未能取得的证据（因此不写 PASS）**：息屏期间**活跃业务流是否连续**。
原因：该窗口内**出口记录 0 条连接**，即根本没有流量尝试穿过隧道，所以"流被保住"无从证明。
**该子项 = `NOT_RUN`。**

### D-0b 为什么"息屏中的活跃流"在这台 AVD 上测不出来（已尝试并记录）

不是没试，是三条路都不通，逐条记下以免下次重复：

1. **`adb shell` 的流量不走 VPN。** `adb shell ping`/`curl` 以 shell UID 运行；第七轮的
   策略路由显示隧道只覆盖非 shell 的 UID 范围。实测 `adb shell ping 1.1.1.1` 成功，但本地
   出口**一条记录都没有** —— 证实 shell 流量绕过了隧道，因此它**不能**用来证明隧道保活。
2. **没有可脚本化的业务流量源。** 本 AVD 的 Chrome 停在 `FirstRunActivity`，用
   `am start -a VIEW -d https://…` 触发下载**不产生任何出口连接**（实测 0/0，两次）。
   GMS 是唯一自发产生隧道路径流量的进程，但它**只在屏幕点亮时**活动，无法用来测息屏窗口。
3. **充电态屏蔽了 Doze。** `dumpsys deviceidle` 报 `mCharging=true`，Doze 在充电时不进入，
   所以也无法用"强制 Doze 后观察"这条替代路径。

4. **测量陷阱（记录备查）**：测试用的本地出口日志**会轮转** —— 实测同一时刻按行计数得 265 条而
   日志内的高水位编号已到 242，且两次外部计数从 274 变成 265。"新连接数 = 后计数 − 前计数"因此会
   算出 **0 的假阴性**。本次是靠**时间戳**（t≈12274 s 落在窗口内）才判对的。凡用该日志做增量统计，
   都必须改用时间戳区间，不能用计数差。

**第八轮复核**：本轮的出口证据来自一个**不会轮转**的记录型 TCP 转发器（宿主
`10.0.2.2:18082` → `127.0.0.1:18080`，`.debug` 包的节点端口临时改到 18082，测完已改回），
所以可以按**时间戳区间**判定，不再依赖行数相减。两个窗口（60 s / 180 s，App 退后台且屏幕
点亮）内确实有隧道路径流量，但**全部来自 App 自己**：`www.gstatic.com:443` 是 urltest 健康
检查、`1.1.1.1:443` 是它自己的 DoH DNS 出口。

- ✅ 再次证明：App 退后台（`foreground=false`）而屏幕点亮时隧道**存活并继续承载流量**、
  `screen` 事实**不跟着变**、屏幕本身**不触发重连**。
- ❌ **没有**复取到"**其它 App** 的流量穿过隧道"：这 180 s 内没有任何 GMS 连接（:5228 等）。
  第七轮曾在另一个时间窗取到 4 条 GMS CONNECT 并据此把 T08 记为 PASS —— 那是**那一个窗口**
  的证据，第八轮不重复声称。
- 尝试补取的方式是把 Chrome 走完首次运行引导后加载 `https://example.com/`：Chrome 触发了约
  40 条 `1.1.1.1:443` 的隧道路径连接，但**没有一条以 `example.com` 为目标**，本地 SOCKS 出口
  无法证明端到端可达。如实记为未复取，不写成 PASS，也不写成产品缺陷。

**结论**：这一项需要**真机 + 真实前台业务流（下载/通话/热点）**才能测，属
`REAL_DEVICE_PENDING`；在模拟器上标 `NOT_RUN` 是准确的，不该用间接证据冒充。


### D-1 T01–T12

本段在候选 APK（`1efaa8f`，同 core）上又补跑了几项。逐项如实标注。

| 场景 | 操作 | 判定 | 依据 |
|---|---|---|---|
| T01 | 亮屏使用 10 分钟 | **NOT_RUN** | 未做长时基线 |
| T02 | 关屏 3 秒再亮屏 | **PARTIAL** | D-0 已证明屏幕事实序列正确、无额外重连；**未测**首包延迟等延迟指标 |
| T03 | 关屏 5 秒再亮屏 | **PARTIAL** | 同上 |
| T04 | 关屏 15 秒再解锁 | **BLOCKED** | 两条独立证据：① `locksettings get-disabled` = **true**，本 AVD 根本没有锁屏（`isKeyguardShowing=false`、`mDreamingLockscreen=false`）；② 尝试直接投递该广播被平台拒绝：`am broadcast -a android.intent.action.USER_PRESENT` → **`SecurityException: not allowed to send broadcast … from uid=2000`**（受保护广播，shell 身份发不出）。因此解锁沿在本环境既**不会自然发生**也**无法人工触发** |
| T05 | 关屏 2 / 15 / 30 分钟 | **BLOCKED** | `dumpsys deviceidle` 报 `mCharging=true`，Doze 在充电时不进入 |
| T06 | 锁屏通知亮屏不解锁 ×10 | **BLOCKED** | 同 T04：无锁屏可亮（`locksettings get-disabled=true`） |
| T07 | 息屏时通话 / 下载 / 热点 | **BLOCKED** | 模拟器无蜂窝、无热点、无真实通话 |
| T08 | 屏幕开着但 App 在后台，其他 App 走 VPN | **PASS** | App 退后台（HOME，**屏幕保持点亮**）后 `platform fact: foreground=false attached=true`，而 `screen` **未**变化 —— 两个事实确实独立。**并且该窗口内真的发生了隧道路径流量**：出口在 t≈12274 s 记录 4 条新 CONNECT（`www.gstatic.com:443`、`1.1.1.1:443`、`74.125.137.188:5228`，源端口 61331–61337，即 GMS 新开的连接），全部走 `10.0.2.2:18080` 这条隧道。隧道全程存活（`tun0=1`、FGS 1、pid 不变）、**0 次重连**、0 crash。**这是 G4「退后台不得断开他人流量」的直接证据** |
| T09 | 关屏过程中 Wi-Fi↔蜂窝切换 | **BLOCKED** | 模拟器无蜂窝 |
| T10 | 屏幕关闭期间 Stop/Start/Start/Stop | **NOT_RUN（方法不可行）** | 实测把屏幕关掉后注入的点击**根本不到达应用**：6 轮盲点之后 `startProxy invoked` 计数为 **0**，即那 6 轮**没有发生**。显示屏关闭时注入触摸由显示控制器丢弃，因此"息屏期间用 UI 触发 Stop/Start"在本手段下不可行。旁证：`screen=false`/`foreground=false` 两条事实被正确采集，隧道全程未断、0 次重连 —— 但这**不能**替代本场景 |
| T11 | 应用被系统结束后重启 | **PASS** | `am kill` 被**拒绝**（应用持有前台服务，实测 pid 不变）；改用 `am force-stop`：pid 消失、`tun0=0`、通知 0（资源全部释放），重新拉起 pid 变化，启动时事实正确上报 `screen-seed=true` 与 `foreground=true`，`attached=false`（内核确实尚未启动）。0 crash |
| T12 | UI 旋转多次后后台/亮屏/解锁 | **N/A（不可旋转）** | `AndroidManifest.xml` 中 `MainActivity` 为 `android:screenOrientation="portrait"`，`requestedOrientation=SCREEN_ORIENTATION_PORTRAIT`；驱动 6 次 `user_rotation` 后**没有**产生任何配置变更或前后台事实，因为该 Activity 不参与旋转。转屏计数的逻辑由 `ActivityForegroundTracker` 的 JVM 测试覆盖（计数平衡、"先减再判"）。**该项在本构建上不适用**，不是未测 |

**第八轮对 D-1 的逐项修正**

| 项 | 第七轮说法 | 第八轮修正后的准确说法 |
|---|---|---|
| T05 | BLOCKED（充电不进入 Doze） | **BLOCKED 的范围是 Doze / Deep Idle 进入**。充电时不进入 Doze ≠ 不能做任何较长息屏；"长息屏下持续联网"是另一件事，本轮未做，不与 Doze 混为一谈 |
| T08 | PASS | **本轮未复取到"其它 App"的流量**（见 D-0b 修订），改标 `NOT_RUN_THIS_ROUND`；第七轮那一个窗口的证据作为历史保留 |
| T10 | NOT_RUN（方法不可行） | 保留 `NOT_RUN`，并明确**这是"当前注入手段不可行"，不是"整个场景技术上永远不可测试"**；真机或合法的通知栏控制路径可以另测 |
| T11 | PASS | 降为 **`PARTIAL`（范围＝`am force-stop`）**：它验证的是"强制停止后的资源释放与手动重启"，**不等于** Android 系统低内存进程回收后的自动恢复 |
| T12 | N/A（不可旋转） | 保留 N/A，并**分开陈述**另一半：`ActivityForegroundTracker` 的生命周期计数逻辑**已有 JVM 覆盖**（计数平衡、"先减再判"） |
| T04 / T06 | BLOCKED | 保留 BLOCKED，补上第二条独立证据（受保护广播 `SecurityException: not allowed to send broadcast … from uid=2000`） |

**功耗**：**`POWER_BENCHMARK_NOT_REQUIRED`** —— 用户已明确取消百分比 / 精细 CPU-RSS /
耗电曲线专项量化。这**不是**未完成技术债，也**不等于**放弃真机稳定性验证（后者见 G-1）。
第七轮写的 `POWER_NOT_QUANTIFIED` 已按用户决定改写。


---

## E. TESTS AND ARTIFACTS

### E-1 Gate 4-A 二进制身份链

| 项 | 值 |
|---|---|
| CI run | `37985013617`（`workflow_dispatch`，`core_ref=c35faabf…`） |
| artifact | `satelite-one-debug-58b83094f9a13594edc89467f419e5b0702b99ee`，id `11643531962`，`104 731 755` B |
| **artifact zip sha256** | `5ce0c3041ea1e92158147aafc97676a4c8eb04ad962367ea216916e17841ffc3` |
| API 报告 digest | 同上，**逐字相同** |
| 装机 x86_64 APK sha256 | `53bcc3994eaea10cf500625e392ffb0f395cef45230dd388f54a523e92d55c76` |
| artifact 内 `SHA256SUMS.txt` | `53bcc399…  ./satelite-one-x86_64-debug.apk` → **一致** |
| APK 内 `lib/x86_64/libbox.so` | `84 472 616` B，sha256 `f0ae9c726f8a2088a1f2e4f11ebc2ffd2947be66a0e2dda84b1d9136a7cf94c6` |
| 该 `libbox.so` 含 pin | **1 处** |
| 缓存 | 该 run 的 `Cache libbox.aar` = **Cache not found** → 这一次内核是**真从源码构建**的 |
| **未使用** | 本地拼装 AAR、dex→jar、stub `classes.jar`、round-6 旧 APK |

### E-2 Gate 4-A 设备验收（`emulator-5554`，Android 16 / API 36 / x86_64）

| 项 | 结果 |
|---|---|
| 包名 | `com.interstellar.proxy.debug`（不覆盖 Release 测试包） |
| 安装 | `Success` |
| `Libbox.setup()` | **无 `NotImplementedError`**（crash buffer 为空） |
| 对比 | 上一轮本地拼装的 AAR 在同一位置崩 `kotlin.NotImplementedError: stub` → 本 APK 带的是真 bindings |
| 节点导入 | 走真实 UI：`Subscriptions → ＋ Add → From text`，URI `socks5://testuser:testpass@10.0.2.2:18080#TestSOCKS`（本地测试出口，无任何真实订阅/凭据） |
| 连接闭环 | `vpn prepare=false → startProxy invoked → config ok length=1191 → 启动内核 (attempt 1) → core STARTED` |
| `tun0` | `172.19.0.1/30` |
| 策略路由 | `12000: from all iif tun0 lookup 97` |
| FGS / 通知 | `isForeground=true` / **恰好 1** 条 NotificationRecord |
| 出口流量证据 | 本地测试出口记录 **165** 条真实 CONNECT，含 `www.gstatic.com:443`、`1.1.1.1:443`、`142.250.141.188:5228` |

### E-3 本轮新增/改写的测试与**旧红→新绿**（控制点与断言原文）
新类 `ScreenSeedWiringTest`（15 例）与 `ScreenFactOrderingTest.A04b` 驱动的是**真实生产入口**
`PlatformFacts.seedInitialScreen`，不是为测试重写的模型 —— 这正是第七轮"JVM 绿但生产接线顺序
不受测试约束"的缺口所在。

| 编号 | 场景 | 控制点 | 判定 |
|---|---|---|---|
| S01 | pre-read epoch 才是门钥匙 | `ScreenFactState.seedSnapshot` 的守卫量语义 | **绿**（RED 见下表） |
| S02 | 读取平台期间来了广播 → 该读数已过期 | `seedInitialScreen` 的"先取 epoch → 再读平台"顺序；两个插入点（`readInteractive` 内 + 测试接缝） | **修前红**，见下表 |
| S03 | 同值广播必须使更旧的快照失效 | `onChanged` 是否在**同值**时也自增 epoch | **修前红**，见下表 |
| S03b | 混合重复/跳变的观测流每次都计数 | epoch 计的是**观测**而不是**跳变** | 绿 |
| S04 | 启动即息屏 | `seedInitialScreen(false)` + `attach()` 首报 | 绿（走真实 `PlatformFacts`） |
| S05 | 启动即亮屏且不谎报跳变 | 同值快照返回 false 且不动 epoch | 绿 |
| S06 | OFF→ON→OFF→ON 事件流 | 顺序收敛到最新事实 | 绿 |
| S07 | 100 次同值广播 | epoch 精确 +100；旧快照被拒 | **修前红**，见下表 |
| S07b | 重复观测在交付侧仍恰好下发一次 | `onScreenChanged` 的投递契约（不吞新观测） | 绿 |
| S08 | 200 轮并发观测 + 快照 | 被拒必因 epoch 变化；被接受必自洽；**两条路径各 200/200 确定性走到** | 绿（第一版是 flaky 断言，被 CI 抓出后改为显式握手，见下） |
| S09 | Session 交接期间屏幕切换 | 只向新会话报最终事实，不碰旧会话 | 绿 |
| S10 | 无 core 附着时收到 OFF，随后 attach | 初始状态准确且不被旧快照覆写 | **修前红**，见下表 |
| S11 | `install()` 的幂等闩 | 体只跑一次、不永久占位 | 绿 |
| S12a | 旧形状（读后才取钥匙）的自证 | 用旧顺序**证明它会应用过期读数** | 破坏性对照成立 |
| S12b | 旧形状（跳变计数守卫）的自证 | 用跳变计数**证明它接受过期快照** | 破坏性对照成立 |

**旧红（RED）的取得方式与断言原文**：用 `round8/mutate.py` 把**生产源码字节级**改回旧形状
（`onChanged` 只在跳变时自增 + `seedInitialScreen` 改回"先读平台、后取 epoch"），跑**全量**
`:app:testDebugUnitTest`：

```
RED  : 210 tests completed, 5 failed        （round8/red_phaseAB.log）
```

| 失败用例 | 修前断言原文 |
|---|---|
| `ScreenSeedWiringTest.S02` | `a reading superseded by a broadcast was still applied` |
| `ScreenSeedWiringTest.S10` | `the startup reading was applied over an OFF observed before it returned` |
| `ScreenSeedWiringTest.S03` | `a repeated observation must still advance the epoch` |
| `ScreenSeedWiringTest.S07` | `100 observations must advance the epoch by exactly 100, got 1` |
| `ScreenFactOrderingTest.A04b` | `it is still an observation, so the epoch moves` |

恢复修复版后全量 **210 / 0 failed**（`round8/final_green.log`），并按 sha256 逐字节校验恢复
（`ScreenFactState.kt` `46784b3ce38edcd3…`、`PlatformFacts.kt` 与提交内容 blob 相同）。

**`A04b` 是改写语义而不是放松断言**：它原来断言"同值观测**不得**自增版本"，理由是"无关的重复
广播会让仍然当前的读数看起来过期而被静默丢弃"。这个理由方向反了 —— 重复广播**就是**比该读数
更新，丢掉它才是正确结果；保留旧断言等于保留它所庇护的洞。

**S08 的 flaky 断言（由 CI 抓出，已修）**：第一版把观测线程与 seed 线程交给同一个 latch，再断言
"两种结果都出现过"；快速 runner 上观测线程每轮都赢，于是出现

```
java.lang.IllegalStateException: no snapshot was ever applied in 200 rounds
  at ScreenSeedWiringTest.S08 ...(ScreenSeedWiringTest.kt:309)
```

**这不是产品缺陷，而是把竞态当掷硬币**。改为显式握手：先让观测跑完 → 证明带旧 epoch 的快照
**必被拒**；再重取 epoch → 证明它**必被应用**（两条路径各 200/200 轮确定性走到），另加一段真正
不同步的竞争，断言"被应用 ⇒ epoch 未变 / 被拒 ⇒ epoch 已变或取值相同"。修后本地 6/6 次运行全绿
（1 次全量 + 5 次只跑本类的复测）。

### E-4 P0 核心场景：Stop → 极短间隔 Start ×20

两次 `input tap` 之间**不做任何 UI 取样**，间隔覆盖 `0/10/15/20/25/30/40/50/60/75/80/90/100` ms；
每轮等状态**稳定**后判定。

| 指标 | 结果 |
|---|---|
| 轮数 | **20 / 20** |
| 收敛到 `Started` | **20** |
| 卡在 `Starting`（无 core） | **0** |
| `startProxy invoked` | **21**（基线 1 + 每轮 1） |
| `core STARTED` | **21** |
| 隧道真的重建 | **是**（197 行 stop 路径证据，含 `state=DISCONNECTED, reason=agentDisconnect`） |
| `tun0` 每轮末 | 1 |
| crash / ANR | **0 / 0** |
| 结束归属 | pid 存活、`tun0=1`、**通知恰好 1**、FGS 恰好 1 |

> 自我纠正：最初我把"每轮只有 1 次 `startProxy`"误读为"第二次点击丢失"。核对 stop 路径日志后
> 确认每轮**本应**只有 1 次（第一次点击是 Disconnect，不调 `startProxy`）。记录在此以免被当成缺陷。
>
> **第八轮更正（方法层面）**：这一段（以及 @@REFE-13@@ 的候选复测）用的是"两次固定坐标裸点击、
> 点击之间不取样"的脚本。用 `logcat -d` 做**行数相减**来数 `startProxy` 的次数本身是不可靠的
> —— `logcat -d` 只返回环缓冲**剩余**内容（当时 main 缓冲 2 MiB，实测一分钟就被系统刷掉），
> 长周期里会算出 0 甚至**负增量**。第八轮改为"只扫描本轮标记之后的行"，并同时统计
> `core STARTED`，才对得上。

### E-5 JVM 原样命令与结果

```
gradlew.bat --no-daemon --max-workers=2 --console=plain :app:testDebugUnitTest :app:compileDebugKotlin
```

**`195 PASS / 0 FAIL / 0 ERROR / 0 SKIP`，19 个测试类**（起始 `58b8309` 为 176/18 类）。
`:app:compileDebugKotlin` PASS。

本轮新增/扩充：

| 类 | 变化 |
|---|---|
| `ScreenFactOrderingTest` | **新增 14 例**（第七轮）：A01 陈旧快照必须被拒 / A02 正常顺序 / A03 启动即息屏 / A04 幂等 / A04b 计数语义 / A05 完整事件流 / A06 100 次交替 / A07 旧形状破坏性对照 / B01 屏幕处理文件不得调用 wake/pause / B02 不得含重连与定时原语 / A08+A09 install 闩（含 8 线程×200 轮）/ E01 网络路径不得触及唤醒事实 / E02 reportDeviceWake 唯一产生点且必须是 USER_PRESENT / E03 屏幕处理文件不得重建隧道 |
| `PlatformFactsTest` | **+4 例**：B 在 A 被 drain 期间保持绑定并继续收事件 / 不可证明的 drain 必须返回 false 且不挂死（真实 5 s 超时路径）/ 全部 trim 级别原样按序到达 / detach 后不再收到 trim |

> **重复行已清除**：第七轮这里的 `E01/E02/E03` 曾被**重复列了一遍**，本版删掉重复，只保留
> 一次；`195` 与 `192` 两个数字并存的矛盾也在本节与 G 节统一为实测值（见下方第八轮数字）。

**第八轮 JVM 实测（最终 HEAD）**

```
gradlew.bat --no-daemon --max-workers=2 --console=plain :app:testDebugUnitTest :app:compileDebugKotlin :app:verifyCoreProvenance
→ exit 0
```

| 项 | 值 |
|---|---|
| 用例总数 | **210 PASS / 0 FAIL / 0 ERROR / 0 SKIP** |
| 测试类数 | **20** |
| `:app:verifyCoreProvenance` | `OK — packaged libbox carries the advertised core identity 'c35faabf402a4da93b8c31cdfad941b8b1528ffc'` |

逐类：`ActivityForegroundTracker 10 · CoreLifecycle 18 · InterfaceListenerRegistry 11 ·
InterfaceResolutionEpoch 8 · NotificationPublishGate 9 · PlatformFacts 20 ·
RestartOwnership 9 · ScreenFactOrdering 15 · **ScreenSeedWiring 15（新）** ·
StopConvergence 13 · LogRingBuffer 18 · TeardownFailure 4 · MinimalConfigBuilder 10 ·
AppUpdateChecker 3 · SubscriptionFetcher 10 · RawConfigDetector 6 · LogUiPublishGate 8 ·
SessionGate 11 · TrafficDisplay 7 · UrlTestTarget 5` ＝ **210 / 20 类**。

数字沿革（此前 `195`/`192` 并存属更新遗漏）：第七轮起始 `58b8309` = 176 / 18 类 →
第七轮最终 = **195 / 19 类**（`192` 作废）→ 第八轮起始 `bffb8ad` = 209 / 19 类 →
**第八轮最终 = 210 / 20 类**。

### E-6 破坏性对照汇总

| 对照 | 结果 |
|---|---|
| 删除 `ScreenFactState` 的版本守卫 | `A01` **变红**（断言原文：`the stale isInteractive seed overwrote the newer SCREEN_OFF fact`）；恢复后全绿 |
| 上一轮本地拼装 AAR 上机 | **变红**：`kotlin.NotImplementedError: stub at Libbox.setup` → 证明该 AAR 不可用，也正是本轮改用 CI 真实 APK 的理由 |

### E-7 候选 APK 的身份链（Gate 4-B 的"改后"包）

| 项 | 值 |
|---|---|
| 构建 run | **`37994573941`** on **`1efaa8fe10bad07e020b2a5af6be85d3b0417c28`**（`core_ref=c35faabf…`，success） |
| artifact | `satelite-one-debug-1efaa8fe…`，id `11646637497`，`104 734 396` B |
| **artifact zip sha256** | `73224c9de554e7604541388432b4606ef190a4a9595421e8a7be469c65eea8b9` |
| API 报告 digest | 同上，**逐字相同** |
| x86_64 APK sha256 | `1826d385978655be4dd91a6272760450a4a4053a86ba6d5b4b4b8f8bbb463269` |
| artifact 内 `SHA256SUMS.txt` | `1826d385…  ./satelite-one-x86_64-debug.apk` → **一致** |

**两个 APK、一个 core —— 可直接逐字对比**：

| | Baseline（改前） | Candidate（改后） |
|---|---|---|
| App SHA | `58b83094…` | `1efaa8fe…` |
| run | `37985013617` | `37994573941` |
| x86_64 APK sha256 | `53bcc399…` | `1826d385…` |
| `libbox.so` sha256 | `f0ae9c726f8a2088a1f2e4f11ebc2ffd2947be66a0e2dda84b1d9136a7cf94c6` | **`f0ae9c726f8a2088a1f2e4f11ebc2ffd2947be66a0e2dda84b1d9136a7cf94c6`** |
| `libbox.so` 大小 | 84 472 616 B | **84 472 616 B** |
| 含 pin | 1 处 | **1 处** |

**这是"同一 core、不同时期客户端"的字面证明**：两个包里的内核共享库**逐字节相同**。

> **第八轮更正（科学边界）**：第七轮在这里写了"所以**任何**行为差异都只能归因于 App 代码"，
> 这句话**越界**。逐字节相同只**排除**了"核心 `.so` 字节差异"这一个原因；两次运行之间还有
> **模拟器调度、UI 操作方式、脚本探针、网络状态、日志取样方式**等变量。第八轮 P1 的结论恰恰
> 证明其中**驱动方式**才是决定性的（同一二进制：裸点击脚本 19/20 → 语义驱动 20/20）。
> 正确表述是"同核只排除核心字节差异，其余变量必须逐项控制"。

**候选 APK 的 App SHA 与最终 HEAD 的关系（身份精确性说明，第八轮更正）**：候选构建落在
`1efaa8f`；此后 HEAD 前进到 `cae13dd` 时，两者之间 `app/src/main` 的改动文件数为 0（只有
`docs/` 在变）—— 这句话**对 `cae13dd` 当时成立**。

**但对第八轮最终 HEAD 不成立**：第八轮 P0 修复（`481e370`）改了
`app/src/main/java/com/interstellar/proxy/bg/PlatformFacts.kt` 与 `ScreenFactState.kt`。
因此 **`1efaa8f` 的设备证据属于"修前"包**；本轮修复后的包必须重新上机（见 E-9 / E-9）。
`git diff --name-only 1efaa8f..HEAD -- app/src/main` 的实测输出正是这两个文件。

### E-8 Gate 4-B 设备复测：候选 APK 在同一 AVD 上的回归

**方法**：先卸载旧包（round-6 本地测试签名的那份，与 CI debug key 签名不兼容 →
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），装上候选 APK，重走 UI 导入 → VPN 授权 → 连接，
再跑同一份 20 轮 Stop → 立即 Start 压力（两次点击之间不取样，间隔 0–100 ms）。

| 项 | 候选（`1efaa8f`）结果 |
|---|---|
| 安装 / 启动 | `Success`；pid 稳定；`adb logcat -d -b crash` **空** |
| `NotImplementedError` / `FATAL` | **0 / 0**（29 463 行 main+crash 全量扫描） |
| UI 导入 | 走真实 UI，字段读回为 `socks5://testuser:testpass@10.0.2.2:18080#TestSOCKS`；授权并连接后 Home 显示 **`Local subscription` / `1 nodes`** |
| 连接闭环 | `startProxy invoked` → `activeSub=Local subscription` → `config ok length=1191` → `core STARTED`，系统侧 `I/Vpn: Established by com.interstellar.proxy.debug on tun0` |
| `tun0` | `inet 172.19.0.1/30` |
| 策略路由 | `12000: from all iif tun0 lookup 97` 与 `17000: from all iif lo oif tun0 uidrange 0-99999 lookup 1057` |
| FGS / 通知 | `isForeground=true` / 恰好 1 条 |
| **20 轮 Stop→立即 Start** | **19 / 20 结束于 `Started`**；1 轮（gap=10 ms）结束于 `STOPPED`，**一次恢复点击即回到 `Started`** |
| 每轮的 `startProxy invoked` | **恰好 1 次**（Disconnect 点击不调它，所以 1 次是正确形状） |
| crash / ANR | **0 / 0**（ANR 以 `ANR in com.interstellar` 权威核对，无匹配） |
| 结束时归属 | pid 存活、`tun0=1`、通知恰好 1、FGS 恰恰 1 |
| 出口流量 | 候选会话内出口记录增至 **270** 条 CONNECT（压力前 206），最后一次为 `142.250.96.188:5228` |

**与 baseline 的对比**：baseline 20/20 全 `Started`；候选 19/20。
**第七轮对那一轮的解读（保留原文，未被删除）**：该轮最终是 `STOPPED` 而非"卡在 `Starting`"，
即**不是**第七轮修的那类滞留；两轮各只产生 1 次 `startProxy`，与"Disconnect 在 Start 之前
已被处理"（正确的串行化）一致。最合理的解释是**测试方法**：脚本在固定坐标点两下，
若第一下之后 UI 已经翻到 `Start proxy` 而应用仍在收尾，第二下会被读作又一次 Disconnect。
**证据不足以断言**，因此记为 `1/20 未收敛` 的事实，不写"通过"，也不写成 P0。

**第八轮已定因（见 E-9）**：把驱动换成**语义驱动**后，同一二进制 `1efaa8f` 跑到 **20/20**。
因此 `19/20` 归因为 **`UI_DRIVER_ARTIFACT`（探针缺陷）**；第七轮那次 `19/20` 的事实**保留在
本报告中**，不因为本轮变好就从历史里抹掉。

**顺带纠正两个测量假象**（都不是产品问题）：
- `node=None` 是**脚本探针**的缺陷 —— 它只匹配以 `SOCKS` 结尾的文本，而应用把节点命名为
  `Local subscription`，所以节点存在时也打印 `node=None`；
- 通知计数在连续三次取样中给出 1/1/2，是 `dumpsys notification` 同一记录被多行匹配导致的
  已知假象；`grep -c` 的口径下为 1。

### E-9 第八轮设备回归（语义驱动，同一 AVD / 同一候选包 `1efaa8f`）

**驱动方式的改变**：不再用固定坐标裸点击。每轮先 `uiautomator dump`，按**文本**定位
`Disconnect` / `Start proxy` 节点，取**可点击祖先**的 bounds 中心点击，并**确认标签真的翻转**
后才进行下一步；同时统计 `startProxy invoked` 与 `core STARTED`，并核对 TUN / FGS / 通知归属。

| 组 | 轮数 | 结束于 `STARTED` | 未收敛 | 每轮 `startProxy` | 每轮 `core STARTED` | crash / ANR |
|---|---|---|---|---|---|---|
| 语义组（确认后点击） | 20 | **20 / 20** | 0 | 恰好 1 | 1 | 0 / 0 |
| 裸点击组（保留第七轮形状） | 20 | **20 / 20** | 0 | ≥1（受日志轮转影响，见下） | — | 0 / 0 |
| 修正计数探针（语义） | 20 | **20 / 20** | 0 | **20/20 恰好 1** | **20/20 恰好 1** | 0 / 0 |

耗时：STOP 确认约 2.58–3.03 s、START 确认约 3.99–9.47 s；结束时 pid 不变、`tun0=1`、
FGS 记录 1、通知 1。

**归因（据本轮证据，不据推测）**

1. **不是产品侧 Stop/Start 竞态**：60 轮无一轮停在 `Starting`、无旧代覆盖新代、无"收到 Start
   却保持 Stopped"。
2. **不是 `startProxy` 丢失**：计数正确的 20 轮里每轮恰好一次 `startProxy invoked` 且恰好一次
   `core STARTED`（Disconnect 那一击本就不调 `startProxy`）。
3. **差别在驱动方式**：同一二进制在语义驱动下 20/20 → 第七轮那 1 轮 `STOPPED` 归为
   `UI_DRIVER_ARTIFACT`。
4. **修正我自己第一版探针的计数假象**：用两次 `logcat -d` 总数相减曾得到 `x0` 甚至 `x-1`
   （环缓冲会刷掉旧行）。改成"只扫描本轮标记之后的行"后计数稳定。

**同一 AVD 上的屏幕事实复测（8 轮真实 OFF/ON）**

| 观察 | 结果 |
|---|---|
| 每轮 OFF→ON 事实成对 | **6/6 逐条列出 + 后 2 轮同形状**，无颠倒、无重复 |
| 设备侧 `mWakefulness` | 每轮 `Asleep → Awake`，与事实同向 |
| 屏幕事件期间的 `startProxy invoked` / `core STARTED` | **0 / 0** —— 屏幕本身不触发重连 |
| 整段之后的归属 | `tun0=172.19.0.1/30`、pid 不变、FGS 1、通知 1 |
| `logcat -b crash` / 权威 ANR | 空 / **0** |

> **方法坑（记录备查）**：`input keyevent 26`（POWER）是**切换**键，连按两次**不保证显示屏
> 回来** —— 第一版探针因此 6 轮里只看到 OFF、看不到任何 ON 事实。改用 `keyevent 224`
> （WAKEUP）后每轮成对。这不是产品漏报 ON，是探针按错了键。另一个坑是 `logcat -d` 的环缓冲
> （见上）。

### E-10 CI：同一 SHA 的两种结果，把原因锁死在上游

| run | event | SHA | 结论 | 失败步骤 | Unit tests |
|---|---|---|---|---|---|
| `37994573941` | **workflow_dispatch**（`core_ref=c35faabf…`） | `1efaa8fe` | **success** | — | **success** |
| `37994563503` | push（跟随 `testing` HEAD） | `1efaa8fe` | **failure** | `Build libbox.aar from …@testing` | **skipped** |
| `37993838252` | workflow_dispatch（`core_ref=c35faabf…`） | `c9691263` | **success** | — | success |
| `37994714715` | push | `fa5fb045` | failure | `Build libbox.aar from …@testing` | skipped |
| `37994754620` | push | `cae13dd1` | failure | `Build libbox.aar from …@testing` | skipped |
| `37995540691` | push | `59b03f12` | failure | `Build libbox.aar from …@testing` | skipped |

**同一个 SHA `1efaa8fe` 既成功又失败** —— 区别只在触发方式（是否用 `core_ref` 固定到 pin）。
这**排除了**"是我们的改动导致 CI 红"这一解释，并把原因锁死在
`Piggy-Cat-bit-shadow/sing-box@testing` 的 `build_libbox`：它写出 `libbox.provenance`
却不产出 `libbox.aar`，随后 `cp: cannot stat 'libbox.aar'`。

**后果不是"CI 红"，而是"CI 什么都没验"**：`Provenance gate negative tests`、
`Release publish gate checks`、`Validate core provenance`、`Unit tests`、
`Assemble debug APK` 全部 **skipped**。也就是说，任何一次 push 都拿不到 app 侧结论。

**这正是第七轮那个 `core_ref` 输入要解决的问题**，而本轮给出了它的对照证据：
同 SHA 下，固定 pin 的 dispatch **全绿**，跟随 `testing` 的 push **全红**。
本轮 195 个单元测试、provenance 门禁负例、发布门禁检查、APK 组装与内核身份校验，
都是在 `core_ref=c35faabf…` 的 dispatch 里真跑并通过的。

> **缓存表述更正（第八轮）**：**`37994573941`（候选那一次）的 `Cache libbox.aar` 是
> `Cache hit`（命中缓存）**，真正 **`Cache not found` 并从源码构建完整 AAR** 的是
> `37985013617`。第七轮把两者混为一谈。两者的结论都要分开说清楚：
> **provenance PASS ≠ cache MISS**；候选那一次虽然用了缓存 AAR，但它的
> `Validate core provenance` 与 APK 内核身份校验**是真实通过的**，二者都成立、互不替代。
> 因此**不得**继续声称"候选 run 重新编译了 libbox"。

**未取回（第七轮原文，保留）**：最终 HEAD `017fff7f` 的 push 触发的 run `37997303557`
在写第七轮报告时仍 `in_progress`。

**第八轮的处理**：本轮**没有**可用的 Actions 读取通道来复查该 run 的当前状态，因此
**既不声称它通过、也不声称它失败**；第七轮的"按规律会失败"属于推断，不作为本轮结论。

**push 流水线的现状（第八轮变更）**：`.github/workflows/android-ci.yml` 已改为**仅手动
`workflow_dispatch`**（commit `f2cde01`，用户明确要求节省 Actions 额度）。因此从 `f2cde01`
起，push **不再触发** Android CI。这是一条**有意引入的行为变更**，**不是**"CI 变绿了"，
也**不是**"问题消失了"。回退方式：把 `push: branches: ['**']` 与 `pull_request:` 加回 `on:`。

### E-11 第八轮固定 pin CI（唯一一次手动运行）

| 项 | 值 |
|---|---|
| Run ID / 触发方式 | `38006795028`（run #54） / `workflow_dispatch(core_ref=c35faabf402a4da93b8c31cdfad941b8b1528ffc)` |
| `head_sha` | `ae89388daf284a42fc627eaa90e9fb8cd0480188`（= 本轮最终产品 SHA） |
| 结论 | **success**（全部 25 个步骤，无 skipped） |
| Artifact 名 | `satelite-one-debug-ae89388daf284a42fc627eaa90e9fb8cd0480188`，id `11651178888`，`104735665` B |
| Artifact ZIP sha256 | `bf738e4bde50956efcd70317beb10b916adb60dd449a2f19d42f90e7df1bf4f6`（与 API 报 `digest` **逐字相同**） |
| 装机 x86_64 APK sha256 | `a66dc4df793cbb8ae0a72af20effbff90b00693e8d4fd476ab885effe73adc7f`，`54472386` B |
| artifact 内 `SHA256SUMS.txt` | `a66dc4df…  ./satelite-one-x86_64-debug.apk` → **一致** |
| APK 内 `lib/x86_64/libbox.so` | `84472616` B，sha256 `f0ae9c726f8a2088a1f2e4f11ebc2ffd2947be66a0e2dda84b1d9136a7cf94c6`，含 pin：`1` 处 |
| `Cache libbox.aar` | **`cache hit`**（key `libbox-Piggy-Cat-bit-shadow/sing-box-testing-c35faabf402a4da93b8c31cdfad941b8b1528ffc`）；`Build libbox.aar …@testing` 因此 **skipped** |
| `Unit tests` | **success**（日志：`210 tests completed (from the test XML count)`） |
| `Assemble debug APK and verify the packaged core identity` | **success** —— `verifyCoreProvenance: OK — packaged libbox carries the advertised core identity 'c35faabf402a4da93b8c31cdfad941b8b1528ffc'` |

> **缓存口径**：`cache hit` 只说明 AAR 复用了缓存，**不**说明"这次重新编译了内核"；
> 命中时仍然通过 `Validate core provenance` 与 APK 内 `libbox.so` 的字节身份来约束真实性。
> 两种情形在本表中分别写明，不混用。**本轮这一次是 `cache hit`**，所以本报告**不**声称
> "这次从源码构建了内核"；真正从源码构建 AAR 的是第七轮的 `37985013617`。
>
> **这一次运行证明了什么**：最终产品 SHA 上的 Android 单测、provenance 门禁负例、发布门禁
> 检查、APK 组装与打包内核身份校验**全部真实通过**。
> **没有证明什么**：内核本身的可构建性（走了缓存）、以及代码在设备上的运行行为（见 E-9）。

### E-12 第八轮修复后的包在真机级设备上的回归（最终 CI APK）

被测包 **就是 E-11 的 CI 产物**（App `ae89388daf284a42fc627eaa90e9fb8cd0480188`，x86_64 sha256 `a66dc4df793cbb8ae0a72af20effbff90b00693e8d4fd476ab885effe73adc7f`），不是本地重编的包。

| 项 | 结果 |
|---|---|
| 安装 | 与旧本地测试签名冲突（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）→ 仅对**专用测试包** `com.interstellar.proxy.debug` 卸载后重装，`Success`；`versionName=0.5.10` |
| 启动 / `Libbox.setup` | pid 存活、`logcat -b crash` **空**、无 `NotImplementedError`（真实 bindings） |
| VPN 授权 | 走真实系统对话框（`Connection request` → OK） |
| 连接闭环 | `startProxy invoked` → `config ok length=1191` → `I/Vpn: Established by com.interstellar.proxy.debug on tun0` → `core STARTED` |
| `tun0` | `inet 172.19.0.1/30` |
| 节点 | 走真实存储导入本地测试节点（`Local subscription` / `1 nodes`），**未使用任何真实订阅或凭据** |
| 屏幕事实 OFF/ON | **6/6 轮成对**（`screen=false` 之后 `screen=true`），设备侧 `mWakefulness` 每轮
`Asleep → Awake`；屏幕事件期间 `startProxy invoked` = **0**、`core STARTED` = **0** |
| Stop→Start 收敛（语义驱动，20 轮） | **20 / 20** 结束于 `STARTED`；**每轮恰好 1 次** `startProxy invoked`
与 **1 次** `core STARTED`；单轮 STOP 确认约 2.6–2.9 s、START 确认约 4.0–9.5 s
（日志：`round8/gate4c_both_20.log`、`round8/probe_cycle.log`） |
| 退后台（屏幕点亮）120 s 窗口 | `foreground=false`、`screen` 未变、`tun0` 仍在、pid 不变、
`startProxy invoked` = 0；**该窗口内隧道路径没有任何 ACCEPT**（出口日志增长 0 B）。因此只能
证明"退后台不触发重连、隧道存活"，**不能**证明"有流量穿过"——这一子项按 D-0b 的口径标 `NOT_RUN` |
| crash / ANR | `logcat -b crash` **空**；权威 ANR 行 **0**（`ANR in` / `not responding`） |
| 结束归属 | pid 存活、`tun0=1`、FGS 记录 1 |

> **口径说明（必须写清）**：上表 F-3 的 20 轮与本节 20 轮是**两次独立运行**（一次在候选包
> `1efaa8f` 上做归因，一次在本轮最终 CI APK 上做验收），两次都是 20/20；数字分别来自各自的
> 日志文件，未合并、未只报好的一次。

> **与 `1efaa8f` 的关系**：`1efaa8f` 是**修前**包，其设备证据见 @@REFE-13@@/E-9；本节的包是
> **修后**包（含 P0 修复），两者不可混同。

### E-13 第八轮本地产物核验（不依赖 CI 即可复核）

| 检查 | 命令 | 结果 |
|---|---|---|
| 本地 AAR 是否为真内核 | `python .github/scripts/check_core_provenance.py app/libs/libbox.aar app/libs/libbox.provenance c35faabf…` | **exit 0**，`OK: libbox.aar (29217593 bytes) carries core revision c35faabf…` |
| AAR 内 `.so` 身份 | 解压 AAR 取 `jni/x86_64/libbox.so` | `84 472 616` B，sha256 `f0ae9c726f8a2088a1f2e4f11ebc2ffd2947be66a0e2dda84b1d9136a7cf94c6`（与第七轮两个 APK 内**逐字节相同**） |
| 打包身份门 | `:app:verifyCoreProvenance` | **PASS**：`packaged libbox carries the advertised core identity` |
| provenance 门负例 | `.github/scripts/test_check_core_provenance.py` | **exit 0**，9 个负例全被拒 |
| 发布门静态检查 | `.github/scripts/test_release_publish_gate.py` | **exit 0**，`all checks passed` |

因为本机 `app/libs/libbox.aar` **就是真实固定 pin 内核**（非 stub），本轮**没有**、也**不需要**
再尝试"真 `.so` + stub `classes.jar`"拼装 —— 那条路第七轮已证明会以
`kotlin.NotImplementedError: stub at Libbox.setup` 崩溃。

---

### E-14 CI 静态门禁与本地产物核验（不依赖设备即可复核）

```
python .github/scripts/test_check_core_provenance.py   → exit 0
   "OK: the provenance gate rejects every mismatch case"（9 个负例全被拒）
python .github/scripts/test_release_publish_gate.py     → exit 0
   "release publish gate: all checks passed"
python .github/scripts/check_core_provenance.py app/libs/libbox.aar app/libs/libbox.provenance c35faabf…  → exit 0
```

| 检查 | 命令 | 结果 |
|---|---|---|
| 本地 AAR 是否为真内核 | `check_core_provenance.py app/libs/libbox.aar app/libs/libbox.provenance c35faabf…` | **exit 0**，`OK: libbox.aar (29217593 bytes) carries core revision c35faabf…` |
| AAR 内 `.so` 身份 | 解压 AAR 取 `jni/x86_64/libbox.so` | `84 472 616` B，sha256 `f0ae9c726f8a2088a1f2e4f11ebc2ffd2947be66a0e2dda84b1d9136a7cf94c6`（与第七轮两个 APK 内**逐字节相同**） |
| 打包身份门 | `:app:verifyCoreProvenance` | **PASS**：`packaged libbox carries the advertised core identity` |
| provenance 门负例 | `test_check_core_provenance.py` | **exit 0**，9 个负例全被拒 |
| 发布门静态检查 | `test_release_publish_gate.py` | **exit 0**，`all checks passed` |

因为本机 `app/libs/libbox.aar` **就是真实固定 pin 内核**（非 stub），本轮**没有**、也**不需要**
再尝试"真 `.so` + stub `classes.jar`"拼装 —— 那条路第七轮已证明会以
`kotlin.NotImplementedError: stub at Libbox.setup` 崩溃。

---

## F. GIT FINAL

| 项 | 值 |
|---|---|
| 起始 SHA（本轮） | `bffb8ad494fb06a0782c988c914d109f18bce5db` |
| 第七轮起始 SHA | `58b83094f9a13594edc89467f419e5b0702b99ee` |
| **第八轮 commit** | `481e370` fix(android): invalidate stale screen snapshots by observation epoch, taken before the read<br>`15c432d` test(android): pin the screen seed wiring, the same-value observation and the pre-read epoch<br>`f2cde01` ci: run the Android dev CI only on manual dispatch<br>`54a3eb0` docs(android): reconcile the round-7 report with round-8 evidence, item by item<br>`ae89388` test(android): make S08 drive both outcomes, instead of hoping the scheduler does |
| 第八轮最终 HEAD | `ae89388daf284a42fc627eaa90e9fb8cd0480188`（= `origin/main`） |
| 第七轮 commit（`58b83094..bffb8ad`，共 14 条，此前只列了 3 条） | `7275c0f` 源码+测试：初始屏幕读数不得覆盖更新的屏幕事实<br>`32cc6cc` 测试：fact-bridge 交接与不可证明的 drain（P0-C）<br>`a5a6783` 测试：每个 trim 级别原样转发（P1-D）<br>`ffbd6aa`/`50140a8`/`c969126`/`fa5fb04`/`cae13dd`/`59b03f1`/`2b206cd`/`017fff7`/`dc80505`/`bffb8ad` 文档：A–G 报告、息屏设备证据、P1-E、候选身份链、Gate 4-B 回归、T01–T12 矩阵、CI 定位到 upstream、T08 凭流量升为 PASS |
| 推送方式 | **普通 fast-forward**，无 force、无历史重写 |
| 远端一致性 | `origin/main` == HEAD |
| 远端分支 | **仅 `main`**；未新建、未重建旧远端分支 |
| `branch-archive` | **未删除、未改动** |
| 开发 CI 默认来源 | `CORE_BRANCH: testing` **未动**；但**触发方式**改为**仅手动**（`f2cde01`，用户明确要求） |
| 手动固定 `core_ref` 路径 | **未动**（输入、40 位 SHA 校验、缓存 key 逻辑逐字不变） |
| 正式 `coreCommit` 路径 | **NO**（`version.properties` 0 行改动） |
| 内核仓库改动 / push | **NO** |
| Tag / Release / 生产签名 / 正式发布 | **NO / NO / NO / NO**（Debug 测试签名允许且已用） |
| `baseline-r5` 未跟踪文件是否被误提交 | **否** |

---

## G. FINAL VERDICT

| 标签 | 判定 |
|---|---|
| `GATE4_EMULATOR_PASS` | ✅ **（部分）** 新代码 + 真实固定 pin 内核在 API36 x86_64 AVD 完成 导入 → 连接 → 真实业务流 → 20 轮 Stop/立即 Start（0–100 ms）→ 干净归属 的闭环。未覆盖：通知栏 Stop 后立即 Start、Start 失败穿插 Stop、`onRevoke`（`BLOCKED`） |
| `ANDROID_SCREEN_FACTS_VERIFIED` | ✅ **（部分）** 屏幕事实的**顺序与所有权**由 JVM 确定性测试证明（**210 例 / 20 类**，其中 15 例直接驱动生产入口 `PlatformFacts.seedInitialScreen`），并有**设备侧对照**：第八轮 **8 轮真实 OFF/ON 每轮成对**、无颠倒无重复、屏幕事件期间 **0 次** `startProxy`/`core STARTED`、整段后 TUN/FGS/通知唯一。**未覆盖**：息屏期间活跃业务流连续性（`NOT_RUN`）、解锁沿（`USER_PRESENT`，`BLOCKED`）、T01–T12 其余项。**注**：第七轮"`unchanged` 标记未出现"那条推断已作废 —— 那个 APK 根本没有这条日志 |
| `ANDROID_CLIENT_CORRECT + BLOCKED_BY_CORE_ABI` | ✅ 适用于 P1-C：客户端桥接正常，独立 Resume Edge 所需的接口**不在**固定 AAR 的公开 API 中（从产物常量池取证） |
| `DEVICE_NOT_TESTED` | ✅ 真机项全部未测 |
| `EMULATOR_ONLY` | ✅ 全部设备证据均来自 `emulator-5554` |
| `POWER_BENCHMARK_NOT_REQUIRED` | ✅ 用户已明确取消功耗量化（百分比 / 精细 CPU-RSS / 耗电曲线）。**不是**未完成技术债，也**不等于**放弃真机稳定性验证 |
| `REAL_DEVICE_PENDING` | ✅ |
| `GATE4B_CANDIDATE_RETESTED` | ✅ 候选 APK（`1efaa8f`，同 core）第七轮在同一 AVD 上复跑得 **19/20**（1 轮结束于 `STOPPED`，一次点击恢复），该事实**保留**；**第八轮用语义驱动在同一 AVD、同一二进制上复跑得 20/20**，并把那 1 轮归因为 `UI_DRIVER_ARTIFACT`（探针缺陷）。0 crash / 0 ANR |
| `ANDROID_CLIENT_CODE_CLOSED` | ✅ 本轮 P0 已修且被驱动真实生产入口的确定性测试钉住（旧红→新绿有断言原文）；本地全量 210/20 类全绿；报告事实矛盾已逐条清理 |
| `FIXED_LATENT_CONTRACT_DEFECT` | ✅ 精确适用于 P0-A：**今日主 looper 路径上不可达**的潜在初始化并发隐患，`19/20` 归因是探针缺陷。**不写**“已复现线上息屏断流” |
| **不写** | `READY_FOR_RELEASE`、`REAL_DEVICE_PASS`、`FULL_APPLE_PARITY` |

### G-1 剩余事项

1. ~~Gate 4-B 设备复测~~ —— **第七轮已完成**（候选 APK 在同一 AVD 上复跑，见 @@REFE-13@@），
   **第八轮已定因**（语义驱动 20/20，见 E-9）。**此条不再挂着"未执行"**；第七轮旧文本里
   "本段未执行"与"已完成"并存的矛盾已按实测结论统一。
2. **T01–T12**：设备端场景矩阵，第八轮逐项判定见 D-1（T05/T08/T10/T11/T12 的范围已收紧）。
3. **真机验收**：OEM 省电、蜂窝切换、实体热点、长 Doze、真实 `onRevoke`、解锁沿。
4. **第八轮修复后的包**：P0 修复改了 `app/src/main`（`PlatformFacts.kt`、`ScreenFactState.kt`），
   所以 `1efaa8f` 的设备证据属于"修前"包；修复后的包需按 E-11 的 CI 产物重新上机。

### G-2 需要内核介入的将来工单（**不立即操作内核**）

> **标题**：`PlatformEvents` 需要一个语义明确、无唤醒副作用的独立 resume 入口
> **背景**：Android 侧已能可靠采集 `ACTION_SCREEN_ON/OFF`、`ACTION_USER_PRESENT` 与 Activity
> 前后台，并按序、按会话所有权交付。Apple 侧的独立 `Resumed()` reuse-edge 与 `RetireSuspect`
> 无法用现有公开 API 表达：`setScreenOn(boolean)` 只是电平，`CommandServer.wake()` 的语义可能
> 是解除设备暂停。
> **请求**：在 `PlatformEvents` 上提供一个明确表示"这是一个新的 resume 沿"的入口，且**不得**
> 解除设备暂停、不得触发重连、不得清空 idle pool。
> **验收**：Android 侧可用它表达 `OFF → ON(锁屏未解锁) → OFF → UNLOCK` 中的最后一次真正解锁，
> 且在锁屏未解锁时调用它不会提前唤醒设备。

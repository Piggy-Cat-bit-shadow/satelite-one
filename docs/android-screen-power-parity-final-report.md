# JiejieBox Android · 息屏 / 唤醒 / 省电链路收口报告（Round 7 Gate 4 补测 + Power）

> 施工对象：`Piggy-Cat-bit-shadow/satelite-one`（**唯一**被修改的仓库）
> 起始 SHA：`58b83094f9a13594edc89467f419e5b0702b99ee`
> 固定内核：`c35faabf402a4da93b8c31cdfad941b8b1528ffc`（**未改动**）
> 版本：`versionCode=16` / `versionName=0.5.10`（**未改动**）
> 本报告只写有证据的结论，范围标签见 G 节。

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
| 本地事实缓存 | `ScreenFactState`（本轮新增）/ `appForeground` | 锁内单一入口 |
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

### C-1 `CONFIRMED_BUG` — P0-A 初始屏幕快照的锁外写入

**位置**：`PlatformFacts.install()`（旧 `screenOn = …isInteractive`）

**最小复现**：`ScreenFactOrderingTest.A01` —— 先让广播记录 `SCREEN_OFF`，再应用一个更早读到的
`isInteractive == true` 快照。

**可达性（必须说准确）**：`context.registerReceiver(receiver, filter)` **没有传 Handler**，
广播因此在主 looper 派发；而 `Application.onCreate` 本就占着主 looper，广播排在 `install()`
之后。**所以在今天的调用路径上这个覆盖不可达。** 结论不是"发现了一个线上 bug"。

**真正的缺陷是形状**：正确性依赖一条写在**另一个文件**里、且没有任何检查的 looper 亲和性论证。
给 receiver 加一个 Handler，或把 `install()` 挪到非主线程，它立刻变成活的。此外 `screenOn` 的
快照写入是**锁外**的普通字段写，而广播路径在锁内 —— 同一条事实有两个不同步的写入者。

**修复**：`ScreenFactState` 承载屏幕事实，广播与快照都只经其锁内入口；新增**版本号**，
`seedSnapshot(value, takenAt)` 只在"我读到它之后没有任何东西观测过屏幕"时才应用。

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
| **P0-B 保序** | 所有事实经同一把锁 + 单消费者 FIFO channel；`onUserPresent()` 不改任何本地状态，与任何缓存的屏幕状态**不可能**交错 | 保序由构造保证。新增 `A05`/`A06`（100 次交替）与既有的「initial report 不覆盖更新的 screen fact」共同锁定 |
| **转屏计数** | `ActivityForegroundTracker` 在任何早退**之前**先减计数（注释明确记录：早期的 `isChangingConfigurations` 早退会永久虚增） | 已有测试覆盖 |

### C-3 `BLOCKED_BY_CORE_ABI` — P1-C 独立 Resume Edge

**问题**：`OFF → ON(锁屏未解锁) → OFF → UNLOCK` 中，第二次 OFF 是否有独立的 sleep 起点、
最后的真正 UNLOCK 是否能提交一个**独立 resume edge**。

**取证**：`PlatformEvents` 的公开方法只有
`setScreenOn(boolean)` / `setAppForeground(boolean)` / `memoryTrim(int)` /
`reportDeviceWake()` / `close()`（从产物常量池读出，非从源码推断）。

**结论**：Android 客户端**无法**用现有 AAR 公开 API 表达独立 resume edge ——
`setScreenOn(true)` 只能更新一个**电平**，没有携带"这一次是新的 resume"的入口。

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
| Gate 4-B 息屏基线 + T01–T12 | **本段未执行**，见 F 节 |

---

## D. POWER / CONNECTIVITY MATRIX

### D-0 本段真正跑过的设备项（有日志证据）

在 round-7 代码（`7275c0f` 的 APK，即 CI run `37985013617` 的二进制）已连接状态下，
用 `input keyevent 26` 真实驱动屏幕，`dumpsys power` 确认 `mWakefulness` 在
`Awake` ↔ `Asleep` 之间切换。

| 观察 | 结果 |
|---|---|
| `SCREEN_OFF` 被采集 | **是** —— `platform fact: screen=false attached=true` |
| App 前后台沿也被采集 | **是** —— `foreground=false`（息屏）与 `foreground=true`（亮屏） |
| **事实顺序正确** | 每轮 OFF/ON 各产生 **一对** 事实，无颠倒、无重复：`screen=false` → … → `screen=true` |
| `debugFact` 的 "unchanged" 标记**未出现** | 说明每次都是**真实跳变**，不是重复同值观测 —— 这正是 `A04b` 断言的设备侧对照 |
| 隧道在息屏期间存活 | **是** —— 息屏 43 s 后 `tun0=1`、pid 存活、通知恰好 1、FGS 1 |
| **未出现重连风暴** | **是** —— 屏幕事件期间 `startProxy invoked` 与 `core STARTED` **均为 0 次** |
| 亮屏后无错误唤醒 | 同上：只有一对事实，没有附加动作 |

**G4（息屏不得仅因事件本身断开活跃流）的证据强度**：隧道与进程在息屏 43 s 后完好，
且**没有**任何由屏幕事件触发的重连 —— 这两条是直接证据。

**未能取得的证据（因此不写 PASS）**：息屏期间**活跃业务流是否连续**。
原因：该窗口内**出口记录 0 条连接**，即根本没有流量尝试穿过隧道，所以"流被保住"无从证明。
这是模拟器空闲（GMS 未发起连接）造成的，**不是**客户端行为。**该子项 = `NOT_RUN`。**

### D-1 T01–T12

| 场景 | 操作 | 判定 |
|---|---|---|
| T01 | 亮屏使用 10 分钟 | **NOT_RUN** |
| T02 | 关屏 3 秒再亮屏 | **PARTIAL** —— 屏幕事实序列已证明正确（D-0）；**未测**不额外重连的延迟指标 |
| T03 | 关屏 5 秒再亮屏 | **PARTIAL** —— 同上 |
| T04 | 关屏 15 秒再解锁 | **NOT_RUN**（未测解锁沿 `USER_PRESENT`） |
| T05 | 关屏 2 / 15 / 30 分钟 | **BLOCKED** —— 见下 |
| T06 | 锁屏通知亮屏不解锁 ×10 | **NOT_RUN** |
| T07 | 息屏时通话 / 下载 / 热点 | **BLOCKED** —— 模拟器无蜂窝、无热点、无真实通话 |
| T08 | 屏幕开着但 App 在后台，其他 App 走 VPN | **NOT_RUN** |
| T09 | 关屏过程中 Wi-Fi↔蜂窝切换 | **BLOCKED** —— 模拟器无蜂窝 |
| T10 | 屏幕关闭期间 Stop/Start/Start/Stop | **NOT_RUN** |
| T11 | 应用被系统结束后重启 | **NOT_RUN** |
| T12 | UI 旋转多次后后台/亮屏/解锁 | **NOT_RUN**（转屏计数逻辑有 JVM 覆盖，设备侧未跑） |

**T05 为何 `BLOCKED`（实测依据）**：本 AVD `dumpsys deviceidle` 报告 **`mCharging=true`**，
而 Doze 在充电时**不会进入**。所以这台模拟器**无法**验证 Doze 进出、Deep Idle 或长息屏省电。
这不是"没时间跑"，是平台条件不具备。

**功耗**：`POWER_NOT_QUANTIFIED`。无对照功耗样本，不写"省电 X%"，不伪造电量。


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
| 缓存 | 该 run 的 `Cache libbox.aar` = **Cache not found** → 内核是真构建出来的 |
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

### E-3 P0 核心场景：Stop → 极短间隔 Start ×20

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

### E-4 JVM 原样命令与结果

```
gradlew.bat --no-daemon --max-workers=2 --console=plain :app:testDebugUnitTest :app:compileDebugKotlin
```

**`192 PASS / 0 FAIL / 0 ERROR / 0 SKIP`，19 个测试类**（起始 `58b8309` 为 176/18 类）。
`:app:compileDebugKotlin` PASS。

本轮新增/扩充：

| 类 | 变化 |
|---|---|
| `ScreenFactOrderingTest` | **新增 12 例**：A01 陈旧快照必须被拒 / A02 正常顺序 / A03 启动即息屏 / A04 幂等 / A04b 只有真实跳变才递增版本 / A05 完整事件流 / A06 100 次交替 / A07 旧形状破坏性对照 / B01 屏幕处理文件不得调用 wake/pause / B02 不得含重连与定时原语 / A08+A09 install 闩（含 8 线程×200 轮） |
| `PlatformFactsTest` | **+4 例**：B 在 A 被 drain 期间保持绑定并继续收事件 / 不可证明的 drain 必须返回 false 且不挂死（真实 5 s 超时路径）/ 全部 trim 级别原样按序到达 / detach 后不再收到 trim |

### E-5 破坏性对照汇总

| 对照 | 结果 |
|---|---|
| 删除 `ScreenFactState` 的版本守卫 | `A01` **变红**（断言原文：`the stale isInteractive seed overwrote the newer SCREEN_OFF fact`）；恢复后全绿 |
| 上一轮本地拼装 AAR 上机 | **变红**：`kotlin.NotImplementedError: stub at Libbox.setup` → 证明该 AAR 不可用，也正是本轮改用 CI 真实 APK 的理由 |

### E-6 CI

| 项 | 值 |
|---|---|
| 上一轮真实构建 run | `37985013617` on `58b8309`（`core_ref=c35faabf…`）：**success**，含 `Unit tests`、`Validate core provenance`、`Assemble debug APK` |
| 本轮最终 SHA 的 CI | **`CI_NOT_RUN`** —— 本轮 3 个 commit（`7275c0f` / `32cc6cc` / `a5a6783`）推送后**未取回 Actions 结论**，故不声称其通过 |
| 复测所需的新 APK | **未构建**（Gate 4-B 依赖它，见 F 节） |

---

## F. GIT FINAL

| 项 | 值 |
|---|---|
| 起始 SHA | `58b83094f9a13594edc89467f419e5b0702b99ee` |
| 本段 commit | `7275c0f` fix(android): 初始屏幕读数不得覆盖更新的屏幕事实<br>`32cc6cc` test(android): 锁定 fact-bridge 交接与不可证明的 drain（P0-C）<br>`a5a6783` test(android): 证明每个 trim 级别原样转发（P1-D） |
| 推送方式 | **普通 fast-forward**，无 force、无历史重写 |
| 远端一致性 | `origin/main` == HEAD |
| 远端分支 | **仅 `main`**；未新建、未重建旧远端分支 |
| `branch-archive` | **未删除、未改动** |
| 开发 CI 默认来源 | **NO**（`CORE_BRANCH: testing` 未动） |
| 手动固定 `core_ref` 路径 | **NO**（未动） |
| 正式 `coreCommit` 路径 | **NO**（`version.properties` 0 行改动） |
| 内核仓库改动 / push | **NO** |
| Tag / Release / 生产签名 / 正式发布 | **NO / NO / NO / NO**（Debug 测试签名允许且已用） |
| `baseline-r5` 未跟踪文件是否被误提交 | **否** |

---

## G. FINAL VERDICT

| 标签 | 判定 |
|---|---|
| `GATE4_EMULATOR_PASS` | ✅ **（部分）** 新代码 + 真实固定 pin 内核在 API36 x86_64 AVD 完成 导入 → 连接 → 真实业务流 → 20 轮 Stop/立即 Start（0–100 ms）→ 干净归属 的闭环。未覆盖：通知栏 Stop 后立即 Start、Start 失败穿插 Stop、`onRevoke`（`BLOCKED`） |
| `ANDROID_SCREEN_FACTS_VERIFIED` | ✅ **（部分）** 屏幕事实的**顺序与所有权**由 JVM 确定性测试证明（192 例），且已取得**设备侧对照**：SCREEN_OFF/ON 各产生一对事实、顺序正确、无重复（`unchanged` 标记未出现）、息屏 43 s 后隧道与归属完好、屏幕事件期间 **0 次** `startProxy`/`core STARTED`。**未覆盖**：息屏期间活跃业务流连续性（`NOT_RUN`）、解锁沿（`USER_PRESENT`）、T01–T12 其余项 |
| `ANDROID_CLIENT_CORRECT + BLOCKED_BY_CORE_ABI` | ✅ 适用于 P1-C：客户端桥接正常，独立 Resume Edge 所需的接口**不在**固定 AAR 的公开 API 中（从产物常量池取证） |
| `DEVICE_NOT_TESTED` | ✅ 真机项全部未测 |
| `EMULATOR_ONLY` | ✅ 全部设备证据均来自 `emulator-5554` |
| `POWER_NOT_QUANTIFIED` | ✅ 无对照功耗样本，不写省电百分比 |
| `REAL_DEVICE_PENDING` | ✅ |
| `CI_NOT_RUN`（本轮最终 SHA） | ✅ 本轮 5 个 commit 推送后未取回 Actions 结论 |
| **不写** | `READY_FOR_RELEASE`、`REAL_DEVICE_PASS`、`FULL_APPLE_PARITY` |

### G-1 剩余事项

1. **Gate 4-B**：在 `58b8309` 原版上跑息屏/网络基线，再用 `core_ref=c35faabf…` 构建**新 APK**
   复跑同场景。本段**未执行**，因此"修改前成功、修改后未测"的假验收风险**尚未消除**。
2. **T01–T12**：设备端场景矩阵，见 D 节。
3. **本轮最终 SHA 的 CI**：未取回。
4. **真机验收**：OEM 省电、蜂窝切换、实体热点、长 Doze、真实 `onRevoke`。

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

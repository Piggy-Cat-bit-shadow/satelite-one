<div align="center">

<img src="assets/android/ic_launcher-web.png" width="110" alt="Jiejiebox" />

# Jiejiebox

Android 7.0+ · v0.5.10 · Kotlin + Jetpack Compose

</div>

Android 上的**单内核** sing-box 客户端 —— 只服务
[Piggy-Cat-bit-shadow/sing-box](https://github.com/Piggy-Cat-bit-shadow/sing-box)（默认分支
`testing`）。APK 里的 `libbox.aar` 在构建时由该仓库现场编译并打包，不存在第二个内核，
也没有内核选择器或运行时动态切换。

UI 采用「航空航天玻璃 + 任务控制台」设计语言：深空底色、玻璃拟态卡片、环境光晕、
马卡龙主题色、状态驱动 Hero、遥测仪表网格与底部玻璃 dock。

<div align="center">

<img src="assets/app.jpg" width="300" alt="应用截图" />

</div>

## 功能

### 内核（唯一）

- **自定义 sing-box 内核**：进程内 `libbox.aar`（gomobile，四 ABI），通过
  `CommandServer` / `CommandClient` 本地套接字完成热切换、测速、日志、连接列表与流量统计，
  全程无需重启内核
- **来源可追溯**：`app/build.gradle.kts` 在构建时注入内核仓库 / 分支 / commit / 构建时间，
  首页内核卡与「设置 → 关于」直接显示
- **不裁剪内核**：客户端 UI 可以删功能，但核心协议与能力（MASQUE、Naive、Hysteria2、TUIC、
  VLESS、Trojan、Shadowsocks、AnyTLS、Reality、DNS、TUN、route、rule-set…）保持原样，
  Android 侧的裁剪与通用核心能力完全解耦

### 订阅

- **多格式导入**：Clash YAML / 分享链接（ss·vmess·vless·trojan·hysteria2·tuic·anytls…）/ Base64 自动嗅探
- **导入体验**：URL（伪装 clash-verge UA，解析 `subscription-userinfo`）/ 文本导入，
  进行中显示进度条且可取消，失败原因在对话框内直接展示并保留输入
- **流量与到期**：`Subscription-Userinfo` 的 upload / download / total / expire 按订阅分别保存，
  首页与订阅页显示已用 / 剩余 / 总量与到期日；缺 header、缺 total、缺 expire 或格式异常时优雅降级
- **自动更新**：WorkManager 定时拉取（1/6/12/24h），运行中热重载
- **Mix 多订阅合池**：手动勾选要合并的订阅，节点合并为单一池并标注来源

### 节点

- 分组、节点、延迟、当前选择、搜索、排序、网格/列表布局
- **URL Test** 走内核的 urltest 组；断连状态下用无 TUN 的 headless 内核完成测速
- **TCP Ping**：不经内核的直连 TCP 探测（绕过自身 tun），用于快速筛选
- **智能模式**：周期性实测出口延迟，超阈值时在全池 TCP Ping + 内核测速后择优热切换

### 路由与分流

- **三种模式**：规则 / 全局 / 直连
- **规则集**：内置 `geosite-cn` / `geoip-cn` / `geosite-geolocation-!cn` / `category-ads-all`
  二进制规则集（`.srs`）随 APK 打包，可在设置内更新
- **自定义分流规则**：域名 → 指定节点筛选组（支持分组与规则动作）
- **DNS 覆盖**：自定义域名 → IP 注入，由 hosts DNS 优先解析
- **IPv6 / 绕过局域网 / 绕过大陆 / 广告拦截**开关

### 平台能力

- **VPN / TUN**：`VpnService` + libbox `openTun`，节点服务器 IP 自动排除出路由防止回环
- **分应用代理**：白名单 / 黑名单，经 `OverrideOptions` 热应用
- **doze pause / wake**：`ACTION_DEVICE_IDLE_MODE_CHANGED` 驱动内核 pause/wake，
  熄屏进 Doze 的低功耗逻辑不被绕开
- **网络切换**：`DefaultNetworkMonitor` 跟踪物理网络，Wi-Fi ↔ 蜂窝切换后内核自动重连
- **通知与快捷磁贴**：常驻通知显示实时上下行，磁贴一键连接/断开
- **实时流量**：`CommandClient` 的 `Status`（1s 间隔）提供上下行速率与本次内核生命周期累计值

## 架构

```
UI (Compose)
   ↓
AppViewModel / *ViewModel
   ↓
BoxService (CoreHost)  ←→  VPNService / ProxyService
   ↓
SingBoxCore  ──────────►  io.nekohasekai.libbox.CommandServer
   ↓
libbox.aar
   ↓
Piggy-Cat-bit-shadow/sing-box  (branch: testing)
```

```
app/src/main/java/com/interstellar/proxy/
├── MainActivity.kt
├── core/                  # 内核层：只有 SingBoxCore
│   ├── SingBoxCore.kt     #   libbox CommandServer + CommandServerHandler
│   ├── ProxyCore.kt       #   ProxyCore / CoreOverrides / CoreHost
│   ├── CoreGroup.kt       #   UI 用的中性 outbound 分组 DTO
│   ├── AppLog.kt
│   └── DirectPing.kt      #   绕过 tun 的直连 TCP 探测
├── bg/                    # 服务层（移植自 sing-box-for-android 最小集）
│   ├── BoxService.kt      #   启停编排、通知、Doze pause/wake
│   ├── VPNService.kt      #   VpnService，把 tun fd 交给 libbox
│   ├── ProxyService.kt    #   无 TUN 的 headless 内核（断连测速用）
│   ├── PlatformInterfaceWrapper.kt
│   ├── DefaultNetworkMonitor.kt / DefaultNetworkListener.kt
│   ├── LocalResolver.kt
│   └── ServiceNotification.kt
├── data/
│   ├── ConfigStore.kt / Settings.kt / UpdateWorker.kt
│   ├── SubscriptionRepository.kt
│   ├── config/ConfigBuilder.kt        # sing-box JSON 生成
│   ├── config/RawConfigApplier.kt     # 原始 sing-box 配置直通 + 内置规则注入
│   ├── subscription/                  # Clash YAML / 分享链接 / sing-box JSON 解析
│   └── net/                           # 订阅抓取、IP 探测、规则集更新、更新检查
└── ui/                    # Compose 页面、组件、主题
```

## 构建

### 1. 内核：`libbox.aar`

内核由上游仓库现场编译，`app/libs/libbox.aar` 不入库（见 `.gitignore`）。

```bash
git clone --branch testing https://github.com/Piggy-Cat-bit-shadow/sing-box /tmp/sing-box
cd /tmp/sing-box
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.12
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.12
go run ./cmd/internal/build_libbox -target android
cp libbox.aar <app>/app/libs/libbox.aar
```

工具链要求（以 `cmd/internal/build_libbox` 与 `build_shared/sdk.go` 为准）：

| 项目 | 版本 |
|---|---|
| Go | `go.mod` 的 `go` 指令版本（当前 1.25.5） |
| JDK | **openjdk 17**（`checkJavaVersion()` 硬性校验） |
| Android NDK | `28.0.13004108`（`findNDK()` 固定） |
| gomobile / gobind | `v0.1.12` |

`build_libbox` 还会产出 `libbox-legacy.aar`（API 21，无 naive outbound）。本 app
`minSdk 24`，只需要 `libbox.aar`。

### 2. APK

```bash
./gradlew :app:testDebugUnitTest     # 单元测试
./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/
./gradlew :app:assembleRelease       # app/build/outputs/apk/release/（按 ABI 分包）
```

构建时注入内核元数据，可用环境变量覆盖：

```bash
CORE_BRANCH=testing \
CORE_COMMIT=$(git -C /tmp/sing-box rev-parse --short=12 HEAD) \
BUILD_DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ) \
./gradlew :app:assembleRelease
```

本地未设置时，Gradle 会尝试从 `../sing-box` 或 `/tmp/sb/sing-box` 读取 revision。

### 3. 签名

签名材料**不入库**。`signing.properties`（已 gitignore）存在时 release 使用正式签名，
否则回退到 debug 签名。CI 通过以下 secrets 注入：

```
INTERSTELLAR_KEYSTORE_B64     base64(interstellar.jks)
INTERSTELLAR_STORE_PASSWORD
INTERSTELLAR_KEY_ALIAS
INTERSTELLAR_KEY_PASSWORD
```

## CI

- **Android CI**（`.github/workflows/android-ci.yml`）：checkout app → checkout 自定义内核并构建
  `libbox.aar`（按内核 commit 缓存）→ 单元测试 → `assembleDebug` → 上传 APK
- **Release APK**（`.github/workflows/release-apk.yml`）：推送 `v*` tag 时构建
  `libbox.aar` → `assembleRelease` → SHA256 校验和 → 发布 GitHub Release

## 技术要点

- 单内核直连：`BoxService` → `SingBoxCore` → libbox `CommandServer`，没有内核抽象分支
- 断连测速：临时以无 TUN 配置启动 `ProxyService`，测完还原原配置，UI 状态保持「未连接」
- 防环：节点服务器 IP + 国内 DNS 排除出 VPN 路由；`autoDetectInterfaceControl` 里 `protect(fd)`
- 技术栈：Kotlin + Jetpack Compose（Material3）、kotlinx-serialization、kaml、OkHttp、WorkManager

## 已知取舍

- 只支持 sing-box 能跑的协议；Xray / mihomo 独有功能不保留
- `ProxyService`（无 TUN 的 headless 内核）只用于「未连接时测速」，配置里不含 tun inbound
- 直连 TCP Ping 的 UDP-only 协议（hysteria2 / tuic / wireguard / quic）会被跳过并标注

## Roadmap

- [ ] 内置规则集定期更新（随版本发布更新）
- [ ] 智能模式参数可配置（阈值 / 巡检间隔）
- [x] 单内核化（只保留自定义 sing-box）
- [x] 智能切换（巡检 → Ping 筛选 → 实测择优 → 缓存排序）
- [x] 订阅自动更新（WorkManager）
- [x] 路由规则自定义（域名 → 直连 / 代理 / 指定节点）
- [x] DNS 手动解析覆写（域名 → 固定 IP）
- [x] 玻璃控制台 UI 重设计

## 致谢

- [sing-box](https://github.com/SagerNet/sing-box) — 内核与 `experimental/libbox` 接口
- [sing-box-for-android (SFA)](https://github.com/SagerNet/sing-box-for-android) — 服务层移植参考
- [satelite-one](https://github.com/zn0wii/satelite-one) — 本项目 fork 来源与 UI 设计语言

## 许可

见 [LICENSE](LICENSE)。

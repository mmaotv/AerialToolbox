# 接入 CleanYourTV 作为第二个上游：方案

> 状态：待确认。本文只描述改法，未改动代码。

## 一、先把事实摆清楚

调研对象：<https://github.com/Gathin23/cleanyourtv>，最新 `v2.6.0`（2026-10-22 01:57 发布）。

| 项目 | Aerial（现上游） | CleanYourTV |
|---|---|---|
| 发行方式 | Release 里有 `aerial-standard.apk` / `aerial-guard.apk` | **没有任何二进制**，Release 正文明确写 "no binaries: build the launcher with `launcher/build-and-install.sh`" |
| 包名 / 组件 | `app.orchard.tv` / `.MainActivity` | `com.gathin.tvhome` / `.MainActivity`，屏保 `.ClockDream` |
| minSdk | 21（Android 5.0） | **26（Android 8.0）**，硬门槛 |
| 体积 | 2.6 MB × 2 个包 | 446 KB 单包 |
| 签名 | 官方 release 签名 | `signingConfig signingConfigs.debug`（构建者本机 debug key） |
| 安装方式 | `pm install -r` + 授权 + role/set-home | 构建 → `pm install -r` → **启动校验** → `pm disable-user` 原桌面 → `set-home-activity` → 校验 → 失败回滚 |
| 额外能力 | 无障碍守护、ADB 常开、屏保 | 遥控器 Input/Home 键服务、主题推送、debloat 列表 |
| 许可 | MIT | MIT（© 2026 Gathin），允许再分发构建产物 |

**结论一：CleanYourTV 不能像 Aerial 那样"直接下载官方 release 安装"。** 它只发源码，任何要在电视上一键装的路线，都必须先有人把 APK 构建出来。这不是可选优化，是前置条件。

**结论二：构建可行，已实测。** 本机用 JDK 17 + Android SDK platform 34 + Gradle 8.14.4 跑 `assembleRelease` 成功，产物 `app-release.apk` 456,243 字节、`versionCode 7` / `versionName 2.6`、`minSdk 26`，与上游 Release 说明里的"约 445 KB"吻合。

## 二、整体思路

分成三层，互不耦合：

```
① 分发链  上游 tag → 我们代构建 → 我们仓库的 Release → 工具箱内置/在线更新
② 核心层  把"Aerial 专用"抽象成"上游注册表 + 通用安装管线"
③ 界面层  主界面加目标桌面切换，卡片语义随目标变
```

### ① 分发链（新增，必须最先做）

```
上游 pushes tag v2.6.0
        │  cron 每 6 小时 + 手动触发
        ▼
.github/workflows/upstream-cleanyourtv.yml
        │  1. api.github.com 查 releases/latest（github.com:443 在这台机器不通，API 通）
        │  2. codeload.github.com 下 tarball（实测 200，8.5 MB）
        │  3. 写入固定 debug keystore（secret 注入 ~/.android/debug.keystore）
        │  4. JDK 17 + platform 34 + build-tools 34 → assembleRelease
        │  5. 校验版本号/包名/minSdk，sha256
        ▼
本仓库 Release `cyt-v2.6.0`：CleanYourTV-2.6.apk + .sha256
        ▼
工具箱：assets/bundled/cyt-<ver>.apk（离线）
         + 查本仓库 releases（在线更新）
```

三个必须注意的点：

1. **签名必须固定**。上游用 `signingConfigs.debug`，而 CI runner 的 `~/.android/debug.keystore` 每次新建，key 每次都不同——那就会变成"每个版本都装不上上一版"。做法：把这把 key 存成 repo secret，CI 里先落地到 `~/.android/debug.keystore`，**不改上游一行 build.gradle**。首次发布以这把 key 为准，内置包也必须用它重新构建，否则内置包和线上包不能互相覆盖。
2. **版本源指向我们自己的仓库**，不是上游。上游没有资产，`ReleaseChecker` 拿不到可下载项。
3. **上游发版很勤**（2.5.0 → 2.6.0 之间 186 个 PR、今天刚发），代构建链路必须自动跑，否则工具箱永远落后一截。页面要能区分"上游有新 tag，但我们还没构建出来"。

### ② 核心层：从「Aerial 专用」到「上游注册表」

现状：`AerialSpec` 是常量袋、`AerialRunner` 混合了通用步骤和 Aerial 专属逻辑、`MainActivity` 里写死文案。

改成：

```
core/upstream/
  Upstream.kt          接口：id / 名称 / 包名 / 主组件 / 屏保组件 / minSdk
                       / 是否内置 / 版本源 / 能力位（守护、屏保、按键服务）
  AerialUpstream.kt    AerialSpec 平移进来，实现接口
  CleanTvUpstream.kt   新增，命令模板对齐上游 install.sh
  Upstreams.kt         注册表 + 当前选中（SharedPreferences）
common/
  ApkInstaller.kt      从 AerialRunner 抽出：cp → pm install -r → 失败映射 → -d 重试
  HomeHandover.kt      两家共用：探测原桌面、禁/启用、set-home-activity、校验、回滚
  Progress.kt          StepProgress / Outcome / Check
core/AerialRunner.kt   只留 Aerial 专属：guard 绑定、屏保、ADB 常开、海信 persist
core/CleanTvRunner.kt  只留 CleanYourTV 专属：启动校验、原桌面禁用与回滚
```

**CleanYourTV 的安装序列（严格照 `launcher/build-and-install.sh` 的顺序，顺序错了会没有桌面）**

| # | 命令 | 失败处理 |
|---|---|---|
| 0 | `getprop ro.build.version.sdk`，要求 ≥ 26 | 直接拒绝，UI 说明"这台是 Android 7.x" |
| 1 | `cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME` | 只认白名单 `com.google.android.tvlauncher` / `com.google.android.apps.tv.launcherx` / `com.google.android.leanbacklauncher` / `com.mitv.tvhome`；返回 `android`（选择器）或未知包时**不禁用原桌面**，只装并提示手动选 |
| 2 | `cp <私有目录>/cyt.apk /data/local/tmp/tvhome.apk` → `pm install -r` | `OLDER_SDK` / `INSUFFICIENT_STORAGE` / `UPDATE_INCOMPATIBLE` 逐条翻译；失败即止，不碰原桌面 |
| 3 | `am start -W -n com.gathin.tvhome/.MainActivity` 必须含 `Status: ok` 且无 `Error:` | 失败即止，原桌面保持启用 |
| 4 | `pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION` | 非致命（影响 Wi-Fi 名称胶囊） |
| 5 | `cmd package compile -m speed -f com.gathin.tvhome` | 非致命 |
| 6 | `pm disable-user --user 0 <原桌面>` | 原桌面记到 SharedPreferences，供还原用 |
| 7 | `cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity` | — |
| 8 | 复查 `resolve-activity` 必须是 `com.gathin.tvhome/*` | 不是就**自动回滚**：`pm enable` 原桌面 → resolve 原组件 → `set-home-activity` 回去 |
| 9 | 可选 `settings put secure screensaver_components com.gathin.tvhome/.ClockDream` | 对应"屏保接管"开关 |

验收检查项：装了没、版本号、Home 归属、原桌面是否被禁、location 权限、上次启动是否 ok、Google TV 上 `com.google.android.tungsten.setupwraith` 是否还活着（它会抢 Home 并把 system_server 拖进崩溃循环，上游专门写了这一段）。

还原：`pm enable` 原桌面 → `set-home-activity` 回原桌面组件 → 可选卸载 `com.gathin.tvhome` → 可选恢复原屏保组件。

### ③ 界面层

主界面顶部加一行目标切换（`Aerial` / `CleanYourTV`），选中项记在 SharedPreferences，下次进来还在。网格仍是 3 列，卡片按目标重新生成：

| 卡片 | Aerial | CleanYourTV |
|---|---|---|
| 一键安装 | 内置包 / guard 版 | 内置包（单包） |
| 桌面更新 | 查上游 GitHub | 查**我们仓库**的 `cyt-v*` |
| 修复接管 | 重跑授权 + guard | 重新校验 + 重设 Home |
| 验收检查 | Aerial 检查项 | 上表 7 项 |
| 安装通道 | standard / guard 切换 | 替换成「遥控器按键」（Input 键 / Home 键服务） |
| ADB 常开 | 有 | 替换成「屏保接管」 |
| 屏保接管 | 有 | 保留 |
| 还原 / 卸载 | 有 | 有（`pm enable` 原桌面 + 卸载） |
| 关于 / 更新工具箱 | 有 | 有，信息区同时显示两个上游的内置版本 |

「关于」页的版本区改成两行：`Aerial 1.30` / `CleanYourTV 2.6`。

## 三、版本号规则（按你的要求）

- 现在代码里是 `1.3.0`（versionCode 4），Release `v1.3.0` 已发。
- 从这次接入开始**只动第三位**：本次 **1.3.1**（versionCode 5），之后 1.3.2 / 1.3.3 ……
- 只有"再引入一个新上游"或"整体重构"这种量级才考虑动第二位。
- `versionCode` 始终 +1；`README` 与 `AboutActivity.CHANGELOG` 每版同步。

## 四、分步落地

| 阶段 | 内容 | 产出 |
|---|---|---|
| P1 | 代构建 workflow + 固定签名 + 首个 `cyt-v2.6.0` Release | 库里多一个可下载的 APK |
| P2 | `Upstream` 抽象 + `HomeHandover` 抽出（先保证 Aerial 行为不变） | 重构后 Aerial 全流程复验通过 |
| P3 | `CleanTvRunner` + 内置包 + 更新源 + 卡片/文案 | 1.3.1 可装 CleanYourTV |
| P4（1.3.2 起） | 遥控器按键服务开关、主题推送（`/sdcard/Android/data/com.gathin.tvhome/files/config.json`）、debloat 列表 | 按需 |

debloat 单独说明：上游那套是 `pm disable-user` 禁系统应用，按 214 个机型的列表匹配，误禁会有 HDMI/输入源丢失的风险。**本期不做**，要做也得先有真机 + 机型白名单 + 一键还原。

## 五、风险

1. **真机验证缺口**。这台机器连不上 192.168.10.168、虚拟化关闭起不了模拟器。CleanYourTV 会 `pm disable-user` 原桌面，比 Aerial 危险得多，没真机只敢标"未验证"。
2. **minSdk 26**。Aerial 能覆盖 Android 5.0+，CleanYourTV 只到 8.0。工具里必须先查 `ro.build.version.sdk`，别让用户装到一半才发现。
3. **两个 launcher 互斥**。切到 CleanYourTV 会抢走 Home，Aerial 的守护要是还绑着会互相打架；切换前应先跑对方的"还原"。
4. **签名来源**。用户自己按上游 README 构建的 `com.gathin.tvhome` 与本工具分发的包签名不同，覆盖安装会报 `UPDATE_INCOMPATIBLE`，需要先卸载（会丢桌面布局）。
5. **国内网络**。更新包只能从我们仓库下，不像 Aerial 有 `aerial.qust.me` 镜像；必要时加 `gh-proxy` 类镜像兜底。
6. **上游节奏快**。代构建一旦断掉，工具箱就会停在旧版；页面要能显示"上游 v2.7.0 已发布，构建未就绪"。

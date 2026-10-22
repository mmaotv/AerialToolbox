# Aerial 工具箱

给电视 / 电视盒子安装开源桌面 [Aerial](https://github.com/pengchujin/aerial-tv) 并接管主页键的一站式工具。

**不需要电脑、不需要 root、不需要拆机** —— 把它装到电视上，剩下的它自己用电视本机的调试通道完成。

<p align="left">
<img alt="minSdk" src="https://img.shields.io/badge/minSdk-21-brightgreen">
<img alt="Android TV" src="https://img.shields.io/badge/Android%20TV-leanback-blue">
<img alt="license" src="https://img.shields.io/badge/license-MIT-orange">
</p>

## 它解决什么问题

Aerial 官方安装说明要求你在电脑上敲一大串 `adb shell` 命令：装包、授权、设默认桌面、
写无障碍守护、接管系统屏保、验收……中间任何一步顺序错了，装得上但主页键回不去。

这个工具把那一整套指令固化进 APK 里，在电视本机执行。除此之外还专门处理了几个真实痛点：

| 痛点 | 处理方式 |
|---|---|
| 电视连不上 GitHub（很常见） | 官方 Release 安装包**内置在 APK 里**，安装全程不联网 |
| TCL / 雷鸟拒绝 ADB 安装 | 提供 guard 版通道；仍失败时改调系统安装器 |
| 小米待机唤醒后 ADB 被系统关掉 | 「ADB 常开」，由桌面自己把调试开关开回来 |
| 装完按主页键还是回原桌面 | 「修复接管」只重跑授权与接管，不重装 |
| 不知道到底接管成功没有 | 「验收检查」按一次主页键，看前台窗口是不是 Aerial |

## 功能

| 卡片 | 做什么 |
|---|---|
| **一键安装 / 升级桌面** | 用内置离线包安装 → 授权 → 接管主页键 → 屏保 → 启动并确认守护绑定 |
| **桌面更新** | 进页面才联网查 GitHub 最新 Release，展示更新日志，用户点「下载并安装」后下载并安装 |
| **修复接管** | 不重装，只重跑授权 + 默认桌面 + 守护（覆盖升级后守护掉线时用） |
| **验收检查** | 4 项检查，只有「按主页键进入 Aerial」算真正通过 |
| **安装通道** | 标准版 ⇄ guard 版（guard 不带 HOME 声明，靠无障碍服务接管） |
| **ADB 常开** | 小米等设备待机后 ADB 被关闭时自动打开 |
| **屏保接管** | 把系统屏保换成 Aerial 的航拍屏保（仅使用系统屏保的电视，如 TCL） |
| **还原 / 卸载** | 逐条撤销授权与设置，可只还原，也可连 Aerial 一起卸载 |

## 两条路径：安装离线，更新在线

```
                    ┌─────────────────────────────┐
  点「一键安装」  →  │ 内置离线包 assets/bundled/*.apk │ → ADB 安装 → 授权 → 接管
                    └─────────────────────────────┘
                                   ↑ 不需要网络

                    ┌─────────────────────────────┐
  点「桌面更新」  →  │ GitHub API 查最新 Release    │ → 展示更新日志 → 用户点下载
                    └─────────────────────────────┘
                                   ↓
                      GitHub 直链失败自动切官网镜像
                                   ↓
                    ADB 安装（顺带接管）／系统安装器
```

「桌面更新」页打开时会自动检查一次，之后不主动联网。断网设备可以一直只用内置包。

## 工作原理

整个工具跑在电视本机，通过 `127.0.0.1:5555` 连接**自己**的 adbd，等价于在电脑上执行
`adb install` 与 `adb shell ...`。ADB 协议（CNXN / AUTH / OPEN / WRTE / CLSE）是自己实现的，
不依赖任何第三方库，也不需要 adb 可执行文件。

首次连接时电视上会弹「允许 USB 调试吗？」，用遥控器选允许即可，密钥保存在应用私有目录，
之后自动复用。设备侧的 adbd 只接受一条主机连接，因此主界面与更新页共用同一通道（`core/AdbSession`）。

安装包必须先落到 shell 能读的位置再 `pm install`：优先公共「下载」目录，
没有写权限时退到应用外部目录；系统安装器路径则通过自实现的 `ContentProvider` 提供只读句柄。

## 快速开始

1. 到 [Releases](../../releases) 下载 `AerialToolbox-x.y.z.apk`，装到电视（U 盘或你惯用的推送工具）。
2. 打开电视的 ADB 调试：

   | 设备 | 路径 |
   |---|---|
   | 小米 / Redmi | 设置 → 账号与安全 → ADB 调试 |
   | 原生 Android TV / 索尼 | 连按「内部版本号」7 次 → 开发者选项 → USB 调试 |
   | TCL / 雷鸟 | 设置 → 关于 → 本机信息 → 遥控器按 上 下 左 右 → 打开 ADB |
   | 海信 VIDAA | 开发者选项 → USB 调试（部分固件需先开启工厂模式） |

3. 打开工具箱 → 点「重新连接」（电视上确认授权）→ 点「一键安装 / 升级桌面」。
4. 装完点「验收检查」，看到「验收通过：主页键进入 Aerial」即完成。

## 兼容性

- `minSdk 21`（Android 5.0），`targetSdk 28`，无 `.so`，32 位老电视与 64 位新电视都能装。
- 已在小米盒子、小米电视系列验证过指令序列（沿用同一套已上线工具箱的实现）。
- **TCL / 雷鸟**：部分固件报 `INSTALL_FAILED_VERIFICATION_FAILURE`，切 guard 版重试；
  仍失败会自动拉起系统安装器，也可以从电视「下载」目录手动安装。
- **海信 VIDAA**：桌面由固件属性 `persist.vt.use_home_pkg` 决定，工具会记录原值并支持还原。
- 未在全部机型实测，遇到问题请带上「验收检查」的结果和 logcat 提 issue。

## 目录结构

```
app/src/main/
├── assets/bundled/            内置离线包（官方 Release 的 standard / guard 两个 APK）
│   └── meta.properties        内置包版本号与日期
├── java/com/miaomiaotv/aerial/
│   ├── adb/                   自实现的 ADB 协议与密钥（CNXN / AUTH / OPEN / WRTE）
│   ├── apk/ApkProvider.kt     交给系统安装器的只读文件提供者
│   ├── core/
│   │   ├── AerialSpec.kt      包名、组件、指令模板（对齐上游 tools/install.sh）
│   │   ├── AerialRunner.kt    安装 / 修复 / 验收 / 还原
│   │   ├── BundledRelease.kt  释放内置离线包
│   │   ├── ReleaseChecker.kt  GitHub Release 查询、镜像兜底、下载
│   │   └── AdbSession.kt      全局唯一调试通道
│   ├── MainActivity.kt        主界面（安装、接管、开关、还原）
│   └── UpdateActivity.kt      桌面更新页（查版本、看日志、下载安装）
└── res/layout/                TV 版 XML 布局（遥控器焦点）
```

## 构建

需要 JDK 17+ 与 Android SDK（compileSdk 36）。

```bash
# local.properties 里写 sdk.dir=...
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

未配置签名时使用本机调试密钥，产出可直接安装的包。正式发布请换成自己的 keystore。

## 更新内置离线包

内置包决定「离线安装」的版本，升级步骤如下：

1. 从 [aerial-tv Releases](https://github.com/pengchujin/aerial-tv/releases) 下载
   `aerial-standard.apk` 与 `aerial-guard.apk`，覆盖 `app/src/main/assets/bundled/`。
2. 改 `app/src/main/assets/bundled/meta.properties` 里的 `version` / `tag` / `publishedAt` / `versionCode`。
3. 改 `app/build.gradle.kts` 里工具箱自己的 `versionCode` / `versionName`。
4. 重新构建。Gradle 已配置 `noCompress += "apk"`，内置包原样入库。

版本号别偷懒：工具箱按 `meta.properties` 的版本给安装包命名，版本对不上会出现
「本机比内置包新」的降级提示（工具会自动 `pm install -r -d` 放行，但会先提醒你）。

## 免责声明

本工具会修改系统设置（默认桌面、无障碍服务、屏保、部分厂商属性），这些改动都可以通过
「还原 / 卸载」撤销，恢复原桌面的方式也一直保留。请自行评估风险，因使用本工具造成的
设备异常由使用者承担。

## 许可证

[MIT](LICENSE)。内置的 Aerial 安装包来自 [pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv)（MIT），
版权归原作者所有。Aerial 桌面本身与本工具无隶属关系。

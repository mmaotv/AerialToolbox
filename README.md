# 桌面工具箱 AerialToolbox

> 🌐 项目主页 / Homepage: **https://mmaotv.github.io/AerialToolbox/**

**给 Android 电视 / 盒子一键安装开源桌面（Aerial、CleanYourTV），并接管主页键。**
不用电脑、不用 root、不用拆机 —— 装到电视上它自己完成，或装到手机上远程帮电视装。

<p align="center">
  <img src="docs/screenshot-main.png" alt="主界面 · Aerial" width="680">
  <img src="docs/screenshot-cleantv.png" alt="主界面 · CleanYourTV" width="680">
</p>

<p align="left">
<img alt="minSdk" src="https://img.shields.io/badge/minSdk-21-brightgreen">
<img alt="license" src="https://img.shields.io/badge/license-MIT-orange">
<a href="https://github.com/mmaotv/AerialToolbox/actions/workflows/android.yml"><img alt="build" src="https://github.com/mmaotv/AerialToolbox/actions/workflows/android.yml/badge.svg"></a>
</p>

## 它能做什么

- **两个应用**：电视端（装在盒子上自己装）、手机端（填 IP 或扫局域网远程装），共用一套核心逻辑，装出来的结果一致。
- **两个桌面**：Aerial（tvOS 航拍风）、CleanYourTV（Apple TV dock 风），一次接管一个，可切换。
- **内置离线包**：安装全程不联网，电视连不上 GitHub 也能装。
- **ADB 常开**：小米等盒子待机后 ADB 被关，工具自动把调试开关开回来。
- **修复 / 验收 / 还原**：装完主页键回不去？一键修复接管；一键还原并把主页交还原桌面。

## 下载

到 [Releases](../../releases) 页面，按需要下载：

| 包 | 装在哪 | 文件名 |
|---|---|---|
| 电视端 | 盒子本机 | `AerialToolbox-x.y.z.apk` |
| 手机端 | 你的手机 | `AerialToolbox-Phone-x.y.z.apk` |
| CleanYourTV 桌面（可选） | 想用这个桌面时单独取 | `CleanYourTV-x.y.z.apk` |

- 电视 / 手机端最新版： [`v1.3.4`](https://github.com/mmaotv/AerialToolbox/releases/tag/v1.3.4) · [`phone-v1.0.2`](https://github.com/mmaotv/AerialToolbox/releases/tag/phone-v1.0.2)
- CleanYourTV 桌面： [`cyt-v3.0.0`](https://github.com/mmaotv/AerialToolbox/releases/tag/cyt-v3.0.0)

每个包都附带 `.sha256` 校验文件。

## 快速开始

### 电视端

1. 下载 `AerialToolbox-*.apk` 装到电视（U 盘或你惯用的推送方式）。
2. 打开电视的「开发者选项 → USB 调试 / ADB 调试」：
   - 小米 / Redmi：设置 → 账号与安全 → ADB 调试
   - 原生 Android TV / 索尼：连按「内部版本号」7 次 → 开发者选项 → USB 调试
3. 打开工具箱 →「重新连接」（电视上选允许授权）→「一键安装桌面」→「验收检查」通过即完成。

### 手机端

1. 下载 `AerialToolbox-Phone-*.apk` 装到手机。
2. 让电视开网络 ADB：连过一次电脑 ADB 后执行 `adb tcpip 5555`，或小米开「网络调试」。
3. 手机打开工具箱 → 扫描局域网找到电视 / 手填电视 IP → 连接（电视允许授权）→ 选桌面 → 安装 → 验收。
4. 手机和电视要在**同一局域网**；手机端不需要 root，也不需要电脑端工具。

## 常见问题

**Q：装完按主页键还是回原桌面？**
A：点「修复接管」；部分固件还要在电视上按一次主页键、弹窗里选「始终」。

**Q：手机扫到电视但连不上？**
A：确认同一局域网且电视已开网络 ADB（5555）。部分路由器开了 AP 隔离会拦设备互访，改手填 IP 或改用电视端。

**Q：连接时一直弹「允许 USB 调试」？**
A：首次连新设备会弹，用遥控器选允许，密钥会保存，以后不再弹。

**Q：电视端和手机端能同时装吗？**
A：可以，是两个独立应用；但同一台电视同一时刻只能有一个桌面在接管，装另一个之前会先解除前一个。

## 兼容性

- 支持 Android 5.0+（minSdk 21），32 / 64 位均可，无 `.so`。
- 已在小米盒子 / 小米电视系列验证；TCL / 雷鸟部分固件装不上时切 guard 版，仍失败会自动拉起系统安装器，也可从电视「下载」目录手动装。
- 遇到问题请带「验收检查」结果与 logcat 提 issue。

## 免责声明

本工具会修改系统设置（默认桌面、无障碍服务、屏保、部分厂商属性），都可通过「还原 / 卸载」撤销。请自行评估风险，因使用本工具造成的设备异常由使用者承担。

## 许可证

[MIT](LICENSE)。内置 Aerial 来自 [pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv)（MIT），CleanYourTV 来自 [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv)（MIT）源码打补丁后构建，版权均归原作者所有；两个桌面与本工具无隶属关系。

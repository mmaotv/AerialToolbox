# 第三方组件说明

本项目的源代码以 [MIT](LICENSE) 许可证发布。仓库中另有三类第三方内容：

## 1. 内置的 Aerial 安装包

`app/src/main/assets/bundled/aerial-standard.apk`
`app/src/main/assets/bundled/aerial-guard.apk`

- 来源：<https://github.com/pengchujin/aerial-tv> 的官方 Release 产物
- 版本：1.30（versionCode 32）
- 许可证：MIT，版权归 pengchujin 所有
- 说明：这两个 APK 未经修改，原样内置，只为了让连不上 GitHub 的设备也能完成离线安装。
  它们是独立应用的构建产物，与本项目不构成衍生作品关系。

## 2. Inter 字体

`app/src/main/assets/fonts/inter.ttf` 的来源与上游 Aerial 相同（上游放在 `res/font/inter.ttf`，
这里放在 assets 是因为要在 Android 5 的老电视上也能加载）：

- 项目：<https://github.com/rsms/inter>
- 许可证：SIL Open Font License 1.1
- 说明：界面字体与 Aerial 使用同一份可变字体（`wght` / `opsz` 两个轴），
  目的是让工具箱与桌面的文字风格保持一致。

## 3. Gradle Wrapper

`gradle/wrapper/gradle-wrapper.jar` 由 Gradle 官方发布，遵循 Apache License 2.0。

## 品牌与商标

图标、品牌色与界面视觉语言参照 [pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv)
的公开品牌资源（`docs/brand/icon.svg`、`app/.../ui/theme/Theme.kt`）制作，
目的是让这个安装工具在视觉上属于 Aerial 的同一套体系。

Aerial 桌面由该项目作者开发，与本工具箱不存在隶属或官方授权关系。
本项目只是把它在电视上的安装流程做得更省事。

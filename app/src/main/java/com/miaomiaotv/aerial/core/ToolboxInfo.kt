package com.miaomiaotv.aerial.core

import android.content.Context
import android.os.Build

/**
 * 工具箱自己的身份信息：仓库、版本、本版本更新日志。
 *
 * 与 [AerialSpec] 分开，因为这里描述的是「安装工具本身」，不是被安装的桌面。
 * 版本号不写死：一律从 PackageManager 读，改 `build.gradle.kts` 就够，
 * 免得界面上的版本和实际安装包对不上。
 */
object ToolboxInfo {

    const val REPO_OWNER = "mmaotv"
    const val REPO_NAME = "AerialToolbox"

    /**
     * 本仓库同时还有代构建的 CleanYourTV 发行版（`cyt-v*`），
     * 所以 `releases/latest` 未必是工具箱自己的那一个 —— 找自己必须扫列表、只认带安装包的那条。
     */
    const val API_LIST = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=30"

    const val RELEASE_PAGE = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest"

    /** Release 里安装包的名字前缀：AerialToolbox-1.3.0.apk */
    const val APK_PREFIX = "AerialToolbox-"

    /**
     * 电视端安装包的资产名规则：`AerialToolbox-<版本>.apk`，版本只允许数字与点。
     *
     * 为什么不用「前缀匹配」：本仓库同时发**手机端**安装包（`AerialToolbox-Phone-1.0.0.apk`），
     * 前缀匹配会把它也当成电视端的自更新包，用户点了升级就会装错应用。
     * 这里收紧成完整匹配，手机端那套带版本号的资产名自然被排除。
     */
    val TV_APK_PATTERN = Regex("^AerialToolbox-\\d+(\\.\\d+)*\\.apk$")

    fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "未知" }

    fun versionCode(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }.getOrNull() ?: 0L

    /** 展示用：v1.3.0（4） */
    fun display(context: Context): String = "v${versionName(context)}（${versionCode(context)}）"

    /**
     * 当前版本的更新日志。
     *
     * 发版时在这里加一段，格式与 GitHub Release 正文一致（markdown），
     * 会经 [Changelog.pretty] 清洗后显示在「关于」页。
     */
    val CHANGELOG: String = """
        ## v1.3.4 · 2026-10-23

        - 修「往下滚到底部时，顶部的按钮重叠显示」：主界面曾把根布局与宫格设成
          `clipChildren="false"`（本意是让焦点放大效果不被裁），但硬件加速下
          祖先不裁剪时，ScrollView 的内容会画到自己的边界外面 —— 滚到底部时
          第一行操作卡片整体压到顶部的信息面板上，文字叠在一起。已恢复默认裁剪，
          宫格内容只在自己的滚动区里显示；焦点放大只有 3%，视觉上不受影响
        - 手机端 v1.0.0 同期发布：填 IP 或扫局域网即可远程给电视装桌面、遥控、截图，
          与电视端共用同一套安装/还原/验收代码

        ## v1.3.3 · 2026-10-23

        - 修「卸载桌面后主页键短按失效、回不到桌面」：根因不在按键，在 HOME role。
          `cmd package set-home-activity` 会把 HOME role 从原桌面摘掉，但不会转给新桌面，
          也不管卸载 —— 实测 Android 14：设完 role 立刻变空，卸载后系统解析出
          `ResolverActivity`、`isDefault=false`，等于「没有任何默认桌面」，
          按主页键要么没反应，要么弹「打开方式」选择框
        - 归还主页改成两步：先 `cmd role add-role-holder` 补回 role，再写 `set-home-activity`，
          最后复查 `isDefault=true` 才算完成（旧版只做第二步，role 一直空着）
        - 原桌面记录不再依赖白名单：以前只有 tvlauncher/leanbox 那几个「可停用」的桌面才被记录，
          其它桌面（launcher3、aijia、手机桌面…）会被清成空，还原时整段跳过，
          主页就留在一个马上要被卸载的应用上 —— 现在「能不能停用」和「要不要归还」分开判断
        - 还原时记录丢失也能救：依次按「HOME role 持有者 → 系统声明的桌面候选」找回原桌面，
          一个不行换下一个，不会把用户留在一个没有桌面的状态
        - 「验收检查」新增「系统承认默认桌面」一项，`isDefault=false` 会直接标红
        - 修 720p 机器上宫格文字被纵向裁掉：三行卡片按权重均分只剩 59dp，装不下两行字。
          宫格改为按内容定高、外加可滚动，字不再被切

        ## v1.3.2 · 2026-10-22

        - 修「点了『我明白了』却执行了弹窗背后的卡片」：弹窗原来是主界面里盖的一层遮罩，
          只挡画面不挡焦点，按确认键会落到背后那张卡上。现在改成独立 Dialog 窗口，
          背景彻底收不到按键；进度弹窗期间不能再关掉它
        - 修「点了一个模块却跳到另一个模块」：宫格每次刷新都会重建，重建后焦点被强行塞给第一张卡片。
          现在按槽位记住原来的焦点，重建后放回原处；操作进行中/弹窗显示时宫格整块不接焦点
        - 修「按确认键操作从头再来一遍」：遥控器连击与关弹窗那一下会二次触发，加了 600ms 防连击闸门；
          主界面改为 singleTask，装完桌面用 am start 叫回前台不再重建实例、丢进度
        - 修 Android 9 及以下设不上默认桌面：这些系统没有 role 服务，`cmd role` 回的是
          「Can't find service: role」，旧代码把它当成成功，后面真正的 set-home-activity 从不执行
          —— 表现就是装完按主页键还是回原桌面（Amlogic / 小米盒子一类 Android 9 机型都能复现）
        - 设默认桌面改为以命令退出码为准，不再靠关键字猜成功
        - 「还原」在 Android 8/9 上同样受 role 服务缺失影响，主页还回不去：现在会在接管前记下原桌面，
          还原时显式设回去
        - 「验收检查」不再把「模拟主页键没反应」判成失败 —— 部分固件会忽略注入的 HOME 键，
          改以系统解析到的默认桌面为准
        - 修两个桌面互相抢主页：装 CleanYourTV 时会停用 Aerial 并撤掉它的无障碍守护
          （Aerial 的 HomeGuardService 会把前台抢回去），切回 Aerial 时自动启用
        - 修 CleanYourTV 内置包读不到：64 KB 的体积阈值原是按 Aerial 定的 1 MB，
          679 KB 的 cyt 包被当成损坏包而回退联网下载；改成校验 ZIP 头
        - 修从别的页面回来时崩溃（后台线程直接改控件，Android viewRoot 直接抛 CalledFromWrongThread）
        - 去掉底部「已连接本机调试通道 …」那行，顶栏状态胶囊已经说明连接状态

        ## v1.3.1 · 2026-10-22

        - 新增第二个上游：CleanYourTV（com.gathin.tvhome），主界面顶栏可切换 Aerial / CleanYourTV
        - CleanYourTV 支持 Android 5.1 以上：上游原版 minSdk 是 26，我们用补丁降到 22 后自行构建
        - 装 CleanYourTV 会先校验新桌面能否启动，再停用原桌面；主页切不过去会自动回滚
        - 新增遥控器「输入源键」「Home 键兼容」两个开关（无障碍服务，默认关）
        - 还原会重新启用原桌面并把主页还回去；可一并卸载 CleanYourTV
        - 内置 CleanYourTV v2.6.0 离线包（约 660 KB），装它不需要联网
        - 桌面更新页按目标分流：Aerial 查上游，CleanYourTV 查本仓库代构建的 cyt-v* 发行版
        - 本仓库同时放两种发行版后，「查自己有没有新版」改成扫发行版列表，不会被 cyt-v* 顶掉

        ## v1.3.0 · 2026-10-22

        - 新增「关于 / 更新工具箱」页：本工具的版本信息、本版本更新日志，以及检查 GitHub 最新 Release
        - 工具箱可以自己升级自己：查版本 → 看更新日志 → 下载 → ADB 安装，ADB 不可用时交给系统安装器
        - 主界面宫格补满 9 格（三行三列），「关于」放在「还原 / 卸载」右边
        - 桌面安装、修复、验收、还原的行为没有变化

        ## v1.2.0 · 2026-10-22

        - 配色、字号、Inter 字体、图标与 TV banner 全部对齐 Aerial 桌面
        - 内置离线包升到 Aerial v1.30（2026-10-22）
        - 卡片改为三列，说明文案压到单行
    """.trimIndent()
}

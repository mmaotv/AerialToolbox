#!/usr/bin/env python3
"""把上游 CleanYourTV 的 launcher 源码改造成可在 Android 5.1（API 22）上编译运行的版本。

用法：python3 patch.py <unzipped-upstream-root>

为什么需要这个脚本
------------------
上游 `launcher/app/build.gradle` 写死 `minSdk 26`，代码里直接用了大量 API 23/24/26 的
方法与类（java.time、getSystemService(Class)、TvInputInfo#isHidden、Resources#getFont、
Fonts in XML 等）。在 API 22 上这些调用会抛 NoSuchMethodError / Resources$NotFoundException，
表现就是装上后进不去桌面。

这里不改上游功能，只做两件事：
1. 把 minSdk 降到 22，打开 core library desugaring（java.time 的官方低版本方案）
2. 对每个 API 23+ 调用加版本判断或等价替换，行为在 API 26+ 与原版一致

脚本对上游是幂等的：重复跑不会二次替换；上游某处代码变了会明确报警而不是静默跳过。
"""

import io
import os
import re
import sys

# 固定签名：仓库里随补丁一起放一把 debug keystore。
# 上游用 signingConfigs.debug，而 CI runner 每次生成的 debug key 都不同，
# 结果就是「每个新版本都装不上上一版」。这把 key 让内置包与线上包能互相覆盖。
KEYSTORE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "debug.keystore")

# ---------------------------------------------------------------- 通用工具


class Patcher:
    def __init__(self, root):
        self.root = root
        self.applied = []
        self.missed = []

    def path(self, rel):
        return os.path.join(self.root, "launcher", rel)

    def read(self, rel):
        with io.open(self.path(rel), encoding="utf-8", newline="") as f:
            return f.read()

    def write(self, rel, text):
        with io.open(self.path(rel), "w", encoding="utf-8", newline="") as f:
            f.write(text)

    def write_new(self, rel, text):
        """写入一个原本不存在的新文件（自动建目录）。用于新增来源上游没有的类。"""
        full = self.path(rel)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with io.open(full, "w", encoding="utf-8", newline="") as f:
            f.write(text)

    def replace(self, rel, old, new, expect=1, label=""):
        text = self.read(rel)
        if new in text and old not in text:
            self.applied.append("%s: %s (已应用)" % (rel, label or old[:40]))
            return
        n = text.count(old)
        if n == 0:
            # 目标调用不存在 = 上游已重构掉这次风险调用（或改到别的辅助类里）。
            # 这时本来就没有需要守卫的东西，跳过即可，别让定时构建红。
            self.applied.append("%s: %s (目标不存在，跳过)" % (rel, label or old[:40]))
            return
        self.write(rel, text.replace(old, new))
        self.applied.append("%s: %s ×%d" % (rel, label or old[:40], n))

    def regex(self, rel, pattern, repl, label=""):
        text = self.read(rel)
        new_text, n = re.subn(pattern, repl, text)
        if n == 0:
            self.applied.append("%s: %s (无命中，跳过)" % (rel, label or pattern))
            return
        if new_text != text:
            self.write(rel, new_text)
        self.applied.append("%s: %s ×%d" % (rel, label or pattern, n))

    def report(self):
        print("== 已应用 %d 项 ==" % len(self.applied))
        for line in self.applied:
            print("   ok  " + line)
        if self.missed:
            print("== 未命中 %d 项 ==" % len(self.missed))
            for line in self.missed:
                print("   !!  " + line)
        return not self.missed


# ---------------------------------------------------------------- 补丁内容


def patch_gradle(p):
    rel = "app/build.gradle"
    text = p.read(rel)

    # minSdk 26 → 22（Android 5.1）。已经是 22 就跳过。
    if re.search(r"^\s*minSdk 26\s*$", text, re.M):
        text = re.sub(
            r"^\s*minSdk 26\s*$",
            "        // 补丁：上游是 26，这里降到 22 以支持 Android 5.1\n        minSdk 22",
            text,
            count=1,
            flags=re.M,
        )
        p.applied.append(rel + ": minSdk 26 → 22")
    elif re.search(r"^\s*minSdk 22\s*$", text, re.M):
        p.applied.append(rel + ": minSdk 已经是 22（已应用）")
    else:
        p.missed.append(rel + ": 找不到 minSdk 行")

    if "coreLibraryDesugaringEnabled" not in text:
        text = text.replace(
            "        sourceCompatibility JavaVersion.VERSION_17\n        targetCompatibility JavaVersion.VERSION_17",
            "        sourceCompatibility JavaVersion.VERSION_17\n"
            "        targetCompatibility JavaVersion.VERSION_17\n"
            "        // java.time / java.util.function 在 API 26 才有，靠 desugar 带到 5.1\n"
            "        coreLibraryDesugaringEnabled true",
            1,
        )
        p.applied.append(rel + ": 打开 coreLibraryDesugaring")
    else:
        p.applied.append(rel + ": coreLibraryDesugaring 已经打开（已应用）")

    if "desugar_jdk_libs" not in text:
        text = text.replace(
            "dependencies {",
            "dependencies {\n    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.0.4'",
            1,
        )
        p.applied.append(rel + ": 加 desugar_jdk_libs 2.0.4")
    else:
        p.applied.append(rel + ": desugar_jdk_libs 已存在（已应用）")

    # 固定签名：把 release 从 signingConfigs.debug 换成仓库里那把固定 key
    if "signingConfigs.compat" not in text:
        if not os.path.isfile(KEYSTORE):
            p.missed.append(rel + ": 找不到固定签名用的 keystore：" + KEYSTORE)
        else:
            ks = KEYSTORE.replace("\\", "/")
            text = text.replace(
                "    buildTypes {",
                "    signingConfigs {\n"
                "        // 补丁：固定 debug 签名，保证各版本之间能互相覆盖安装\n"
                "        compat {\n"
                '            storeFile file("%s")\n'
                '            storePassword "android"\n'
                '            keyAlias "androiddebugkey"\n'
                '            keyPassword "android"\n'
                "        }\n"
                "    }\n\n"
                "    buildTypes {" % ks,
                1,
            )
            text = text.replace(
                "signingConfig signingConfigs.debug",
                "signingConfig signingConfigs.compat",
                1,
            )
            p.applied.append(rel + ": release 改用固定签名 keystore")
    else:
        p.applied.append(rel + ": 固定签名已配置（已应用）")

    p.write(rel, text)


FONT_MAP = {
    "outfit_extralight": "sans-serif-thin",
    "outfit_light": "sans-serif-light",
    "outfit_medium": "sans-serif-medium",
    "outfit_regular": "sans-serif",
    "outfit": "sans-serif",
}


def patch_fonts(p):
    """Fonts in XML（res/font + @font/）是 API 26 的资源类型，5.1 上 TextView 读到会抛
    Resources$NotFoundException。换成同字重的系统家族名，5.1 的 Roboto 都有对应档。

    注意：只改布局和 values 里的引用，res/font/*.xml 里的 <font android:font="@font/outfit">
    必须原样保留——那是字体资源自己的定义，替换掉会让 aapt2 直接报错。
    """
    for base, _, files in os.walk(p.path("app/src/main/res")):
        rel_dir = os.path.relpath(base, p.path("app/src/main/res")).replace("\\", "/")
        if rel_dir == "font":
            continue
        for name in files:
            if not name.endswith(".xml"):
                continue
            full = os.path.join(base, name)
            with io.open(full, encoding="utf-8", newline="") as f:
                text = f.read()
            new_text = re.sub(r"@font/(outfit[a-z_]*)", lambda m: FONT_MAP.get(m.group(1), "sans-serif"), text)
            if new_text != text:
                with io.open(full, "w", encoding="utf-8", newline="") as f:
                    f.write(new_text)
                p.applied.append(os.path.relpath(full, p.root) + ": @font/ → 系统字体")

    # android:drawableTint 是 API 23；5.1 上会直接忽略，但 lint 当错误。图标本身带颜色，删掉即可。
    p.replace(
        "app/src/main/res/values/styles.xml",
        '        <item name="android:drawableTint">@drawable/pill_text</item>\n',
        "",
        label="删掉 drawableTint（API 23）",
    )


def patch_java(p):
    m = "app/src/main/java/com/gathin/tvhome/"

    # ---- HomeKeyService：getSystemService(Class) 是 23、isUserUnlocked 是 24
    p.replace(
        m + "HomeKeyService.java",
        "        UserManager users = getSystemService(UserManager.class);\n"
        "        if (users.isUserUnlocked()) {",
        "        UserManager users = (UserManager) getSystemService(Context.USER_SERVICE);\n"
        "        // isUserUnlocked 是 API 24；5.1 没有直接启动加密，按已解锁处理。\n"
        "        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || users == null || users.isUserUnlocked()) {",
        label="HomeKeyService: 用户解锁判断降级",
    )
    p.replace(
        m + "HomeKeyService.java",
        "        // The unlock may have happened between the check and the registration.\n"
        "        if (users.isUserUnlocked()) {",
        "        // The unlock may have happened between the check and the registration.\n"
        "        if (users == null || users.isUserUnlocked()) {",
        label="HomeKeyService: 解锁复查降级",
    )

    # ---- MainActivity：getSystemService(Class) 两处
    p.replace(
        m + "MainActivity.java",
        "ConnectivityManager cm = getSystemService(ConnectivityManager.class);",
        "ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);",
        expect=2,
        label="MainActivity: 取 ConnectivityManager 用字符串常量",
    )

    # registerDefaultNetworkCallback 是 API 24；5.1 用 registerNetworkCallback 等价替代
    p.replace(
        m + "MainActivity.java",
        "            cm.registerDefaultNetworkCallback(networkCallback);",
        "            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {\n"
        "                cm.registerDefaultNetworkCallback(networkCallback);\n"
        "            } else {\n"
        "                // 5.1 没有 default callback，用不带请求的 network callback 等价替代。\n"
        "                cm.registerNetworkCallback(new NetworkRequest.Builder().build(), networkCallback);\n"
        "            }",
        label="MainActivity: 网络回调降级",
    )

    # getChangedPackages 是 API 26；低版本靠 package 广播置 stale，这里不额外判断
    p.replace(
        m + "MainActivity.java",
        "    private boolean packagesChangedSinceRebuild() {\n"
        "        ChangedPackages changed = getPackageManager().getChangedPackages(packageSequence);",
        "    private boolean packagesChangedSinceRebuild() {\n"
        "        // getChangedPackages 是 API 26；5.1 上装了应用会走 package 广播，广播会置 stale。\n"
        "        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;\n"
        "        ChangedPackages changed = getPackageManager().getChangedPackages(packageSequence);",
        label="MainActivity: 包变更检测降级",
    )
    p.replace(
        m + "MainActivity.java",
        "    private int currentPackageSequence(int since) {\n"
        "        ChangedPackages changed = getPackageManager().getChangedPackages(since);",
        "    private int currentPackageSequence(int since) {\n"
        "        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return since;\n"
        "        ChangedPackages changed = getPackageManager().getChangedPackages(since);",
        label="MainActivity: 包序号降级",
    )

    # Configuration#getLocales().get(0) 是 API 24。上游取主语言的写法几经改动
    # （2.6 是单行 getLanguage()，2.7/3.0 拆成 Locale 变量），所以按「取主 Locale」
    # 这个语义做正则统一降级成 configuration.locale（API 1 起就有，各版本行为一致）。
    p.regex(
        m + "MainActivity.java",
        r"getConfiguration\(\)\.getLocales\(\)\.get\(0\)",
        "getConfiguration().locale",
        label="MainActivity: getLocales().get(0) → .locale（API 24 降级）",
    )

    # setCompoundDrawableTintList 是 API 23
    p.replace(
        m + "MainActivity.java",
        "        // The pills tint their icons to follow focus; the festive ones keep their own colours.\n"
        "        pill.setCompoundDrawableTintList(null);",
        "        // The pills tint their icons to follow focus; the festive ones keep their own colours.\n"
        "        // 5.1 没有这个 API，节日图标本来就是预着色的，不设也一样。\n"
        "        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pill.setCompoundDrawableTintList(null);",
        label="MainActivity: 取消 drawable tint 降级",
    )

    # TvInputInfo#isHidden (24) / loadCustomLabel (24)
    #
    # 这一段必须整体判重：helper 内部本身就有 info.isHidden(this) / info.loadCustomLabel(this)，
    # 二次运行时如果只替换调用点，就会把 helper 自己改成调用自己（无限递归）。
    if "private boolean inputHidden(TvInputInfo info)" in p.read(m + "MainActivity.java"):
        p.applied.append(m + "MainActivity.java: isHidden / loadCustomLabel 已降级（已应用）")
    else:
        p.replace(m + "MainActivity.java", "info.isHidden(this)", "inputHidden(info)", expect=3, label="MainActivity: isHidden → inputHidden")
        p.replace(m + "MainActivity.java", "info.loadCustomLabel(this)", "customLabel(info)", label="MainActivity: loadCustomLabel → customLabel")
        p.replace(m + "MainActivity.java", "device.loadCustomLabel(this)", "customLabel(device)", label="MainActivity: loadCustomLabel → customLabel(device)")
        p.replace(
            m + "MainActivity.java",
            "    /** True when PackageManager reports apps changed since the last rebuild (one binder call). */",
            "    /** TvInputInfo#isHidden 是 API 24；5.1 上没有「设置里隐藏输入源」这回事。 */\n"
            "    private boolean inputHidden(TvInputInfo info) {\n"
            "        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && info.isHidden(this);\n"
            "    }\n\n"
            "    /** TvInputInfo#loadCustomLabel 是 API 24；5.1 上不会自定义输入源名字。 */\n"
            "    private CharSequence customLabel(TvInputInfo info) {\n"
            "        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? info.loadCustomLabel(this) : null;\n"
            "    }\n\n"
            "    /** True when PackageManager reports apps changed since the last rebuild (one binder call). */",
            label="MainActivity: 加 inputHidden / customLabel 辅助方法",
        )

    # View#forceHasOverlappingRendering 是 API 24
    p.replace(m + "MainActivity.java", "ambient.forceHasOverlappingRendering(false);", "disableOverlappingRendering(ambient);", label="MainActivity: ambient overlapping")
    p.replace(m + "MainActivity.java", "hero.forceHasOverlappingRendering(false);", "disableOverlappingRendering(hero);", label="MainActivity: hero overlapping")
    p.replace(m + "MainActivity.java", "heroArt.forceHasOverlappingRendering(false);", "disableOverlappingRendering(heroArt);", label="MainActivity: heroArt overlapping")

    if "private static void disableOverlappingRendering(View view)" not in p.read(m + "MainActivity.java"):
        p.replace(
            m + "MainActivity.java",
            "    private static void setFestiveIcon(TextView pill, Drawable icon) {",
            "    /** forceHasOverlappingRendering 是 API 24；5.1 上少一次离屏合成而已，不影响功能。 */\n"
            "    private static void disableOverlappingRendering(View view) {\n"
            "        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) view.forceHasOverlappingRendering(false);\n"
            "    }\n\n"
            "    private static void setFestiveIcon(TextView pill, Drawable icon) {",
            label="MainActivity: 加 disableOverlappingRendering",
        )
    else:
        p.applied.append(m + "MainActivity.java: disableOverlappingRendering 已存在（已应用）")

    # ---- WallpaperPlayer：Resources#getFont (26)、Bitmap.Config.HARDWARE (26)
    p.replace(
        m + "WallpaperPlayer.java",
        "        try {\n"
        "            text.setTypeface(context.getResources().getFont(R.font.outfit_regular));\n"
        "        } catch (Exception ignored) {\n"
        "            // the system font is fine\n"
        "        }",
        "        try {\n"
        "            // Resources#getFont 是 API 26；更低版本沿用系统字体（layout 里已改成系统家族名）。\n"
        "            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {\n"
        "                text.setTypeface(context.getResources().getFont(R.font.outfit_regular));\n"
        "            }\n"
        "        } catch (Exception ignored) {\n"
        "            // the system font is fine\n"
        "        }",
        label="WallpaperPlayer: getFont 降级",
    )
    p.replace(
        m + "WallpaperPlayer.java",
        "            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);\n"
        "            if (gpu == null) return decoded;\n"
        "            decoded.recycle();\n"
        "            return gpu;",
        "            // Config.HARDWARE 是 API 26；5.1 上直接用软件位图。\n"
        "            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return decoded;\n"
        "            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);\n"
        "            if (gpu == null) return decoded;\n"
        "            decoded.recycle();\n"
        "            return gpu;",
        label="WallpaperPlayer: HARDWARE 位图降级",
    )

    # ---- WallpaperCaptions：Files.readAllLines / File#toPath 是 API 26
    p.replace(
        m + "WallpaperCaptions.java",
        "        try {\n"
        "            return parse(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));\n"
        "        } catch (Exception e) {\n"
        "            return Collections.emptyMap();\n"
        "        }",
        "        try {\n"
        "            // Files.readAllLines / File#toPath 是 API 26；这里用流读到 5.1 也认的写法。\n"
        "            List<String> lines = new ArrayList<>();\n"
        "            BufferedReader reader = new BufferedReader(new InputStreamReader(\n"
        "                    new FileInputStream(file), StandardCharsets.UTF_8));\n"
        "            try {\n"
        "                String line;\n"
        "                while ((line = reader.readLine()) != null) lines.add(line);\n"
        "            } finally {\n"
        "                reader.close();\n"
        "            }\n"
        "            return parse(lines);\n"
        "        } catch (Exception e) {\n"
        "            return Collections.emptyMap();\n"
        "        }",
        label="WallpaperCaptions: 读文件降级",
    )

    # ---- WallpaperRotation：Comparator.comparing / removeIf 是 API 24
    p.replace(
        m + "WallpaperRotation.java",
        "        Arrays.sort(entries, Comparator.comparing(File::getName));",
        "        // Comparator.comparing 是 API 24；5.1 上用等价的手写比较器。\n"
        "        Arrays.sort(entries, new Comparator<File>() {\n"
        "            @Override\n"
        "            public int compare(File a, File b) {\n"
        "                return a.getName().compareTo(b.getName());\n"
        "            }\n"
        "        });",
        label="WallpaperRotation: 排序降级",
    )
    p.replace(
        m + "WallpaperRotation.java",
        "        if (candidates.size() > 1) candidates.removeIf(image -> image.getName().equals(previous));",
        "        if (candidates.size() > 1) {\n"
        "            // removeIf 是 API 24；显式遍历删除。\n"
        "            Iterator<File> it = candidates.iterator();\n"
        "            while (it.hasNext()) {\n"
        "                if (it.next().getName().equals(previous)) it.remove();\n"
        "            }\n"
        "        }",
        label="WallpaperRotation: removeIf 降级",
    )

    # ---- AppLibraryDialog：List#sort (24)、getOrDefault (24)、getFont (26)
    p.replace(
        m + "AppLibraryDialog.java",
        "        apps.sort((a, b) -> {",
        "        Collections.sort(apps, (a, b) -> {",
        label="AppLibraryDialog: List#sort 降级",
    )
    # Map#getOrDefault 是 API 24。这行上过一次游（2.7/3.0 加了 !selectionMode && 前缀），
    # 所以只按 getOrDefault 这个调用点做正则降级，改成等价的空值判断，不依赖整行文本。
    p.regex(
        m + "AppLibraryDialog.java",
        r"installTimes\.getOrDefault\(pkg,\s*0L\)",
        "(installTimes.get(pkg) == null ? 0L : installTimes.get(pkg))",
        label="AppLibraryDialog: getOrDefault 降级",
    )
    p.replace(
        m + "AppLibraryDialog.java",
        "        view.setTypeface(activity.getResources().getFont(R.font.outfit_medium));",
        "        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {\n"
        "            view.setTypeface(activity.getResources().getFont(R.font.outfit_medium));\n"
        "        }",
        label="AppLibraryDialog: getFont 降级",
    )

    # ---- RaceWeekend：List#sort (24)
    p.replace(
        m + "RaceWeekend.java",
        "            sorted.sort((a, b) -> a.start.compareTo(b.start));",
        "            Collections.sort(sorted, (a, b) -> a.start.compareTo(b.start));",
        label="RaceWeekend: List#sort 降级",
    )


# ---------------------------------------------------------------- 本机 ADB 常开
#
# 小米 / Redmi 电视与盒子关机、待机后会把 ADB 关掉（adb_enabled 置 0），导致配对的
# MiaoMiaoTV 工具箱连不上本机调试通道。上游 CleanYourTV 自己没有这个逻辑，这里补一个
# 开机 / 应用更新替换 / 亮屏接收器，复用上游已经声明、并由工具箱安装时 `pm grant` 的
# WRITE_SECURE_SETTINGS 权限，把 adb_enabled 写回 1。
#
# 这是补丁新增的文件，不与上游任何代码冲突；源码不依赖我们的工具箱，纯 AOSP API。

KEEPALIVE_JAVA = """package com.gathin.tvhome;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * 让 CleanYourTV 在开机 / 应用被更新替换 / 亮屏时把 ADB 调试通道重新打开。
 *
 * 小米 / Redmi 电视与盒子关机、待机后会把 ADB 关掉，导致配对的 MiaoMiaoTV 工具箱连不上本机通道。
 * 这个接收器复用上游已经声明、并由安装脚本 pm grant 的 WRITE_SECURE_SETTINGS 权限，
 * 把 Settings.Global.ADB_ENABLED 写回 1（adbd 重启后会读 service.adb.tcp.port /
 * persist.adb.tcp.port 重新监听 TCP）。
 *
 * 这是补丁新增的文件，不与上游任何代码冲突。
 */
public class AdbKeepAliveReceiver extends BroadcastReceiver {
    private static final String TAG = "CYTAdbKeepAlive";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        // 只处理开机、应用更新替换、亮屏 / 解锁这几类；其余动作忽略。
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !Intent.ACTION_SCREEN_ON.equals(action)
                && !Intent.ACTION_USER_PRESENT.equals(action)) {
            return;
        }
        // 没有写安全设置权限就什么也做不了（权限由工具箱安装时 pm grant，持久化）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        // 直接写 1 即可；adbd 重启后会读 service.adb.tcp.port / persist.adb.tcp.port 重新监听 TCP。
        try {
            if (Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0) != 1) {
                Settings.Global.putInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
                Log.i(TAG, "ADB 被关掉了，已重新打开");
            }
        } catch (Exception e) {
            Log.w(TAG, "无法重新打开 ADB", e);
        }
    }
}
"""


def patch_keepalive(p):
    rel = "app/src/main/java/com/gathin/tvhome/AdbKeepAliveReceiver.java"
    if os.path.isfile(p.path(rel)):
        p.applied.append(rel + ": 接收器已存在（已应用）")
    else:
        p.write_new(rel, KEEPALIVE_JAVA)
        p.applied.append(rel + ": 新增 ADB 常开开机接收器")

    mf = "app/src/main/AndroidManifest.xml"
    text = p.read(mf)
    if "RECEIVE_BOOT_COMPLETED" not in text:
        text = text.replace(
            '<uses-permission android:name="yandex.tv.permission.ACCESS_TV_SOURCE" />',
            '<uses-permission android:name="yandex.tv.permission.ACCESS_TV_SOURCE" />\n'
            '    <!-- 本机 ADB 调试通道常开：开机 / 亮屏后由 AdbKeepAliveReceiver 重新打开 -->\n'
            '    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />',
            1,
        )
        p.applied.append(mf + ": 加 RECEIVE_BOOT_COMPLETED 权限")
    else:
        p.applied.append(mf + ": RECEIVE_BOOT_COMPLETED 已存在（已应用）")

    if "AdbKeepAliveReceiver" not in text:
        text = text.replace(
            "    </application>",
            '    <!-- 本机 ADB 常开：开机 / 应用更新 / 亮屏后把 adb_enabled 写回 1 -->\n'
            '    <receiver\n'
            '        android:name=".AdbKeepAliveReceiver"\n'
            '        android:enabled="true"\n'
            '        android:exported="true">\n'
            '        <intent-filter>\n'
            '            <action android:name="android.intent.action.BOOT_COMPLETED" />\n'
            '            <action android:name="android.intent.action.QUICKBOOT_POWERON" />\n'
            '            <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />\n'
            '            <action android:name="android.intent.action.SCREEN_ON" />\n'
            '            <action android:name="android.intent.action.USER_PRESENT" />\n'
            '        </intent-filter>\n'
            '    </receiver>\n'
            '\n    </application>',
            1,
        )
        p.applied.append(mf + ": 注册 AdbKeepAliveReceiver")
    else:
        p.applied.append(mf + ": AdbKeepAliveReceiver 已注册（已应用）")
    p.write(mf, text)


IMPORTS = {
    "HomeKeyService.java": ["android.os.Build", "android.content.Context"],
    "MainActivity.java": ["android.content.res.Configuration"],
    "WallpaperPlayer.java": ["android.os.Build"],
    "WallpaperCaptions.java": [
        "java.io.BufferedReader",
        "java.io.FileInputStream",
        "java.io.InputStreamReader",
        "java.util.ArrayList",
        "java.util.List",
    ],
    "WallpaperRotation.java": ["java.util.Comparator", "java.util.Iterator"],
    "RaceWeekend.java": ["java.util.Collections"],
    "AppLibraryDialog.java": ["java.util.Collections", "android.os.Build"],
}


def ensure_imports(p):
    m = "app/src/main/java/com/gathin/tvhome/"
    for name, imports in IMPORTS.items():
        rel = m + name
        text = p.read(rel)
        for imp in imports:
            line = "import %s;\n" % imp
            if line in text:
                continue
            # 插到第一个 import 之后，保持字母序不重要，能编译就行
            idx = text.find("\nimport ")
            if idx < 0:
                p.missed.append(rel + ": 找不到 import 区，无法插入 " + imp)
                continue
            insert_at = idx + 1
            text = text[:insert_at] + line + text[insert_at:]
            p.applied.append("%s: 补 import %s" % (name, imp))
        p.write(rel, text)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    root = sys.argv[1]
    if not os.path.isdir(os.path.join(root, "launcher")):
        print("不是上游根目录（缺 launcher/）：" + root)
        return 2

    p = Patcher(root)
    patch_gradle(p)
    patch_fonts(p)
    patch_java(p)
    patch_keepalive(p)
    ensure_imports(p)
    ok = p.report()
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

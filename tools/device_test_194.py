#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AerialToolbox 真机实测脚本（192.168.10.194 MBox, SDK 29）。

不依赖 gradle / 不装应用即可在真机上逐条复刻 Kotlin 核心代码的命令序列，
验证「安装 / 接管 / 验收 / 修复 / 还原」每个功能在真实固件上的真实结果。
所有命令经由已在本机授权的 PC adb 下发（与工具箱在设备上走 127.0.0.1:5555 是同一台 adbd，
权限上下文一致 = shell uid 2000）。顺序连接不会触发 adbd 单连接互斥。

跑完会把设备还原回原始默认桌面（com.droidlogic.mboxlauncher），并在末尾强制复查。
"""
import subprocess, sys, json, re, time

SERIAL = "192.168.10.194:5555"
ADB = r"C:/Users/Administrator/AppData/Local/Android/Sdk/platform-tools/adb.exe"
STAGING_AERIAL = "/data/local/tmp/aerial-install.apk"
STAGING_CYT = "/data/local/tmp/tvhome.apk"

AERIAL_PKG = "app.orchard.tv"
AERIAL_MAIN = "app.orchard.tv/.MainActivity"
AERIAL_GUARD = "app.orchard.tv/app.orchard.tv.guard.HomeGuardService"
REVIVAL_LISTENER = "app.orchard.tv/app.orchard.tv.guard.RevivalListener"
CONFIG_RECEIVER = "app.orchard.tv/.guard.ConfigReceiver"
CONFIG_ACTION = "app.orchard.tv.CONFIG"

CYT_PKG = "com.gathin.tvhome"
CYT_MAIN = "com.gathin.tvhome/.MainActivity"

STOCK = "com.droidlogic.mboxlauncher"
STOCK_MAIN = "com.droidlogic.mboxlauncher/.Launcher"

results = []  # (section, name, ok, detail)

def sh(cmd, timeout=90):
    """执行一条 adb shell 命令，返回 stdout 文本。"""
    try:
        p = subprocess.run(
            [ADB, "-s", SERIAL, "shell", cmd],
            capture_output=True, text=True, timeout=timeout
        )
        return (p.stdout or "") + (p.stderr or "")
    except subprocess.TimeoutExpired:
        return "<<TIMEOUT>>"
    except Exception as e:
        return "<<ERR:%s>>" % e

def log(line):
    print("  · " + line)

def section(title):
    print("\n===== %s =====" % title)

def record(section_name, name, ok, detail):
    results.append((section_name, name, ok, detail))
    print(("  [OK]  " if ok else "  [FAIL]") + name + " :: " + detail)

def resolve_home():
    out = sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
    line = ""
    for l in out.splitlines():
        l = l.strip()
        if "/" in l and not l.startswith("priority="):
            line = l
    is_default = "isDefault=true" in out
    return line.strip("[]").strip(), is_default

def installed_version(pkg):
    dump = sh("dumpsys package %s" % pkg)
    m = re.search(r"versionName=([^\s]+)", dump)
    if m:
        return m.group(1)
    lst = sh("pm list packages %s" % pkg)
    return "" if ("package:%s" % pkg) in lst else None

# ---------------------------------------------------------------------------
# AERIAL
# ---------------------------------------------------------------------------
def test_aerial():
    section("AERIAL 桌面：安装 / 接管 / 验收 / 修复 / 还原")
    # 0) 初始状态
    prev_home, prev_default = resolve_home()
    record("Aerial", "初始默认桌面", True, "接管前 = %s (isDefault=%s)" % (prev_home or "无", prev_default))

    # 1) 安装（已 push 到 staging；复刻 installApk 的 pm install -r）
    out = sh("pm install -r %s" % STAGING_AERIAL, timeout=120)
    ok = (installed_version(AERIAL_PKG) is not None) and ("Failure" not in out)
    record("Aerial", "安装 aerial-standard.apk", ok, out.strip()[:120])
    if not ok:
        return

    # 2) 授权（尽力而为，非致命）
    grants = [
        "pm grant %s android.permission.ACCESS_FINE_LOCATION" % AERIAL_PKG,
        "pm grant %s android.permission.WRITE_SECURE_SETTINGS" % AERIAL_PKG,
        "appops set %s SYSTEM_ALERT_WINDOW allow" % AERIAL_PKG,
        "appops set %s WRITE_SETTINGS allow" % AERIAL_PKG,
        "cmd notification allow_listener %s" % REVIVAL_LISTENER,
        "appops set %s APP_AUTO_START allow" % AERIAL_PKG,
        "appops set %s APP_ASSOC_START allow" % AERIAL_PKG,
    ]
    bad = []
    for g in grants:
        r = sh(g).strip()
        if any(k in r for k in ("Exception", "Error", "Failure", "not found", "Unknown")):
            bad.append(g.split(' ', 2)[-1][:40])
    record("Aerial", "授权（非致命）", True,
           "失败项 %d 个：%s" % (len(bad), ", ".join(bad) if bad else "无"))

    # 3) 接管（默认 useGuard=false → 直接设默认桌面）
    role_out = sh("cmd role add-role-holder --user 0 android.app.role.HOME %s; echo RC:$?" % AERIAL_PKG)
    set_out = sh("cmd package set-home-activity --user 0 %s; echo RC:$?" % AERIAL_MAIN)
    rc_set = re.search(r"RC:(-?\d+)", set_out)
    record("Aerial", "设默认桌面(set-home-activity)", rc_set and rc_set.group(1) == "0",
           "role rc=%s, set-home rc=%s" % (
               re.search(r"RC:(-?\d+)", role_out).group(1) if re.search(r"RC:(-?\d+)", role_out) else "?",
               rc_set.group(1) if rc_set else "?"))
    home, is_def = resolve_home()
    record("Aerial", "接管后默认桌面 = Aerial", home.startswith(AERIAL_PKG + "/"),
           "home=%s, isDefault=%s" % (home, is_def))
    record("Aerial", "系统承认默认桌面(isDefault)", is_def, "isDefault=%s" % is_def)

    # 4) 启动
    launch = sh("am start -W -n %s" % AERIAL_MAIN, timeout=30)
    ok_launch = ("Status: ok" in launch) or ("Activity" in launch and "Error" not in launch)
    record("Aerial", "启动 Aerial", ok_launch, launch.strip()[:120])

    # 5) 无障碍守护兜底校验（标准版默认不写守护；这里验证守护服务在该固件能绑定，兜底路径可用）
    sh("settings put secure enabled_accessibility_services '%s'" % AERIAL_GUARD)
    sh("settings put secure accessibility_enabled 1")
    time.sleep(4)
    a11y = sh("dumpsys accessibility")
    guard_bound = "label=Aerial" in a11y
    record("Aerial", "守护服务可绑定(兜底路径)", guard_bound,
           "dumpsys accessibility 含 label=Aerial = %s" % guard_bound)
    # 还原守护写入（后续 restore 也会清，这里先清掉保持干净）
    sh("settings delete secure enabled_accessibility_services")

    # 6) 修复接管（重跑接管步骤）
    r2 = sh("cmd package set-home-activity --user 0 %s; echo RC:$?" % AERIAL_MAIN)
    rc2 = re.search(r"RC:(-?\d+)", r2)
    home2, is_def2 = resolve_home()
    record("Aerial", "修复接管后默认桌面仍为 Aerial", home2.startswith(AERIAL_PKG + "/") and is_def2,
           "home=%s, isDefault=%s" % (home2, is_def2))

    # 7) 还原 / 卸载（复刻 AerialRunner.restore + HomeRoles.giveBack）
    sh("am broadcast -a %s -n %s --ez keep_adb false --ez own_screensaver false" % (CONFIG_ACTION, CONFIG_RECEIVER))
    sh("cmd notification disallow_listener %s" % REVIVAL_LISTENER)
    sh("pm revoke %s android.permission.WRITE_SECURE_SETTINGS" % AERIAL_PKG)
    sh("appops set %s SYSTEM_ALERT_WINDOW default" % AERIAL_PKG)
    sh("appops set %s APP_AUTO_START default" % AERIAL_PKG)
    sh("appops set %s APP_ASSOC_START default" % AERIAL_PKG)
    sh("cmd role remove-role-holder --user 0 android.app.role.HOME %s" % AERIAL_PKG)
    sh("am force-stop %s" % AERIAL_PKG)
    # 把主页还给原桌面
    sh("cmd role add-role-holder --user 0 android.app.role.HOME %s; echo RC:$?" % STOCK)
    gb = sh("cmd package set-home-activity --user 0 %s; echo RC:$?" % STOCK_MAIN)
    rc_gb = re.search(r"RC:(-?\d+)", gb)
    sh("settings delete secure enabled_accessibility_services")
    uninstall = sh("pm uninstall %s" % AERIAL_PKG, timeout=60)
    record("Aerial", "卸载 Aerial", "Success" in uninstall, uninstall.strip()[:80])
    home_r, is_def_r = resolve_home()
    record("Aerial", "还原后默认桌面回原桌面", home_r.startswith(STOCK + "/") and is_def_r,
           "home=%s, isDefault=%s" % (home_r, is_def_r))

# ---------------------------------------------------------------------------
# CLEANYOURTV
# ---------------------------------------------------------------------------
def test_cleanyourtv():
    section("CleanYourTV 桌面：安装 / 接管 / 验收 / 还原")
    prev_home, prev_def = resolve_home()
    record("CleanTV", "进入时默认桌面", True, "= %s (isDefault=%s)" % (prev_home or "无", prev_def))

    # 1) 安装
    out = sh("pm install -r %s" % STAGING_CYT, timeout=120)
    ok = (installed_version(CYT_PKG) is not None) and ("Failure" not in out)
    record("CleanTV", "安装 CleanYourTV-2.6.apk", ok, out.strip()[:120])
    if not ok:
        return

    # 2) 启动校验（先确认能起，再动原桌面）
    launch = sh("am start -W -n %s" % CYT_MAIN, timeout=30)
    ok_launch = ("Status: ok" in launch) and ("Error: Activity not started" not in launch)
    record("CleanTV", "启动校验 Status:ok", ok_launch, launch.strip()[:120])
    if not ok_launch:
        # 启动失败则回滚，不动原桌面
        sh("am force-stop %s" % CYT_PKG)
        sh("pm uninstall %s" % CYT_PKG, timeout=60)
        record("CleanTV", "启动失败→回滚卸载", True, "已卸载，未动原桌面")
        return

    # 3) 授权（Android 6+ 运行时定位；5.1 报错忽略）
    g = sh("pm grant %s android.permission.ACCESS_FINE_LOCATION" % CYT_PKG)
    record("CleanTV", "授权定位", True, g.strip()[:80])

    # 4) 编译优化（Android 7+；老系统报错忽略）
    c = sh("cmd package compile -m speed -f %s" % CYT_PKG, timeout=120)
    record("CleanTV", "编译优化", True, c.strip()[:80] if c.strip() else "(无输出/可能不支持)")

    # 5) 原桌面是否可安全停用：本机是 com.droidlogic.mboxlauncher，不在白名单 → 不停用
    stock_pkg = prev_home.split("/")[0] if "/" in prev_home else prev_home
    known = ["com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
             "com.google.android.leanbacklauncher", "com.mitv.tvhome"]
    disable_stock = stock_pkg in known
    record("CleanTV", "原桌面可否安全停用", True,
           "原=%s, 白名单内=%s → 本次不停用原桌面" % (stock_pkg, disable_stock))

    # 6) 设默认桌面
    set_out = sh("cmd package set-home-activity --user 0 %s; echo RC:$?" % CYT_MAIN)
    rc_set = re.search(r"RC:(-?\d+)", set_out)
    record("CleanTV", "设默认桌面(set-home-activity)", rc_set and rc_set.group(1) == "0",
           "rc=%s" % (rc_set.group(1) if rc_set else "?"))

    # 7) 复查
    home, is_def = resolve_home()
    on_us = home.startswith(CYT_PKG + "/")
    record("CleanTV", "复查主页归 CleanYourTV", on_us and is_def,
           "home=%s, isDefault=%s" % (home, is_def))
    if not on_us:
        # 回滚：把主页还回原桌面，不卸载（保留供查看）
        sh("cmd role add-role-holder --user 0 android.app.role.HOME %s" % STOCK)
        sh("cmd package set-home-activity --user 0 %s" % STOCK_MAIN)
        record("CleanTV", "接管未完成→回滚主页", True, "主页已还回 %s" % STOCK)
        return

    # 8) 验收检查项
    ver = installed_version(CYT_PKG)
    record("CleanTV", "验收-已安装", ver is not None, "version=%s" % ver)
    record("CleanTV", "验收-主页归 CleanYourTV", on_us, home)
    has_def = is_def
    record("CleanTV", "验收-系统承认默认桌面", has_def, "isDefault=%s" % has_def)
    sdk = sh("getprop ro.build.version.sdk").strip()
    record("CleanTV", "验收-系统版本达标(>=22)", sdk.isdigit() and int(sdk) >= 22,
           "SDK=%s" % sdk)

    # 9) 还原 / 卸载（复刻 CleanTvRunner.restore）
    # 原桌面本来就没停用，直接还给它
    sh("cmd role add-role-holder --user 0 android.app.role.HOME %s" % STOCK)
    gb = sh("cmd package set-home-activity --user 0 %s; echo RC:$?" % STOCK_MAIN)
    rc_gb = re.search(r"RC:(-?\d+)", gb)
    sh("settings put secure enabled_accessibility_services null")
    sh("settings put secure accessibility_enabled 0")
    uninstall = sh("pm uninstall %s" % CYT_PKG, timeout=60)
    record("CleanTV", "卸载 CleanYourTV", "Success" in uninstall, uninstall.strip()[:80])
    home_r, is_def_r = resolve_home()
    record("CleanTV", "还原后默认桌面回原桌面", home_r.startswith(STOCK + "/") and is_def_r,
           "home=%s, isDefault=%s" % (home_r, is_def_r))

# ---------------------------------------------------------------------------
# 版本检查（设备侧拉 GitHub，复刻 ReleaseChecker 的数据源）
# ---------------------------------------------------------------------------
def test_version_check():
    section("版本检查逻辑（设备侧联网拉 GitHub）")
    # Aerial 上游
    out = sh("curl -s -m 20 -H 'Accept: application/vnd.github+json' https://api.github.com/repos/pengchujin/aerial-tv/releases/latest", timeout=30)
    m = re.search(r'"tag_name"\s*:\s*"([^"]+)"', out)
    aerial_latest = m.group(1) if m else "(取不到)"
    bundled_aerial = "1.30"
    newer = re.sub(r'[^\d.]', '', aerial_latest) not in ("", bundled_aerial) and \
        tuple(map(int, re.sub(r'[^\d.]', '', aerial_latest).split('.'))) > tuple(map(int, bundled_aerial.split('.')))
    record("版本", "Aerial 上游最新=%s (内置 %s)" % (aerial_latest, bundled_aerial),
           True, "结论：%s" % ("有新版本可更新" if newer else "已是最新"))
    # CleanYourTV (cyt-v*)
    out2 = sh("curl -s -m 20 -H 'Accept: application/vnd.github+json' 'https://api.github.com/repos/mmaotv/AerialToolbox/releases?per_page=30'", timeout=30)
    m2 = re.search(r'"tag_name"\s*:\s*"(cyt-v[^"]+)"', out2)
    cyt_latest = m2.group(1) if m2 else "(取不到)"
    record("版本", "CleanYourTV 代构建最新=%s (内置 cyt-v2.6.0)" % cyt_latest,
           True, "结论：%s" % ("有新版本" if (m2 and m2.group(1) != "cyt-v2.6.0") else "已是最新"))

# ---------------------------------------------------------------------------
# 收尾：强制确保设备回到原桌面
# ---------------------------------------------------------------------------
def ensure_clean():
    section("收尾：确保设备回到原始默认桌面")
    for pkg in (AERIAL_PKG, CYT_PKG):
        v = installed_version(pkg)
        if v is not None:
            u = sh("pm uninstall %s" % pkg, timeout=60)
            log("卸载 %s: %s" % (pkg, u.strip()[:60]))
    # 清掉可能残留的无障碍写入
    sh("settings delete secure enabled_accessibility_services 2>/dev/null")
    sh("settings put secure enabled_accessibility_services null 2>/dev/null")
    # 还主页
    sh("cmd role add-role-holder --user 0 android.app.role.HOME %s" % STOCK)
    sh("cmd package set-home-activity --user 0 %s" % STOCK_MAIN)
    time.sleep(1)
    home, is_def = resolve_home()
    record("收尾", "最终默认桌面=原桌面", home.startswith(STOCK + "/") and is_def,
           "home=%s, isDefault=%s" % (home, is_def))
    v1 = installed_version(AERIAL_PKG); v2 = installed_version(CYT_PKG)
    record("收尾", "两个桌面均已卸载", v1 is None and v2 is None,
           "Aerial=%s, CleanYourTV=%s" % (v1, v2))

if __name__ == "__main__":
    print("开始 AerialToolbox 真机实测 @ %s" % SERIAL)
    test_aerial()
    test_cleanyourtv()
    test_version_check()
    ensure_clean()

    # 汇总
    print("\n################ 汇总 ################")
    fails = 0
    for sec, name, ok, detail in results:
        if not ok:
            fails += 1
        print(("[通过] " if ok else "[失败] ") + "[%s] %s" % (sec, name))
    print("\n总计 %d 项，失败 %d 项。" % (len(results), fails))
    with open(r"D:\APPDEV\AerialToolbox\build\device_test_194_report.json", "w", encoding="utf-8") as f:
        json.dump([{"section": s, "name": n, "ok": o, "detail": d} for s, n, o, d in results],
                  f, ensure_ascii=False, indent=2)
    sys.exit(0 if fails == 0 else 1)

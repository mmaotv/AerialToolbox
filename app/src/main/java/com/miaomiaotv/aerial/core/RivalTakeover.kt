package com.miaomiaotv.aerial.core

/**
 * 两个上游互斥：接管一个桌面之前，先把另一个的接管撤掉。
 *
 * 为什么必须有这一步：Aerial 自带的 `HomeGuardService` 会盯着前台窗口，
 * 一旦发现「不属于自己的桌面」回到前台就把 Aerial 拉回来。
 * 真机上（Amlogic / Android 9）装完 CleanYourTV 后出现的就是这个现象 ——
 * 日志里能看到 `AerialGuard: stock home com.gathin.tvhome came to the front; bringing Aerial back`，
 * 用户按主页键永远回到 Aerial，CleanYourTV 一次都留不住。
 *
 * 反过来同理：CleanYourTV 的 `InputKeyService` / `HomeKeyService` 会抢遥控器按键。
 *
 * 这里只动无障碍服务列表里属于对方的项，别家的服务一律保留。
 */
object RivalTakeover {

    /**
     * 解除 [otherPkg] 这个桌面的接管。
     *
     * @param shell 下发 shell 命令的执行器（两个 Runner 各自的 sh()）
     * @param otherPkg 要撤掉的桌面包名
     * @param listener 对方的「被杀后复活」通知监听组件，传了就一并撤掉
     * @param disable true 时把对方整个应用停用（还原时再启用）
     * @return 有改动时返回一句给用户看的说明，没改动返回 null
     */
    fun release(
        shell: (String) -> String,
        otherPkg: String,
        listener: String? = null,
        disable: Boolean = false
    ): String? {
        val raw = shell("settings get secure enabled_accessibility_services").trim()
        val current = raw.removePrefix("[").removeSuffix("]")
        val parts = if (current.isBlank() || current == "null") emptyList() else current.split(":")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val kept = parts.filterNot { it.startsWith("$otherPkg/") }
        val removed = parts.size - kept.size

        if (removed > 0) {
            if (kept.isEmpty()) {
                shell("settings delete secure enabled_accessibility_services")
            } else {
                shell("settings put secure enabled_accessibility_services '${kept.joinToString(":")}'")
            }
        }

        // 光删设置项不够：对方被授予过 WRITE_SECURE_SETTINGS，一跑起来就把自己的守护写回去。
        // 先试着收回这个权限（部分固件不认），再彻底停用 —— 真机上只靠撤销权限压不住它。
        shell("pm revoke $otherPkg android.permission.WRITE_SECURE_SETTINGS")
        if (!listener.isNullOrBlank()) shell("cmd notification disallow_listener $listener")
        if (disable) shell("pm disable-user --user 0 $otherPkg")

        // 撤了无障碍还得停掉它，新桌面才抢得回主页
        shell("am force-stop $otherPkg")

        if (removed == 0 && listener.isNullOrBlank() && !disable) return null
        val pkgShort = when (otherPkg) {
            AerialSpec.PKG -> "Aerial"
            CleanTvSpec.PKG -> "CleanYourTV"
            else -> otherPkg.substringAfterLast('.')
        }
        return when {
            disable -> "已停用另一个桌面 $pkgShort 并解除它的接管（装回它时会自动启用）"
            removed > 0 -> "已解除另一个桌面的接管（移除 $removed 个无障碍服务并停止它）"
            else -> "已停止另一个桌面并收回它的系统设置权限"
        }
    }

    /** 把之前停用的桌面重新启用（切回它之前调用） */
    fun reenable(shell: (String) -> String, pkg: String): String? {
        val disabled = shell("pm list packages -d $pkg")
        if (!disabled.contains("package:$pkg")) return null
        shell("pm enable $pkg")
        val name = if (pkg == AerialSpec.PKG) "Aerial" else if (pkg == CleanTvSpec.PKG) "CleanYourTV" else pkg
        return "已重新启用 $name"
    }
}

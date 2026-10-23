package com.miaomiaotv.aerial.core

import java.io.File

/**
 * 设备侧文件探测。
 *
 * 关键点：安装包永远在「设备这一侧」，而运行本工具的机器不一定是设备本身 ——
 *  - 电视端是本机模式：工具箱跑在电视上，安装包在电视的公共下载目录；
 *  - 手机端是远程模式：安装包先被 push 到电视的 /data/local/tmp。
 *
 * 所以只能用 shell 到设备上量大小，不能用 File.exists()/length()：
 * 那查的是「跑工具的这台机器」。手机端去查 /data/local/tmp/… 永远是「不存在」，
 * 安装会被误判成「安装包已损坏」而直接失败（真机代理测试实测踩到过）。
 */
object DeviceFiles {

    /** 设备上该路径的字节数；取不到时返回 0 */
    fun size(sh: (String) -> String, path: String): Long {
        val byShell = sh("wc -c < \"$path\"").trim().toLongOrNull() ?: 0L
        if (byShell > 0L) return byShell
        // 个别固件的 wc 行为不同时的兜底：电视端本机模式正好也成立
        return runCatching {
            val file = File(path)
            if (file.exists()) file.length() else 0L
        }.getOrDefault(0L)
    }
}

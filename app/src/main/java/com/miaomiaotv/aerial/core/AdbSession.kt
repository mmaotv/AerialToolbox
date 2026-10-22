package com.miaomiaotv.aerial.core

import com.miaomiaotv.aerial.adb.AdbChannel

/**
 * 全局唯一的调试通道。
 *
 * 设备侧的 adbd 只接受一条主机连接，两个页面各自连一次会互相把对方踢下线，
 * 所以「主界面」连上之后放在这里，「桌面更新」页面直接复用。
 */
object AdbSession {

    @Volatile
    var channel: AdbChannel? = null

    @Volatile
    var runner: AerialRunner? = null

    /** 最近一次读到的已装版本；null 表示未安装 */
    @Volatile
    var installedVersion: String? = null

    @Volatile
    var deviceLabel: String = ""

    val connected: Boolean get() = channel != null && runner != null

    fun attach(channel: AdbChannel, runner: AerialRunner) {
        this.channel = channel
        this.runner = runner
    }

    fun clear() {
        channel = null
        runner = null
        installedVersion = null
    }

    /** 重新读一次已装版本（更新 / 卸载后调用） */
    fun refreshInstalled(): String? {
        val current = runner
        val value = if (current == null) null else runCatching { current.installedVersion() }.getOrNull()
        installedVersion = value
        return value
    }
}

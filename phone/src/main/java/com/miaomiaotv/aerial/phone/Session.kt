package com.miaomiaotv.aerial.phone

import com.miaomiaotv.aerial.adb.AdbChannel
import com.miaomiaotv.aerial.core.AerialRunner
import com.miaomiaotv.aerial.core.CleanTvRunner
import com.miaomiaotv.aerial.core.Target
import java.util.concurrent.Executors

/**
 * 手机端的连接与会话状态（单例）。
 *
 * 电视端 app 是「跑在电视本机、连 127.0.0.1」，状态只活在当前页；
 * 手机端是「一个连接 + 多个页共享」，所以用单例把 AdbChannel 与各页要用的信息挂在这里。
 */
object Session {

    var channel: AdbChannel? = null

    var target: Target = Target.AERIAL

    var deviceModel: String = ""
    var androidSdk: Int = 0

    /** 系统当前解析到的默认桌面（每次进主页/操作前刷新） */
    var currentHome: String = ""

    /** 接管前记下的原桌面组件，还原时还给它（HOME 键失灵修复的关键） */
    var prevHome: String = ""

    /** CleanYourTV 路线用：装之前占着主页的原厂桌面 */
    var stockLauncher: String = ""

    val executor = Executors.newSingleThreadExecutor()

    fun isConnected(): Boolean = channel?.isConnected == true

    fun close() {
        runCatching { channel?.disconnect() }
        channel = null
    }

    fun aerialRunner(onLog: (String) -> Unit, onStep: (String, Int) -> Unit): AerialRunner? {
        val ch = channel ?: return null
        return AerialRunner(ch, onLog, onStep)
    }

    fun cleanRunner(onLog: (String) -> Unit, onStep: (String, Int) -> Unit): CleanTvRunner? {
        val ch = channel ?: return null
        return CleanTvRunner(ch, onLog, onStep)
    }
}

package com.miaomiaotv.aerial.phone

import android.content.Context
import com.miaomiaotv.aerial.adb.AdbChannel
import com.miaomiaotv.aerial.adb.AdbIdentity
import com.miaomiaotv.aerial.core.AerialRunner
import com.miaomiaotv.aerial.core.AerialSpec
import com.miaomiaotv.aerial.core.BundledRelease
import com.miaomiaotv.aerial.core.CleanTvRunner
import com.miaomiaotv.aerial.core.CleanTvSpec
import com.miaomiaotv.aerial.core.Target
import java.io.File

/** ADB 连接的封装：钥匙持久化、连上电视、把内置安装包推过去。 */
object AdbKit {

    /** 建立到电视的网络 ADB 连接；onNeedAuth 在设备要求授权时回调（用来提示用户点允许） */
    fun connect(ctx: Context, host: String, port: Int, onNeedAuth: () -> Unit): AdbChannel {
        val identity = AdbIdentity(File(ctx.filesDir, "adb"))
        val channel = AdbChannel()
        channel.attachIdentity(identity)
        channel.onNeedAuthorization = onNeedAuth
        channel.connect(host, port)
        return channel
    }

    fun makeAerial(ch: AdbChannel, onLog: (String) -> Unit, onStep: (String, Int) -> Unit) =
        AerialRunner(ch, onLog, onStep)

    fun makeClean(ch: AdbChannel, onLog: (String) -> Unit, onStep: (String, Int) -> Unit) =
        CleanTvRunner(ch, onLog, onStep)

    /**
     * 把内置安装包推到电视的 staging 路径，返回该远程路径。
     * 手机端必须推送：安装包在手机上，电视的 pm install 读的是电视本机路径。
     * 释放到自己的 cache 目录就够了——推完电视那边就有副本，本地这份可以随时丢。
     */
    fun pushBundled(ctx: Context, ch: AdbChannel, target: Target, useGuard: Boolean): String {
        val local = BundledRelease(ctx, target.bundledDir).extract(useGuard, ctx.cacheDir)
            ?: throw IllegalStateException("内置安装包缺失（${target.bundledDir}），请重新构建")
        val staging = if (target.isAerial) AerialSpec.STAGING_APK else CleanTvSpec.STAGING_APK
        ch.push(local, staging)
        return staging
    }
}

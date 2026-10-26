package com.miaomiaotv.aerial

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.miaomiaotv.aerial.adb.AdbChannel

/**
 * 让工具箱自己保持本机 ADB 调试通道（127.0.0.1:5555）常开。
 *
 * 背景：小米 / Redmi 电视与盒子在关机、待机后都会把 ADB 关掉（adb_enabled 置 0），
 * 工具箱连不上本机通道就什么都做不了。上游 Aerial 桌面本来就有类似逻辑，但它只在
 * 装了 Aerial 桌面时才生效；本工具装完就该能用，必须自己兜底。
 *
 * 做法：
 *  - 首次连上本机通道后，用工具箱自己的 adb shell 给自己 `pm grant` 写安全设置权限
 *    （这个权限只能经 ADB 授予，授权后持久化、重启后仍在）；同时把 TCP 端口属性固化成 5555；
 *  - 开机（[AdbKeepAliveReceiver]）、亮屏（MainActivity 动态注册的 SCREEN_ON）、以及
 *    每次连上通道时，用这个权限把 [Settings.Global.ADB_ENABLED] 写回 1，必要时把设置从 0
 *    切回 1 来重启 adbd，让 5555 重新监听。
 *
 * 边界：WRITE_SECURE_SETTINGS 是 signature/system 权限，只能经 ADB 授予，所以「自助授权」
 * 依赖用户至少成功连上过一次本机通道（授权后权限持久化，之后开机才有用）。Android 11+
 * 网络 ADB 改走无线调试、5555 不再由 adb_enabled 拉起，本机 TCP 方案在 11+ 上可能救不回端口。
 */
object AdbKeepAlive {

    private const val TAG = "AdbKeepAlive"
    private const val PREF_FILE = "aerial_toolbox"
    private const val PREF_KEEP = "keep_adb"
    private const val TCP_PORT = "5555"
    private const val ADB_BOOT_WINDOW_MS = 3 * 60 * 1000L

    /** 与 [com.miaomiaotv.aerial.core.AerialSettings] 保持同一份开关，开机接收器直接读它 */
    fun isKeepAdbEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .getBoolean(PREF_KEEP, true)

    /**
     * 连上本机通道后调用：给自己授予写安全设置权限（让本机恢复 ADB 成为可能），
     * 并把 TCP 端口属性固化成 5555，保证重启后 adbd 仍进 TCP 模式。
     */
    fun ensurePermissionGranted(channel: AdbChannel) {
        runCatching {
            channel.shell("pm grant $PKG android.permission.WRITE_SECURE_SETTINGS")
            // persist 属性重启后仍在；service.adb.tcp.port 在下次 adbd 重启时生效。
            channel.shell("setprop persist.adb.tcp.port $TCP_PORT")
            channel.shell("setprop service.adb.tcp.port $TCP_PORT")
        }.onFailure { Log.w(TAG, "授予写安全设置权限失败", it) }
    }

    /** 把 ADB 重新打开。需要 WRITE_SECURE_SETTINGS 权限；没有就直接返回（拿不到也救不了）。 */
    fun reenableAdb(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val resolver = context.contentResolver
        val enabled = Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0)
        if (enabled == 0) {
            runCatching {
                Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)
                Log.i(TAG, "ADB 被关掉了，已重新打开")
            }.onFailure { Log.w(TAG, "无法重新打开 ADB", it) }
            return
        }
        // 已开启但 adbd 没在监听 TCP：部分固件重启后 adbd 没进 TCP 模式，
        // 把设置从 0 切回 1 才能重启 adbd。只在开机后几分钟内、且端口确实没开时做一次。
        ensureListening(context)
    }

    private fun ensureListening(context: Context) {
        if (danced || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return
        if (SystemClock.elapsedRealtime() > ADB_BOOT_WINDOW_MS) return
        if (adbdListens()) return
        Thread {
            if (danced) return@Thread
            synchronized(lock) {
                if (danced) return@Thread
                danced = true
                dancing = true
                try {
                    runCatching {
                        // 0→1 的切换让系统重启 adbd；切换期间不要有人立刻把 1 写回去。
                        Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
                        Thread.sleep(1000)
                        Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)
                        Log.i(TAG, "adb_enabled 已开但 adbd 未监听 TCP，切换一次以重启 adbd")
                    }.onFailure { Log.w(TAG, "无法切换 ADB 设置", it) }
                } finally {
                    dancing = false
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** adbd 本机是否在监听：连不上说明没进 TCP 模式。 */
    private fun adbdListens(): Boolean = try {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", TCP_PORT.toInt()), 400) }
        true
    } catch (_: Exception) {
        false
    }

    /** 亮屏 / 解锁时回调，由 MainActivity 在 onResume 里动态注册，覆盖待机唤醒。 */
    fun screenOnReceiver(): BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON ||
                intent.action == Intent.ACTION_USER_PRESENT
            ) {
                reenableAdb(context)
            }
        }
    }

    @Volatile private var danced = false
    @Volatile private var dancing = false
    private val lock = Any()

    private const val PKG = "com.miaomiaotv.aerial"
}

/**
 * 开机 / 应用被更新替换后，若用户开着「ADB 常开」，立刻把 ADB 重新打开。
 *
 * 只声明 BOOT_COMPLETED / QUICKBOOT_POWERON / MY_PACKAGE_REPLACED：这几个是白名单广播，
 * 清单里注册就能收到。亮屏恢复交给 MainActivity 动态注册的 [Intent.ACTION_SCREEN_ON]
 * （API 26+ 的清单接收器收不到隐式 SCREEN_ON）。
 */
class AdbKeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val handled = action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!handled) return
        if (AdbKeepAlive.isKeepAdbEnabled(context)) {
            AdbKeepAlive.reenableAdb(context)
        }
    }
}

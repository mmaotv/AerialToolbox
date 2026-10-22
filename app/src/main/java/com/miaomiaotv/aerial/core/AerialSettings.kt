package com.miaomiaotv.aerial.core

import android.content.Context

/**
 * 工具的本地设置。
 *
 * 只存三类东西：安装通道、两个接管开关、上一轮拿到的 Release 信息（避免每次开屏都请求 GitHub）。
 * 全部用 SharedPreferences，不引入任何依赖。
 */
class AerialSettings(context: Context) {

    private val prefs = context.getSharedPreferences("aerial_toolbox", Context.MODE_PRIVATE)

    /** 是否安装 guard 版（不带 HOME 声明，靠无障碍服务接管，部分 TCL/海信固件只认它） */
    var useGuard: Boolean
        get() = prefs.getBoolean(KEY_USE_GUARD, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_GUARD, value).apply()

    /** 是否让 Aerial 保持 ADB 常开（小米待机唤醒后会自己关掉 ADB） */
    var keepAdb: Boolean
        get() = prefs.getBoolean(KEY_KEEP_ADB, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_ADB, value).apply()

    /** 是否把系统屏保接管成 Aerial 的航拍屏保（仅使用系统屏保的电视有效，如 TCL） */
    var ownScreensaver: Boolean
        get() = prefs.getBoolean(KEY_OWN_SCREENSAVER, true)
        set(value) = prefs.edit().putBoolean(KEY_OWN_SCREENSAVER, value).apply()

    /** CleanYourTV 装之前占着主页的原厂桌面，还原时要用它；空串表示没记录/没禁用 */
    var cytStockLauncher: String
        get() = prefs.getString(KEY_CYT_STOCK, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CYT_STOCK, value).apply()

    /** 遥控器「输入源」键交给 CleanYourTV（无障碍服务，默认关） */
    var cytInputKey: Boolean
        get() = prefs.getBoolean(KEY_CYT_INPUT_KEY, false)
        set(value) = prefs.edit().putBoolean(KEY_CYT_INPUT_KEY, value).apply()

    /** 遥控器 Home 键兼容（小米等把 Home 键发成 ALL_APPS 的机器，默认关） */
    var cytHomeKey: Boolean
        get() = prefs.getBoolean(KEY_CYT_HOME_KEY, false)
        set(value) = prefs.edit().putBoolean(KEY_CYT_HOME_KEY, value).apply()

    private companion object {
        const val KEY_USE_GUARD = "use_guard"
        const val KEY_KEEP_ADB = "keep_adb"
        const val KEY_OWN_SCREENSAVER = "own_screensaver"
        const val KEY_CYT_STOCK = "cyt_stock_launcher"
        const val KEY_CYT_INPUT_KEY = "cyt_input_key"
        const val KEY_CYT_HOME_KEY = "cyt_home_key"
    }
}

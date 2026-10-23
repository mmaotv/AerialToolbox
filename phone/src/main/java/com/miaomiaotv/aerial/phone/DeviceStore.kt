package com.miaomiaotv.aerial.phone

import android.content.Context
import android.content.SharedPreferences
import java.util.LinkedHashSet

/** 历史连接过的电视 IP，落 SharedPreferences。 */
object DeviceStore {

    private const val NAME = "phone_devices"
    private const val KEY = "history"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<String> =
        prefs(ctx).getStringSet(KEY, emptySet())?.toList()?.sorted() ?: emptyList()

    fun add(ctx: Context, ip: String) {
        val cur = LinkedHashSet(prefs(ctx).getStringSet(KEY, emptySet()) ?: emptySet())
        cur.add(ip)
        // 最多保留 30 条
        while (cur.size > 30) cur.remove(cur.first())
        prefs(ctx).edit().putStringSet(KEY, cur).apply()
    }
}

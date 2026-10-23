package com.miaomiaotv.aerial.phone

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.miaomiaotv.aerial.core.Ui

/** 手机当遥控器：把按键通过电视的 adb shell 发出去。 */
class RemoteActivity : Activity() {

    private lateinit var tvSent: TextView

    // 遥控按键 → KEYCODE
    private val keys = mapOf(
        R.id.btn_up to 19,
        R.id.btn_down to 20,
        R.id.btn_left to 21,
        R.id.btn_right to 22,
        R.id.btn_ok to 23,
        R.id.btn_home to 3,
        R.id.btn_back to 4,
        R.id.btn_menu to 82,
        R.id.btn_power to 26
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.isConnected()) {
            Toast.makeText(this, R.string.remote_not_connected, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContentView(R.layout.activity_remote)
        tvSent = findViewById(R.id.tv_sent)

        // 顶栏「关闭」只关本页，不断开连接（断开在主页）
        findViewById<Button>(R.id.btn_close).setOnClickListener { finish() }

        keys.forEach { (id, code) ->
            findViewById<Button>(id).setOnClickListener { send(code) }
        }

        // 与 TV 端同一份 Inter 字体
        Ui.apply(window.decorView)
    }

    private fun send(code: Int) {
        if (!Session.isConnected()) {
            tvSent.setText(R.string.remote_not_connected)
            return
        }
        val name = codeName(code)
        tvSent.text = getString(R.string.remote_sent, name)
        Session.executor.execute {
            runCatching { Session.channel?.shell("input keyevent $code") }
        }
    }

    private fun codeName(code: Int): String = when (code) {
        19 -> "上"; 20 -> "下"; 21 -> "左"; 22 -> "右"; 23 -> "确认"
        3 -> "主页"; 4 -> "返回"; 82 -> "菜单"; 26 -> "电源"
        else -> "$code"
    }
}

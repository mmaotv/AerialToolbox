package com.miaomiaotv.aerial.phone

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.miaomiaotv.aerial.core.AdbGuide
import com.miaomiaotv.aerial.core.Target
import com.miaomiaotv.aerial.core.Ui

class ConnectActivity : Activity() {

    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnScan: Button
    private lateinit var pbScan: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvAdvanced: TextView
    private lateinit var tvAdbHelp: TextView
    private lateinit var lvSaved: ListView
    private lateinit var lvFound: ListView
    private lateinit var tvEmpty: TextView

    private lateinit var savedAdapter: ArrayAdapter<String>
    private lateinit var foundAdapter: ArrayAdapter<String>

    private var connecting = false

    /** 自动扫描只做一次：从主页退回来时不再扫，免得用户刚填好的 IP 被刷走 */
    private var autoScanned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)

        etIp = findViewById(R.id.et_ip)
        etPort = findViewById(R.id.et_port)
        btnConnect = findViewById(R.id.btn_connect)
        btnScan = findViewById(R.id.btn_scan)
        pbScan = findViewById(R.id.pb_scan)
        tvStatus = findViewById(R.id.tv_status)
        tvAdvanced = findViewById(R.id.tv_advanced)
        tvAdbHelp = findViewById(R.id.tv_adb_help)
        lvSaved = findViewById(R.id.lv_saved)
        lvFound = findViewById(R.id.lv_found)
        tvEmpty = findViewById(R.id.tv_empty)

        savedAdapter = ArrayAdapter(this, R.layout.item_device, mutableListOf())
        foundAdapter = ArrayAdapter(this, R.layout.item_device, mutableListOf())
        lvSaved.adapter = savedAdapter
        lvFound.adapter = foundAdapter

        refreshSaved()
        updateEmpty()
        tvAdvanced.text = getString(R.string.label_port_advanced, DEFAULT_PORT)

        // 与 TV 端同一份 Inter 字体
        Ui.apply(window.decorView)

        btnConnect.setOnClickListener { onConnect() }
        btnScan.setOnClickListener { onScan() }
        tvAdvanced.setOnClickListener { toggleAdvanced() }
        tvAdbHelp.setOnClickListener { showAdbHelp() }

        lvSaved.setOnItemClickListener { _, _, pos, _ ->
            val ip = savedAdapter.getItem(pos) ?: return@setOnItemClickListener
            etIp.setText(ip)
            doConnect(ip)
        }
        lvFound.setOnItemClickListener { _, _, pos, _ ->
            val ip = foundAdapter.getItem(pos) ?: return@setOnItemClickListener
            etIp.setText(ip)
            doConnect(ip)
        }

        // 进页面先把残留的连接清掉，避免上一次没正常断开
        if (!Session.isConnected()) Session.close()

        // 没有历史设备时直接扫一次：新用户面对两个空输入框 + 一个空列表，不知道下一步该干什么
        if (savedAdapter.count == 0 && !autoScanned) {
            autoScanned = true
            onScan()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!connecting) refreshSaved()
    }

    private fun refreshSaved() {
        savedAdapter.clear()
        savedAdapter.addAll(DeviceStore.list(this))
        savedAdapter.notifyDataSetChanged()
        Ui.apply(window.decorView)
        updateEmpty()
    }

    private fun updateEmpty() {
        val hasAny = savedAdapter.count > 0 || foundAdapter.count > 0
        tvEmpty.visibility = if (hasAny) View.GONE else View.VISIBLE
    }

    /** 端口默认藏起来：adbd 就固定 5555，只有改过端口的固件才需要它 */
    private fun toggleAdvanced() {
        val show = etPort.visibility != View.VISIBLE
        etPort.visibility = if (show) View.VISIBLE else View.GONE
        val port = etPort.text.toString().trim().ifBlank { DEFAULT_PORT }
        tvAdvanced.text = getString(R.string.label_port_advanced, port)
        if (show) etPort.requestFocus()
    }

    private fun onConnect() {
        val ip = etIp.text.toString().trim()
        val portStr = etPort.text.toString().trim().ifBlank { DEFAULT_PORT }
        if (ip.isBlank()) {
            Toast.makeText(this, "请输入电视 IP", Toast.LENGTH_SHORT).show()
            return
        }
        val port = portStr.toIntOrNull() ?: DEFAULT_PORT.toInt()
        doConnect(ip, port)
    }

    private fun doConnect(ip: String, port: Int = DEFAULT_PORT.toInt()) {
        if (connecting) return
        connecting = true
        btnConnect.isEnabled = false
        tvStatus.text = getString(R.string.status_connecting)
        tvStatus.setTextColor(resources.getColor(R.color.text_secondary))

        Session.executor.execute {
            try {
                val channel = AdbKit.connect(this@ConnectActivity, ip, port) {
                    runOnUiThread {
                        Toast.makeText(this@ConnectActivity, R.string.auth_hint, Toast.LENGTH_LONG).show()
                    }
                }
                Session.channel = channel
                Session.target = Target.AERIAL
                DeviceStore.add(this@ConnectActivity, ip)
                runOnUiThread {
                    connecting = false
                    btnConnect.isEnabled = true
                    tvStatus.text = getString(R.string.status_connected, ip)
                    tvStatus.setTextColor(resources.getColor(R.color.ok))
                    startActivity(Intent(this@ConnectActivity, DashboardActivity::class.java))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Session.close()
                runOnUiThread {
                    connecting = false
                    btnConnect.isEnabled = true
                    val msg = e.message?.lines()?.firstOrNull() ?: e.javaClass.simpleName
                    tvStatus.text = getString(R.string.connect_fail, msg)
                    tvStatus.setTextColor(resources.getColor(R.color.danger))
                }
            }
        }
    }

    private fun onScan() {
        pbScan.visibility = View.VISIBLE
        btnScan.isEnabled = false
        foundAdapter.clear()
        tvStatus.text = getString(R.string.scanning)
        tvStatus.setTextColor(resources.getColor(R.color.text_secondary))
        Session.executor.execute {
            try {
                val found = mutableListOf<String>()
                LanScanner.scan { host -> found.add(host) }
                found.sort()
                runOnUiThread {
                    foundAdapter.clear()
                    foundAdapter.addAll(found)
                    foundAdapter.notifyDataSetChanged()
                    Ui.apply(window.decorView)
                    updateEmpty()
                    pbScan.visibility = View.GONE
                    btnScan.isEnabled = true
                    if (found.isEmpty()) {
                        tvStatus.text = "没扫到开放 ${LanScanner.ADB_PORT} 端口的设备（电视上要先打开 ADB）"
                        tvStatus.setTextColor(resources.getColor(R.color.warn))
                    } else {
                        tvStatus.text = "扫到 ${found.size} 台可调试设备，点一下就直接连"
                        tvStatus.setTextColor(resources.getColor(R.color.ok))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    pbScan.visibility = View.GONE
                    btnScan.isEnabled = true
                    tvStatus.text = getString(R.string.scan_fail, e.message ?: "")
                    tvStatus.setTextColor(resources.getColor(R.color.danger))
                }
            }
        }
    }

    /**
     * 开 ADB 的帮助弹窗。
     *
     * 内容与电视端 AdbHelpActivity 同源（core 的 AdbGuide），
     * 这样两端不会说两套话；手机上是手指滑动，纯文本列表就够。
     */
    @Suppress("DEPRECATION")
    private fun showAdbHelp() {
        val dialog = Dialog(this, R.style.Dialog_App)
        dialog.setContentView(R.layout.dialog_adb_help)
        val list = dialog.findViewById<LinearLayout>(R.id.brandList)
        val density = resources.displayMetrics.density

        AdbGuide.ALL.forEach { brand ->
            val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(this).apply {
                text = brand.brand
                setTextColor(resources.getColor(R.color.text_primary))
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val steps = TextView(this).apply {
                text = if (brand.note.isBlank()) {
                    brand.steps
                } else {
                    brand.steps + "\n（" + brand.note + "）"
                }
                setTextColor(resources.getColor(R.color.text_secondary))
                textSize = 12f
                setLineSpacing(0f, 1.2f)
            }
            block.addView(title)
            block.addView(steps)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (12 * density).toInt()
            list.addView(block, lp)
        }

        Ui.apply(dialog.window?.decorView)
        dialog.findViewById<Button>(R.id.btn_ok).setOnClickListener { dialog.dismiss() }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    private companion object {
        const val DEFAULT_PORT = "5555"
    }
}

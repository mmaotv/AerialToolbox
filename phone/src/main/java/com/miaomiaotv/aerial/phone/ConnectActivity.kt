package com.miaomiaotv.aerial.phone

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.miaomiaotv.aerial.core.Target
import com.miaomiaotv.aerial.core.Ui

class ConnectActivity : Activity() {

    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnScan: Button
    private lateinit var pbScan: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var lvSaved: ListView
    private lateinit var lvFound: ListView
    private lateinit var tvEmpty: TextView

    private lateinit var savedAdapter: ArrayAdapter<String>
    private lateinit var foundAdapter: ArrayAdapter<String>

    private var connecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)

        etIp = findViewById(R.id.et_ip)
        etPort = findViewById(R.id.et_port)
        btnConnect = findViewById(R.id.btn_connect)
        btnScan = findViewById(R.id.btn_scan)
        pbScan = findViewById(R.id.pb_scan)
        tvStatus = findViewById(R.id.tv_status)
        lvSaved = findViewById(R.id.lv_saved)
        lvFound = findViewById(R.id.lv_found)
        tvEmpty = findViewById(R.id.tv_empty)

        savedAdapter = ArrayAdapter(this, R.layout.item_device, mutableListOf())
        foundAdapter = ArrayAdapter(this, R.layout.item_device, mutableListOf())
        lvSaved.adapter = savedAdapter
        lvFound.adapter = foundAdapter

        refreshSaved()
        updateEmpty()

        // 与 TV 端同一份 Inter 字体
        Ui.apply(window.decorView)

        btnConnect.setOnClickListener { onConnect() }
        btnScan.setOnClickListener { onScan() }

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

    private fun onConnect() {
        val ip = etIp.text.toString().trim()
        val portStr = etPort.text.toString().trim().ifBlank { "5555" }
        if (ip.isBlank()) {
            Toast.makeText(this, "请输入电视 IP", Toast.LENGTH_SHORT).show()
            return
        }
        val port = portStr.toIntOrNull() ?: 5555
        doConnect(ip, port)
    }

    private fun doConnect(ip: String, port: Int = 5555) {
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
                        tvStatus.text = "没扫到开放 ${LanScanner.ADB_PORT} 端口的设备（确认电视已开启网络调试）"
                    } else {
                        tvStatus.text = "扫到 ${found.size} 台可调试设备"
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
}

package com.miaomiaotv.aerial.phone

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** 截取电视屏幕：通过 exec: 通道拿原始 PNG 字节（避免 pty 换行转换损坏图片）。 */
class ScreenshotActivity : Activity() {

    private lateinit var btnCapture: Button
    private lateinit var tvStatus: TextView
    private lateinit var img: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.isConnected()) {
            Toast.makeText(this, R.string.screenshot_not_connected, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContentView(R.layout.activity_screenshot)
        btnCapture = findViewById(R.id.btn_capture)
        tvStatus = findViewById(R.id.tv_status)
        img = findViewById(R.id.img_capture)

        btnCapture.setOnClickListener { capture() }
    }

    private fun capture() {
        if (!Session.isConnected()) {
            tvStatus.setText(R.string.screenshot_not_connected)
            return
        }
        btnCapture.isEnabled = false
        tvStatus.text = "截取中…"
        Session.executor.execute {
            try {
                val bytes = Session.channel!!.execBytes("screencap -p")
                if (bytes.isEmpty()) {
                    runOnUiThread { tvStatus.text = getString(R.string.screenshot_fail, "空数据") }
                    return@execute
                }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp == null) {
                    runOnUiThread { tvStatus.text = getString(R.string.screenshot_fail, "PNG 解码失败") }
                } else {
                    val file = save(bytes)
                    runOnUiThread {
                        img.setImageBitmap(bmp)
                        img.visibility = View.VISIBLE
                        tvStatus.text = getString(R.string.screenshot_saved, file.absolutePath)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { tvStatus.text = getString(R.string.screenshot_fail, e.message ?: "") }
            } finally {
                runOnUiThread { btnCapture.isEnabled = true }
            }
        }
    }

    /** 存到应用私有 Pictures 目录：免存储权限，文件管理器可访问。 */
    private fun save(bytes: ByteArray): File {
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
        dir.mkdirs()
        val file = File(dir, "aerial_${System.currentTimeMillis()}.png")
        file.writeBytes(bytes)
        return file
    }
}

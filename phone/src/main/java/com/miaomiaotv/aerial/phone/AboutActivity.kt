package com.miaomiaotv.aerial.phone

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.miaomiaotv.aerial.apk.ApkProvider
import com.miaomiaotv.aerial.core.Changelog
import com.miaomiaotv.aerial.core.ReleaseChecker
import com.miaomiaotv.aerial.core.RemoteRelease
import com.miaomiaotv.aerial.core.ToolboxInfo
import com.miaomiaotv.aerial.core.ToolboxUpdater
import com.miaomiaotv.aerial.core.Ui
import com.miaomiaotv.aerial.core.VersionCompare
import java.io.File
import java.util.Locale

/**
 * 关于 / 更新手机端。
 *
 * 与电视端的「关于 / 更新工具箱」同构：版本信息 + 更新日志 + 检查更新。
 * 自更新只能走系统安装器 —— 手机不会对自己开 ADB 调试，
 * 也没有 root 权限去跑 `pm install`；安装包以 content:// 一次性授权交给安装器。
 */
class AboutActivity : Activity() {

    private lateinit var versionText: TextView
    private lateinit var latestText: TextView
    private lateinit var changelogText: TextView
    private lateinit var blockBusy: View
    private lateinit var progressBar: ProgressBar
    private lateinit var busyStep: TextView
    private lateinit var checkButton: Button
    private lateinit var upgradeButton: Button
    private lateinit var backButton: Button

    @Volatile
    private var remote: RemoteRelease? = null

    @Volatile
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        versionText = findViewById(R.id.tv_about_version)
        latestText = findViewById(R.id.tv_about_latest)
        changelogText = findViewById(R.id.tv_about_changelog)
        blockBusy = findViewById(R.id.block_busy)
        progressBar = findViewById(R.id.about_progress)
        busyStep = findViewById(R.id.tv_busy_step)
        checkButton = findViewById(R.id.btn_check)
        upgradeButton = findViewById(R.id.btn_upgrade)
        backButton = findViewById(R.id.btn_back)

        backButton.setOnClickListener { finish() }
        checkButton.setOnClickListener { checkUpdate() }
        upgradeButton.setOnClickListener { startUpgrade() }

        versionText.text = "v${ToolboxInfo.versionName(this)}"
        latestText.text = "点下面按钮检查 GitHub 上有没有新版"
        changelogText.text = Changelog.pretty(ToolboxInfo.PHONE_CHANGELOG, "暂无更新日志")

        Ui.apply(window.decorView)
    }

    private fun checkUpdate() {
        if (busy) return
        busy = true
        setBusy(true, "正在检查…", 0)
        latestText.text = "正在查询 GitHub…"
        Session.executor.execute {
            val release = runCatching { ToolboxUpdater.latestPhone() }.getOrNull()
            busy = false
            runOnUiThread {
                setBusy(false, "", 0)
                val current = ToolboxInfo.versionName(this)
                if (release == null) {
                    latestText.text = "检查失败：连不上 GitHub"
                    upgradeButton.isEnabled = false
                    upgradeButton.alpha = 0.4f
                    toast("检查失败：网络不通")
                } else {
                    remote = release
                    val newer = release.version.isNotBlank() &&
                        VersionCompare.compare(release.version, current) > 0
                    latestText.text = if (newer) {
                        "发现新版 v${release.version}（当前 v$current）"
                    } else {
                        "已是最新版 v$current"
                    }
                    upgradeButton.isEnabled = true
                    upgradeButton.alpha = 1f
                    upgradeButton.text = if (newer) "下载并升级到 v${release.version}" else "重新下载并安装"
                }
            }
        }
    }

    private fun startUpgrade() {
        val release = remote ?: run {
            toast("先点「检查手机端更新」")
            return
        }
        if (busy) return
        val versionTag = release.version.ifBlank { "latest" }
        val dest = File(cacheDir, "install/AerialToolbox-Phone-$versionTag.apk")

        busy = true
        setBusy(true, "准备下载…", 0)
        Session.executor.execute {
            try {
                if (ReleaseChecker.isUsableCache(dest)) {
                    ui { step("复用已下载的安装包", 60) }
                } else {
                    ui { step("连接下载服务器…", 3) }
                    ToolboxUpdater.download(release.standardUrl, dest) { read, total ->
                        val percent = if (total > 0) (read * 90 / total).toInt().coerceIn(0, 90) else 40
                        val mb = read / 1024.0 / 1024.0
                        ui { step(String.format(Locale.US, "下载 %.1f MB…", mb), percent) }
                    }
                }
                ui {
                    setBusy(false, "", 100)
                    val opened = openSystemInstaller(dest)
                    // 装自己会杀掉本进程，所以提示必须在拉起安装器之前给出来
                    toast(if (opened) "已交给系统安装器，确认后会自动打开新版" else "打不开系统安装器")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "手机端自更新失败", t)
                ui {
                    setBusy(false, "", 0)
                    Toast.makeText(this, "下载失败：${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                }
            } finally {
                busy = false
            }
        }
    }

    /**
     * 用系统安装器打开下载好的 APK。
     * Android 7 起禁止 file:// 传给安装器，统一经 [ApkProvider] 转成 content://。
     */
    private fun openSystemInstaller(apk: File): Boolean = runCatching {
        val uri = ApkProvider.uriFor(this, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        true
    }.onFailure {
        if (it !is ActivityNotFoundException) Log.e(TAG, "打开系统安装器失败", it)
    }.getOrDefault(false)

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun setBusy(value: Boolean, label: String, percent: Int) {
        blockBusy.visibility = if (value) View.VISIBLE else View.GONE
        progressBar.progress = percent.coerceIn(0, 100)
        if (label.isNotBlank()) busyStep.text = label
        checkButton.isEnabled = !value
        checkButton.alpha = if (value) 0.4f else 1f
        upgradeButton.isEnabled = !value && remote != null
        upgradeButton.alpha = if (upgradeButton.isEnabled) 1f else 0.4f
    }

    private fun step(label: String, percent: Int) {
        blockBusy.visibility = View.VISIBLE
        progressBar.progress = percent.coerceIn(0, 100)
        busyStep.text = label
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "PhoneAbout"
    }
}

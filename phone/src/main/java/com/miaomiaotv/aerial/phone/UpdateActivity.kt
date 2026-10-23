package com.miaomiaotv.aerial.phone

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.miaomiaotv.aerial.core.AerialRunner
import com.miaomiaotv.aerial.core.AerialSettings
import com.miaomiaotv.aerial.core.AerialSpec
import com.miaomiaotv.aerial.core.Changelog
import com.miaomiaotv.aerial.core.CleanTvRunner
import com.miaomiaotv.aerial.core.CleanTvSpec
import com.miaomiaotv.aerial.core.RemoteRelease
import com.miaomiaotv.aerial.core.ReleaseChecker
import com.miaomiaotv.aerial.core.Target
import com.miaomiaotv.aerial.core.Ui
import com.miaomiaotv.aerial.core.VersionCompare
import java.io.File
import java.util.Locale

/**
 * 桌面更新页（手机端）。
 *
 * 与电视端同一套流程：查线上最新版 → 展示更新日志 → 下载 → 安装。
 * 唯一的差别在最后一公里：手机端下的包要先经 ADB 推到电视，
 * 再由电视本机的 `pm install` 装 —— 因为 `pm` 读的是电视自己文件系统上的路径。
 */
class UpdateActivity : Activity() {

    private lateinit var settings: AerialSettings
    private lateinit var target: Target

    private lateinit var statusText: TextView
    private lateinit var installedText: TextView
    private lateinit var onlineText: TextView
    private lateinit var changelogText: TextView
    private lateinit var blockBusy: View
    private lateinit var progressBar: ProgressBar
    private lateinit var busyStep: TextView
    private lateinit var checkButton: Button
    private lateinit var downloadButton: Button
    private lateinit var backButton: Button

    @Volatile
    private var remote: RemoteRelease? = null

    @Volatile
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.isConnected()) {
            startActivity(Intent(this, ConnectActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_update)

        settings = AerialSettings(this)
        target = Session.target

        statusText = findViewById(R.id.tv_update_status)
        installedText = findViewById(R.id.tv_installed_version)
        onlineText = findViewById(R.id.tv_online)
        changelogText = findViewById(R.id.tv_changelog)
        blockBusy = findViewById(R.id.block_busy)
        progressBar = findViewById(R.id.update_progress)
        busyStep = findViewById(R.id.tv_busy_step)
        checkButton = findViewById(R.id.btn_check)
        downloadButton = findViewById(R.id.btn_download)
        backButton = findViewById(R.id.btn_back)

        backButton.setOnClickListener { finish() }
        checkButton.setOnClickListener { checkOnline() }
        downloadButton.setOnClickListener { startInstall() }

        renderInstalled()
        checkOnline()

        Ui.apply(window.decorView)
    }

    private fun renderInstalled() {
        val ch = Session.channel ?: return
        Session.executor.execute {
            val version = when (target) {
                Target.AERIAL -> AerialRunner(ch, { }, { _, _ -> }).installedVersion()
                Target.CLEAN_TV -> CleanTvRunner(ch, { }, { _, _ -> }).installedVersion()
            }
            runOnUiThread {
                installedText.text = when {
                    version.isNullOrBlank() -> "未安装"
                    else -> "v$version"
                }
            }
        }
    }

    // ===================== 线上检查 =====================

    private fun checkOnline() {
        if (busy) return
        busy = true
        setBusy(true, getString(R.string.update_checking), 0)
        statusText.text = getString(R.string.update_checking)

        Session.executor.execute {
            val release = runCatching {
                if (target.isAerial) {
                    ReleaseChecker.latest()
                } else {
                    // 上游不发二进制，只能查本仓库代构建的 cyt-v* 发行版
                    ReleaseChecker.latestFor(
                        target.updateApi, CleanTvSpec.ASSET_PREFIX, target.releasePage, target.tagPrefix
                    )
                }
            }.getOrNull()
            busy = false
            runOnUiThread {
                setBusy(false, "", 0)
                if (release == null) {
                    statusText.text = "检查失败"
                    onlineText.text = "无法获取"
                    changelogText.text = buildString {
                        append("连不上 GitHub，也拿不到官网镜像。\n\n")
                        append("先回主页点「一键安装」，内置离线包不依赖网络。\n\n")
                        append("需要联网时请检查：手机是否连上 Wi-Fi、是否开了代理/防火墙。")
                    }
                    downloadButton.isEnabled = false
                    downloadButton.alpha = 0.4f
                    toast("检查失败：网络不通")
                } else {
                    remote = release
                    onlineText.text = if (release.version.isBlank()) "最新" else "v${release.version}"
                    statusText.text = if (release.version.isBlank()) {
                        "已获取（${release.source}）"
                    } else {
                        getString(R.string.update_available, release.version, installedText.text)
                    }
                    changelogText.text = Changelog.pretty(
                        release.changelog,
                        "该版本没有附带更新说明。\n\n可在发布页查看：${release.pageUrl}"
                    )
                    downloadButton.isEnabled = true
                    downloadButton.alpha = 1f
                }
            }
        }
    }

    // ===================== 下载 + 安装 =====================

    private fun startInstall() {
        val release = remote ?: run {
            toast("先点「重新检查」拿到线上版本")
            return
        }
        if (busy) return
        val guard = settings.useGuard && target.isAerial
        val prefix = if (target.isAerial) "aerial" else "cleanyourtv"
        val flavor = when {
            guard -> "-guard"
            target.isAerial -> "-standard"
            else -> ""
        }
        val versionTag = release.version.ifBlank { "latest" }
        val dest = File(cacheDir, "install/$prefix$flavor-$versionTag.apk")
        val staging = if (target.isAerial) AerialSpec.STAGING_APK else CleanTvSpec.STAGING_APK

        busy = true
        setBusy(true, "准备下载…", 0)

        Session.executor.execute {
            try {
                if (ReleaseChecker.isUsableCache(dest)) {
                    ui { step("复用已下载的安装包", 20) }
                } else {
                    ui { step("连接下载服务器…", 3) }
                    val progress: (Long, Long) -> Unit = { read, total ->
                        val percent = if (total > 0) (read * 45 / total).toInt().coerceIn(0, 45) else 20
                        val mb = read / 1024.0 / 1024.0
                        ui { step(String.format(Locale.US, "下载安装包 %.1f MB…", mb), 3 + percent) }
                    }
                    if (target.isAerial) {
                        ReleaseChecker.downloadWithFallback(release.urlFor(guard), guard, dest, progress)
                    } else {
                        ReleaseChecker.download(release.standardUrl, dest, progress)
                    }
                }
                ui { step("推送安装包到电视…", 50) }
                Session.channel?.push(dest, staging) ?: error("连接已断开")
                ui { step("开始安装…", 65) }

                val onLog: (String) -> Unit = { }
                val onStep: (String, Int) -> Unit = { label, pct -> ui { step(label, 65 + pct * 35 / 100) } }
                val ch = Session.channel ?: error("连接已断开")
                val outcome = if (target.isAerial) {
                    AerialRunner(ch, onLog, onStep).install(
                        staging, settings.keepAdb, settings.ownScreensaver, settings.useGuard
                    )
                } else {
                    CleanTvRunner(ch, onLog, onStep).install(
                        staging,
                        settings.cytStockLauncher.ifBlank { null },
                        settings.cytInputKey,
                        settings.cytHomeKey,
                        settings.ownScreensaver
                    )
                }
                ui {
                    setBusy(false, "", 100)
                    renderInstalled()
                    showResult(outcome.title, outcome.details, outcome.ok)
                }
            } catch (t: Throwable) {
                ui {
                    setBusy(false, "", 0)
                    showResult(
                        "处理失败",
                        listOf(t.message ?: t.javaClass.simpleName, "可回主页点「一键安装」用内置离线包"),
                        false
                    )
                }
            } finally {
                busy = false
            }
        }
    }

    // ===================== 界面辅助 =====================

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun setBusy(value: Boolean, label: String, percent: Int) {
        blockBusy.visibility = if (value) View.VISIBLE else View.GONE
        progressBar.progress = percent.coerceIn(0, 100)
        if (label.isNotBlank()) busyStep.text = label
        checkButton.isEnabled = !value
        checkButton.alpha = if (value) 0.4f else 1f
        downloadButton.isEnabled = !value && remote != null
        downloadButton.alpha = if (downloadButton.isEnabled) 1f else 0.4f
    }

    private fun step(label: String, percent: Int) {
        blockBusy.visibility = View.VISIBLE
        progressBar.progress = percent.coerceIn(0, 100)
        busyStep.text = label
    }

    private fun showResult(title: String, details: List<String>, ok: Boolean) {
        val dialog = android.app.Dialog(this, R.style.Dialog_App)
        dialog.setContentView(R.layout.dialog_message)
        dialog.findViewById<TextView>(R.id.tv_title).apply {
            text = title
            setTextColor(resources.getColor(if (ok) R.color.ok else R.color.danger))
        }
        dialog.findViewById<TextView>(R.id.tv_detail).text = details.joinToString("\n")
        dialog.findViewById<Button>(R.id.btn_ok).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

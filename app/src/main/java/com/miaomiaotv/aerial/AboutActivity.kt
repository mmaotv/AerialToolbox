package com.miaomiaotv.aerial

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.miaomiaotv.aerial.apk.ApkProvider
import com.miaomiaotv.aerial.core.AdbSession
import com.miaomiaotv.aerial.core.ApkStore
import com.miaomiaotv.aerial.core.BundledRelease
import com.miaomiaotv.aerial.core.Changelog
import com.miaomiaotv.aerial.core.ReleaseChecker
import com.miaomiaotv.aerial.core.RemoteRelease
import com.miaomiaotv.aerial.core.ToolboxInfo
import com.miaomiaotv.aerial.core.ToolboxUpdater
import com.miaomiaotv.aerial.core.VersionCompare
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 关于页：工具箱自己的版本信息与更新。
 *
 * 和「桌面更新」页是两条独立的线：
 *  - 桌面更新 更新的是 Aerial（app.orchard.tv），装完要授权、接管主页键；
 *  - 这一页更新的是工具箱本身（[ToolboxInfo] 指向的仓库），装上即完成，没有接管动作。
 *
 * 进页面先把本版本更新日志摆出来（不联网也能看），随后自动查一次 GitHub；
 * 查到新版才把日志区换成线上版本的说明，用户点「升级到 x.y.z」才开始下载。
 *
 * 自己装自己有一个绕不开的事实：`pm install -r` 一执行，本进程就会被系统杀掉。
 * 所以下载完成后先弹确认框讲清楚「工具箱会退出」，用户确认后再安装，避免看起来像闪退。
 */
class AboutActivity : Activity() {

    private lateinit var bundled: BundledRelease

    private lateinit var statusText: TextView
    private lateinit var toolboxText: TextView
    private lateinit var bundledText: TextView
    private lateinit var channelText: TextView
    private lateinit var logTitle: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var blockBusy: View
    private lateinit var progressBar: ProgressBar
    private lateinit var busyStep: TextView
    private lateinit var checkButton: Button
    private lateinit var updateButton: Button
    private lateinit var backButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var remote: RemoteRelease? = null

    @Volatile
    private var busy = false

    private val localVersion: String by lazy { ToolboxInfo.versionName(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        Ui.apply(window.decorView)

        bundled = BundledRelease(this)

        statusText = findViewById(R.id.tvAboutStatus)
        toolboxText = findViewById(R.id.tvToolboxVersion)
        bundledText = findViewById(R.id.tvAboutBundled)
        channelText = findViewById(R.id.tvAboutChannel)
        logTitle = findViewById(R.id.tvAboutLogTitle)
        logText = findViewById(R.id.tvAboutLog)
        logScroll = findViewById(R.id.scrollAboutLog)
        blockBusy = findViewById(R.id.blockBusy)
        progressBar = findViewById(R.id.aboutProgress)
        busyStep = findViewById(R.id.tvBusyStep)
        checkButton = findViewById(R.id.btnCheckAbout)
        updateButton = findViewById(R.id.btnUpdateToolbox)
        backButton = findViewById(R.id.btnBack)

        backButton.setOnClickListener { finish() }
        checkButton.setOnClickListener { check(manual = true) }
        updateButton.setOnClickListener { startDownload() }

        renderLocal()
        renderLocalLog()
        check(manual = false)
        updateButton.requestFocus()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    // ===================== 本地信息 =====================

    private fun renderLocal() {
        toolboxText.text = ToolboxInfo.display(this)

        val info = bundled.info()
        bundledText.text = when {
            !info.available -> "未内置"
            info.publishedAt.isNotBlank() -> "v${info.version}（${info.publishedAt}）"
            else -> "v${info.version}"
        }

        channelText.text = if (AdbSession.connected) "已连接" else "未连接"

        status(getString(R.string.about_checking))
        updateButton.isEnabled = false
        updateButton.alpha = 0.4f
    }

    /** 本版本更新日志：随 APK 一起发出去，看它不需要网络 */
    private fun renderLocalLog() {
        logTitle.text = "本版本更新日志 · v$localVersion"
        logText.text = Changelog.pretty(ToolboxInfo.CHANGELOG)
        logScroll.scrollTo(0, 0)
    }

    /** 状态胶囊：绿点=已是最新 / 橙点=失败或未连接 / 灰点=进行中 */
    @Suppress("DEPRECATION")
    private fun status(text: String, ok: Boolean? = null) {
        val color = when (ok) {
            true -> resources.getColor(R.color.ok)
            false -> resources.getColor(R.color.warn)
            else -> resources.getColor(R.color.text_tertiary)
        }
        Ui.status(statusText, text, color)
    }

    // ===================== 检查更新 =====================

    private fun check(manual: Boolean) {
        if (busy) return
        busy = true
        setBusy(true, "正在查询 GitHub 最新发行版…", 0)
        status(getString(R.string.about_checking))

        executor.execute {
            val release = runCatching { ToolboxUpdater.latest() }.getOrNull()
            busy = false
            ui {
                setBusy(false, "", 0)
                if (release == null) {
                    renderFailure(manual)
                } else {
                    remote = release
                    renderRelease(release)
                }
            }
        }
    }

    private fun renderFailure(manual: Boolean) {
        status("检查失败", false)
        // 日志区保持本版本内容：断网时这一页仍然有用
        renderLocalLog()
        updateButton.isEnabled = false
        updateButton.alpha = 0.4f
        updateButton.text = getString(R.string.btn_update_toolbox)
        if (manual) toast("检查失败：拿不到最新版本")
    }

    private fun renderRelease(release: RemoteRelease) {
        val newer = release.version.isNotBlank() &&
            VersionCompare.compare(release.version, localVersion) > 0

        if (newer) {
            status("线上 v${release.version}，当前 v$localVersion", true)
            logTitle.text = "v${release.version} 更新日志（GitHub）"
            logText.text = Changelog.pretty(
                release.changelog,
                "该版本没有附带更新说明。\n\n可在发布页查看：${release.pageUrl}"
            )
            logScroll.scrollTo(0, 0)
            updateButton.text = "升级到 v${release.version}"
            updateButton.isEnabled = true
            updateButton.alpha = 1f
        } else {
            // 线上不比本地新（含线上版本号为空的情况）：只保留本版本日志，不做降级安装
            status(
                if (release.version.isBlank()) "已获取（${release.source}）" else "已是最新版 v${release.version}",
                true
            )
            renderLocalLog()
            updateButton.text = getString(R.string.btn_latest_toolbox)
            updateButton.isEnabled = false
            updateButton.alpha = 0.4f
        }
    }

    // ===================== 下载 =====================

    private fun startDownload() {
        val release = remote ?: run {
            toast("先点「重新检查」拿到线上版本")
            return
        }
        if (busy) return
        val versionTag = release.version.ifBlank { "latest" }
        val dest = File(ApkStore.stagingDir(this), "AerialToolbox-$versionTag.apk")
        busy = true
        setBusy(true, "准备下载…", 0)

        executor.execute {
            try {
                if (release.version.isBlank()) {
                    finishBusy()
                    ui { showResult("没有可用的安装包", listOf(release.pageUrl), ok = false) }
                    return@execute
                }
                if (release.standardUrl.isBlank()) {
                    finishBusy()
                    ui { showResult("这个 Release 没有挂安装包", listOf("可在发布页手动下载：${release.pageUrl}"), ok = false) }
                    return@execute
                }
                if (ReleaseChecker.isUsableCache(dest)) {
                    step("已存在同版本安装包，跳过下载", 100)
                } else {
                    step("连接下载服务器…", 5)
                    ToolboxUpdater.download(release.standardUrl, dest) { read, total ->
                        val percent = if (total > 0) (read * 100 / total).toInt().coerceIn(0, 100) else 30
                        val mb = read / 1024.0 / 1024.0
                        step(String.format(Locale.US, "下载安装包 %.1f MB…", mb), percent)
                    }
                }
                finishBusy()
                ui { confirmInstall(dest, release.version) }
            } catch (t: Throwable) {
                finishBusy()
                ui {
                    showResult(
                        "下载失败",
                        listOf(
                            t.message ?: t.javaClass.simpleName,
                            "电视可能连不上 GitHub，可换网络后重试",
                            "也可以到发布页手动下载：${release.pageUrl}"
                        ),
                        ok = false
                    )
                }
            }
        }
    }

    /** 下载完成后的确认：讲清楚「工具箱会退出」，避免被当成闪退 */
    private fun confirmInstall(apk: File, version: String) {
        val hasAdb = AdbSession.channel != null
        val message = buildString {
            append("即将安装工具箱 v$version。\n\n")
            if (hasAdb) {
                append("· 安装时系统会关闭当前工具箱，这是正常现象\n")
                append("· 装完请重新打开工具箱\n\n")
                append("桌面（Aerial）的安装状态不受影响。")
            } else {
                append("调试通道没连上，会打开系统安装器，请在电视上确认安装。\n\n")
                append("桌面（Aerial）的安装状态不受影响。")
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (hasAdb) "安装 v$version" else "交给系统安装器")
            .setMessage(message)
            .setPositiveButton(R.string.btn_install_now) { _, _ -> installSelf(apk, version) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
        }
        dialog.show()
    }

    // ===================== 安装自己 =====================

    private fun installSelf(apk: File, version: String) {
        if (busy) return
        val channel = AdbSession.channel
        if (channel == null) {
            val opened = openSystemInstaller(apk)
            showResult(
                if (opened) "已打开系统安装器" else "打不开系统安装器",
                if (opened) {
                    listOf(
                        "请在电视上选择「安装」并确认",
                        "装好后工具箱就是 v$version",
                        "桌面（Aerial）的接管状态不受影响"
                    )
                } else {
                    listOf(
                        "这个固件不允许第三方应用拉起安装界面",
                        "安装包已放在：${apk.absolutePath}",
                        "可以把它拷到 U 盘，插电视手动安装"
                    )
                },
                ok = opened
            )
            return
        }

        busy = true
        setBusy(true, "正在安装工具箱…", 60)

        executor.execute {
            // 安装包先落到 shell 能读的位置，再交给包管理器
            val staged = "/data/local/tmp/aerial-toolbox.apk"
            try {
                step("准备安装包…", 65)
                runCatching { channel.shell("cp \"${apk.absolutePath}\" $staged") }
                step("安装中，工具箱会退出…", 80)
                var output = channel.shell("pm install -r $staged")
                log("pm install 返回：${output.trim()}")
                // 线上包比本机新才会走到这里，但 CI 产出的包版本号可能更高，兜一下
                if (output.contains("VERSION_DOWNGRADE")) {
                    output = channel.shell("pm install -r -d $staged")
                    log("降级安装返回：${output.trim()}")
                }
                runCatching { channel.shell("rm -f $staged") }
                val ok = output.contains("Success")
                busy = false
                if (ok) {
                    finishBusy()
                    showResult(
                        "已安装 v$version",
                        listOf(
                            "工具箱已被系统重启，如果界面没刷新请重新打开",
                            "桌面（Aerial）的接管状态没有变化"
                        ),
                        ok = true
                    )
                } else {
                    finishBusy()
                    val opened = openSystemInstaller(apk)
                    showResult(
                        "ADB 安装没有成功",
                        listOf(
                            describeInstallFailure(output),
                            if (opened) "已打开系统安装器，可在电视上手动完成" else "可把 ${apk.name} 拷到 U 盘手动安装"
                        ),
                        ok = false
                    )
                }
            } catch (t: Throwable) {
                busy = false
                finishBusy()
                val opened = openSystemInstaller(apk)
                showResult(
                    "安装中断",
                    listOf(
                        t.message ?: t.javaClass.simpleName,
                        if (opened) "已打开系统安装器，可在电视上手动完成" else "安装包已放在：${apk.absolutePath}"
                    ),
                    ok = false
                )
            }
        }
    }

    /** 把 pm 的报错翻成人话，最常见的是签名不一致 */
    private fun describeInstallFailure(output: String): String = when {
        output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ->
            "签名不一致：这个包不是用同一把密钥签的（比如 CI 构建的包），需要先卸载再装"
        output.contains("INSTALL_FAILED_VERSION_DOWNGRADE") ->
            "安装包比本机版本旧，系统拒绝降级"
        output.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE") ->
            "电视存储空间不足"
        output.isBlank() -> "包管理器没有返回结果，可能是调试通道中断"
        else -> "系统返回：${output.trim().take(160)}"
    }

    // ===================== 系统安装器 =====================

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

    // ===================== 界面辅助 =====================

    private fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    private fun setBusy(value: Boolean, label: String, percent: Int) {
        blockBusy.visibility = if (value) View.VISIBLE else View.GONE
        progressBar.progress = percent.coerceIn(0, 100)
        if (label.isNotBlank()) busyStep.text = label
        checkButton.isEnabled = !value
        updateButton.isEnabled = !value && remote != null &&
            VersionCompare.compare(remote?.version, localVersion) > 0
        listOf(checkButton, updateButton).forEach {
            it.alpha = if (it.isEnabled) 1f else 0.4f
        }
    }

    private fun step(label: String, percent: Int) {
        blockBusy.visibility = View.VISIBLE
        progressBar.progress = percent.coerceIn(0, 100)
        busyStep.text = label
    }

    private fun finishBusy() {
        busy = false
        ui { setBusy(false, "", 100) }
    }

    private fun showResult(title: String, details: List<String>, ok: Boolean) {
        val message = details.filter { it.isNotBlank() }.take(10)
            .joinToString("\n") { "· $it" }
            .ifBlank { " " }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.btn_know, null)
            .setCancelable(true)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
        }
        dialog.show()
        Log.i(TAG, "结果：$title ${details.joinToString(" | ")}")
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun log(line: String) {
        Log.i(TAG, line)
    }

    private companion object {
        const val TAG = "AerialAbout"
    }
}

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
import com.miaomiaotv.aerial.core.AerialRunner
import com.miaomiaotv.aerial.core.AerialSettings
import com.miaomiaotv.aerial.core.AerialSpec
import com.miaomiaotv.aerial.core.ApkStore
import com.miaomiaotv.aerial.core.BundledRelease
import com.miaomiaotv.aerial.core.Changelog
import com.miaomiaotv.aerial.core.CleanTvRunner
import com.miaomiaotv.aerial.core.CleanTvSpec
import com.miaomiaotv.aerial.core.RemoteRelease
import com.miaomiaotv.aerial.core.ReleaseChecker
import com.miaomiaotv.aerial.core.Target
import com.miaomiaotv.aerial.core.VersionCompare
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 桌面更新页。
 *
 * 两条路径分开，符合「先能装、再谈升级」：
 *  1) 离线：直接用 APK 内置的官方 Release（[BundledRelease]），全程不联网；
 *  2) 在线：点了才去 GitHub 查最新 Release，展示更新日志，用户点「下载并安装」才开始下载，
 *     下载完先走 ADB 安装（可同时完成授权与接管），ADB 不可用时交给系统安装器。
 */
class UpdateActivity : Activity() {

    private lateinit var settings: AerialSettings
    private lateinit var bundled: BundledRelease

    /** 更新哪个桌面：Aerial（查上游）或 CleanYourTV（查本仓库代构建包） */
    private lateinit var target: Target

    private lateinit var statusText: TextView
    private lateinit var bundledText: TextView
    private lateinit var installedText: TextView
    private lateinit var onlineText: TextView
    private lateinit var changelogText: TextView
    private lateinit var changelogScroll: ScrollView
    private lateinit var blockBusy: View
    private lateinit var progressBar: ProgressBar
    private lateinit var busyStep: TextView
    private lateinit var checkButton: Button
    private lateinit var downloadButton: Button
    private lateinit var bundledButton: Button
    private lateinit var backButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var remote: RemoteRelease? = null

    @Volatile
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)
        Ui.apply(window.decorView)

        settings = AerialSettings(this)
        target = Target.of(intent.getStringExtra(EXTRA_TARGET))
        bundled = BundledRelease(this, target.bundledDir)

        statusText = findViewById(R.id.tvUpdateStatus)
        bundledText = findViewById(R.id.tvBundled)
        installedText = findViewById(R.id.tvInstalledVersion)
        onlineText = findViewById(R.id.tvOnline)
        changelogText = findViewById(R.id.tvChangelog)
        changelogScroll = findViewById(R.id.scrollChangelog)
        blockBusy = findViewById(R.id.blockBusy)
        progressBar = findViewById(R.id.updateProgress)
        busyStep = findViewById(R.id.tvBusyStep)
        checkButton = findViewById(R.id.btnCheck)
        downloadButton = findViewById(R.id.btnDownload)
        bundledButton = findViewById(R.id.btnBundled)
        backButton = findViewById(R.id.btnBack)

        backButton.setOnClickListener { finish() }
        checkButton.setOnClickListener { checkOnline(manual = true) }
        downloadButton.setOnClickListener { startOnlineInstall() }
        bundledButton.setOnClickListener { startBundledInstall() }

        renderLocal()
        checkOnline(manual = false)
        downloadButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // 从系统安装器回来时版本可能已经变了，重新读一次
        refreshInstalledQuietly()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    // ===================== 本地信息 =====================

    private fun renderLocal() {
        val info = bundled.info()
        bundledText.text = if (info.available) {
            if (info.publishedAt.isBlank()) info.display else "${info.display}（${info.publishedAt}）"
        } else {
            "未内置"
        }
        status(if (AdbSession.connected) "调试通道已连接" else "未连接：只能交给系统安装器", AdbSession.connected)
        renderInstalled(AdbSession.installedVersion)
        if (!info.available) {
            bundledButton.isEnabled = false
            bundledButton.alpha = 0.4f
        }
    }

    private fun refreshInstalledQuietly() {
        if (!AdbSession.connected) {
            renderInstalled(AdbSession.installedVersion)
            return
        }
        executor.execute {
            val value = AdbSession.refreshInstalled()
            ui { renderInstalled(value) }
        }
    }

    private fun renderInstalled(version: String?) {
        installedText.text = when {
            // 没连上调试通道时读不到真实版本，别把「未知」说成「未安装」
            version == null && !AdbSession.connected -> "未连接"
            version == null -> "未安装"
            version.isBlank() -> "已安装"
            else -> "v$version"
        }
    }

    /** 状态胶囊：绿点=通道可用 / 橙点=失败或未连接 / 灰点=进行中 */
    @Suppress("DEPRECATION")
    private fun status(text: String, ok: Boolean? = null) {
        val color = when (ok) {
            true -> resources.getColor(R.color.ok)
            false -> resources.getColor(R.color.warn)
            else -> resources.getColor(R.color.text_tertiary)
        }
        Ui.status(statusText, text, color)
    }

    // ===================== 线上检查 =====================
    private fun checkOnline(manual: Boolean) {
        if (busy) return
        busy = true
        setBusy(true, "正在查询 GitHub 最新发行版…", 0)
        status(getString(R.string.update_checking))

        executor.execute {
            val release = runCatching {
                if (target.isAerial) {
                    ReleaseChecker.latest()
                } else {
                    // 上游不发二进制，只能查我们自己代构建的 release
                    ReleaseChecker.latestFor(
                        target.updateApi, CleanTvSpec.ASSET_PREFIX, target.releasePage, target.tagPrefix
                    )
                }
            }.getOrNull()
            busy = false
            ui {
                setBusy(false, "", 0)
                if (release == null) {
                    status("检查失败", false)
                    onlineText.text = "无法获取"
                    changelogText.text = buildString {
                        append("连不上 GitHub，也拿不到官网镜像。\n\n")
                        append("可以先点「用内置包重装」，内置包不依赖网络，能完成安装与接管。\n\n")
                        append("需要联网时请检查：电视是否连上 Wi-Fi、是否开了代理/防火墙、DNS 是否被污染。")
                    }
                    downloadButton.isEnabled = false
                    downloadButton.alpha = 0.4f
                    if (manual) toast("检查失败：网络不通")
                } else {
                    remote = release
                    val installed = AdbSession.installedVersion
                    val newer = if (installed.isNullOrBlank() || release.version.isBlank()) {
                        false
                    } else {
                        VersionCompare.compare(release.version, installed) > 0
                    }
                    status(
                        when {
                            release.version.isBlank() -> "已获取（${release.source}）"
                            installed.isNullOrBlank() -> "线上 v${release.version}"
                            newer -> "线上 v${release.version}，本机 v$installed"
                            else -> "已是最新版 v${release.version}"
                        },
                        true
                    )
                    onlineText.text = buildString {
                        append(if (release.version.isBlank()) "最新" else "v${release.version}")
                        if (release.publishedAt.isNotBlank()) append("（${release.publishedAt}）")
                    }
                    changelogText.text = prettyChangelog(release)
                    changelogScroll.scrollTo(0, 0)
                    downloadButton.isEnabled = true
                    downloadButton.alpha = 1f
                    downloadButton.text = when {
                        release.version.isBlank() -> "下载最新版并安装"
                        installed.isNullOrBlank() -> "安装 v${release.version}"
                        newer -> "升级到 v${release.version}"
                        else -> "重新安装 v${release.version}"
                    }
                    syncBundledState(release)
                }
            }
        }
    }

    /** 线上比内置新时，在按钮上直说差别；并且按钮要提示「已是最新」时也不禁用（可用于重装） */
    private fun syncBundledState(release: RemoteRelease) {
        val local = bundled.info()
        if (!local.available || release.version.isBlank()) return
        val diff = VersionCompare.compare(release.version, local.version)
        if (diff > 0) {
            changelogText.append("\n\n—— 内置包是 v${local.version}，线上已更新 ——")
        }
    }

    // ===================== 在线下载 + 安装 =====================

    private fun startOnlineInstall() {
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
        val dest = File(ApkStore.stagingDir(this), "$prefix$flavor-$versionTag.apk")

        busy = true
        setBusy(true, "准备下载…", 0)

        executor.execute {
            try {
                if (ReleaseChecker.isUsableCache(dest)) {
                    log("复用已下载的安装包：${dest.absolutePath}")
                    ui { step("已存在同版本安装包，直接安装", 50) }
                } else {
                    ui { step("连接下载服务器…", 3) }
                    val progress: (Long, Long) -> Unit = { read, total ->
                        val percent = if (total > 0) (read * 50 / total).toInt().coerceIn(0, 50) else 20
                        val mb = read / 1024.0 / 1024.0
                        ui { step(String.format(Locale.US, "下载安装包 %.1f MB…", mb), 3 + percent) }
                    }
                    val used = if (target.isAerial) {
                        ReleaseChecker.downloadWithFallback(release.urlFor(guard), guard, dest, progress)
                    } else {
                        ReleaseChecker.download(release.standardUrl, dest, progress)
                        release.standardUrl
                    }
                    log("下载完成：$used → ${dest.absolutePath}")
                }
                installApk(dest, base = 50, span = 50, fromBundled = false)
            } catch (t: Throwable) {
                finishBusy()
                ui {
                    showResult(
                        "处理失败",
                        listOf(
                            t.message ?: t.javaClass.simpleName,
                            "可以换用「用内置包重装」先把桌面装好",
                            "或检查电视网络后重试"
                        ),
                        ok = false
                    )
                }
            }
        }
    }

    // ===================== 内置包安装 =====================

    private fun startBundledInstall() {
        if (busy) return
        busy = true
        setBusy(true, "释放内置安装包…", 2)

        executor.execute {
            val apk = bundled.extract(settings.useGuard && target.isAerial)
            if (apk == null) {
                finishBusy()
                ui { showResult("没有内置包", listOf("这个版本没有把安装包打进来，请改用「下载并安装」"), ok = false) }
                return@execute
            }
            log("使用内置离线包：${apk.absolutePath}")
            ui { step("已释放内置包（v${bundled.info().version}）", 12) }
            try {
                installApk(apk, base = 12, span = 88, fromBundled = true)
            } catch (t: Throwable) {
                finishBusy()
                ui { showResult("安装中断", listOf(t.message ?: t.javaClass.simpleName), ok = false) }
            }
        }
    }

    /**
     * 安装并接管。ADB 在就自己干（顺带授权、接管、验收）；不在就交给系统安装器。
     *
     * @param base 进度起点（下载已经占掉的部分）
     * @param span 本次安装占用的进度区间
     */
    private fun installApk(apk: File, base: Int, span: Int, fromBundled: Boolean) {
        val channel = AdbSession.channel

        if (channel == null) {
            // 没有调试通道：只能交给系统安装器，授权与接管需要回到工具箱主界面补做
            ui {
                finishBusy()
                val opened = openSystemInstaller(apk)
                if (opened) {
                    showResult(
                        "已打开系统安装器",
                        listOf(
                            "请在电视上用遥控器选择「安装」并确认",
                            "装好后回到工具箱，点「修复接管」完成授权与主页接管",
                            "想全自动就先把电视的 ADB 调试打开，再回主界面重新连接"
                        ),
                        ok = true
                    )
                } else {
                    showResult(
                        "打不开系统安装器",
                        listOf(
                            "这个固件不允许第三方应用拉起安装界面",
                            "请打开电视的 ADB 调试，让工具箱用 adb 安装",
                            "安装包已放在：${apk.absolutePath}"
                        ),
                        ok = false
                    )
                }
            }
            return
        }

        ui { step("开始安装…", base) }
        val onStep: (String, Int) -> Unit = { label, percent ->
            ui { step(label, base + percent * span / 100) }
        }
        val onLog: (String) -> Unit = { line -> log(line) }
        val finished: Boolean
        val title: String
        val details: MutableList<String>
        val version: String?
        if (target.isAerial) {
            val runner = AerialRunner(channel, onLog, onStep)
            val outcome = runner.install(apk, settings.keepAdb, settings.ownScreensaver, settings.useGuard)
            version = runner.installedVersion()
            finished = outcome.ok
            title = outcome.title
            details = outcome.details.toMutableList()
        } else {
            // 装之前先把原桌面记下来：装完它可能已被停用，就探测不到了
            if (settings.cytStockLauncher.isBlank()) {
                val home = runCatching {
                    CleanTvRunner(channel, onLog, onStep).homeComponent()
                }.getOrDefault("")
                val pkg = home.substringBefore('/')
                if (pkg.isNotBlank() && pkg != CleanTvSpec.PKG) settings.cytStockLauncher = pkg
            }
            val runner = CleanTvRunner(channel, onLog, onStep)
            val outcome = runner.install(
                apk,
                settings.cytStockLauncher,
                settings.cytInputKey,
                settings.cytHomeKey,
                settings.ownScreensaver
            )
            version = runner.installedVersion()
            finished = outcome.ok
            title = outcome.title
            details = outcome.details.toMutableList()
        }

        AdbSession.installedVersion = version
        // 安装过程会把新桌面拉到前台，把本页再叫回来，用户才看得到结果
        runCatching { channel.shell("am start -n $packageName/.UpdateActivity") }
        finishBusy()
        ui {
            renderInstalled(version)
            if (fromBundled) details += "本次使用的是内置离线包"
            if (!finished) {
                // ADB 被固件拒绝（TCL 常见）时，再给一条系统安装器的路
                val opened = openSystemInstaller(apk)
                details += if (opened) {
                    "已打开系统安装器，可在电视上手动完成安装"
                } else {
                    "手动安装：把 ${apk.name} 拷到 U 盘插电视安装"
                }
            }
            showResult(if (finished) "更新完成" else title, details, finished)
        }
    }

    // ===================== 系统安装器 =====================

    /**
     * 用系统包安装器打开下载好的 APK。
     * 通过 [ApkProvider] 提供 content:// 只读句柄，兼容 Android 7 以后禁止 file:// 的限制。
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

    // ===================== 界面辅助 =====================

    private fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    private fun setBusy(value: Boolean, label: String, percent: Int) {
        blockBusy.visibility = if (value) View.VISIBLE else View.GONE
        progressBar.progress = percent.coerceIn(0, 100)
        if (label.isNotBlank()) busyStep.text = label
        checkButton.isEnabled = !value
        bundledButton.isEnabled = !value && bundled.info().available
        downloadButton.isEnabled = !value && remote != null
        listOf(checkButton, bundledButton, downloadButton).forEach {
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
        val message = details.filter { it.isNotBlank() }.take(14)
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

    /** Release 正文是 markdown，电视上没条件渲染，交给 [Changelog] 统一清洗 */
    private fun prettyChangelog(release: RemoteRelease): String = Changelog.pretty(
        release.changelog,
        "该版本没有附带更新说明。\n\n可在发布页查看：${release.pageUrl}"
    )

    companion object {
        private const val TAG = "AerialUpdate"

        /** 主界面传进来的目标桌面 key */
        const val EXTRA_TARGET = "target"
    }
}

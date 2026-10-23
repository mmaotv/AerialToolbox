package com.miaomiaotv.aerial.phone

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.miaomiaotv.aerial.core.AerialRunner
import com.miaomiaotv.aerial.core.AerialSettings
import com.miaomiaotv.aerial.core.CleanTvRunner
import com.miaomiaotv.aerial.core.HomeRoles
import com.miaomiaotv.aerial.core.Target
import com.miaomiaotv.aerial.core.Ui

class DashboardActivity : Activity() {

    private lateinit var tvDevice: TextView
    private lateinit var tvHome: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvAppVersion: TextView
    private lateinit var btnTargetAerial: TextView
    private lateinit var btnTargetClean: TextView
    private lateinit var btnInstall: Button
    private lateinit var btnRestore: Button
    private lateinit var btnVerify: Button
    private lateinit var btnRepair: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStep: TextView
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var btnRemote: Button
    private lateinit var btnScreenshot: Button
    private lateinit var btnBack: Button
    private lateinit var btnUpdate: Button
    private lateinit var btnAbout: Button
    private lateinit var opt1: TextView
    private lateinit var opt2: TextView
    private lateinit var opt3: TextView
    private lateinit var settings: AerialSettings

    private val logBuilder = StringBuilder()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.isConnected()) {
            // 没连上就进来了：退回连接页
            startActivity(Intent(this, ConnectActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_dashboard)

        tvDevice = findViewById(R.id.tv_device)
        tvHome = findViewById(R.id.tv_home)
        tvStatus = findViewById(R.id.tv_status)
        tvAppVersion = findViewById(R.id.tv_app_version)
        btnTargetAerial = findViewById(R.id.btn_target_aerial)
        btnTargetClean = findViewById(R.id.btn_target_clean)
        btnInstall = findViewById(R.id.btn_install)
        btnRestore = findViewById(R.id.btn_restore)
        btnVerify = findViewById(R.id.btn_verify)
        btnRepair = findViewById(R.id.btn_repair)
        progressBar = findViewById(R.id.progress_bar)
        tvStep = findViewById(R.id.tv_step)
        tvLog = findViewById(R.id.tv_log)
        svLog = findViewById(R.id.sv_log)
        btnRemote = findViewById(R.id.btn_remote)
        btnScreenshot = findViewById(R.id.btn_screenshot)
        btnBack = findViewById(R.id.btn_back)
        btnUpdate = findViewById(R.id.btn_update)
        btnAbout = findViewById(R.id.btn_about)
        opt1 = findViewById(R.id.opt_1)
        opt2 = findViewById(R.id.opt_2)
        opt3 = findViewById(R.id.opt_3)

        settings = AerialSettings(this)
        // 目标是持久化的：上次选了 CleanYourTV，下次打开还是它
        Session.target = Target.current(this)

        btnTargetAerial.setOnClickListener { switchTarget(Target.AERIAL) }
        btnTargetClean.setOnClickListener { switchTarget(Target.CLEAN_TV) }
        btnInstall.setOnClickListener { onInstall() }
        btnRestore.setOnClickListener { onRestore() }
        btnVerify.setOnClickListener { onVerify() }
        btnRepair.setOnClickListener { onRepair() }
        btnRemote.setOnClickListener { startActivity(Intent(this, RemoteActivity::class.java)) }
        btnScreenshot.setOnClickListener { startActivity(Intent(this, ScreenshotActivity::class.java)) }
        btnUpdate.setOnClickListener { startActivity(Intent(this, UpdateActivity::class.java)) }
        btnAbout.setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }
        btnBack.setOnClickListener { Session.close(); finish() }

        opt1.setOnClickListener { onOption(0) }
        opt2.setOnClickListener { onOption(1) }
        opt3.setOnClickListener { onOption(2) }

        applyTargetStyle()
        refreshInfo()

        // 与 TV 端同一份 Inter 字体
        Ui.apply(window.decorView)
    }

    override fun onResume() {
        super.onResume()
        if (!Session.isConnected()) {
            startActivity(Intent(this, ConnectActivity::class.java))
            finish()
        } else {
            refreshInfo()
        }
    }

    private fun switchTarget(target: Target) {
        Session.target = target
        Target.select(this, target)
        applyTargetStyle()
        refreshInfo()
    }

    private fun applyTargetStyle() {
        val aerialSel = Session.target == Target.AERIAL
        btnTargetAerial.setBackgroundResource(if (aerialSel) R.drawable.bg_chip_on else R.drawable.bg_chip)
        btnTargetAerial.setTextColor(resources.getColor(if (aerialSel) R.color.text_primary else R.color.text_secondary))
        btnTargetClean.setBackgroundResource(if (aerialSel) R.drawable.bg_chip else R.drawable.bg_chip_on)
        btnTargetClean.setTextColor(resources.getColor(if (aerialSel) R.color.text_secondary else R.color.text_primary))
        renderOptions()
    }

    // ===================== 安装选项（与电视端三张开关卡片一一对应） =====================

    private fun onOption(slot: Int) {
        if (busy) return
        when (Session.target) {
            Target.AERIAL -> when (slot) {
                0 -> settings.useGuard = !settings.useGuard
                1 -> settings.keepAdb = !settings.keepAdb
                2 -> settings.ownScreensaver = !settings.ownScreensaver
            }
            Target.CLEAN_TV -> when (slot) {
                0 -> settings.cytInputKey = !settings.cytInputKey
                1 -> settings.cytHomeKey = !settings.cytHomeKey
                2 -> settings.ownScreensaver = !settings.ownScreensaver
            }
        }
        renderOptions()
    }

    private fun renderOptions() {
        val on = getString(R.string.state_on)
        val off = getString(R.string.state_off)
        val triples: List<Triple<String, Boolean, Boolean>> = if (Session.target.isAerial) {
            listOf(
                Triple(
                    if (settings.useGuard) getString(R.string.opt_channel_guard) else getString(R.string.opt_channel_standard),
                    settings.useGuard, false
                ),
                Triple(getString(R.string.opt_keep_adb, if (settings.keepAdb) on else off), settings.keepAdb, false),
                Triple(getString(R.string.opt_screensaver, if (settings.ownScreensaver) on else off), settings.ownScreensaver, false)
            )
        } else {
            listOf(
                Triple(getString(R.string.opt_input_key, if (settings.cytInputKey) on else off), settings.cytInputKey, false),
                Triple(getString(R.string.opt_home_key, if (settings.cytHomeKey) on else off), settings.cytHomeKey, false),
                Triple(getString(R.string.opt_screensaver, if (settings.ownScreensaver) on else off), settings.ownScreensaver, false)
            )
        }
        listOf(opt1, opt2, opt3).forEachIndexed { index, view ->
            val (label, enabled, _) = triples[index]
            view.text = label
            view.setBackgroundResource(if (enabled) R.drawable.bg_chip_on else R.drawable.bg_chip)
            view.setTextColor(
                resources.getColor(if (enabled) R.color.text_primary else R.color.text_secondary)
            )
        }
    }

    private fun sh(cmd: String): String = runCatching {
        Session.channel?.shell(cmd)
    }.onFailure { Log.e("PhoneDash", "shell 失败: $cmd", it) }.getOrDefault("") ?: ""

    /**
     * 刷新设备信息、当前默认桌面、是否已安装。
     *
     * 必须整个丢到后台线程：这些查询都是走 ADB 的 socket I/O，
     * 而在主线程做网络操作会触发 StrictMode 的 NetworkOnMainThreadException
     * （targetSdk ≥ 11 就默认开启），表现就是所有信息永远显示「未知」。
     */
    private fun refreshInfo() {
        val ch = Session.channel ?: return
        Session.executor.execute {
            val model = sh("getprop ro.product.model").trim().ifBlank { getString(R.string.unknown) }
            val sdk = sh("getprop ro.build.version.sdk").trim().toIntOrNull() ?: 0
            Session.deviceModel = model
            Session.androidSdk = sdk
            Session.currentHome = HomeRoles.current { sh(it) }
            val home = Session.currentHome.ifBlank { getString(R.string.none_default) }
            val installed = installedVersion()
            val installedText = if (installed == null) "（未安装）" else "（已装 $installed）"

            runOnUiThread {
                tvDevice.text = "$model · Android $sdk"
                tvHome.text = "$home $installedText"
                tvStatus.text = getString(R.string.status_connected, Session.target.label)
                tvStatus.setTextColor(resources.getColor(R.color.ok))
                tvAppVersion.text = getString(R.string.app_version, appVersionName())
            }
        }
    }

    /** 工具箱自己的版本号，从包管理器读，界面不会和实际安装包对不上 */
    private fun appVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { getString(R.string.unknown) }

    private fun installedVersion(): String? {
        val ch = Session.channel ?: return null
        return when (Session.target) {
            Target.AERIAL -> AerialRunner(ch, { }, { _, _ -> }).installedVersion()
            Target.CLEAN_TV -> CleanTvRunner(ch, { }, { _, _ -> }).installedVersion()
        }
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            logBuilder.append(line).append("\n")
            tvLog.text = logBuilder.toString()
            svLog.post { svLog.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setStep(label: String, percent: Int) {
        runOnUiThread {
            progressBar.progress = percent
            tvStep.text = label
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        runOnUiThread {
            val en = !b
            btnInstall.isEnabled = en
            btnRestore.isEnabled = en
            btnVerify.isEnabled = en
            btnRepair.isEnabled = en
            btnUpdate.isEnabled = en
            btnAbout.isEnabled = en
        }
    }

    private fun runOp(title: String, work: (onLog: (String) -> Unit, onStep: (String, Int) -> Unit) -> Unit) {
        if (busy) return
        setBusy(true)
        appendLog("==== $title ====")
        Session.executor.execute {
            try {
                work(
                    { line -> appendLog(line) },
                    { label, pct -> setStep(label, pct) }
                )
            } catch (e: Exception) {
                e.printStackTrace()
                appendLog("出错：${e.message}")
            } finally {
                setBusy(false)
                runOnUiThread { refreshInfo() }
            }
        }
    }

    private fun onInstall() {
        val target = Session.target
        val useGuard = settings.useGuard
        runOp("一键安装 ${target.label}") { onLog, onStep ->
            val ch = Session.channel ?: return@runOp
            // 接管前先记下原桌面，还原时才还得出去（HOME 键失灵修复的关键）
            Session.prevHome = HomeRoles.current { ch.shell(it) }
            Session.stockLauncher = Session.prevHome
            onLog("已记录原桌面：${Session.prevHome.ifBlank { "无" }}")

            onLog("正在取出内置安装包…")
            val staging = AdbKit.pushBundled(this, ch, target, useGuard)
            onLog("已推送到电视：$staging")

            val outcome = if (target.isAerial) {
                AerialRunner(ch, onLog, onStep).install(
                    staging,
                    keepAdb = settings.keepAdb,
                    ownScreensaver = settings.ownScreensaver,
                    useGuard = useGuard
                )
            } else {
                CleanTvRunner(ch, onLog, onStep).install(
                    staging,
                    stockHint = Session.stockLauncher.ifBlank { null },
                    inputKey = settings.cytInputKey,
                    homeKey = settings.cytHomeKey,
                    screensaver = settings.ownScreensaver
                )
            }
            onLog("————")
            outcome.details.forEach { onLog(it) }
            runOnUiThread {
                showResult(outcome.title, outcome.details)
            }
        }
    }

    private fun onRestore() {
        val target = Session.target
        runOp("还原并卸载 ${target.label}") { onLog, onStep ->
            val ch = Session.channel ?: return@runOp
            val outcome = if (target.isAerial) {
                AerialRunner(ch, onLog, onStep).restore(
                    uninstall = true,
                    ownScreensaver = settings.ownScreensaver,
                    prevHome = Session.prevHome
                )
            } else {
                CleanTvRunner(ch, onLog, onStep).restore(
                    uninstall = true, stockHint = Session.stockLauncher.ifBlank { null }
                )
            }
            onLog("————")
            outcome.details.forEach { onLog(it) }
            runOnUiThread { showResult(outcome.title, outcome.details) }
        }
    }

    private fun onVerify() {
        runOp("验收检查") { onLog, _ ->
            val ch = Session.channel ?: return@runOp
            val checks = if (Session.target.isAerial) {
                AerialRunner(ch, onLog, { _, _ -> }).verify()
            } else {
                CleanTvRunner(ch, onLog, { _, _ -> }).verify()
            }
            val pass = checks.count { it.ok }
            onLog("————")
            checks.forEach { onLog("${if (it.ok) "✓" else "✗"} ${it.label}：${it.detail}") }
            onLog("通过 $pass / ${checks.size}")
            runOnUiThread {
                Toast.makeText(
                    this, "验收通过 $pass / ${checks.size}", Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun onRepair() {
        val target = Session.target
        if (target != Target.AERIAL) {
            Toast.makeText(this, "修复接管仅 Aerial 支持", Toast.LENGTH_SHORT).show()
            return
        }
        runOp("修复 Aerial 接管") { onLog, onStep ->
            val ch = Session.channel ?: return@runOp
            val outcome = AerialRunner(ch, onLog, onStep).repair(
                keepAdb = settings.keepAdb,
                ownScreensaver = settings.ownScreensaver,
                useGuard = settings.useGuard
            )
            outcome.details.forEach { onLog(it) }
            runOnUiThread { showResult(outcome.title, outcome.details) }
        }
    }

    /**
     * 结果弹窗走独立 Dialog 窗口 + 自定义布局，与 TV 端同一个视觉、同一套交互语义。
     * 不用 AlertDialog：系统主题会把内容包进一块灰色方底，跟两端的黑玻璃语言不搭。
     */
    private fun showResult(title: String, details: List<String>) {
        val dialog = Dialog(this, R.style.Dialog_App)
        dialog.setContentView(R.layout.dialog_message)
        dialog.findViewById<TextView>(R.id.tv_title).text = title
        dialog.findViewById<TextView>(R.id.tv_detail).text = details.joinToString("\n")
        dialog.findViewById<Button>(R.id.btn_ok).setOnClickListener { dialog.dismiss() }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }
}

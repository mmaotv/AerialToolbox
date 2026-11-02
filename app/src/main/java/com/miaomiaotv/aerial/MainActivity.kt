package com.miaomiaotv.aerial

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.miaomiaotv.aerial.adb.AdbChannel
import com.miaomiaotv.aerial.adb.AdbIdentity
import com.miaomiaotv.aerial.adb.UnauthorizedException
import com.miaomiaotv.aerial.core.AdbGuide
import com.miaomiaotv.aerial.core.AdbSession
import com.miaomiaotv.aerial.core.AerialRunner
import com.miaomiaotv.aerial.core.AerialSettings
import com.miaomiaotv.aerial.core.AerialSpec
import com.miaomiaotv.aerial.core.ApkStore
import com.miaomiaotv.aerial.core.BundledRelease
import com.miaomiaotv.aerial.core.CleanTvRunner
import com.miaomiaotv.aerial.core.CleanTvSpec
import com.miaomiaotv.aerial.core.ReleaseChecker
import com.miaomiaotv.aerial.core.Target
import com.miaomiaotv.aerial.core.ToolboxInfo
import com.miaomiaotv.aerial.core.VersionCompare
import com.miaomiaotv.aerial.core.Ui
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 主界面（TV 风格）。
 *
 * 整个工具就跑在电视/盒子本机，通过 127.0.0.1:5555 调自己的调试通道，
 * 等价于在电脑上执行 `adb install` 与 `adb shell ...`：
 *  安装 Aerial → 授予只有 adb 能给的权限 → 接管主页键与系统屏保 → 验收 / 还原。
 *
 * 安装默认用 APK 里内置的官方 Release，全程不联网；想升级时进「桌面更新」页，
 * 那里才会去 GitHub 查最新版、展示更新日志并下载。
 */
class MainActivity : Activity() {

    private lateinit var infoText: TextView
    private lateinit var logText: TextView
    private lateinit var actionGrid: LinearLayout
    private lateinit var actionScroll: ScrollView
    private lateinit var retryButton: Button
    private lateinit var helpButton: Button

    /** 未连通调试通道时顶替操作区的引导块（它自己就是首屏的「第 1 步」） */
    private lateinit var blockGuide: ScrollView
    private lateinit var guideBrand: TextView
    private lateinit var guideError: TextView
    private lateinit var guideRetry: Button
    private lateinit var guideHelp: Button

    /** 「更多功能」弹窗：只有这一个，反复 show/dismiss，不重复创建窗口 */
    private var moreDialog: Dialog? = null
    private var moreGrid: LinearLayout? = null

    /** 当前这一屏「更多功能」里的动作，随目标桌面与安装状态变化，重建宫格时一起算 */
    private var moreActions: List<Action> = emptyList()

    /**
     * 模态弹窗。
     *
     * 必须是独立 Dialog 窗口，不能是主界面里盖一层 FrameLayout：
     * 同窗口遮罩只能挡住画面，挡不住焦点，按确认键会落到背后那张卡片上
     * （用户看到的就是「点了『我明白了』却执行了背后的动作」）。
     */
    private var overlayDialog: Dialog? = null
    private lateinit var blockProgress: LinearLayout
    private lateinit var blockDone: LinearLayout
    private lateinit var blockConfirm: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var percentText: TextView
    private lateinit var stepText: TextView
    private lateinit var doneTitle: TextView
    private lateinit var doneDetail: TextView
    private lateinit var confirmTitle: TextView
    private lateinit var confirmDetail: TextView
    private lateinit var okButton: Button
    private lateinit var restoreOnlyButton: Button
    private lateinit var restoreUninstallButton: Button
    private lateinit var cancelButton: Button

    /** 弹窗此刻是否在显示：显示期间绝不能让主界面抢焦点 */
    private val modalShowing: Boolean
        get() = overlayDialog?.isShowing == true || moreDialog?.isShowing == true

    /** 上一次真正执行动作的时间，用来吃掉遥控器的连击 */
    private var lastActionAt = 0L

    /** 上一张拿到焦点的卡片槽位（列表顺序固定，用下标就够），重建宫格后要还原回去 */
    private var lastFocusedIndex = 0

    /** 弹窗因为窗口还没准备好而重试的次数 */
    private var modalRetries = 0

    /** Activity 是否在前台（弹窗只能在前台弹） */
    private var resumed = false

    /** 前台恢复后要补弹的弹窗 */
    private var pendingModal: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var settings: AerialSettings

    /** 亮屏恢复 ADB 用的动态广播；仅在「ADB 常开」开启且本页在前台时注册 */
    private var screenOnReceiver: BroadcastReceiver? = null
    private lateinit var bundled: BundledRelease
    private lateinit var chipAerial: TextView
    private lateinit var chipCleanTv: TextView

    /** 当前目标桌面：Aerial 或 CleanYourTV，两个上游互斥，一次只装一个 */
    private var target: Target = Target.AERIAL

    @Volatile
    private var channel: AdbChannel? = null

    @Volatile
    private var runner: AerialRunner? = null

    @Volatile
    private var cytRunner: CleanTvRunner? = null

    @Volatile
    private var installedVersion: String? = null

    @Volatile
    private var deviceLabel: String = ""

    /** 电视的 API 等级：CleanYourTV 有 5.1 门槛，卡片要据此禁用 */
    @Volatile
    private var deviceSdk: Int = 0

    @Volatile
    private var busy = false

    /** 信息栏第一个字段：连接状态文字与它的颜色（原来是一个独立的胶囊控件） */
    private var stateLabel = ""
    private var stateState = STATE_NONE

    private val logLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Ui.apply(window.decorView)

        settings = AerialSettings(this)
        target = Target.current(this)
        bundled = BundledRelease(this, target.bundledDir)

        infoText = findViewById(R.id.tvInfo)
        logText = findViewById(R.id.tvLog)
        actionGrid = findViewById(R.id.actionGrid)
        actionScroll = findViewById(R.id.actionScroll)
        retryButton = findViewById(R.id.btnRetry)
        helpButton = findViewById(R.id.btnHelp)
        blockGuide = findViewById(R.id.blockGuide)
        guideBrand = findViewById(R.id.tvGuideBrand)
        guideError = findViewById(R.id.tvGuideError)
        guideRetry = findViewById(R.id.btnGuideRetry)
        guideHelp = findViewById(R.id.btnGuideHelp)
        chipAerial = findViewById(R.id.chipAerial)
        chipCleanTv = findViewById(R.id.chipCleanTv)

        createOverlayDialog()
        renderGuideBrands()

        retryButton.setOnClickListener { guarded { connect() } }
        guideRetry.setOnClickListener { guarded { connect() } }
        helpButton.setOnClickListener { guarded { openAdbHelp() } }
        guideHelp.setOnClickListener { guarded { openAdbHelp() } }
        chipAerial.setOnClickListener { guarded { switchTarget(Target.AERIAL) } }
        chipCleanTv.setOnClickListener { guarded { switchTarget(Target.CLEAN_TV) } }

        renderTargetChips()
        buildActionGrid()
        requestStoragePermissionIfNeeded()
        connect()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // 亮屏恢复 ADB：小米待机唤醒后会关掉 ADB，前台时动态注册 SCREEN_ON 兜底
        if (settings.keepAdb) registerScreenOn()
        // 挂起的弹窗（装完桌面被挤下去那会儿）现在补上
        pendingModal?.let { pending ->
            pendingModal = null
            pending()
        }
        // 正在装/正在弹结果的时候不要动宫格：重建会把焦点抖到第一张卡片上，
        // 用户手里那一次按键就会落到「一键安装」上，看起来像是又从零开始装了一遍。
        if (busy || modalShowing) return
        // 从「桌面更新」页回来时版本可能已经变了，重新读一次真实状态
        val ch = channel ?: return
        executor.execute {
            val version = targetInstalledVersion(ch)
            installedVersion = version
            AdbSession.installedVersion = version
            // 这里是后台线程，碰控件必须回主线程（rebuildActionGrid 会 removeAllViews）
            renderInfo()
            ui { rebuildActionGrid() }
        }
    }

    override fun onPause() {
        resumed = false
        unregisterScreenOn()
        super.onPause()
    }

    override fun onBackPressed() {
        // 「更多功能」弹窗：返回键先关它，不能一路退到桌面
        val more = moreDialog
        if (more != null && more.isShowing) {
            more.dismiss()
            return
        }
        if (modalShowing) {
            // 进度弹窗不可取消（busy 期间 dismiss 会被忽略），结果/确认弹窗按返回等于关闭
            hideOverlay()
            return
        }
        super.onBackPressed()
    }

    // ===================== 连接本机调试通道 =====================

    private fun connect() {
        if (busy) return
        status(getString(R.string.connecting))
        handler.post { guideError.visibility = View.GONE }
        executor.execute {
            val identity = runCatching { AdbIdentity(File(filesDir, KEYS_DIR)) }
                .onFailure { Log.e(TAG, "密钥初始化失败", it) }
                .getOrNull()
            if (identity == null) {
                failConnect("密钥初始化失败")
                return@execute
            }

            channel?.let { runCatching { it.disconnect() } }
            channel = null
            runner = null
            cytRunner = null
            AdbSession.clear()

            val candidate = AdbChannel()
            candidate.attachIdentity(identity)
            candidate.onNeedAuthorization = { status("请在电视上选择「允许」", STATE_WARN) }

            try {
                candidate.connect(TARGET_HOST, TARGET_PORT, CONNECT_TIMEOUT_MS)
                channel = candidate
                val r = AerialRunner(
                    adb = candidate,
                    onLog = { line -> log(line) },
                    onStep = { label, percent -> updateProgress(label, percent) }
                )
                runner = r
                AdbSession.attach(candidate, r)
                status(getString(R.string.connected), STATE_OK)
                // 连接成功不再刷日志：状态点已经说明了，底部只留真正有内容的操作记录

                // 自助授权写安全设置权限（持久化），并立即把 ADB 拉回常开状态
                AdbKeepAlive.ensurePermissionGranted(candidate)
                if (settings.keepAdb) AdbKeepAlive.reenableAdb(this@MainActivity)

                val info = r.deviceInfo()
                deviceLabel = "${info.model} · Android ${info.sdk}"
                deviceSdk = info.sdk
                AdbSession.deviceLabel = deviceLabel
                cytRunner = CleanTvRunner(
                    adb = candidate,
                    onLog = { line -> log(line) },
                    onStep = { label, percent -> updateProgress(label, percent) }
                )
                installedVersion = targetInstalledVersion(candidate)
                AdbSession.installedVersion = installedVersion
                renderInfo()
                buildActionGrid()
            } catch (e: UnauthorizedException) {
                candidate.disconnect()
                failConnect("电视上没点「允许」调试授权")
            } catch (e: Exception) {
                candidate.disconnect()
                // 原始异常只留在 logcat：界面上给用户看 「ECONNREFUSED ... port 59298」 等于没说
                Log.i(TAG, "连接失败：${e.message}")
                failConnect(friendlyReason(e))
            }
        }
    }

    /** 把 socket 异常翻成用户能对上号的说法 */
    private fun friendlyReason(e: Throwable): String {
        val raw = e.message.orEmpty()
        return when {
            raw.contains("ECONNREFUSED", true) || raw.contains("refused", true) ->
                "电视本机的 5555 端口没在监听，ADB 调试还没打开"
            raw.contains("timeout", true) || raw.contains("timed out", true) ||
                raw.contains("failed to connect", true) ->
                "连不上电视本机的 5555 端口，ADB 开关可能被系统关掉了"
            raw.isBlank() -> "连不上本机调试通道"
            else -> raw.lineSequence().first().trim()
        }
    }

    /**
     * 连接失败：把原因写进首屏引导，不再弹一个「报错框」。
     *
     * 失败时用户唯一想知道的就是「下一步做什么」，而引导块本身就是那件事；
     * 弹窗反而把引导盖住，关掉之后又找不回来（旧版就是这样，用户以为软件坏了）。
     */
    private fun failConnect(reason: String) {
        installedVersion = null
        deviceLabel = ""
        AdbSession.installedVersion = null
        status(getString(R.string.not_connected), STATE_WARN)
        handler.post {
            guideError.text = getString(R.string.guide_failed, reason)
            guideError.visibility = View.VISIBLE
            renderInfoBar()
            rebuildActionGrid()
        }
    }

    /** 开 ADB 的品牌路径页（顶栏「?」、引导里的「全部品牌」都走这里） */
    private fun openAdbHelp() {
        if (busy) return
        startActivity(Intent(this, AdbHelpActivity::class.java))
    }

    /**
     * 首屏引导只露一条最常见的品牌（小米 / 盒子用户占比最高），压成一行。
     * 其余十个品牌在「全部品牌」页里，引导页不铺长文本——那会把标题挤出屏幕。
     */
    private fun renderGuideBrands() {
        val top = AdbGuide.TOP.firstOrNull() ?: return
        guideBrand.text = "${top.oneLineBrand}：${top.oneLine}"
    }

    /** 打开「桌面更新」页：联网查最新版、看日志、下载安装都在那一页 */
    private fun openUpdatePage() {
        if (busy) return
        startActivity(
            Intent(this, UpdateActivity::class.java)
                .putExtra(UpdateActivity.EXTRA_TARGET, target.key)
        )
    }

    /** 切换目标桌面：版本、内置包、卡片都要跟着换一套 */
    private fun switchTarget(next: Target) {
        if (busy || next == target) return
        target = next
        Target.select(this, next)
        bundled = BundledRelease(this, next.bundledDir)
        installedVersion = null
        AdbSession.installedVersion = null
        log("目标桌面切换为 ${next.label}")
        renderTargetChips()
        renderInfo()
        buildActionGrid()
        // 版本要走一次 ADB 查询，别放在点击回调里：切换瞬间卡主线程还容易查失败
        val ch = channel ?: return
        executor.execute {
            val version = targetInstalledVersion(ch)
            installedVersion = version
            AdbSession.installedVersion = version
            renderInfo()
            ui { rebuildActionGrid() }
        }
    }

    /** 目标胶囊：选中态换更实的胶囊底，未选中也保留胶囊形（否则聚焦时会露出个灰方块） */
    private fun renderTargetChips() {
        listOf(chipAerial to Target.AERIAL, chipCleanTv to Target.CLEAN_TV).forEach { (view, t) ->
            val on = t == target
            view.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0x8AFFFFFF.toInt())
            view.background = getDrawable(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
        }
    }

    /** 按当前目标读已装版本（两个包的查询方式一样，包名不同） */
    private fun targetInstalledVersion(ch: AdbChannel): String? = runCatching {
        val pkg = target.pkg
        val list = ch.shell("pm list packages $pkg")
        if (!list.contains("package:$pkg")) return@runCatching null
        val dump = ch.shell("dumpsys package $pkg")
        Regex("versionName=([^\\s]+)").find(dump)?.groupValues?.get(1).orEmpty()
    }.getOrNull()

    /** 打开「关于」页：工具箱自己的版本、更新日志与自更新 */
    private fun openAboutPage() {
        if (busy) return
        startActivity(Intent(this, AboutActivity::class.java))
    }

    // ===================== 操作卡片 =====================

    private data class Action(
        val title: String,
        val subtitle: String,
        val enabled: Boolean = true,
        val onClick: () -> Unit
    )

    private fun buildActionGrid() {
        ui { rebuildActionGrid() }
    }

    /** 所有界面改动都必须回到主线程；后台线程直接调用也安全 */
    private fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    /**
     * 一屏的排布：一张主卡 + 两张次卡 + 一组收进「更多」的动作。
     *
     * 首屏的判断标准是「用户下一步该点哪个」，不是「我们有几个功能」。
     * 旧版 9 张卡等权铺在 3×3 宫里，把有先后顺序的流程（安装 → 验收）拆成了并列按钮，
     * 开关和动作混排，720p 上第三行还落在屏幕外且没有任何提示。
     */
    private data class Screen(
        val primary: Action,
        val secondary: List<Action>,
        val more: List<Action>
    )

    private fun installAction(
        connected: Boolean,
        installed: String?,
        bundledVersion: String,
        installedIsNewer: Boolean,
        tooOld: Boolean
    ): Action {
        val title = if (installed == null) {
            getString(R.string.install_title, target.label)
        } else {
            getString(R.string.install_title_reinstall)
        }
        val sub = when {
            tooOld -> getString(R.string.install_sub_too_old)
            installedIsNewer -> getString(R.string.install_sub_downgrade)
            // 次卡只有 1/3 宽，塞不下「离线安装，约 1 分钟」，那句话留给主卡
            installed != null && bundledVersion.isNotBlank() ->
                getString(R.string.install_sub_bundled_short, bundledVersion)
            bundledVersion.isNotBlank() -> getString(R.string.install_sub_bundled, bundledVersion)
            else -> getString(R.string.install_sub_download)
        }
        return Action(title, sub, connected && !busy && !tooOld) { startInstall() }
    }

    private fun verifyAction(connected: Boolean, installed: String?): Action = Action(
        getString(R.string.verify_title),
        getString(if (target.isAerial) R.string.verify_sub_aerial else R.string.verify_sub_clean),
        connected && installed != null && !busy
    ) { startVerify() }

    private fun updateAction(): Action = Action(
        getString(R.string.update_title_action),
        getString(if (target.isAerial) R.string.update_sub_aerial else R.string.update_sub_clean),
        !busy
    ) { openUpdatePage() }

    private fun aboutAction(toolboxVersion: String): Action = Action(
        getString(R.string.about_title_action),
        getString(R.string.about_sub, toolboxVersion),
        !busy
    ) { openAboutPage() }

    private fun onOff(on: Boolean): String =
        getString(if (on) R.string.state_on else R.string.state_off)

    private fun aerialScreen(
        connected: Boolean,
        installed: String?,
        bundledVersion: String,
        installedIsNewer: Boolean,
        toolboxVersion: String
    ): Screen {
        val install = installAction(connected, installed, bundledVersion, installedIsNewer, tooOld = false)
        val verify = verifyAction(connected, installed)
        // 装过之后首屏该回答的是「接管成功了吗」，而不是继续劝你再装一遍
        val primary = if (installed == null) install else verify
        val secondary = if (installed == null) listOf(verify, updateAction()) else listOf(install, updateAction())
        val more = listOf(
            Action(
                getString(R.string.repair_title),
                getString(R.string.repair_sub_aerial),
                connected && installed != null && !busy
            ) { startRepair() },
            Action(
                getString(R.string.restore_title),
                getString(R.string.restore_sub_aerial),
                connected && installed != null && !busy
            ) { showRestoreConfirm() },
            Action(
                getString(R.string.opt_keep_adb, onOff(settings.keepAdb)),
                getString(R.string.opt_keep_adb_sub),
                !busy
            ) { toggleKeepAdb() },
            Action(
                getString(R.string.opt_screensaver, onOff(settings.ownScreensaver)),
                getString(R.string.opt_screensaver_sub_aerial),
                !busy
            ) { toggleScreensaver() },
            Action(
                getString(R.string.opt_compat, onOff(settings.useGuard)),
                getString(R.string.opt_compat_sub),
                !busy
            ) { toggleFlavor() },
            aboutAction(toolboxVersion)
        )
        return Screen(primary, secondary, more)
    }

    /** CleanYourTV 的一屏：没有守护与安装通道，换成遥控器按键相关的两个开关 */
    private fun cleanTvScreen(
        connected: Boolean,
        installed: String?,
        bundledVersion: String,
        installedIsNewer: Boolean,
        toolboxVersion: String
    ): Screen {
        val tooOld = deviceSdk in 1 until target.minSdk
        val install = installAction(connected, installed, bundledVersion, installedIsNewer, tooOld)
        val verify = verifyAction(connected, installed)
        val primary = if (installed == null) install else verify
        val secondary = if (installed == null) listOf(verify, updateAction()) else listOf(install, updateAction())
        val more = listOf(
            Action(
                getString(R.string.repair_title),
                getString(R.string.repair_sub_clean),
                connected && installed != null && !busy
            ) { startRepair() },
            Action(
                getString(R.string.restore_title),
                getString(R.string.restore_sub_clean),
                connected && installed != null && !busy
            ) { showRestoreConfirm() },
            Action(
                getString(R.string.opt_input_key, onOff(settings.cytInputKey)),
                getString(R.string.opt_input_key_sub),
                connected && !busy
            ) { toggleCytInputKey() },
            Action(
                getString(R.string.opt_home_key, onOff(settings.cytHomeKey)),
                getString(R.string.opt_home_key_sub),
                connected && !busy
            ) { toggleCytHomeKey() },
            Action(
                getString(R.string.opt_screensaver, onOff(settings.ownScreensaver)),
                getString(R.string.opt_screensaver_sub_clean),
                !busy
            ) { toggleScreensaver() },
            aboutAction(toolboxVersion)
        )
        return Screen(primary, secondary, more)
    }

    private fun rebuildActionGrid() {
        if (isFinishing || isDestroyed) return
        val connected = channel != null && (runner != null || cytRunner != null)
        val installed = installedVersion
        val bundledVersion = bundled.info().version
        val toolboxVersion = ToolboxInfo.versionName(this)
        // 本机已经在线升级过时，再用内置包装就是降级，提前说清楚
        val installedIsNewer = installed != null && bundledVersion.isNotBlank() &&
            VersionCompare.compare(installed, bundledVersion) > 0
        val screen = if (target.isAerial) {
            aerialScreen(connected, installed, bundledVersion, installedIsNewer, toolboxVersion)
        } else {
            cleanTvScreen(connected, installed, bundledVersion, installedIsNewer, toolboxVersion)
        }
        moreActions = screen.more
        renderInfoBar()

        // 通道没通：整块换成「第 1 步」引导。
        // 这时九张卡一张都用不了，摆出来只会让用户以为是自己点错了地方。
        val showGuide = !connected
        blockGuide.visibility = if (showGuide) View.VISIBLE else View.GONE
        actionScroll.visibility = if (showGuide) View.GONE else View.VISIBLE
        // 引导块自带「重新连接」，顶栏那个就收起来，避免同一屏两个一样的按钮
        retryButton.visibility = if (showGuide) View.GONE else View.VISIBLE
        if (showGuide) {
            if (busy || modalShowing) return
            guideRetry.post { if (!guideRetry.hasFocus()) guideRetry.requestFocus() }
            return
        }

        val inflater = LayoutInflater.from(this)
        actionGrid.removeAllViews()
        // 操作进行中 / 弹窗在显示：宫格里一张卡都不许拿焦点，否则按键会打到背后
        actionGrid.descendantFocusability = if (busy || modalShowing) {
            ViewGroup.FOCUS_BLOCK_DESCENDANTS
        } else {
            ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        var slot = 0

        // 主卡：独占一行，占满宽度。
        // 高度随屏幕长：1080p 上内容只有半屏、下面一大片空黑，主卡长高一点把版面撑住；
        // 720p（360dp 高）按 26% 算出来就是 93dp，不会把次卡行挤出屏幕。
        val primaryCard = inflater.inflate(R.layout.item_action_main, actionGrid, false)
        Ui.apply(primaryCard)
        primaryCard.minimumHeight = primaryCardMinHeight()
        bindCard(primaryCard, screen.primary, slot++)
        actionGrid.addView(primaryCard)

        // 次卡 + 「更多」：一行三列
        val secondary = screen.secondary + Action(
            getString(R.string.more_title),
            getString(R.string.more_sub),
            true
        ) { showMore() }
        var index = 0
        while (index < secondary.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            // 高度交给内容决定：小屏（720p）按 weight 均分会把卡片里的两行字裁掉，
            // 外面套了 ScrollView，装不下就能滚
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            for (column in 0 until GRID_COLUMNS) {
                val action = secondary.getOrNull(index) ?: break
                val card = inflater.inflate(R.layout.item_action, row, false)
                Ui.apply(card)
                bindCard(card, action, slot++)
                row.addView(card)
                index++
            }
            // 补齐最后一行不足时的空位，保证卡片宽度一致
            while (row.childCount < GRID_COLUMNS) {
                val placeholder = View(this)
                placeholder.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                row.addView(placeholder)
            }
            actionGrid.addView(row)
        }

        // 焦点还原：优先原来那张卡，它不可用了才退回第一张可用的。
        // 之前这里是「永远抢第一张」，所以点完开关、或者状态刷新一下，焦点就跳到「一键安装」上了，
        // 用户看着就像是自己点错了模块。
        if (busy || modalShowing) return
        if (!focusCard(lastFocusedIndex)) focusCard(0)
    }

    /**
     * 把 [action] 绑到一张卡上。
     *
     * [slot] 是它在整屏里的序号，重建后靠它把焦点放回去；
     * 传 null 表示这张卡不参与焦点记忆（「更多」弹窗里的卡就是这种，
     * 否则关弹窗时会把主宫格的焦点序号改成弹窗里的位置，焦点乱跳）。
     */
    private fun bindCard(card: View, action: Action, slot: Int?) {
        card.findViewById<TextView>(R.id.tvTitle).text = action.title
        card.findViewById<TextView>(R.id.tvSub).text = action.subtitle
        card.alpha = if (action.enabled) 1f else 0.4f
        card.isFocusable = action.enabled
        card.isClickable = action.enabled
        card.tag = slot
        if (action.enabled) {
            card.setOnClickListener { guarded { action.onClick() } }
            bindFocusZoom(card)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** 主卡最小高度：屏幕越高长得越高，夹在 82dp（720p 不挤压次卡）到 132dp（1080p 撑住版面）之间 */
    private fun primaryCardMinHeight(): Int {
        val metrics = resources.displayMetrics
        val screenDp = metrics.heightPixels / metrics.density
        return (screenDp * 0.26f).toInt().coerceIn(dp(82), dp(132))
    }

    /**
     * 把焦点放到第 [slot] 张卡片上；该槽位不可聚焦时返回 false。
     *
     * 主卡直接挂在 [actionGrid] 下、次卡包在行里，所以不能假设「两层」结构——
     * 这里按创建顺序做一次扁平遍历，凡是带 tag 的视图就是一个槽位。
     */
    private fun focusCard(slot: Int): Boolean {
        val cards = ArrayList<View>(8)
        collectSlots(actionGrid, cards)
        val card = cards.getOrNull(slot) ?: return false
        if (!card.isFocusable) return false
        if (currentFocus !== card) card.requestFocus()
        return true
    }

    private fun collectSlots(parent: ViewGroup, out: MutableList<View>) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.tag is Int) {
                out.add(child)
            } else if (child is ViewGroup) {
                collectSlots(child, out)
            }
        }
    }

    // ===================== 更多功能 =====================

    /**
     * 不常用的动作与开关全收在这个弹窗里。
     *
     * 固定 3 列 × 2 行，不做可滚动的长列表：遥控器在长列表里上下找东西很痛苦，
     * 而这里本来就只有 6 项。选中任意一项先关弹窗再执行，免得两个窗口叠在一起。
     */
    private fun showMore() {
        if (busy || overlayDialog?.isShowing == true) return
        val actions = moreActions
        if (actions.isEmpty()) return
        val dialog = moreDialog ?: createMoreDialog()
        val grid = moreGrid ?: return
        grid.removeAllViews()

        val inflater = LayoutInflater.from(this)
        var firstFocusable: View? = null
        var index = 0
        while (index < actions.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            for (column in 0 until MORE_COLUMNS) {
                val action = actions.getOrNull(index) ?: break
                val card = inflater.inflate(R.layout.item_action, row, false)
                Ui.apply(card)
                // 先关弹窗再执行：结果弹窗 / 进度弹窗都另开一个窗口，叠着会互相抢焦点
                val wrapped = action.copy(onClick = {
                    dialog.dismiss()
                    handler.post { action.onClick() }
                })
                bindCard(card, wrapped, null)
                if (firstFocusable == null && action.enabled) firstFocusable = card
                row.addView(card)
                index++
            }
            while (row.childCount < MORE_COLUMNS) {
                val placeholder = View(this)
                placeholder.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                row.addView(placeholder)
            }
            grid.addView(row)
        }

        if (!dialog.isShowing) dialog.show()
        // 宽度按屏宽算：固定在 620dp 的话，720p（屏宽 640dp）上几乎贴满边缘
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        val focusView = firstFocusable ?: grid
        focusView.post { firstFocusable?.requestFocus() }
    }

    private fun createMoreDialog(): Dialog {
        moreDialog?.let { return it }
        val dialog = Dialog(this, R.style.Dialog_App)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_more)
        dialog.setCanceledOnTouchOutside(false)
        // 关掉之后把焦点还给宫格（还给原来那张卡），不要留一个「没有焦点」的空窗口
        dialog.setOnDismissListener { handler.post { rebuildActionGrid() } }
        (dialog.findViewById<View>(R.id.moreGrid)?.parent as? View)?.let { Ui.apply(it) }
        moreGrid = dialog.findViewById(R.id.moreGrid)
        dialog.findViewById<Button>(R.id.btnMoreClose).setOnClickListener {
            guarded { dialog.dismiss() }
        }
        moreDialog = dialog
        return dialog
    }

    /** 遥控器焦点放大效果，TV 上必须有明显的焦点反馈 */
    private fun bindFocusZoom(view: View) {
        view.setOnFocusChangeListener { target, hasFocus ->
            if (hasFocus) {
                (target.tag as? Int)?.let { lastFocusedIndex = it }
            }
            val scale = if (hasFocus) FOCUS_SCALE else 1f
            target.animate().scaleX(scale).scaleY(scale).setDuration(140).start()
        }
    }

    /**
     * 遥控器连击/长按会连发确认键。
     * 关弹窗的那一下如果还能顺带打到底下的卡片，操作就会被从零再跑一遍，
     * 所以所有「会改变状态」的入口都从这里过一道 600ms 的闸。
     */
    private fun guarded(block: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastActionAt < ACTION_DEBOUNCE_MS) {
            Log.i(TAG, "忽略连击：距上次动作 ${now - lastActionAt}ms")
            return
        }
        lastActionAt = now
        block()
    }

    // ===================== 动作：安装 =====================

    /**
     * 一键安装（离线优先）。
     *
     * 先用 APK 里内置的官方 Release，整个流程不需要网络；
     * 只有在内置包被裁掉（体积优化版）时才退回联网下载，保证任何情况下都能装。
     */
    private fun startInstall() {
        if (target.isAerial) startInstallAerial() else startInstallCleanTv()
    }

    private fun startInstallAerial() {
        val ch = channel ?: return notice("调试通道未连接")
        if (busy) return
        busy = true
        buildActionGrid()
        showProgress()

        executor.execute {
            try {
                val guard = settings.useGuard
                rememberHomeBeforeTakeover(ch)

                updateProgress("准备安装包…", 4)
                val bundledApk = bundled.extract(guard)
                val apk: File
                if (bundledApk != null) {
                    val version = bundled.info().version.ifBlank { "内置版" }
                    log("使用内置离线包：${bundledApk.absolutePath}")
                    updateProgress("使用内置离线包 v$version（无需联网）", 8)
                    apk = bundledApk
                } else {
                    log("没有内置包，改为联网下载")
                    updateProgress("查询线上版本…", 6)
                    val release = runCatching { ReleaseChecker.latest() }.getOrNull()
                    if (release == null) {
                        busy = false
                        buildActionGrid()
                        showDone(
                            "拿不到安装包",
                            listOf(
                                "这个版本没有内置安装包，GitHub 与官网镜像也都访问不了",
                                "请确认电视已联网后再试",
                                "也可以进「桌面更新」页重试"
                            ),
                            ok = false
                        )
                        return@execute
                    }
                    val versionTag = release.version.ifBlank { "latest" }
                    val fileName = "aerial-" + (if (guard) "guard" else "standard") + "-" + versionTag + ".apk"
                    val dest = File(ApkStore.stagingDir(this), fileName)
                    if (ReleaseChecker.isUsableCache(dest)) {
                        log("复用已下载的安装包：${dest.absolutePath}")
                    } else {
                        updateProgress("下载安装包…", 8)
                        ReleaseChecker.downloadWithFallback(release.urlFor(guard), guard, dest) { read, total ->
                            val percent = if (total > 0) (read * 40 / total).toInt().coerceIn(0, 40) else 20
                            val mb = read / 1024.0 / 1024.0
                            updateProgress(String.format(Locale.US, "下载安装包 %.1f MB…", mb), 8 + percent)
                        }
                        log("安装包已下载：${dest.absolutePath}")
                    }
                    apk = dest
                }

                updateProgress("开始安装…", 50)
                val installer = AerialRunner(
                    adb = ch,
                    onLog = { line -> log(line) },
                    onStep = { label, percent -> updateProgress(label, 50 + percent / 2) }
                )
                val outcome = installer.install(apk.path, settings.keepAdb, settings.ownScreensaver, guard)
                installedVersion = installer.installedVersion()
                AdbSession.installedVersion = installedVersion
                busy = false
                renderInfo()
                buildActionGrid()
                bringToolboxToFront()
                showDone(outcome.title, outcome.details, outcome.ok)
            } catch (t: Throwable) {
                busy = false
                buildActionGrid()
                showDone("处理中断", listOf(t.message ?: t.javaClass.simpleName), ok = false)
            }
        }
    }

    /**
     * CleanYourTV 的一键安装。
     *
     * 与 Aerial 最大的不同：它没有官方发布包，在线更新查的是本仓库的 `cyt-v*`，
     * 而且安装过程会停用原桌面，所以装之前先把原桌面包名记下来，还原时要用。
     */
    private fun startInstallCleanTv() {
        val ch = channel ?: return notice("调试通道未连接")
        val cyt = cytRunner ?: return notice("调试通道未连接")
        if (busy) return
        busy = true
        buildActionGrid()
        showProgress()

        executor.execute {
            try {
                updateProgress("准备安装包…", 6)
                val bundledApk = bundled.extract(false)
                val apk: File
                if (bundledApk != null) {
                    log("使用内置离线包：${bundledApk.absolutePath}")
                    updateProgress("使用内置包 v${bundled.info().version}（无需联网）", 12)
                    apk = bundledApk
                } else {
                    log("没有内置包，改为联网下载")
                    updateProgress("查询本仓库发行版…", 8)
                    val release = runCatching {
                        ReleaseChecker.latestFor(
                            target.updateApi,
                            CleanTvSpec.ASSET_PREFIX,
                            target.releasePage,
                            target.tagPrefix
                        )
                    }.getOrNull()
                    if (release == null) {
                        busy = false
                        buildActionGrid()
                        showDone(
                            "拿不到安装包",
                            listOf(
                                "这个版本没有内置 CleanYourTV，本仓库也没有可下载的代构建包",
                                "请确认电视已联网后重试",
                                "或用「桌面更新」页手动检查"
                            ),
                            ok = false
                        )
                        return@execute
                    }
                    val dest = File(
                        ApkStore.stagingDir(this),
                        "cleanyourtv-" + release.version.ifBlank { "latest" } + ".apk"
                    )
                    if (ReleaseChecker.isUsableCache(dest, 64 * 1024)) {
                        log("复用已下载的安装包：${dest.absolutePath}")
                    } else {
                        updateProgress("下载安装包…", 10)
                        ReleaseChecker.download(release.standardUrl, dest) { read, total ->
                            val percent = if (total > 0) (read * 40 / total).toInt().coerceIn(0, 40) else 20
                            val mb = read / 1024.0 / 1024.0
                            updateProgress(String.format(Locale.US, "下载安装包 %.1f MB…", mb), 10 + percent)
                        }
                        log("安装包已下载：${dest.absolutePath}")
                    }
                    apk = dest
                }

                // 记录原桌面：装完之后它可能已被停用，届时就探测不到了
                val currentHome = runCatching { cyt.homeComponent() }.getOrDefault("")
                val currentPkg = currentHome.substringBefore('/')
                if (currentPkg.isNotBlank() && currentPkg != CleanTvSpec.PKG) {
                    settings.cytStockLauncher = currentPkg
                    log("记录原桌面：$currentPkg")
                }

                updateProgress("开始安装…", 46)
                val outcome = cyt.install(
                    apk.path,
                    settings.cytStockLauncher,
                    settings.cytInputKey,
                    settings.cytHomeKey,
                    settings.ownScreensaver
                )
                installedVersion = targetInstalledVersion(ch)
                AdbSession.installedVersion = installedVersion
                busy = false
                renderInfo()
                buildActionGrid()
                bringToolboxToFront()
                showDone(outcome.title, outcome.details, outcome.ok)
            } catch (t: Throwable) {
                busy = false
                buildActionGrid()
                showDone("处理中断", listOf(t.message ?: t.javaClass.simpleName), ok = false)
            }
        }
    }

    // ===================== 动作：修复 / 验收 / 还原 =====================

    private fun startRepair() {
        val ch = channel ?: return notice("调试通道未连接")
        if (busy) return
        busy = true
        buildActionGrid()
        showProgress()

        executor.execute {
            try {
                if (target.isAerial) {
                    rememberHomeBeforeTakeover(ch)
                    val report = AerialRunner(
                        adb = ch,
                        onLog = { line -> log(line) },
                        onStep = { label, percent -> updateProgress(label, percent) }
                    ).repair(settings.keepAdb, settings.ownScreensaver, settings.useGuard)
                    busy = false
                    buildActionGrid()
                    bringToolboxToFront()
                    showDone(report.title, report.details, report.ok)
                } else {
                    val report = CleanTvRunner(
                        adb = ch,
                        onLog = { line -> log(line) },
                        onStep = { label, percent -> updateProgress(label, percent) }
                    ).repair(
                        settings.cytStockLauncher,
                        settings.cytInputKey,
                        settings.cytHomeKey,
                        settings.ownScreensaver
                    )
                    installedVersion = targetInstalledVersion(ch)
                    busy = false
                    renderInfo()
                    buildActionGrid()
                    bringToolboxToFront()
                    showDone(report.title, report.details, report.ok)
                }
            } catch (t: Throwable) {
                busy = false
                buildActionGrid()
                showDone("处理中断", listOf(t.message ?: t.javaClass.simpleName), ok = false)
            }
        }
    }

    private fun startVerify() {
        val ch = channel ?: return notice("调试通道未连接")
        if (busy) return
        busy = true
        buildActionGrid()
        showProgress()

        executor.execute {
            try {
                updateProgress("按一次主页键并观察前台窗口…", 30)
                if (target.isAerial) {
                    val checks = AerialRunner(
                        adb = ch,
                        onLog = { line -> log(line) },
                        onStep = { _, _ -> }
                    ).verify()
                    // 上游的结论：只有「按主页键进入 Aerial」能证明接管成功，其余三项仅供参考
                    val decisive = checks.lastOrNull()?.ok == true
                    val lines = checks.map { (if (it.ok) "✓ " else "✗ ") + it.label + "：" + it.detail }
                    busy = false
                    buildActionGrid()
                    bringToolboxToFront()
                    showDone(
                        if (decisive) "验收通过：主页键进入 Aerial" else "验收未通过",
                        lines,
                        ok = decisive
                    )
                } else {
                    val checks = CleanTvRunner(
                        adb = ch,
                        onLog = { line -> log(line) },
                        onStep = { _, _ -> }
                    ).verify()
                    // 决定性项是「主页归 CleanYourTV」，其余是参考
                    val decisive = checks.firstOrNull { it.label.contains("主页") }?.ok == true
                    val lines = checks.map { (if (it.ok) "✓ " else "✗ ") + it.label + "：" + it.detail }
                    busy = false
                    buildActionGrid()
                    bringToolboxToFront()
                    showDone(
                        if (decisive) "验收通过：主页归 CleanYourTV" else "验收未通过",
                        lines,
                        ok = decisive
                    )
                }
            } catch (t: Throwable) {
                busy = false
                buildActionGrid()
                showDone("验收中断", listOf(t.message ?: t.javaClass.simpleName), ok = false)
            }
        }
    }

    private fun showRestoreConfirm() {
        ui {
            blockProgress.visibility = View.GONE
            blockDone.visibility = View.GONE
            blockConfirm.visibility = View.VISIBLE
            confirmTitle.text = "还原桌面"
            confirmDetail.text = buildString {
                if (target.isAerial) {
                    append("会撤销授权、取消默认桌面、关掉桌面守护与屏保接管。\n")
                    append("电视上原来的桌面一直保留着，随时能切回去。\n")
                    append("「还原并卸载」会连 Aerial 一起卸掉。")
                } else {
                    append("会重新启用被停用的原桌面、把主页还给它，并关掉按键服务与屏保接管。\n")
                    append("这一步很重要：原桌面如果没恢复，电视会没有主页可用。\n")
                    append("「还原并卸载」会连 CleanYourTV 一起卸掉（桌面布局与主题设置一并清除）。")
                }
            }
            showModal(cancelable = true)
        }
    }

    /**
     * 记下接管之前占着主页的组件。
     *
     * Android 8/9 上没有 role 服务，`cmd role remove-role-holder` 是空操作 ——
     * 还原时只能靠这条记录，用 set-home-activity 把主页显式还给原来的桌面。
     */
    private fun rememberHomeBeforeTakeover(ch: AdbChannel) {
        runCatching {
            val output = ch.shell(
                "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
            )
            val component = output.lineSequence()
                .map { it.trim() }
                .lastOrNull { it.contains("/") }
                .orEmpty()
            if (component.isNotBlank() && !component.startsWith("${AerialSpec.PKG}/")) {
                settings.aerialPrevHome = component
            }
        }
    }

    private fun startRestore(uninstall: Boolean) {
        val ch = channel ?: return notice("调试通道未连接")
        if (busy) return
        busy = true
        buildActionGrid()
        showProgress()

        executor.execute {
            try {
                if (target.isAerial) {
                    val report = AerialRunner(
                        adb = ch,
                        onLog = { line -> log(line) },
                        onStep = { label, percent -> updateProgress(label, percent) }
                    ).restore(uninstall, settings.ownScreensaver, settings.aerialPrevHome)
                    if (uninstall) settings.aerialPrevHome = ""
                    installedVersion = targetInstalledVersion(ch)
                    busy = false
                    renderInfo()
                    buildActionGrid()
                    showDone(report.title, report.details, report.ok)
                } else {
                    val report = CleanTvRunner(
                        adb = ch,
                        onLog = { line -> log(line) },
                        onStep = { label, percent -> updateProgress(label, percent) }
                    ).restore(uninstall, settings.cytStockLauncher)
                    installedVersion = targetInstalledVersion(ch)
                    if (uninstall) settings.cytStockLauncher = ""
                    busy = false
                    renderInfo()
                    buildActionGrid()
                    showDone(report.title, report.details, report.ok)
                }
            } catch (t: Throwable) {
                busy = false
                buildActionGrid()
                showDone("处理中断", listOf(t.message ?: t.javaClass.simpleName), ok = false)
            }
        }
    }

    // ===================== 动作：开关 =====================

    private fun toggleFlavor() {
        settings.useGuard = !settings.useGuard
        log("安装通道：${if (settings.useGuard) "guard 版" else "标准版"}（下次安装生效）")
        buildActionGrid()
    }

    private fun toggleKeepAdb() {
        settings.keepAdb = !settings.keepAdb
        log("ADB 常开：${if (settings.keepAdb) "开" else "关"}")
        buildActionGrid()
        if (settings.keepAdb) AdbKeepAlive.reenableAdb(this)
        applyQuietly { ch ->
            // 连着通道时就顺手把权限也补上，确保开机恢复能生效
            if (settings.keepAdb) AdbKeepAlive.ensurePermissionGranted(ch)
            ch.shell(AerialSpec.configBroadcast("--ez keep_adb ${settings.keepAdb}"))
        }
    }

    /** 注册亮屏 / 解锁广播，待机唤醒后把 ADB 重新打开（小米待机后会关 ADB） */
    private fun registerScreenOn() {
        if (screenOnReceiver != null) return
        val r = AdbKeepAlive.screenOnReceiver()
        screenOnReceiver = r
        registerReceiver(r, IntentFilter(Intent.ACTION_SCREEN_ON).apply {
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }

    private fun unregisterScreenOn() {
        screenOnReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenOnReceiver = null
    }

    private fun toggleScreensaver() {
        settings.ownScreensaver = !settings.ownScreensaver
        log("屏保接管：${if (settings.ownScreensaver) "开" else "关"}")
        buildActionGrid()
        applyQuietly { ch ->
            if (target.isAerial) {
                val runnerLocal = AerialRunner(ch, { line -> log(line) }, { _, _ -> })
                log(runnerLocal.updateScreensaver(settings.ownScreensaver))
            } else {
                val command = if (settings.ownScreensaver) {
                    CleanTvSpec.screenSaverOn()
                } else {
                    CleanTvSpec.screenSaverOff()
                }
                ch.shell(command)
                log(if (settings.ownScreensaver) "系统屏保已换成 CleanYourTV 时钟" else "已清空屏保接管")
            }
        }
    }

    private fun toggleCytInputKey() {
        settings.cytInputKey = !settings.cytInputKey
        log("输入源键：${if (settings.cytInputKey) "开" else "关"}")
        buildActionGrid()
        applyQuietly { ch ->
            val service = CleanTvSpec.INPUT_KEY_SERVICE
            ch.shell(
                if (settings.cytInputKey) CleanTvSpec.enableAccessibility(service)
                else CleanTvSpec.disableAccessibility(service)
            )
            log(if (settings.cytInputKey) "遥控器输入源键已交给 CleanYourTV" else "已关闭输入源键接管")
        }
    }

    private fun toggleCytHomeKey() {
        settings.cytHomeKey = !settings.cytHomeKey
        log("Home 键兼容：${if (settings.cytHomeKey) "开" else "关"}")
        buildActionGrid()
        applyQuietly { ch ->
            val service = CleanTvSpec.HOME_KEY_SERVICE
            ch.shell(
                if (settings.cytHomeKey) CleanTvSpec.enableAccessibility(service)
                else CleanTvSpec.disableAccessibility(service)
            )
            log(if (settings.cytHomeKey) "Home 键兼容已开启" else "已关闭 Home 键兼容")
        }
    }

    /** 开关类操作：连得上就顺手应用，连不上只保存设置，不打扰用户 */
    private fun applyQuietly(block: (AdbChannel) -> Unit) {
        val ch = channel ?: return
        if (installedVersion == null) return
        executor.execute { runCatching { block(ch) } }
    }

    // ===================== 弹窗 =====================

    /**
     * 创建模态弹窗（独立窗口）。
     *
     * 这里刻意不用 `findViewById` 从 activity_main 里取控件：弹窗内容在 dialog_message.xml，
     * 是另一个窗口的视图树。抽出来之后，主界面再怎么重建宫格都碰不到弹窗，反之亦然。
     */
    private fun createOverlayDialog() {
        if (overlayDialog != null) return
        val dialog = Dialog(this, R.style.Dialog_App)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_message)
        dialog.setCanceledOnTouchOutside(false)
        // 关掉之后把焦点还给宫格（还给原来那张卡），不要留一个「没有焦点」的空窗口。
        // 必须 post 一帧：dismiss 过程中系统还会自己选一次焦点，晚一步才能盖住它
        dialog.setOnDismissListener { handler.post { rebuildActionGrid() } }

        val content = dialog.findViewById<View>(R.id.blockProgress)?.parent as? View
        content?.let { Ui.apply(it) }

        blockProgress = dialog.findViewById(R.id.blockProgress)
        blockDone = dialog.findViewById(R.id.blockDone)
        blockConfirm = dialog.findViewById(R.id.blockConfirm)
        progressBar = dialog.findViewById(R.id.progressBar)
        percentText = dialog.findViewById(R.id.tvPercent)
        stepText = dialog.findViewById(R.id.tvStep)
        doneTitle = dialog.findViewById(R.id.tvDoneTitle)
        doneDetail = dialog.findViewById(R.id.tvDoneDetail)
        confirmTitle = dialog.findViewById(R.id.tvConfirmTitle)
        confirmDetail = dialog.findViewById(R.id.tvConfirmDetail)
        okButton = dialog.findViewById(R.id.btnOk)
        restoreOnlyButton = dialog.findViewById(R.id.btnRestoreOnly)
        restoreUninstallButton = dialog.findViewById(R.id.btnRestoreUninstall)
        cancelButton = dialog.findViewById(R.id.btnCancel)

        okButton.setOnClickListener { guarded { hideOverlay() } }
        cancelButton.setOnClickListener { guarded { hideOverlay() } }
        restoreOnlyButton.setOnClickListener {
            guarded {
                hideOverlay()
                startRestore(uninstall = false)
            }
        }
        restoreUninstallButton.setOnClickListener {
            guarded {
                hideOverlay()
                startRestore(uninstall = true)
            }
        }
        overlayDialog = dialog
    }

    /** 弹窗统一入口：该关的关，该开的开，窗口尺寸钉死为内容大小 */
    private fun showModal(cancelable: Boolean) {
        val dialog = overlayDialog ?: return
        // 装完桌面后 Aerial 会抢到前台，自己这时是「已暂停」。这时候 addView 会拿到失效的窗口令牌，
        // 干脆先挂起，等 onResume 再弹，结果弹窗不会丢。
        if (!resumed) {
            pendingModal = { showModal(cancelable) }
            return
        }
        pendingModal = null
        dialog.setCancelable(cancelable)
        if (!dialog.isShowing) {
            val shown = runCatching { dialog.show() }.isSuccess
            if (!shown) {
                if (isFinishing || isDestroyed || modalRetries > MODAL_MAX_RETRIES) return
                modalRetries++
                handler.postDelayed({ showModal(cancelable) }, 300)
                return
            }
        }
        modalRetries = 0
        dialog.window?.setLayout(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        // 弹窗是新窗口，焦点要显式搬进来；迟一帧再说，避免窗口还没拿到焦点时 requestFocus 落空
        dialog.window?.decorView?.post {
            val focus = when {
                blockDone.visibility == View.VISIBLE -> okButton
                blockConfirm.visibility == View.VISIBLE -> restoreOnlyButton
                else -> null
            }
            if (focus != null && !focus.hasFocus()) {
                focus.requestFocus()
                // 极少数固件上第一次会失败（窗口尚未获得焦点），补一次
                focus.postDelayed({ if (!focus.hasFocus()) focus.requestFocus() }, 150)
            }
        }
    }

    private fun showProgress() {
        ui {
            blockProgress.visibility = View.VISIBLE
            blockDone.visibility = View.GONE
            blockConfirm.visibility = View.GONE
            progressBar.progress = 0
            percentText.text = getString(R.string.percent_zero)
            stepText.text = getString(R.string.preparing)
            // 进度中不可取消：这段时间的背景既不该响应按键，也不该被误关
            showModal(cancelable = false)
        }
    }

    private fun updateProgress(label: String, percent: Int) {
        handler.post {
            val value = percent.coerceIn(0, 100)
            progressBar.progress = value
            percentText.text = "$value%"
            stepText.text = label
        }
    }

    private fun showDone(title: String, details: List<String>, ok: Boolean) {
        ui {
            blockProgress.visibility = View.GONE
            blockConfirm.visibility = View.GONE
            blockDone.visibility = View.VISIBLE
            doneTitle.text = title
            doneTitle.setTextColor(resources.getColor(if (ok) R.color.ok else R.color.warn))
            doneDetail.text = details.filter { it.isNotBlank() }
                .take(12)
                .joinToString("\n") { "· $it" }
            showModal(cancelable = true)
        }
        Log.i(TAG, "结果：$title ${details.joinToString(" | ")}")
    }

    private fun hideOverlay() {
        ui {
            val dialog = overlayDialog ?: return@ui
            // 进度中不许关：关掉就看不到步骤了，而命令还在后台跑
            if (busy && blockProgress.visibility == View.VISIBLE) return@ui
            runCatching { dialog.dismiss() }
        }
    }

    private fun notice(message: String) {
        showDone(message, emptyList(), ok = false)
    }

    // ===================== 界面辅助 =====================

    private fun renderInfo() = renderInfoBar()

    /**
     * 信息栏一行：● 状态 · 设备 · 桌面版本。
     *
     * 原来是三块并排面板（设备 / 桌面状态 / 内置离线包）各占 1/3 宽，
     * 说的是同一件事的三个字段，却占掉首屏 58dp，还让「内置 v1.30」在页面上出现了三次。
     * 现在合成一行：状态点按连接结果变色，其余两段用次级色。
     */
    @Suppress("DEPRECATION")
    private fun renderInfoBar() {
        handler.post {
            val primary = resources.getColor(R.color.text_primary)
            val secondary = resources.getColor(R.color.text_secondary)
            val dot = when (stateState) {
                STATE_OK -> resources.getColor(R.color.ok)
                STATE_WARN -> resources.getColor(R.color.warn)
                else -> resources.getColor(R.color.text_tertiary)
            }
            val sb = SpannableStringBuilder()
            sb.append("●")
            sb.setSpan(ForegroundColorSpan(dot), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" ")
            var from = sb.length
            sb.append(stateLabel.ifBlank { getString(R.string.not_connected) })
            sb.setSpan(
                ForegroundColorSpan(primary), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            val part = { text: String ->
                if (text.isNotBlank()) {
                    sb.append("   ·   ")
                    from = sb.length
                    sb.append(text)
                    sb.setSpan(
                        ForegroundColorSpan(secondary), from, sb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            part(deviceLabel)
            if (channel != null) {
                val installed = installedVersion
                part(
                    if (installed == null) {
                        getString(R.string.desktop_not_installed)
                    } else {
                        getString(R.string.desktop_installed, installed.ifBlank { "—" })
                    }
                )
            }
            infoText.text = sb
        }
    }

    /** 状态：写进信息栏的第一个字段（原来是一个独立的胶囊控件） */
    private fun status(text: String, state: Int = STATE_NONE) {
        stateLabel = text
        stateState = state
        renderInfoBar()
    }
    private fun log(line: String) {
        Log.i(TAG, line)
        handler.post {
            logLines.addLast(line)
            while (logLines.size > 2) logLines.removeFirst()
            logText.text = logLines.joinToString("\n")
        }
    }

    /**
     * 装完 / 验收完 Aerial 会跑到前台，把工具箱拉回来，用户才看得到结果弹窗。
     * 走本机 shell 的 am start，绕过「后台应用不能启动 Activity」的系统限制。
     */
    private fun bringToolboxToFront() {
        val ch = channel ?: return
        runCatching { ch.shell("am start -n $packageName/.MainActivity") }
    }

    /** 安装包落地位置统一由 ApkStore 决定（公共下载目录 → 应用外部目录） */

    private fun requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.P) {
            val granted = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE)
            }
        }
    }

    private companion object {
        const val TAG = "AerialToolbox"

        /** 处理对象就是本机 */
        const val TARGET_HOST = "127.0.0.1"
        const val TARGET_PORT = 5555
        const val CONNECT_TIMEOUT_MS = 5000

        const val KEYS_DIR = "keys"
        const val FOCUS_SCALE = 1.03f

        /** 遥控器连击闸门：600ms 内的第二下确认键直接丢掉 */
        const val ACTION_DEBOUNCE_MS = 600L

        /** 弹窗最多重试这么多次（每次 300ms），再不行就放弃，避免死循环 */
        const val MODAL_MAX_RETRIES = 20

        /** 操作卡片列数：3 列更像 tvOS 的 app 宫格 */
        const val GRID_COLUMNS = 3

        /** 「更多功能」弹窗的列数：3 列 × 2 行整块放下，720p 上也不用滚动 */
        const val MORE_COLUMNS = 3

        const val STATE_NONE = 0
        const val STATE_OK = 1
        const val STATE_WARN = 2
        const val REQUEST_STORAGE = 1001
    }
}

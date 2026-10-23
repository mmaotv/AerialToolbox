package com.miaomiaotv.aerial.core

/**
 * 主页归属（HOME role + 默认桌面组件）的读取与归还。
 *
 * 为什么单独抽一层：`cmd package set-home-activity` 只写「默认桌面」，
 * 它会顺手把 HOME role 从原桌面摘下来，**但不会转给新桌面，更不会在卸载时还回去**。
 *
 * Android 14 / TrebleDroid 真机实测的三步：
 *
 *   1. 设置前        `cmd role get-role-holders` → com.android.launcher3，isDefault=true
 *   2. 设成新桌面后  `cmd role get-role-holders` → 空，isDefault=true（靠 override 顶着）
 *   3. 卸载新桌面后  `cmd role get-role-holders` → 空，isDefault=false，
 *                    resolve-activity 回 `android/...ResolverActivity`
 *
 * 第 3 步就是用户看到的「主页键短按没反应 / 回不到桌面」——
 * 系统根本没有默认桌面，按主页要么弹「打开方式」选择框，要么什么都不做。
 *
 * 所以归还必须是两步：先 `cmd role add-role-holder` 把 role 还给原桌面，
 * 再 `set-home-activity` 写默认桌面，最后复查 `isDefault=true`。
 * 只做后者的话 role 一直是空的，重启后 override 一失效就原形毕露。
 */
object HomeRoles {

    const val ROLE = "android.app.role.HOME"

    const val RESOLVE =
        "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"

    private const val QUERY =
        "cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME"

    /** 系统兜底选择器的标记：解析到它等于「没有默认桌面」 */
    private const val RESOLVER = "ResolverActivity"

    /** 系统自身与兜底 Home，不算「用户的桌面」 */
    private val NOT_A_DESKTOP = setOf("android", "com.android.settings", "com.android.systemui")

    data class Candidate(val pkg: String, val component: String)

    /** 系统当前解析到的默认桌面；没有默认桌面（落到选择器）时返回空串 */
    fun current(run: (String) -> String): String {
        val line = run(RESOLVE).lineSequence()
            .map { it.trim() }
            .lastOrNull { it.contains("/") && !it.startsWith("priority=") }
            .orEmpty()
        return when {
            line.endsWith(RESOLVER) -> ""
            line.substringBefore('/') in NOT_A_DESKTOP -> ""
            else -> line
        }
    }

    /** 系统是否真的有一个默认桌面 */
    fun hasDefault(run: (String) -> String): Boolean = run(RESOLVE).contains("isDefault=true")

    /** 当前 HOME role 的持有者（Android 10 以下没有这个服务，返回空表） */
    fun holders(run: (String) -> String): List<String> =
        run("cmd role get-role-holders --user 0 $ROLE").lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains(".") && !it.contains(" ") && !it.startsWith("cmd:") }
            .toList()

    /** 声明了 HOME 的应用，按系统给它们的顺序 */
    fun candidates(run: (String) -> String): List<Candidate> {
        val list = mutableListOf<Candidate>()
        var name = ""
        // 每个候选后面还跟着一段 ApplicationInfo（name 是 Application 类，不是组件），
        // 不跳过它就会把 `pkg/pkg.XxxApplication` 当成可启动组件
        var inAppInfo = false
        for (raw in run(QUERY).lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("ActivityInfo") -> inAppInfo = false
                line.startsWith("ApplicationInfo") -> {
                    inAppInfo = true
                    name = ""
                }
                inAppInfo -> Unit
                line.startsWith("name=") -> name = line.removePrefix("name=").trim()
                line.startsWith("packageName=") -> {
                    val pkg = line.removePrefix("packageName=").trim()
                    if (name.isNotBlank() && pkg.isNotBlank() && pkg !in NOT_A_DESKTOP) {
                        list += Candidate(pkg, "$pkg/$name")
                    }
                    name = ""
                }
            }
        }
        return list.distinctBy { it.component }
    }

    /**
     * 把主页还给原桌面，并把 HOME role 一起补回去。
     *
     * @param preferred 接管前记下的组件；为空时退回「role 持有者 → 候选列表第一个」
     * @param exclude   不能被选中当原桌面的包（通常是本工具装的那几个桌面）
     * @return 给用户看的一句话
     */
    fun giveBack(
        run: (String) -> String,
        log: (String) -> Unit,
        preferred: String,
        exclude: Set<String>
    ): String {
        val target = pick(run, preferred, exclude)
            ?: return "没找到可以归还的原桌面，请按主页键在弹窗里手动选择"

        if (apply(run, log, target)) return "主页已还给 ${target.pkg}"

        // 首选没成：按候选顺序再试，宁可多试几个也不要把用户留在一个没有桌面的状态
        for (other in candidates(run)) {
            if (other.pkg in exclude || other.component == target.component) continue
            if (apply(run, log, other)) return "主页已还给 ${other.pkg}"
        }
        return "主页没能自动还原（系统当前指向${current(run).ifBlank { "桌面选择器" }}），请按主页键手动选择"
    }

    private fun pick(run: (String) -> String, preferred: String, exclude: Set<String>): Candidate? {
        val p = preferred.trim()
        val pkg = p.substringBefore('/')
        if (pkg.isNotBlank() && pkg !in exclude && pkg !in NOT_A_DESKTOP) {
            // 记录可能只存了包名（CleanYourTV 那条链路记的就是包名），补上组件
            if (p.contains("/")) return Candidate(pkg, p)
            candidates(run).firstOrNull { it.pkg == pkg }?.let { return it }
        }
        val all = candidates(run)
        // 记录丢了（老版本从没记过）时，role 持有者是最准的原桌面
        holders(run).firstOrNull { it !in exclude }
            ?.let { holder -> all.firstOrNull { it.pkg == holder }?.let { return it } }
        return all.firstOrNull { it.pkg !in exclude }
    }

    private fun apply(run: (String) -> String, log: (String) -> Unit, c: Candidate): Boolean {
        val role = run("cmd role add-role-holder --user 0 $ROLE ${c.pkg}; echo RC:\$?")
        val set = run("cmd package set-home-activity --user 0 ${c.component}; echo RC:\$?")
        val rc = Regex("RC:(-?\\d+)").findAll(set).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
        val ok = hasDefault(run) && current(run).startsWith("${c.pkg}/")
        log(
            "归还主页：${c.component}（role ${if (role.contains("RC:0")) "已恢复" else "不支持"}，" +
                "set-home rc=${rc ?: "?"}，复查 ${if (ok) "通过" else "未通过"}）"
        )
        return ok
    }
}

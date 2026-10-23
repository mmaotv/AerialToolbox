package com.miaomiaotv.aerial.core

/**
 * Release 正文是 markdown，电视上没条件渲染，做一次轻量清洗。
 *
 * 「桌面更新」页与「关于」页共用这一份，保证同一段 Release 说明在两处显示一致：
 * 去掉标题井号、粗体星号、代码块（安装命令在电视上没有意义），列表项统一成「· 」。
 */
object Changelog {

    fun pretty(markdown: String?, fallback: String = "该版本没有附带更新说明。"): String {
        val body = markdown.orEmpty().trim()
        if (body.isBlank()) return fallback
        var inCodeBlock = false
        val cleaned = body.lines().mapNotNull { raw ->
            var line = raw.trim()
            if (line.startsWith("```")) {
                inCodeBlock = !inCodeBlock
                return@mapNotNull null
            }
            if (inCodeBlock) return@mapNotNull null
            line = line.replace(Regex("^#{1,6}\\s*"), "")
            line = line.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            line = line.replace(Regex("__(.+?)__"), "$1")
            line = line.replace(Regex("`([^`]+)`"), "$1")
            line = line.replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            line = line.replace(Regex("^[-*+]\\s+"), "· ")
            line = line.replace(Regex("^>\\s*"), "")
            if (line == "---" || line == "***") "" else line
        }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        return cleaned.ifBlank { fallback }
    }
}

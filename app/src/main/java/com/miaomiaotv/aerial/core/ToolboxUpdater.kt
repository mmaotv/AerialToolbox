package com.miaomiaotv.aerial.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 工具箱自己的发行版检查。
 *
 * 和 [ReleaseChecker]（查的是 Aerial 桌面）分开：这边看的是本仓库的 Release，
 * 只关心一个资产——`AerialToolbox-<version>.apk`。
 * 没有资产就当成「没有可用更新」，避免让用户下到一个只带源码的 tag。
 *
 * 本仓库还托管着代构建的 `cyt-v*` 发行版，它比工具箱的版本号更晚，
 * `releases/latest` 会拿到它 —— 所以这里扫列表，挑第一条**真的带工具箱安装包**的发行版。
 */
object ToolboxUpdater {

    private const val USER_AGENT = "AerialToolbox-Updater/1.0 (Android)"

    /** 取最新发行版；网络不通或没有带 APK 的发行版时返回 null */
    fun latest(): RemoteRelease? {
        val body = runCatching { httpGet(ToolboxInfo.API_LIST) }.getOrNull() ?: return null
        val text = body.trim()
        val candidates: List<JSONObject> = runCatching {
            if (text.startsWith("[")) {
                val array = JSONArray(text)
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            } else {
                listOf(JSONObject(text))
            }
        }.getOrNull() ?: return null

        for (json in candidates) {
            val apk = apkUrl(json) ?: continue
            val tag = json.optString("tag_name")
            return RemoteRelease(
                version = VersionCompare.normalize(tag),
                tag = tag,
                standardUrl = apk,
                guardUrl = apk,
                source = "GitHub",
                changelog = json.optString("body"),
                publishedAt = json.optString("published_at").take(10),
                pageUrl = json.optString("html_url").ifBlank { ToolboxInfo.RELEASE_PAGE }
            )
        }
        return null
    }

    /**
     * 在 Release 资产里找**电视端**的安装包。
     *
     * 完整匹配 `AerialToolbox-<版本>.apk`，不匹配的前缀一律跳过 ——
     * 手机端的资产名是 `AerialToolbox-Phone-<版本>.apk`，前缀一样但不是电视端的包，
     * 认错了会让用户把手机端包装到电视上。
     */
    private fun apkUrl(json: JSONObject): String? {
        val assets = json.optJSONArray("assets") ?: return null
        for (index in 0 until assets.length()) {
            val item = assets.optJSONObject(index) ?: continue
            val name = item.optString("name")
            if (ToolboxInfo.TV_APK_PATTERN.matches(name)) {
                val url = item.optString("browser_download_url")
                if (url.isNotBlank()) return url
            }
        }
        return null
    }

    /** 下载安装包：与桌面更新共用同一份实现（临时 .part → 校验非空 → 改名） */
    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) =
        ReleaseChecker.download(url, dest, onProgress)

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { connection.disconnect() }
        }
    }
}

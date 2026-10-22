package com.miaomiaotv.aerial.core

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
 */
object ToolboxUpdater {

    private const val USER_AGENT = "AerialToolbox-Updater/1.0 (Android)"

    /** 取最新发行版；网络不通或该 Release 没挂 APK 时返回 null */
    fun latest(): RemoteRelease? {
        val body = runCatching { httpGet(ToolboxInfo.API_LATEST) }.getOrNull() ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val tag = json.optString("tag_name")
        val apk = apkUrl(json) ?: return null
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

    /** 在 Release 资产里找安装包，sha256 之类的附属文件跳过 */
    private fun apkUrl(json: JSONObject): String? {
        val assets = json.optJSONArray("assets") ?: return null
        for (index in 0 until assets.length()) {
            val item = assets.optJSONObject(index) ?: continue
            val name = item.optString("name")
            if (name.startsWith(ToolboxInfo.APK_PREFIX) && name.endsWith(".apk")) {
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

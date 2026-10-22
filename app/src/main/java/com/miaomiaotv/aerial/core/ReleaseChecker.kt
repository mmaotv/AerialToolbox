package com.miaomiaotv.aerial.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本号比较：把 "1.22" / "v1.22" / "1.22-guard" 统一拆成数字段逐个比较。
 * 只用于「有没有新版」的判断，取不到数字时返回 0，不会误判成有更新。
 */
object VersionCompare {

    fun parse(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        val digits = raw.trim().removePrefix("v").removePrefix("V")
        return Regex("\\d+")
            .findAll(digits)
            .mapNotNull { it.value.toIntOrNull() }
            .take(4)
            .toList()
    }

    /** a 比 b 新返回正数，旧返回负数，相同返回 0 */
    fun compare(a: String?, b: String?): Int {
        val left = parse(a)
        val right = parse(b)
        if (left.isEmpty() || right.isEmpty()) return 0
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val x = left.getOrElse(i) { 0 }
            val y = right.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    /** 去掉版本号里可能带的 v 前缀与空格，便于展示与比较 */
    fun normalize(raw: String?): String = raw.orEmpty().trim().removePrefix("v").removePrefix("V")
}

/** 从 GitHub Release 拿到的信息 */
data class RemoteRelease(
    /** 归一化后的版本号，例如 "1.22"；镜像兜底时可能为空 */
    val version: String,
    /** 原始 tag，例如 "v1.22" */
    val tag: String,
    val standardUrl: String,
    val guardUrl: String,
    val source: String,
    /** 更新日志（GitHub Release 正文，markdown） */
    val changelog: String = "",
    /** 发布时间，形如 "2026-10-21" */
    val publishedAt: String = "",
    /** Release 页面地址，拿不到日志时提供给用户手动查看 */
    val pageUrl: String = AerialSpec.RELEASE_PAGE
) {
    fun urlFor(useGuard: Boolean): String = if (useGuard) guardUrl else standardUrl
}

/**
 * Aerial 发行版检查与下载。
 *
 * 走 GitHub 官方 API 拿最新 Release 与两个 APK 的直链；
 * 国内网络打不开 GitHub 时退回官网镜像（版本号未知，但拿到的一定是官网最新包）。
 */
object ReleaseChecker {

    private const val USER_AGENT = "AerialToolbox/1.0 (Android)"

    /** 取最新发行版；两个来源都失败时返回 null */
    fun latest(): RemoteRelease? {
        fetchFromGitHub()?.let { return it }
        return mirrorFallback()
    }

    /**
     * 通用版本查询：给一个 GitHub Release 接口与资产名前缀，返回第一个匹配的 APK。
     *
     * CleanYourTV 用它查**本仓库**的 `cyt-v*`（上游不发二进制，只有我们自己代构建的包）。
     * 这里传的是 `releases?per_page=N` 列表接口 —— 本仓库同时放着工具箱自己的发行版，
     * `releases/latest` 会拿到工具箱而不是 CleanYourTV，所以必须按 tag 前缀筛。
     *
     * 不做镜像兜底：拿不到就让界面说清楚，总比装一个来路不明的包好。
     */
    fun latestFor(
        api: String,
        assetPrefix: String,
        pageUrl: String,
        tagPrefix: String? = null
    ): RemoteRelease? = runCatching {
        val body = httpGet(api).trim()
        val json = if (body.startsWith("[")) {
            val array = JSONArray(body)
            var picked: JSONObject? = null
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val tag = item.optString("tag_name")
                if (tagPrefix == null || tag.startsWith(tagPrefix)) {
                    picked = item
                    break
                }
            }
            picked ?: return@runCatching null
        } else {
            JSONObject(body)
        }
        val tag = json.optString("tag_name")
        val assets = json.optJSONArray("assets")
        var apk: String? = null
        var apkName = ""
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val item = assets.optJSONObject(i) ?: continue
                val name = item.optString("name")
                if (name.startsWith(assetPrefix) && name.endsWith(".apk")) {
                    apk = item.optString("browser_download_url")
                    apkName = name
                }
            }
        }
        val url = apk
        if (url.isNullOrBlank()) return@runCatching null
        RemoteRelease(
            // tag 形如 cyt-v2.6.0：取最后一个 v 之后的部分
            version = VersionCompare.normalize(if (tag.contains('v')) tag.substringAfterLast('v') else tag),
            tag = tag,
            standardUrl = url,
            guardUrl = url,
            source = "GitHub · $apkName",
            changelog = json.optString("body"),
            publishedAt = json.optString("published_at").take(10),
            pageUrl = json.optString("html_url").ifBlank { pageUrl }
        )
    }.getOrNull()

    private fun fetchFromGitHub(): RemoteRelease? = runCatching {
        val body = httpGet(AerialSpec.API_LATEST)
        val json = JSONObject(body)
        val tag = json.optString("tag_name")
        val assets = json.optJSONArray("assets")
        var standard: String? = null
        var guard: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val item = assets.optJSONObject(i) ?: continue
                val name = item.optString("name")
                val url = item.optString("browser_download_url")
                when (name) {
                    AerialSpec.ASSET_STANDARD -> standard = url
                    AerialSpec.ASSET_GUARD -> guard = url
                }
            }
        }
        val standardUrl = standard ?: "${AerialSpec.MIRROR_BASE}${AerialSpec.ASSET_STANDARD}"
        val guardUrl = guard ?: "${AerialSpec.MIRROR_BASE}${AerialSpec.ASSET_GUARD}"
        RemoteRelease(
            version = VersionCompare.normalize(tag),
            tag = tag,
            standardUrl = standardUrl,
            guardUrl = guardUrl,
            source = "GitHub",
            changelog = json.optString("body"),
            publishedAt = json.optString("published_at").take(10),
            pageUrl = json.optString("html_url").ifBlank { AerialSpec.RELEASE_PAGE }
        )
    }.getOrNull()

    /**
     * 官网镜像兜底。
     * 官网只放「当前最新」的两个 APK，没有版本号接口，因此 version 记为空，
     * 界面会提示「拿到的是官网最新包」，安装仍然继续。
     */
    private fun mirrorFallback(): RemoteRelease? = runCatching {
        val standard = "${AerialSpec.MIRROR_BASE}${AerialSpec.ASSET_STANDARD}"
        // 探测一下镜像是否可用（只要拿到 200/302 就算可用）
        val code = headCode(standard)
        if (code !in 200..399) return@runCatching null
        RemoteRelease(
            version = "",
            tag = "",
            standardUrl = standard,
            guardUrl = "${AerialSpec.MIRROR_BASE}${AerialSpec.ASSET_GUARD}",
            source = "官网镜像"
        )
    }.getOrNull()

    /**
     * 下载安装包到指定文件。
     *
     * @param onProgress 已下载字节数 / 总字节数（总长度未知时为 -1）
     */
    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile, dest.name + ".part")
        val connection = openConnection(url)
        try {
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(copied, total)
                    }
                    output.flush()
                }
            }
            if (temp.length() <= 0L) throw IOException("下载的安装包为空")
            if (dest.exists()) dest.delete()
            if (!temp.renameTo(dest)) {
                temp.copyTo(dest, overwrite = true)
                temp.delete()
            }
        } finally {
            runCatching { connection.disconnect() }
            if (temp.exists() && !dest.exists()) temp.delete()
        }
    }

    /** 本地已有的安装包是否可以复用（同版本、大小正常） */
    fun isUsableCache(file: File, expectedMinBytes: Long = 1024L * 1024L): Boolean =
        file.exists() && file.length() > expectedMinBytes

    /**
     * 先走 GitHub 直链，失败再走官网镜像。
     *
     * GitHub 的 Release 资源地址会 302 到 objects.githubusercontent.com，
     * 国内网络经常只能解析前者，因此两条腿都要有。
     *
     * @return 实际下载成功的地址
     */
    fun downloadWithFallback(
        url: String,
        useGuard: Boolean,
        dest: File,
        onProgress: (Long, Long) -> Unit
    ): String = try {
        download(url, dest, onProgress)
        url
    } catch (first: Throwable) {
        val mirror = AerialSpec.MIRROR_BASE + if (useGuard) AerialSpec.ASSET_GUARD else AerialSpec.ASSET_STANDARD
        if (url == mirror) throw first
        download(mirror, dest, onProgress)
        mirror
    }

    private fun httpGet(url: String): String {
        val connection = openConnection(url)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun headCode(url: String): Int {
        val connection = openConnection(url, head = true)
        return try {
            connection.responseCode
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun openConnection(url: String, head: Boolean = false): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.requestMethod = if (head) "HEAD" else "GET"
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        return connection
    }
}

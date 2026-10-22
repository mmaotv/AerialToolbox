package com.miaomiaotv.aerial.core

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.Properties

/**
 * 内置离线包。
 *
 * 为什么要有它：电视连不上 GitHub 是常态（DNS 污染、公司网络、没有外网），
 * 而「装桌面」这一步不该被网络卡住。所以把官方 Release 的两个 APK 直接打进工具箱，
 * 一键安装默认用内置包，完全不联网；只有用户主动点「桌面更新」时才去 GitHub。
 *
 * 安装包不能留在 APK 内部直接装：负责安装的系统进程读不到应用私有目录，
 * 必须先释放到公共下载目录（shell 与安装进程都能读）。
 */
class BundledRelease(private val context: Context) {

    data class Info(
        val version: String,
        val tag: String,
        val publishedAt: String,
        val versionCode: Int
    ) {
        val available: Boolean get() = version.isNotBlank()
        val display: String get() = if (version.isBlank()) "无内置包" else "v$version"
    }

    private val prefs: Properties by lazy { loadMeta() }

    fun info(): Info = Info(
        version = prefs.getProperty("version").orEmpty().trim(),
        tag = prefs.getProperty("tag").orEmpty().trim(),
        publishedAt = prefs.getProperty("publishedAt").orEmpty().trim(),
        versionCode = prefs.getProperty("versionCode").orEmpty().trim().toIntOrNull() ?: 0
    )

    fun hasStandard(): Boolean = assetName(useGuard = false).isNotBlank()
    fun hasGuard(): Boolean = assetName(useGuard = true).isNotBlank()

    /**
     * 把内置包释放到 shell 能读的目录，返回可直接安装的文件。
     *
     * @param useGuard true 取 guard 版（不带 HOME 声明，靠无障碍服务接管）
     * @return 没有对应的内置包（或被裁掉）时返回 null，调用方应回退到联网下载
     */
    fun extract(useGuard: Boolean): File? {
        // 想要 guard 版但内置包里没有时，退回标准版而不是直接失败
        val actualGuard = when {
            useGuard && hasGuard() -> true
            !useGuard && hasStandard() -> false
            hasStandard() -> false
            hasGuard() -> true
            else -> return null
        }
        val name = if (actualGuard) "aerial-guard.apk" else "aerial-standard.apk"
        val version = info().version.ifBlank { "bundled" }
        val dest = File(
            ApkStore.stagingDir(context),
            "aerial-${if (actualGuard) "guard" else "standard"}-$version.apk"
        )
        if (dest.exists() && dest.length() > MIN_APK_BYTES) return dest

        return runCatching {
            dest.parentFile?.mkdirs()
            val temp = File(dest.parentFile, dest.name + ".part")
            context.assets.open("bundled/$name").use { input ->
                temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            if (temp.length() <= MIN_APK_BYTES) {
                temp.delete()
                return null
            }
            if (dest.exists()) dest.delete()
            if (!temp.renameTo(dest)) {
                temp.copyTo(dest, overwrite = true)
                temp.delete()
            }
            dest
        }.getOrNull()
    }

    private fun assetName(useGuard: Boolean): String = runCatching {
        context.assets.open("bundled/${if (useGuard) "aerial-guard.apk" else "aerial-standard.apk"}").close()
        if (useGuard) "aerial-guard.apk" else "aerial-standard.apk"
    }.getOrDefault("")

    private fun loadMeta(): Properties {
        val properties = Properties()
        runCatching {
            context.assets.open("bundled/meta.properties").use { properties.load(it) }
        }
        return properties
    }

    private companion object {
        /** 小于这个体积一定不是完整的 APK（内置包约 2.4 MB） */
        const val MIN_APK_BYTES = 1024L * 1024L
    }
}

/** 安装包落地位置的统一约定：优先公共下载目录，其次应用外部目录 */
object ApkStore {

    fun stagingDir(context: Context): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        runCatching { downloads.mkdirs() }
        if (downloads.exists() && downloads.canWrite()) return downloads
        val external = context.getExternalFilesDir(null)
        if (external != null) {
            runCatching { external.mkdirs() }
            if (external.canWrite()) return external
        }
        return context.filesDir
    }
}

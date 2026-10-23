package com.miaomiaotv.aerial.apk

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * 极简的文件提供者，只为「交给系统安装器安装」服务。
 *
 * 为什么不用 androidx 的 FileProvider：整个项目坚持零第三方依赖（体积、老电视兼容都受益），
 * 而这里需要的功能只有「把一个只读文件描述符给安装器」这一件事。
 *
 * 安全约束：
 *  - 只暴露 cacheDir/install 这一个目录，且文件名不允许带路径分隔符，杜绝目录穿越；
 *  - provider 不导出（exported=false），安装器只能通过一次性 URI 授权访问。
 */
class ApkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val file = resolve(uri) ?: return null
        // projection 是 Array<out String>，直接 toTypedArray() 不成立，这里手动转成可写数组
        val columns: Array<String> = projection?.let { source -> Array(source.size) { source[it] } }
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        cursor.addRow(
            columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            }
        )
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = resolve(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private fun resolve(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.isBlank() || name.contains('/') || name.contains("..")) return null
        val dir = installDir(context ?: return null)
        val file = File(dir, name)
        return if (file.exists() && file.isFile) file else null
    }

    companion object {

        private const val AUTHORITY_SUFFIX = ".apk"

        /** 把安装包复制到缓存目录并返回可交给安装器的 URI */
        fun uriFor(context: Context, apk: File): Uri {
            val dir = installDir(context)
            runCatching { dir.mkdirs() }
            val target = File(dir, apk.name)
            if (!target.exists() || target.length() != apk.length()) {
                apk.copyTo(target, overwrite = true)
            }
            return Uri.parse("content://${context.packageName}$AUTHORITY_SUFFIX/${Uri.encode(target.name)}")
        }

        private fun installDir(context: Context): File = File(context.cacheDir, "install")
    }
}

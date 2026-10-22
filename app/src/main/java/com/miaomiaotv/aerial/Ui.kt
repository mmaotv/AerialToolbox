package com.miaomiaotv.aerial

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * 字体统一：与上游 Aerial 用同一份 Inter 可变字体（assets/fonts/inter.ttf）。
 *
 * 上游在 Compose 里按 14pt / 32pt 两个光学尺寸取字重，这里退化到 View 体系：
 * 普通文字 W500，XML 里标了 bold 的走 W600，正好对上它的 Body 与 Title 两档。
 * API 26 以下不支持可变轴，退回系统字体 + 粗体，版式不受影响。
 */
object Ui {

    private const val FONT_PATH = "fonts/inter.ttf"

    private var medium: Typeface? = null
    private var semibold: Typeface? = null
    private var loaded = false

    /** 递归套用字体，动态创建出来的卡片也要单独调用一次 */
    fun apply(root: View?) {
        root ?: return
        ensureLoaded(root.context)
        walk(root)
    }

    private fun walk(view: View) {
        if (view is TextView) {
            val bold = view.typeface?.isBold == true
            val target = if (bold) semibold else medium
            if (target != null) view.setTypeface(target)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val assets = context.assets
        val base = runCatching { Typeface.createFromAsset(assets, FONT_PATH) }.getOrNull() ?: return
        medium = variation(context, 500) ?: base
        semibold = variation(context, 600) ?: Typeface.create(base, Typeface.BOLD)
    }

    /** 只有 Android 8.0+ 才能按轴取字重，更老的电视统一用默认字面 */
    private fun variation(context: Context, weight: Int): Typeface? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return runCatching {
            Typeface.Builder(context.assets, FONT_PATH)
                .setFontVariationSettings("'wght' $weight")
                .build()
        }.getOrNull()
    }

    /**
     * 状态胶囊文字：前面带一个状态点。
     * 与 tvOS 的状态提示同一个语汇，颜色只上在圆点上，文字保持白色。
     */
    fun status(view: TextView, text: String, dotColor: Int) {
        val dot = "●  "
        view.text = SpannableString(dot + text).apply {
            setSpan(
                ForegroundColorSpan(dotColor),
                0,
                dot.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}

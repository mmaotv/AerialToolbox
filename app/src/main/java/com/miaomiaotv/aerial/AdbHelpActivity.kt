package com.miaomiaotv.aerial

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.miaomiaotv.aerial.core.AdbGuide
import com.miaomiaotv.aerial.core.Ui

/**
 * 各品牌电视 / 盒子「打开 ADB 调试」的路径页。
 *
 * 单独一屏而不是弹窗：11 条内容在 720p 上放不进一个居中的对话框，
 * 而 TV 上的滚动列表必须是「可聚焦的行」才滚得动（纯文本 ScrollView 遥控器滚不动），
 * 一屏卡片正好两边都满足。
 *
 * 入口有三处，都指向这一页：顶栏「?」、首屏引导的「全部品牌」、连不上时的失败提示。
 */
class AdbHelpActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_adb_help)
        Ui.apply(window.decorView)

        val brandList = findViewById<LinearLayout>(R.id.brandList)
        val brandScroll = findViewById<ScrollView>(R.id.svBrands)
        val backButton = findViewById<Button>(R.id.btnBack)
        backButton.setOnClickListener { finish() }

        val inflater = layoutInflater
        AdbGuide.ALL.forEach { brand ->
            val card = inflater.inflate(R.layout.item_adb_brand, brandList, false)
            Ui.apply(card)
            card.findViewById<TextView>(R.id.tvBrand).text = brand.brand
            card.findViewById<TextView>(R.id.tvSteps).text = brand.steps
            val noteView = card.findViewById<TextView>(R.id.tvNote)
            if (brand.note.isNotBlank()) {
                noteView.text = brand.note
                noteView.visibility = View.VISIBLE
            }
            // 卡片本身不触发任何动作，只为了让遥控器能选到它、把这一行滚进视野
            card.setOnClickListener { brandScroll.smoothScrollTo(0, card.top) }
            brandList.addView(card)
        }

        // 进来先把焦点放到第一张卡，遥控器立刻就能上下走
        brandList.post {
            (brandList.getChildAt(0) as? View)?.requestFocus()
        }
    }
}

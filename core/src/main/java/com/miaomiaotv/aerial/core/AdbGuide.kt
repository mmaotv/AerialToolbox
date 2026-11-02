package com.miaomiaotv.aerial.core

/**
 * 各品牌电视 / 盒子「打开 ADB 调试」的遥控器路径。
 *
 * 这些入口都藏在厂商固件的隐藏菜单里，路径随固件版本变动，没法自动化，
 * 只能把操作顺序一条条写给用户看。文案保持「设置 → …」的按键顺序，
 * 用户照着按即可；两端（电视端引导页、手机端连接页）共用这一份，
 * README 与落地页上的对照表也照这张表写，避免三处说法不一致。
 */
data class AdbBrand(
    /** 品牌 / 机型 */
    val brand: String,
    /** 遥控器操作顺序 */
    val steps: String,
    /** 例外说明，没有就留空 */
    val note: String = "",
    /** 首屏引导里单行显示用的压缩版；留空表示直接用 [steps] */
    val brief: String = "",
    /** 首屏引导里的短品牌名；留空表示直接用 [brand] */
    val shortBrand: String = ""
) {
    /** 引导页那一行：压到一条里放得下 */
    val oneLine: String get() = brief.ifBlank { steps }

    /** 引导页的品牌名：短名优先 */
    val oneLineBrand: String get() = shortBrand.ifBlank { brand }
}

object AdbGuide {

    /**
     * 全部品牌，顺序按国内电视盒子的实际保有量排：
     * 小米系（含小米盒子）排第一，原生 Android TV / 索尼排第二（海外机型与 Shield 都走这条），
     * TCL / 雷鸟第三（遥控器「上下左右」那套最容易被问到）。
     */
    val ALL: List<AdbBrand> = listOf(
        AdbBrand(
            "小米 / Redmi 电视、小米盒子",
            "设置 → 关于 → 产品型号连按 7 次 → 返回「账号与安全」→ ADB 调试 → 允许",
            "重启或待机后会被系统自动关掉，这正是「ADB 常开」要解决的问题",
            brief = "设置 → 关于 → 产品型号连按 7 次 → 账号与安全 → ADB 调试",
            shortBrand = "小米 / Redmi"
        ),
        AdbBrand(
            "索尼 BRAVIA、原生 Android TV / Google TV、NVIDIA Shield",
            "设置 → 系统 → 关于 → 版本号连按 7 次 → 返回「开发者选项」→ 打开「USB 调试」"
        ),
        AdbBrand(
            "TCL / 雷鸟 / 海尔",
            "设置 → 关于（本机信息）→ 遥控器按 上 下 左 右 → 底部出现「更多」→ 打开 ADB"
        ),
        AdbBrand(
            "海信 Android TV",
            "设置 → 关于 → 版本号连按 7 次 → 开发者选项 → 打开「USB 调试」"
        ),
        AdbBrand(
            "海信 VIDAA",
            "设置 → 声音 → 平衡 → 输入 0532 进工厂模式",
            "多数机型不开放网络调试，进不去就换用别的盒子"
        ),
        AdbBrand(
            "创维 / 酷开",
            "设置 → 本机信息 → 遥控器依次输入 上 上 下 下 左 右 左 右"
        ),
        AdbBrand(
            "华为智慧屏",
            "设置 → 关于本机 → 版本号连按 7 次 → 开发人员选项 → 打开「USB 调试」与「启用 ADB」"
        ),
        AdbBrand("长虹", "设置 → 关于 → 遥控器输入 0816"),
        AdbBrand("康佳", "设置 → 关于 → 遥控器输入 2008"),
        AdbBrand("乐视", "设置 → 关于 → 遥控器输入 2580"),
        AdbBrand(
            "天猫魔盒 / 当贝 / 外贸盒子",
            "设置 → 系统信息连按 7 次，或直接走原生 Android TV 的「版本号连按 7 次」",
            "外贸盒子多数默认开着 5555，直接填 IP 就能连"
        )
    )

    /** 电视端首屏引导里只露最常用的三条，其余走「全部品牌」页 */
    val TOP: List<AdbBrand> = ALL.take(3)
}

package com.miaomiaotv.aerial.core

/**
 * 设备操作的结果。
 *
 * 两个上游（Aerial / CleanYourTV）的安装、接管、修复、还原都返回它，
 * 抽成共享类型后，手机端等「按目标二选一」的调用处才能拿到统一的字段。
 */
data class Outcome(val ok: Boolean, val title: String, val details: List<String>)

/** 验收检查项：标签 + 是否通过 + 说明。 */
data class Check(val label: String, val ok: Boolean, val detail: String)

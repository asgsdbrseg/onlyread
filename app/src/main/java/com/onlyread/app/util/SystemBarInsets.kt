package com.onlyread.app.util

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * 0.4.31：Android 15（API 35）起，系统对 targetSdk ≥ 35 的应用**强制 edge-to-edge**，
 * 窗口内容会绘制到状态栏 / 导航栏之后，且 `window.statusBarColor` 被忽略。
 * 本项目使用普通非全屏主题，书架与文件浏览器的标题栏因此会顶进通知栏区域，
 * 与状态栏图标重叠——只在 Android 15+ 机型出现，旧机型保持传统布局，即「部分机型」。
 *
 * 这里显式消费 `systemBars()` 的上下 inset 并作用为内容根 View 的 padding：
 * 根 View 自身背景（`@color/bg`）铺满让出的状态栏区域，配合主题的
 * `windowLightStatusBar` 深色图标，视觉与传统布局一致。
 *
 * 只在 API ≥ 35 生效，避免给未强制 edge-to-edge 的旧机型凭空多加一段 padding；
 * 每次分发都基于初始 padding 累加（幂等），不会因为多次回调不断叠加。
 */
object SystemBarInsets {

    fun applyTo(root: View) {
        if (Build.VERSION.SDK_INT < 35) return
        val start = root.paddingStart
        val end = root.paddingEnd
        val top0 = root.paddingTop
        val bottom0 = root.paddingBottom
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPaddingRelative(start, top0 + bars.top, end, bottom0 + bars.bottom)
            insets
        }
        root.requestApplyInsets()
    }
}

package com.onlyread.app.util

/** 四边尺寸（View padding 或系统栏 inset），纯数据、无 Android 依赖，便于 JVM 单测。 */
data class Edges(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * 0.4.35：把系统栏 inset 叠加到 View 初始 padding 上的纯计算。
 * 抽成纯函数是为了单测两个易错点：四边都要加（横屏导航栏/刘海在侧边）、
 * 以及必须以「初始 padding」为基准（否则多次 insets 分发会不断累加留白）。
 */
object InsetsGeometry {
    fun withBars(base: Edges, bars: Edges): Edges = Edges(
        base.left + bars.left,
        base.top + bars.top,
        base.right + bars.right,
        base.bottom + bars.bottom
    )
}

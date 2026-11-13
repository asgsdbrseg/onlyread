package com.onlyread.app.util

import org.junit.Assert.*
import org.junit.Test

/**
 * 0.4.35：全局 edge-to-edge 的 inset 叠加纯计算单测（JVM，无 Android 依赖）。
 * 覆盖「四边都加」与「以初始 padding 为基准不累加」两个易错点：
 * 前者漏了横屏侧边导航栏 / 刘海，后者会让多次 insets 分发凭空叠出留白。
 */
class InsetsGeometryTest {

    @Test
    fun withBars_addsAllFourEdges() {
        val base = Edges(4, 8, 12, 16)
        val bars = Edges(20, 30, 40, 50)
        assertEquals(Edges(24, 38, 52, 66), InsetsGeometry.withBars(base, bars))
    }

    @Test
    fun withBars_horizontalNavBarAndCutoutAreNotIgnored() {
        // 横屏：导航栏在右、刘海在左，旧版只加上下会漏掉左右
        val p = InsetsGeometry.withBars(Edges(0, 0, 0, 0), Edges(36, 24, 48, 0))
        assertEquals(36, p.left)
        assertEquals(24, p.top)
        assertEquals(48, p.right)
        assertEquals(0, p.bottom)
    }

    @Test
    fun withBars_repeatedDispatchDoesNotAccumulate() {
        val base = Edges(1, 2, 3, 4)
        val bars = Edges(10, 20, 30, 40)
        val first = InsetsGeometry.withBars(base, bars)
        val second = InsetsGeometry.withBars(base, bars)
        assertEquals(first, second)
        assertEquals(Edges(11, 22, 33, 44), second)
        assertNotEquals(InsetsGeometry.withBars(first, bars), second)
    }

    @Test
    fun withBars_zeroInsetsKeepsInitialPadding() {
        val base = Edges(4, 8, 12, 16)
        assertEquals(base, InsetsGeometry.withBars(base, Edges(0, 0, 0, 0)))
    }
}

package com.onlyread.app.util

import android.os.Build
import android.view.View
import android.view.WindowInsets
import com.onlyread.app.R

/**
 * 0.4.35：全局 edge-to-edge 下「让出状态栏 / 导航栏区域」的统一入口。
 *
 * Android 15（API 35）起，系统对 targetSdk ≥ 35 强制 edge-to-edge：窗口内容会
 * 绘制到状态栏 / 导航栏之后，`window.statusBarColor` 被忽略。0.4.31 只在书架与
 * 文件浏览器两个页面打补丁，阅读页以及挂在 decorView 上的菜单 / 弹注 / 图片查看器
 * 浮层仍会顶进系统栏。现在改由 App 的 ActivityLifecycleCallbacks 对所有页面自动
 * 调用，浮层在创建时显式调用同一个 [applyTo]，杜绝遗漏。
 *
 * 三个要点：
 *  - 四边都处理：横屏时导航栏落在左/右、刘海屏 cutout 在侧边，旧版只加上下会漏；
 *  - 以「初始 padding」为基准（缓存在 View tag），重复调用与多次 insets 分发不叠加；
 *  - 只在 API ≥ 35 生效：旧机型未强制 edge-to-edge，DecorView 已消费系统栏 insets，
 *    再叠一段 padding 会凭空多出留白。
 */
object SystemBarInsets {

    /** systemBars 覆盖状态栏 / 导航栏 / 标题栏；displayCutout 覆盖刘海所在的侧边。 */
    private val types: Int
        get() = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()

    /** 读取当前窗口的系统栏 inset（API < 35 或窗口未附着时返回全 0，用于坐标钳制）。 */
    fun bars(view: View): Edges {
        if (Build.VERSION.SDK_INT < 35) return Edges(0, 0, 0, 0)
        val insets = view.rootWindowInsets ?: return Edges(0, 0, 0, 0)
        val b = insets.getInsets(types)
        return Edges(b.left, b.top, b.right, b.bottom)
    }

    fun applyTo(root: View?) {
        if (root == null || Build.VERSION.SDK_INT < 35) return

        val key = R.id.tag_insets_base
        val base = root.getTag(key) as? Edges ?: Edges(
            root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom
        ).also { root.setTag(key, it) }

        fun apply(v: View, insets: WindowInsets) {
            val bars = insets.getInsets(types)
            val p = InsetsGeometry.withBars(
                base, Edges(bars.left, bars.top, bars.right, bars.bottom)
            )
            v.setPadding(p.left, p.top, p.right, p.bottom)
        }

        root.setOnApplyWindowInsetsListener { v, insets ->
            apply(v, insets)
            insets // 不消费：子 View 仍可读取；本项目布局未声明 fitsSystemWindows，不会重复留白
        }
        // decorView 上后加的浮层可能等不到下一次分发，用窗口现有 insets 立即应用一次
        root.rootWindowInsets?.let { apply(root, it) }
        root.requestApplyInsets()
    }
}

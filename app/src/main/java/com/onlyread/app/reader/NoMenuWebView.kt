package com.onlyread.app.reader

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.webkit.WebView

/** M3：屏蔽 WebView 长按选择的系统菜单（复制/分享/全选/网页搜索）——
 *  选区仍可建立（系统选择句柄保留），交由自绘选区菜单处理。
 *  ⚠️ XML inflate 需要三参构造器全给出：0.4.3 只有单参构造器导致
 *  BookActivity setContentView 直接 InflateException（点封面即闪退） */
class NoMenuWebView : WebView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)

    override fun startActionMode(callback: ActionMode.Callback): ActionMode = NoActionMode()
    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode = NoActionMode()

    /** 空 ActionMode：无菜单无标题，finish/invalidate 均为 no-op */
    private class NoActionMode : ActionMode() {
        override fun setTitle(title: CharSequence) {}
        override fun setTitle(resId: Int) {}
        override fun setSubtitle(subtitle: CharSequence) {}
        override fun setSubtitle(resId: Int) {}
        override fun setCustomView(view: View?) {}
        override fun invalidate() {}
        override fun invalidateContentRect() {}
        override fun finish() {}
        override fun getMenu(): Menu? = null
        override fun getTitle(): CharSequence? = null
        override fun getSubtitle(): CharSequence? = null
        override fun getCustomView(): View? = null
        override fun getMenuInflater(): MenuInflater? = null
        override fun hide(duration: Long) {}
    }
}

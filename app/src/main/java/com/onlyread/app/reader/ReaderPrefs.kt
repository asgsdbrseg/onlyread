package com.onlyread.app.reader

import android.content.Context

/**
 * M2 全局阅读偏好：主题（白/纸/夜）与沉浸式开关，SharedPreferences 存储；
 * 排版参数（字号/行距/字族）按书存 books 表（决策 #17），见 BooksDb.saveTypography。
 */
object ReaderPrefs {
    private const val FILE = "reader_prefs"
    const val THEME_WHITE = 0
    const val THEME_PAPER = 1
    const val THEME_NIGHT = 2

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // M3：schema 版本——升级默认排版参数用（只迁移一次）
    private const val SCHEMA = 2

    /** 一次性迁移到新默认：字号20 / 行距1.5 / 边距 10,10,30,30 / 注释20（主题与自定义字体保留）。
     *  在 App.onCreate 调用，读取任何偏好之前 */
    fun migrateIfNeeded(c: Context) {
        val p = sp(c)
        if (p.getInt("schema", 0) >= SCHEMA) return
        p.edit()
            .putInt("font_px", 20)
            .putLong("line_h", 1.5.toRawBits())
            .putInt("margin_l", 10)
            .putInt("margin_r", 10)
            .putInt("margin_t", 30)
            .putInt("margin_b", 30)
            .putInt("fn_font", 20)
            .putInt("schema", SCHEMA)
            .apply()
    }

    fun theme(c: Context): Int = sp(c).getInt("theme", THEME_PAPER)
    fun setTheme(c: Context, t: Int) = sp(c).edit().putInt("theme", t).apply()

    fun immersive(c: Context): Boolean = sp(c).getBoolean("immersive", false)
    fun setImmersive(c: Context, b: Boolean) = sp(c).edit().putBoolean("immersive", b).apply()

    // M2.2：页边距（CSS px，上下左右独立）；M3 默认 10/10/30/30
    fun marginL(c: Context): Int = sp(c).getInt("margin_l", 10)
    fun marginR(c: Context): Int = sp(c).getInt("margin_r", 10)
    fun marginT(c: Context): Int = sp(c).getInt("margin_t", 30)
    fun marginB(c: Context): Int = sp(c).getInt("margin_b", 30)
    fun setMargins(c: Context, l: Int, r: Int, t: Int, b: Int) =
        sp(c).edit().putInt("margin_l", l).putInt("margin_r", r)
            .putInt("margin_t", t).putInt("margin_b", b).apply()

    // M2.2：全局排版（所有书共享；0/0/''=书内默认）
    // M3：默认字号 20px（原来 0=书内默认）
    fun fontSize(c: Context): Int = sp(c).getInt("font_px", 22) // M3.6：默认 20 → 22
    fun setFontSize(c: Context, v: Int) = sp(c).edit().putInt("font_px", v).apply()

    // M3：默认行距 1.5（原来 0=书内默认）
    fun lineHeight(c: Context): Double = Double.fromBits(sp(c).getLong("line_h", 1.5.toRawBits()))
    fun setLineHeight(c: Context, v: Double) = sp(c).edit().putLong("line_h", v.toRawBits()).apply()
    fun fontFamily(c: Context): String = sp(c).getString("font_family", "") ?: ""
    fun setFontFamily(c: Context, v: String) = sp(c).edit().putString("font_family", v).apply()

    // M2.2：注释（弹注）字号；M3 默认 20
    fun fnFont(c: Context): Int = sp(c).getInt("fn_font", 20)
    fun setFnFont(c: Context, v: Int) = sp(c).edit().putInt("fn_font", v).apply()

    // M2.2：自定义 TTF 字体（filesDir/fonts/custom.ttf）
    fun fontPath(c: Context): String = sp(c).getString("font_path", "") ?: ""
    fun setFontPath(c: Context, p: String) = sp(c).edit().putString("font_path", p).apply()
}

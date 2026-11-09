package com.onlyread.app.util

/**
 * 轻量日志门面：让原本 `catch (_: Exception) {}` 的静默吞异常至少留下可排查的痕迹。
 *
 * - Android 运行时通过反射调用 [android.util.Log]，日志进 logcat；
 * - 纯 JVM 单元测试（无 android.jar）反射失败时回退到 stderr，不影响测试执行。
 *
 * 只用反射而不直接 `import android.util.Log`，是为了让 EpubArchive / ZipPaths 等
 * 被 JVM 单测引用的类不携带 Android 框架符号。
 */
object Logx {
    private const val MAX_MSG = 512

    fun w(tag: String, msg: String, t: Throwable? = null) {
        val line = buildString {
            append(msg)
            if (t != null) append(" | ").append(t.javaClass.simpleName).append(": ").append(t.message)
        }.take(MAX_MSG)
        try {
            val c = Class.forName("android.util.Log")
            val m = c.getMethod("w", String::class.java, String::class.java, Throwable::class.java)
            m.invoke(null, tag, line, t)
        } catch (e: Throwable) {
            System.err.println("W/$tag: $line (log fallback: ${e.javaClass.simpleName})")
        }
    }
}

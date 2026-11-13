package com.onlyread.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import com.onlyread.app.data.BooksDb
import com.onlyread.app.reader.ReaderPrefs
import com.onlyread.app.reader.VariantsStore
import com.onlyread.app.util.SystemBarInsets

class App : Application() {
    val db: BooksDb by lazy { BooksDb(this) }

    override fun onCreate() {
        super.onCreate()
        // M3：一次性迁移默认排版参数（字号22/行距1.5/边距/注释字号），须先于任何偏好读取
        ReaderPrefs.migrateIfNeeded(this)
        // M3.10：归并历史重复导入记录（旧版仅按 path 字符串去重，同一文件会出现两本书）
        runCatching { db.dedupeDuplicates() }
        // M3.7/M3.8：首次把内置异体字表写出到外部文件夹（仅首次、不覆盖用户修改），
        // 随后在启动时一次性读入进程内存缓存；搜索不再读盘
        VariantsStore.ensureExternal(this)
        VariantsStore.loadOnStartup(this)

        // 0.4.35：全局 edge-to-edge 适配。Android 15+ 强制内容绘制到系统栏之后，
        // 统一在 Activity 创建后给 android.R.id.content 应用系统栏 inset。此前
        // 0.4.31 只在书架 / 文件浏览器打补丁，阅读页被漏掉；此后新增页面也自动覆盖。
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                SystemBarInsets.applyTo(activity.findViewById<View>(android.R.id.content))
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}

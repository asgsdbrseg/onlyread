package com.onlyread.app

import android.app.Application
import com.onlyread.app.data.BooksDb
import com.onlyread.app.reader.ReaderPrefs

class App : Application() {
    val db: BooksDb by lazy { BooksDb(this) }

    override fun onCreate() {
        super.onCreate()
        // M3：一次性迁移默认排版参数（字号20/行距1.5/边距/注释字号），须先于任何偏好读取
        ReaderPrefs.migrateIfNeeded(this)
    }
}

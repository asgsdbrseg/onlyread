package com.onlyread.app

import android.app.Application
import com.onlyread.app.data.BooksDb
import com.onlyread.app.reader.ReaderPrefs
import com.onlyread.app.reader.VariantsStore

class App : Application() {
    val db: BooksDb by lazy { BooksDb(this) }

    override fun onCreate() {
        super.onCreate()
        // M3：一次性迁移默认排版参数（字号20/行距1.5/边距/注释字号），须先于任何偏好读取
        ReaderPrefs.migrateIfNeeded(this)
        // M3.7/M3.8：首次把内置异体字表写出到外部文件夹（仅首次、不覆盖用户修改），
        // 随后在启动时一次性读入进程内存缓存；搜索不再读盘
        VariantsStore.ensureExternal(this)
        VariantsStore.loadOnStartup(this)
        // M3.10：归并历史重复记录（旧版本按原始路径字符串判重，符号链接差异会插两本）
        try {
            db.dedupeDuplicates()
        } catch (_: Exception) {
        }
    }
}

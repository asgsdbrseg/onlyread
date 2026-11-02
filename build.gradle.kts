// onlyread · 根构建脚本
// AGP 8.7.3 需要 Gradle 8.9+（Android Studio 打开时自动处理）
// M0 起工程零外部依赖，已移除 KSP（Room 改为框架 SQLite 实现）
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

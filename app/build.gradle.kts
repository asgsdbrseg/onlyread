plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.onlyread.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.onlyread.app"
        minSdk = 31          // 决策 #13：最低支持 Android 12
        targetSdk = 35
        versionCode = 34
        versionName = "0.4.18-m3"
    }

    // CI 签名：GitHub Actions 将密钥解码为 app/keystore.jks，凭据经环境变量注入
    signingConfigs {
        create("release") {
            val ksFile = file("keystore.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("ONLYREAD_KS_PASSWORD")
                keyAlias = System.getenv("ONLYREAD_KEY_ALIAS")
                keyPassword = System.getenv("ONLYREAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有密钥则签名；无密钥产出 app-release-unsigned.apk（仅本地/CI 预览用）
            if (file("keystore.jks").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// M0 架构精简：零外部依赖（纯 Android 框架 + Kotlin 标准库），
// 数据层为框架 SQLiteOpenHelper（见 data/BooksDb.kt），慢网络环境下 Gradle 构建也极快。
// testImplementation 仅用于 ReaderHtmlTest / EpubArchiveTest（纯 JVM JUnit4），不进入 APK、不影响手工构建。
// kxml2 为 JVM 侧 XmlPullParser 实现（Android 框架自带，测试需要显式提供）。
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}

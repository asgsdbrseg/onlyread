# onlyread · Android EPUB 阅读器（M0）

个人自用 EPUB 阅读器，架构思路参考 Lithium / Reasily（仅借鉴设计，代码全部独立实现）。

- **应用名 / 包名**：onlyread / `com.onlyread.app`
- **平台基线**：minSdk 31（Android 12）· targetSdk 35 · Kotlin 2.0.21 · AGP 8.7.3
- **分发**：侧载 APK，不上架；需「所有文件访问」权限（绝对路径直读）
- **语言**：界面中文

## M0 架构说明（零第三方依赖）

为保证在任何网络环境下都能稳定构建，M0 起工程**只依赖 Android 框架 + Kotlin 标准库**：

| 领域 | 实现 |
|---|---|
| 数据层 | 框架 `SQLiteOpenHelper`（`data/BooksDb.kt`，books 表结构与《设计文档 v1.1》§5 完全一致） |
| UI | 框架 `Activity` + `GridView`/`ListView` + `AlertDialog` |
| 线程模型 | `Thread` + `Handler(Looper.getMainLooper())` |
| EPUB 解析 | `ZipFile` + `XmlPullParser`（无 Jsoup 等三方库） |
| 阅读内核 | WebView + 虚拟资源通道（zip 直读回流，不落盘解包） |

原设计的 Room/Coroutines/AppCompat 已移除；M1 进度锚点表将沿用同一 SQLite 方式扩展，表结构设计不受影响。

## M0 功能范围

- 所有文件访问权限引导与检测（FR-01）
- 书架网格（封面/书名/作者，最近在读优先；长按删除记录）
- 应用内文件浏览器按需导入（登记绝对路径 + 首/尾 64KB SHA-1 身份指纹 + 封面提取）
- EPUB 解析（container.xml → OPF → 元数据/manifest/spine/封面探测）
- 阅读页裸渲染（竖向滚动）+ 章节内链跳转 + 外链拦截
- 阅读常亮（无开关，决策 #19）

## M1 功能范围（0.2.0-m1，阅读闭环）

- **分页引擎**：CSS 多栏 + 视口对齐几何（页宽=屏宽、16px 页边距），竖排横向翻页；
  旋转屏幕自动重排版并保持阅读位置
- **点击翻页（决策 #16）**：左 40% 上页 / 中 20% 呼出目录 / 右 40% 下页；
  章末右翻进入下一章、章首左翻进入上一章末页
- **进度锚点（FR-04/FR-11）**：progress 表（spine_index/page_index/total_pages/
  元素序号/百分比），翻页与退出即存；重开书籍按「首可见块元素序号」重定位，
  元素缺失时按页比例兜底；信息条显示 百分比 · 页码 · 章节
- **目录（FR-06）**：导入时解析 EPUB3 NAV / EPUB2 NCX 存 toc 表；阅读中点中部呼出，
  点击即跳章（层级缩进展示）

主题 / 字体 / 全屏 / 脚注 → M2；搜索与选区 → M3。

## 构建方式

**方式一：Android Studio / Gradle（推荐日常）**
打开本目录，等待同步（工程零依赖，秒级），Run 即可。

**方式二：GitHub Actions（自动发布）**
见下节；打 `v*` tag 自动构建签名 APK 并发布 Release。

**方式三：手工管线（无 Gradle 环境适用，见 `scripts/manual_build.sh`）**
`aapt2 compile/link → 生成 R.kt → kotlinc → d8 → zip → zipalign → apksigner`，
需 JDK 17+、Android SDK build-tools 35.0.0 与 platform android-35、kotlin-compiler 2.0.21。

## 签名

- 密钥：`app/keystore.jks`（已列入 .gitignore，**严禁提交/外传**）
- 别名：`onlyread`；密码：`onlyread2026`（仅本仓库自用开发密钥，泄露后可自行重新生成并升级签名）
- CI 通过 4 个 Secrets 注入（`ONLYREAD_KEYSTORE_BASE64` / `ONLYREAD_KS_PASSWORD` / `ONLYREAD_KEY_ALIAS` / `ONLYREAD_KEY_PASSWORD`）
- ⚠️ 升级安装必须使用同一密钥签名；请备份 keystore

## CI 自动构建与发布（GitHub Actions）

`.github/workflows/release.yml`：**打 `v*` tag 自动构建并发布到 Release**；
Actions 页手动触发仅产出 artifact 不发版。

### 首次配置（一次性）

1. 本机生成密钥（或直接使用现有 `app/keystore.jks`）：

   ```bash
   keytool -genkeypair -v -keystore onlyread-release.jks \
     -alias onlyread -keyalg RSA -keysize 2048 -validity 10000
   base64 -w0 onlyread-release.jks > ks.b64   # macOS: base64 -i xx -o ks.b64
   ```

2. 仓库 Settings → Secrets and variables → Actions，添加 4 个 Secret：
   `ONLYREAD_KEYSTORE_BASE64`（ks.b64 内容）、`ONLYREAD_KS_PASSWORD`、
   `ONLYREAD_KEY_ALIAS`（onlyread）、`ONLYREAD_KEY_PASSWORD`。

3. 验证：Actions → Build & Release → Run workflow → run 页下载 `onlyread-apk`
   （文件名不含 `unsigned` 即签名成功）。

### 发布新版本

```bash
git tag v0.1.0-m0 && git push origin v0.1.0-m0
```

Release 自动出现 `onlyread-0.1.0-m0.apk`。以后发版 = 打 tag。

| 场景 | 操作 | 结果 |
|---|---|---|
| 试构建（不发版） | Actions 手动 Run workflow | run 页下载 APK |
| 正式发版 | 推送 `v*` tag | Release 自动发布 |
| 改版本号 | 改 `app/build.gradle.kts` 的 versionName/Code 后打 tag | 新 Release |

## 目录结构

```
onlyread/
├─ .github/workflows/release.yml    CI：tag 触发构建+发布
├─ app/build.gradle.kts             零依赖构建配置 + CI 签名注入
├─ app/src/main/java/com/onlyread/app/
│  ├─ App.kt / MainActivity.kt      书架（GridView + 按需导入）
│  ├─ browser/FileBrowserActivity.kt 文件浏览器
│  ├─ reader/BookActivity.kt        阅读页（虚拟资源通道）
│  ├─ epub/EpubArchive.kt · EpubBook.kt   zip/OPF 解析
│  ├─ data/BooksDb.kt               SQLite 书库表
│  └─ util/ZipPaths.kt · Hashing.kt 路径/编码/MIME、身份指纹
└─ app/src/main/res/                中文文案 / 布局 / 自适应图标
```

## 里程碑

M0 工程骨架（本版）→ M1 阅读闭环（分页 + 进度锚点 + 目录）→
M2 阅读体验（主题/字体/全屏/脚注）→ M3 搜索与选区（正则搜索/词典）→
M4 打磨（畸形回归集/性能）。详见设计文档 v1.1 第 6 节。

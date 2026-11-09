# onlyread · Android EPUB 阅读器（M0）

个人自用 EPUB 阅读器，架构思路参考 Lithium / Reasily（仅借鉴设计，代码全部独立实现）。

- **应用名 / 包名**：onlyread / `com.onlyread.app`
- **平台基线**：minSdk 31（Android 12）· targetSdk 35 · Kotlin 2.0.21 · AGP 8.7.3
- **分发**：侧载 APK，不上架；需「所有文件访问」权限（绝对路径直读）
- **语言**：界面中文

## M0 架构说明（零第三方依赖）

为保证在任何网络环境下都能稳定构建，M0 起工程**只依赖 Android 框架 + Kotlin 标准库**：

| 领域      | 实现                                                                    |
| ------- | --------------------------------------------------------------------- |
| 数据层     | 框架 `SQLiteOpenHelper`（`data/BooksDb.kt`，books 表结构与《设计文档 v1.1》§5 完全一致） |
| UI      | 框架 `Activity` + `GridView`/`ListView` + `AlertDialog`                 |
| 线程模型    | 固定线程池 `ExecutorService` + `Handler(Looper.getMainLooper())`（0.4.22 起，可取消） |
| EPUB 解析 | `ZipFile` + `XmlPullParser`（无 Jsoup 等三方库）                             |
| 阅读内核    | WebView + 虚拟资源通道（zip 直读回流，不落盘解包）                                      |

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

主题 / 字体 / 脚注 → M2；搜索与选区 → M3。

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

| 场景       | 操作                                                 | 结果           |
| -------- | -------------------------------------------------- | ------------ |
| 试构建（不发版） | Actions 手动 Run workflow                            | run 页下载 APK  |
| 正式发版     | 推送 `v*` tag                                        | Release 自动发布 |
| 改版本号     | 改 `app/build.gradle.kts` 的 versionName/Code 后打 tag | 新 Release    |

## 本版调整（M3.7 / M3.8 / M3.9 / M3.10 / M3.11 / M3.12 / M3.13）

- **弹注窗口取消圆角**：旧圆角来自 `panelBg()` 的 `GradientDrawable(cornerRadius=14dp)`，  
  但面板内 WebView 是不透明矩形且铺满面板，改 radius 既看不出也「去不掉」；另一条来源是  
  书籍自带 `<head>` 给 `html/body` 设的 `border-radius`。现改为纯色直角背景，并在弹注 CSS  
  里显式压制 `html/body` 的圆角与阴影，两条来源一起去掉。选区菜单仍保留圆角。
- **目录窗口尺寸对齐全文搜索窗口**：`MATCH_PARENT` 宽 × 80% 屏高（原为 72% + 主题默认宽度）；  
  顶部居中，输入法弹起时仍会压缩高度以保证过滤框可见。  
  ⚠️ **0.4.23 起已被统一浮层取代**：三个菜单统一为 80vw × 80vh 居中、跟随主题，见下文。
- **全文搜索栏**：第一行保留输入框 + 「搜索」；「范围」按钮移到第二行，其后新增  
  **「异体字」开关按钮**（默认「开」，状态持久化）。切换开关时若已有查询会立即重新搜索。
- **选区菜单默认置于选区上方**（M3.8）：菜单放在下方会挡住向下拖动选区时的文字与拖动手柄。  
  现默认 `选区顶 - 菜单高 - 12dp`，上方空间不足才退回下方，再放不下贴屏底。
- **variants.json 只在应用启动时读取一次**（M3.8）：`App.onCreate` 读盘后常驻内存，  
  搜索直接取缓存、不再读盘。修改文件后需**完全退出并重新打开 App** 才生效。
- **不含 noteref 的裸注释标记可正确跳转**（M3.9）：古籍常见的 `<a href="#fn1">` 目标  
  多被 `data-fnhide` 隐藏，此前完全交给 WebView 原生 fragment 导航，滚不到目标、只能落到  
  文档开头。现在同文档 `#` 链接一律在 JS 捕获阶段拦截：注释类仍弹注，普通锚点由  
  `__goFrag` 解除目标隐藏后按内容坐标翻到对应页；跨章锚点改用 `Anchor.Frag` 定位，  
  不再丢到该章开头。目标确实不可见时保持原页，不回退章首。
- **异体字展开提示即时显示**（M3.9）：此前「异体字已展开」只在扫描结束后才拼进完成文案。  
  现在点「搜索」后状态栏立即显示，并贯穿「搜索中 / 完成 / 已停止」各阶段，不被刷新覆盖。
- **全文搜索取消 500 条上限**（M3.9）：扫描不再在 `collected.size >= 500` 时提前停止，  
  仅用户点「取消」或线程中断才结束；`search_done` 文案里的「（上限 500）」同步去掉。
- **自定义字体缺字时回退到书内 CSS 字体**（M3.9）：古籍常在 CSS 里给生僻字、异体字指定专用  
  字体，但 `#__wrap *{font-family:inherit !important}` 会压掉这些声明，缺字只能落到同样缺这些字的  
  系统字体，显示为豆腐块。现在从章节内联 `<style>` / `style=` 以及 `<link rel=stylesheet>` 外部  
  样式表中提取字体名，接进字体栈：`'__Custom'` → 书内 CSS 字体 → `'Noto Sans CJK SC'` →  
  `sans-serif`，浏览器按字符逐个回退。外部 CSS 在后台线程读取（最多 8 个），字体名经安全过滤  
  （最多 24 个）；纯函数 `ReaderHtml.bookFontFamilies / fontStackCss` 有 JVM 单测。
- **弹注内图片取消强制居中，跟随书籍 CSS**（M3.9）：`popupCss` 原有  
  `display:block + margin:auto + float:none` 三条 `!important` 把图片统一块级化居中，  
  书籍 CSS 的对齐、浮动、文字环绕全部失效。现去掉这三条，只保留 `max-width:100%;height:auto`  
  尺寸保护（防大图撑破弹注宽度），与正文 `pagedCss` 的图片口径一致。
- **正文长网址可换行，不再超出屏幕边界**（M3.9）：Chromium 默认不在长 URL 等连续无空格串  
  内部断行，溢出列宽后被 `body{overflow:hidden}` 裁掉（弹注 body 本就有 `word-break:break-word`，  
  故只有正文出问题）。现在 `pagedCss` 注入 `#__wrap,#__wrap *` 的 `overflow-wrap/word-wrap/
  word-break:break-word` 规则，仅当单词本身超过行宽时才断，中文与常规西文排版不受影响。
- **外部文件夹 EPUB 可直接「用 onlyread 打开」**（M3.9）：`MainActivity` 新增三组  
  `ACTION_VIEW` intent-filter（标准 `application/epub+zip` / `.epub` 扩展名兜底 /  
  `application/octet-stream` 兜底），在系统「文件」应用、下载管理器里点 EPUB 即出现在打开方式列表。  
  导入逻辑抽成 `BookImporter`，文件浏览器、SAF 选择、外部打开三处共用；导入成功后直接打开阅读，  
  重复打开同一文件按 canonical 路径 + `identity_hash` 去重并直接跳到已有书（详见 M3.10）。  
  **严格直引原文件、绝不复制到私有目录**：`resolveFile()` 只接受能解析出真实路径且可读的本地  
  文件（`UriPath` 覆盖 `file://`、系统「文件」应用 externalstorage document、MediaStore `_data`）；  
  解析不到（第三方 content provider / 云盘）即提示「无法定位所选文件的原路径…」并中止，  
  不会静默降级。运行时另有 `EpubNames` 按 MIME / 文件名二次校验，避免 octet-stream 兜底  
  把任意二进制文件登记进书架；该纯函数有 JVM 单测。

### M3.10 修正（本次）

- **同一本书不再重复导入**：`BookImporter.importFile` 先对路径取 `canonicalFile`（消除
  `/sdcard` 与 `/storage/emulated/0` 等符号链接写法差异），再按路径与 `identity_hash`
  （首/尾 64KB SHA-1 + 长度）双重去重；命中即复用已有 id 直接打开，并把旧记录路径归一。
  `BooksDb.dedupeDuplicates()` 在启动时归并历史重复记录，升级后书架不再残留两本一样的书。
- **正文长网址可换行**：`pagedCss` 注入 `#__wrap,#__wrap *` 的
  `overflow-wrap/word-wrap/word-break:break-word`，长 URL 不再溢出列宽、与下一页文字重叠。
- **自定义字体缺字回退到书内 CSS 字体**：`ReaderHtml.bookFontFamilies / fontStackCss`
  从章节内联 `<style>`、`style=` 与 `<link rel=stylesheet>` 外部 CSS（后台线程，最多 8 个）
  提取字体名（安全过滤、最多 24 个），接进字体栈 `'__Custom'` → 书内 CSS 字体 →
  `'Noto Sans CJK SC'` → `sans-serif`；正文与弹注同源，均有 JVM 单测。
- **默认字号改为 22px**：`migrateIfNeeded` 与 `fontSize` 默认值、设置面板兜底统一为 22；
  旧版停留在 20px 默认值的用户升级时自动迁移到 22，手动设置过的字号保持不动。
- **取消「全屏沉浸阅读」**：移除 `ReaderPrefs.immersive/setImmersive`、`applyImmersive()`、
  设置面板 CheckBox 与 `set_immersive` 文案，系统栏保持常规显示。
- **修复异体字关联搜索**：字表为空 / 读取失败 / JSON 非法时回退内置模板并提示一次（不再
  静默变空表）；字符类内正则元字符做最小转义；取消扫描结果 500 条上限；切换「异体字」
  开关立即用当前查询重搜；「异体字已展开」提示贯穿搜索各阶段。

### M3.11 / 0.4.21 修正

- **全文搜索窗口呼出输入法时保持不上移**：搜索 `AlertDialog` 用
  `SOFT_INPUT_ADJUST_NOTHING`，输入法弹出后窗口保持原位（目录窗口另有 IME insets
  压缩逻辑，搜索窗口按需求不跟随）。不用 `SOFT_INPUT_ADJUST_RESIZE`（API 35 已弃用）。
- **弹注图片取消强制居中**：`popupCss` 移除 `display:block / margin:auto / float:none`
  三条 `!important`，图片的对齐、浮动、文字环绕完全跟随书内 CSS。
- **正文与弹注图片宽度口径统一**：两处均为 `img,svg{max-width:95vw;}`，且**不加
  `!important`**（书内 CSS 可覆盖）；弹注同时去掉 `height:auto` 注入。
- **自定义字体缺字回退到 CSS（重做）**：M3.10 的「统一字体栈 + `!important` 压制所有
  后代」会抹掉书内 CSS 各元素自己的字体链与顺序，缺字仍落回系统字体。现改为在
  `intercept()` 里对书内 CSS 调用纯函数 `ReaderHtml.prependFontFamily()`，把
  `'__Custom'` 前置到**每一个** `font-family` 使用声明之前（跳过 `@font-face` 定义、
  注释、`inherit`），原字体链与顺序完整保留；`fontFamilyRules` 去掉 `!important`
  与后代继承压制，只保留根容器一条无 important 的兜底栈。
- **版本**：`versionCode 37` / `versionName 0.4.21`。

### M3.12 / 0.4.21 重打包修正（本次）

- **衬线 / 黑体模式与自定义字体机制完全一致**：M3.11 只有 custom 走「前置字体栈 +
  无 important 兜底栈」，衬线 / 黑体仍是 `!important` 全局替换 + 后代 `inherit` 压制。
  现三种模式统一：`fontPrependStack()` 按当前字族给出前置栈（custom 为 `'__Custom'`、
  衬线为 `Georgia,'Noto Serif CJK SC','Source Han Serif SC'`、黑体为
  `'Noto Sans CJK SC'`，均不含通用族以免截断书内回退链），`familyCss()` 统一组装
  「前置栈 → 书内 CSS 字体 → 系统中文字体 → sans-serif」，`fontFamilyRules` 对所有模式
  只输出一条无 important 的兜底栈。外链 CSS 由 `intercept()` 改写，章节内联 `<style>`
  与 `style=` 属性由新增纯函数 `ReaderHtml.prependFontFamilyInHtml()` 在后台线程改写，
  两处口径一致；切换字族改为重载章节（前置发生在加载/拦截阶段，热更新改不到已加载的 CSS）。
- **弹注内点击图片同样进入图片查看器**：弹注 `click` 监听原先只认 `<a>`，图片（包括
  被 `<a>` 包裹的图片）点击被忽略。现按正文 `__hitTest` 同口径最内层优先识别 `IMG`，
  把 `src` 解析为 `reader.local` 绝对地址交给 `ReaderBridge.onImage()` → `showImage()`。
- **全文搜索界面保持不上移**：撤销 M3.11 曾加入的 IME insets 压缩窗口逻辑，恢复为
  单纯 `SOFT_INPUT_ADJUST_NOTHING` + 80% 屏高，输入法弹出时窗口纹丝不动。
- **版本**：仍为 `versionCode 37` / `versionName 0.4.21`（同版本重打包）。

### M3.13 / 0.4.21 再重打包修正（本次）

- **修复衬线字族显示成黑体**：M3.12 把三种字族统一成「前置栈 + 无 important
  兜底栈」时，`fontStackCss` 的链尾无条件写成 `sans-serif`，链中间还插了
  `'Noto Sans CJK SC'`；而衬线前置栈里的 `Georgia` / `Noto Serif CJK SC` /
  `Source Han Serif SC` 在 Android WebView 里按字体名基本都匹配不到，于是中文
  必然落到系统默认的思源黑体。现给 `fontStackCss` 增加链尾通用族参数：衬线模式
  链尾用 `serif`、中间系统字体改用 `'Noto Serif CJK SC'`；黑体 / custom 仍用
  `sans-serif`。通用族只在链尾出现，不截断书内 CSS 字体的逐字符回退；
  `bookFontFamilies` 继续过滤通用族（书内声明里的 `serif` 由 `prependFontFamily`
  原样保留在书内链中，无需混入兜底栈）。衬线前置栈另补 `'Noto Serif'` 提高
  设备命中率。
- **版本**：仍为 `versionCode 37` / `versionName 0.4.21`（同版本重打包）。
- **已知局限**：Android 原生 `serif` 通用族主要覆盖拉丁字符，中文能否呈宋体取决于
  ROM 是否提供衬线中文字体；若要跨设备保证中文衬线，需由 App 内置字体并通过
  `@font-face` 注入。

### 0.4.22 修正（代码审查问题全量收口 + 输入法问题根治）

**P0（正确性）**

- **DB 版本号 3 → 4**：此前 `SQLiteOpenHelper` 版本停在 3，`onUpgrade` 里
  `if (oldVersion < 4) ALTER TABLE toc ADD COLUMN frag` 分支永不触发；v3 老库升级后
  查询 `frag` 会抛异常并被静默吞掉，目录「同文件多标题」跳转失效。现版本升到 4，
  迁移前用 `PRAGMA table_info` 判断列是否已存在，既不再吞异常，也兼容 v2 建表时已带
  `frag` 的库；`SELECT *` 全部改为显式列清单，游标按 `getColumnIndexOrThrow` 取列名，
  杜绝表结构演进后的列序错位。
- **生命周期清理**：`onDestroy()` 现在会 `destroy` 主 WebView（停加载 → 摘 JS 桥 →
  about:blank → 脱离视图树）、`ui.removeCallbacksAndMessages(null)` 清掉所有延迟回调、
  `shutdownNow` 统一后台线程池、清空缓存与在途任务后再关闭 `EpubArchive`。所有后台
  任务与 JS 桥回调检查 `destroyed` 标志，避免操作已销毁对象。`EpubArchive` 内部给
  ZipFile 的读取与关闭加同一把锁和 `closed` 标志，解决「关归档时拦截线程仍在读」
  的并发关闭崩溃。

**P1（性能）**

- **正则预编译**：`ReaderHtml.extractVisibleText` 每调用一次新建 3 个 Regex（全书搜索
  对上万块元素调用）、`markChapterForReader` 每次新建 7 个，现全部提升为预编译常量。
- **书架封面**：改为 `LruCache`（上限 1/8 最大内存）+ 固定 2 线程池 + 在途任务去重；
  `ImageView` 绑定 `R.id.cover_path_tag` 路径标签，异步解码回来先校验再贴，消除
  GridView 复用导致的封面错位与快速滑动时的线程泛滥。
- **资源拦截不再整块读内存**：`EpubArchive` 新增 `entryStream()`，图片/字体等二进制
  资源经 `WebResourceResponse` 直接流式交给 WebView；小文本资源走带 4MB 上限的
  LRU 字节缓存，同一 CSS 不被反复解压；外链 CSS 的字体前置改写结果按
  「路径 + 前置栈」缓存。

**P2（代码质量）**

- 全项目 `catch (_: Exception) {}` 静默吞异常改为带日志的明确回退（新增
  `util/Logx.kt`，Android 走 logcat、JVM 单测回退 stderr）。
- `EpubArchive` 所有 XML 解析输入流明确关闭（或改用 `ByteArrayInputStream` + `use`）。
- `pendingSrchHtml` 改 `ConcurrentHashMap`，`archive` / `epub` / `toc` / `bookFonts` /
  `pendingFnHtml` / `searchCancelled` / `searchSession` 加 `@Volatile`，消除跨线程可见性隐患。
- 消除全部 `!!`；`BookActivity` 的无状态 HTML/CSS 工具抽出为
  `reader/ReaderFragments.kt`（渐进拆分 God Class，正则预编译、可 JVM 单测）。
- `MainActivity` / `FileBrowserActivity` 移除 `configChanges` 交给系统重建；
  `BookActivity` 保留（分页几何/锚点需跨旋屏保留，且必须拦截 `keyboardHidden`，
  否则输入法弹出会重建 Activity 令搜索浮层消失）。

**全文搜索窗口呼出输入法不上移（0.4.21 失效的根治）**

0.4.21 用 `AlertDialog` + `SOFT_INPUT_ADJUST_NOTHING`，在部分 ROM 上仍被上推：
Dialog 是 `TYPE_APPLICATION_PANEL` 子窗口，系统会为保证输入框可见而平移它，
忽略 `ADJUST_NOTHING`。现改为挂在 Activity `decorView` 上的普通浮层，主窗口已声明
`windowSoftInputMode="adjustNothing"`，浮层坐标由代码写死（`Gravity.TOP` + 固定 y、
80% 屏高），IME 弹出时窗口与面板都不发生位移；返回键优先关闭该浮层，点外部关闭，
关闭时打断扫描并收起输入法。（0.4.23 起三个菜单统一改为 `Gravity.CENTER` 居中、80vw 宽。）

- **版本**：`versionCode 38` / `versionName 0.4.22`（与 0.4.21 同密钥，可覆盖安装）。

### 0.4.23 优化（三大菜单统一 + 同文件多标题目录定位）

**① 目录 / 阅读设置 / 全文搜索界面统一**

三个界面此前形态各异：目录与阅读设置用系统 `AlertDialog`（`dialogTheme()` 只分亮/暗，
纸色主题跟随不到），全文搜索是 decorView 浮层但宽 100vw；文字色一律取固定的
`R.color.text_main`，而 Activity 主题恒为 Light，夜间主题下系统输入框/按钮仍是白底黑字。
0.4.23 抽出 `showMenuOverlay()` 统一托管：

| 项 | 0.4.23 统一行为 |
|---|---|
| 容器 | Activity `decorView` 浮层（与 0.4.22 搜索一致），任意时刻只存在一个 |
| 尺寸 | **宽 80vw × 高 80vh，`Gravity.CENTER` 水平垂直居中** |
| 主题 | 背景 `pal().bg`、文字 `pal().text`、强调 `pal().link`，分隔线/描边由文字色派生 alpha |
| 控件 | 输入框、按钮、ListView 分割线、SeekBar 进度/滑块全部跟随主题，不再用系统默认色 |
| 输入法 | 窗口已声明 `adjustNothing`，浮层坐标写死 → **呼出 IME 仍不上移**；过滤/搜索输入框移到面板顶部，避免被底部键盘遮挡 |
| 关闭 | 点遮罩 / 返回键 / 「取消」统一关闭；搜索浮层关闭时打断扫描并收起输入法 |

阅读设置项较多，内容套 `ScrollView` 保证小屏可完整操作；目录与搜索列表改用权重填满
内容区，不再写死 430dp。三个界面都改为普通 View 后不再依赖 `AlertDialog`，
`dialogTheme()` 随之移除。

**② 一个 html 内含多个标题时目录定位错误**

旧实现把「当前章节」无条件取为 `spineIndex` 匹配的**最后一条**目录行（注释写「取最深」
但代码没判断），导致同一文件有多个小节标题时，无论读到哪一节，打开目录都只高亮/
滚动到最后一个标题；此外 `__reanchor` 的 frag 分支优先查 `[data-srch0]`，会把目录锚点
重定位拽回上一次搜索命中处。修复：

- `ReaderFragments.currentTocIndex()`（纯函数，已补 7 个单测）按优先级选当前项：
  **① 当前页锚点 frag 精确命中 → ② 最近一次目录跳转的 ord → ③ 该章第一条 → ④ -1**，
  不再取最后一条。当前页属于哪个标题由 INIT_JS 的 `__report()` 回报：把本章目录锚点
  `window.__tocFrags` 注入页面，从页首可见元素向前找最近的目录 id 一并回报给
  `ReaderBridge.onLayout()`。
- `__reanchor` 的 frag 分支改为**先 `getElementById(a.frag)`**，只有搜索专用锚 `srch0`
  才回退 `[data-srch0]`，目录/交叉引用跳转不再被搜索残留干扰。
- 新增 `Anchor.TocText`：NCX/NAV 未给 `#frag` 但同一 html 有多个标题时（旧行为是所有
  条目都跳章首），按标题文本在正文里找首个匹配块并翻到其所在页；找不到仍回退章首。
  页码计算统一收敛到 JS 辅助函数 `__pageOfNode()`。

- **版本**：`versionCode 39` / `versionName 0.4.23`（与 0.4.22 同密钥，可覆盖安装）。

## 异体字搜索（可编辑的 variants.json）

开启「异体字」时，输入 `黄钟` 会按表展开为 `[黄黃][钟锺𲈛鍾鐘]` 再作为正则扫描，  
同组任意写法（`黃鐘`、`黃𲈛` 等）都能命中；关闭则按原词搜索。点「搜索」后状态栏  
立即显示「异体字已展开」，并在进度 / 完成 / 停止各阶段保持，便于确认开关与启动时  
读入的表已生效。切换开关会以当前查询立即重搜；扫描结果不再有 500 条上限。

**用户可编辑文件**（外部存储，用任意文件管理器即可修改）：

```
/storage/emulated/0/onlyread/variants.json
```

- 首次启动 App 自动把模板文件放到该路径；文件已存在则**绝不覆盖或改写**用户的修改。
- **仅在应用启动时读取一次**（`App.onCreate`）：读盘后把表常驻进程内存，之后每次搜索直接  
  取缓存，**不再读盘**。因此改完保存后需**完全退出并重新打开 App**（杀掉进程）才会生效；  
  启动读取失败 / 格式错误 / 空表时打开搜索窗口会提示一次。
- **原样读取，不做额外修改或补充**：文件可解析时按内容原样使用（不补字、不去重、不改顺序）；  
  读取失败、格式非法或空表时**回退到 assets 内置模板并提示**，避免关联搜索静默失效。
- 模板内容在 `app/src/main/assets/variants.json`；仓库根目录的 `variants.json` 为同一份内容，  
  方便查看与版本管理。

格式：键为单字，值为该字允许命中的全部写法；数组内容**原样**拼成字符类（顺序、重复项均保留，键自身不在数组里也不会自动补上）：

```json
{
  "钟": ["钟", "锺", "𲈛", "鍾", "鐘"],
  "锺": ["钟", "锺", "𲈛", "鍾", "鐘"],
  "𲈛": ["钟", "锺", "𲈛", "鍾", "鐘"],
  "鍾": ["钟", "锺", "𲈛", "鍾", "鐘"],
  "鐘": ["钟", "锺", "𲈛", "鍾", "鐘"],
  "黄": ["黄", "黃"],
  "黃": ["黄", "黃"]
}
```

注意两点：

1. 搜索框「正则恒启用」——查询里含 `.` `*` `[` `(` 等元字符时视为用户手写正则，**不做**异体字改写。
2. `𲈛` 是增补平面汉字（UTF-16 代理对），解析与展开均按 Unicode code point 处理，不会被拆坏。  
   纯函数 `Variants.parse/expandQuery` 无 Android 依赖，JVM 单元测试见 `VariantsTest.kt`。

## 目录结构

```
onlyread/
├─ .github/workflows/release.yml    CI：tag 触发构建+发布
├─ variants.json                    异体字表模板（与 assets 内置默认一致）
├─ app/build.gradle.kts             零依赖构建配置 + CI 签名注入
├─ app/src/main/assets/variants.json   异体字表模板（首次启动放到外部文件夹）
├─ app/src/main/java/com/onlyread/app/
│  ├─ App.kt / MainActivity.kt      书架（GridView + 按需导入）
│  ├─ browser/FileBrowserActivity.kt 文件浏览器
│  ├─ reader/BookActivity.kt        阅读页（虚拟资源通道）
│  ├─ reader/Variants.kt · VariantsStore.kt  异体字表解析/展开 + 启动时读盘与内存缓存
│  ├─ reader/ReaderHtml.kt · ReaderPrefs.kt  HTML 纯函数 / 阅读偏好
│  ├─ reader/ReaderFragments.kt     弹注 HTML/CSS 无状态工具 + 目录当前项定位（0.4.22/23 抽出）
│  ├─ epub/EpubArchive.kt · EpubBook.kt   zip/OPF 解析（流式 + LRU，并发安全）
│  ├─ data/BookImporter.kt          导入单一入口（严格直引原文件，不复制）
│  ├─ data/BooksDb.kt               SQLite 书库表（v4，显式列）
│  ├─ util/EpubNames.kt             EPUB 名字/MIME 识别（纯 JVM）
│  ├─ util/Logx.kt                  轻量日志（Android logcat / JVM stderr）
│  └─ util/ZipPaths.kt · Hashing.kt · UriPath.kt 路径/编码/Uri、身份指纹
└─ app/src/main/res/values/ids.xml   封面 ImageView 防错位 tag key
```

## 里程碑

M0 工程骨架（本版）→ M1 阅读闭环（分页 + 进度锚点 + 目录）→  
M2 阅读体验（主题/字体/脚注）→ M3 搜索与选区（正则搜索/词典）→  
M4 打磨（畸形回归集/性能）。详见设计文档 v1.1 第 6 节。

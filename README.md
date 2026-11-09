# onlyread · Android EPUB 阅读器

个人自用的 Android EPUB 阅读器，架构思路参考 Lithium / Reasily（仅借鉴设计，代码独立实现）。

设计上有两条硬约束：**直引原文件**——EPUB 始终从原始路径读取，只登记绝对路径与内容指纹，不复制到应用私有目录；**零第三方依赖**——除 JVM 单测外只使用 Android 框架与 Kotlin 标准库，在任何网络环境下都能快速构建。

## 项目概况

| 项 | 值 |
| --- | --- |
| 应用名 / 包名 | onlyread / `com.onlyread.app` |
| 平台基线 | minSdk 31（Android 12）· targetSdk / compileSdk 35 |
| 工具链 | Kotlin 2.0.21 · AGP 8.7.3 · Gradle 8.11.1 · JDK 17 |
| 当前版本 | 0.4.28（versionCode 44） |
| 分发方式 | 侧载 APK，不上架应用商店 |
| 权限 | 「所有文件访问」（`MANAGE_EXTERNAL_STORAGE`），绝对路径直读 |
| 界面语言 | 中文 |

## 架构说明

| 领域 | 实现 |
| --- | --- |
| 数据层 | 框架 `SQLiteOpenHelper`（`data/BooksDb.kt`），books / progress / toc 三表 |
| UI | 框架 `Activity` + `GridView` / `ListView`，菜单浮层挂在 decorView 上 |
| 线程模型 | 固定线程池 + `Handler(Looper.getMainLooper())`，销毁时可取消 |
| EPUB 解析 | `ZipFile` + `XmlPullParser`，无 Jsoup 等三方库 |
| 阅读内核 | WebView + 虚拟资源通道（`shouldInterceptRequest` 直读 zip 条目回流，不落盘解包） |
| 资源传输 | 图片 / 字体流式交给 WebView，小文本资源走带上限的 LRU 字节缓存 |

- **导入不降级**：只接受能解析出真实路径且可读的本地文件；第三方 content provider、云盘等解析不到时明确提示改用本机文件，不会静默复制到私有目录。
- **生命周期安全**：Activity 销毁时停加载、摘 JS 桥、清回调、关线程池与归档；后台任务统一检查销毁标志，异常一律留日志，不静默吞掉。
- **可测的纯函数**：HTML / CSS 改写、目录当前项定位、搜索命中锚点、异体字展开、EPUB 名字识别等无 Android 依赖，均有 JVM 单元测试。

## 功能总览

### 书架与导入

- 书架网格展示**封面 / 书名 / 作者**，按最近在读优先排序；长按条目删除（连带清除进度与目录缓存），删除后立即刷新。
- 三个导入入口共用同一套逻辑：应用内文件浏览器、系统文件选择器（SAF）、外部文件管理器「用 onlyread 打开」。导入完成书架立即刷新，封面即时显示。
- **导入去重**：路径先取 `canonicalFile` 消除 `/sdcard` 与 `/storage/emulated/0` 等写法差异，再叠加「首 / 尾 64KB SHA-1 + 长度」内容指纹；命中即复用已有记录直接打开，并把旧路径归一。启动时归并历史重复记录。
- 导入时解析元数据、封面与目录（EPUB3 NAV / EPUB2 NCX）并落库，封面提取到应用私有 `covers/` 后按书籍 id 命名。
- 兜底 intent-filter 会放行 `application/octet-stream`，运行时再按 MIME / 文件名二次校验，避免把任意二进制文件登记进书架。
- 未获权限时书架顶部给出引导卡片，一键跳转系统「所有文件访问」设置页。

### 阅读与翻页

- **CSS 多列分页 + transform 横向翻页**：页宽对齐视口、页内边距可调，翻页不改变文档滚动位置。
- **点击分区**：左侧 40% 上页 / 中部 20% 呼出目录 / 右侧 40% 下页；章末右翻进入下一章，章首左翻回到上一章末页。
- 旋屏或窗口尺寸变化自动重新分页，并按锚点保持阅读位置；屏幕保持常亮。
- 底部信息条显示 **百分比 · 页码 · 章节名**。
- 外链在阅读器内拦截，不跳浏览器。

### 目录与定位

- 导入时解析 EPUB3 NAV / EPUB2 NCX（含层级）存入 toc 表，NCX 按前序解析保证父子顺序正确。
- 目录中**多级自动折叠**、关键词过滤、当前章节高亮并滚到列表中部。
- 同一 HTML 内含多个标题时按 fragment 精确定位当前项，不再一律落到最后一个标题；NCX 未给 fragment 时按标题文本在正文中查找首个匹配块。
- 支持跨章跳转、同文档锚点跳转（兼容 `id` 与 `<a name>`、URL 编码的 fragment），空锚点会改用紧随其后的可见块定位；EPUB3 NAV 文件不作为正文渲染。

### 注释、链接与图片

- **标准注释容器**（`<aside>` / `epub:type=footnote|note|endnote|rearnote`）不进排版流，点击 `noteref` 系列标记就地弹注展示；跨文件引用会从目标文件提取内容后弹注，弹注内还能继续点下一层注释标记。
- **EPUB2 裸注释**（无 `noteref`、目标也不是注释容器）保持书籍原有排版、不隐藏，点击标记直接翻到注释所在页。
- 同文档 `#` 链接在 JS 捕获阶段拦截，按内容坐标换算页码翻页，不依赖 WebView 原生 fragment 滚动（分栏 + transform 下原生滚动会把版面滚乱）。
- 图片**长按**进入图片查看器；**单击**让位给注释标记跳转或按区域翻页，避免吃掉包在 `<a>` 里的注释标记。图片最大宽度 95vw，对齐 / 浮动 / 文字环绕完全跟随书内 CSS。

### 全文搜索

- 范围可切「全书 / 本章」，**正则恒启用**；扫描可随时取消，结果无条数上限。
- 「异体字」开关默认开启且状态持久化，切换后立即用当前查询重搜（用法见下节）。
- 结果按章列出上下文片段并高亮命中词；点击结果跳到**命中词真实所在列**——跨列长块用内联命中标记的首个 rect 定位，不会因块起始列而偏移；命中落在注释内时自动弹注。
- 搜索状态栏实时显示进度 / 完成 / 已停止，以及「异体字已展开」提示。

### 选区与词典

- 长按选中文字弹出菜单：**复制 / 分享 / 发送到全文搜索**。
- 自动枚举可接收文本的应用并列出图标：ColorDict 词典接口优先，其次系统 `PROCESS_TEXT` / `SEND` 文字处理应用。
- 菜单默认置于选区上方，上方空间不足才退回下方；弹注内的选区同样可用。

### 排版与字体

- **主题**：白 / 纸 / 夜三套配色。
- **可调项**：字号（14–30px，默认 22）、行距（1.2–2.25，默认 1.5）、上下左右页边距独立设置、注释字号；设置先存草稿，点「完成」才统一重排生效。排版参数按书保存。
- **字族**：书内默认 / 衬线 / 黑体 / 自定义 TTF-OTF 字体。三种覆盖模式走完全相同的机制——前置字体栈 → 书内 CSS 字体链 → 系统中文字体 → 链尾通用族（衬线用 `serif`，其余用 `sans-serif`），通用族只在链尾出现，不截断书内回退链。
- **缺字回退**：自定义字体不含某字时按字符回退到书内 CSS 声明的专用字体，不显示豆腐块；外部样式表里的字体名在后台线程提取并安全过滤。
- 正文长网址等连续无空格串自动断行，不溢出列宽、不与下一页文字重叠。

### 主题与界面

- **目录 / 阅读设置 / 全文搜索**三个菜单统一为 **80vw × 80vh 居中浮层**，背景、文字、强调色、分割线、输入框与按钮全部跟随当前主题，任意时刻只存在一个。
- 窗口声明 `adjustNothing`，浮层坐标由代码固定，**输入法弹出时界面不上移**；返回键或点遮罩关闭，搜索浮层关闭时打断扫描并收起输入法。
- 弹注、选区菜单、图片查看器均为轻量浮层，不打断当前分页状态。

### 进度与数据

- **进度锚点**：progress 表记录 spine 序号 + 页码 + 首可见块元素序号 + 全局百分比；翻页与退出即存，重开按元素序号重定位，元素缺失时按页比例兜底。
- 书库表使用显式列清单查询，迁移前用 `PRAGMA table_info` 判断列是否存在，避免表结构演进后列序错位或静默失败。
- 封面使用 `LruCache`（上限为进程最大内存 1/8）+ 固定线程池 + 在途任务去重；ImageView 绑定封面路径标签，解码完成后按标签回贴当前可见项，杜绝 GridView 复用导致的错位与空白。

## 异体字表（variants.json）

开启「异体字」时，输入 `黄钟` 会按表展开为 `[黄黃][钟锺𲈛鍾鐘]` 再作为正则扫描，同组任意写法（`黃鐘`、`黃𲈛` 等）都能命中；关闭则按原词搜索。

**用户可编辑文件**（外部存储，用任意文件管理器即可修改）：

```
/storage/emulated/0/onlyread/variants.json
```

- 首次启动自动把内置模板放到该路径；文件已存在则**绝不覆盖或改写**用户修改。
- **仅在应用启动时读取一次**，之后常驻进程内存，搜索不再读盘。改完保存需**完全退出并重新打开 App**（杀掉进程）才生效；启动读取失败 / 格式错误 / 空表时回退到 assets 内置模板并提示一次。
- 文件可解析时按内容原样使用（不补字、不去重、不改顺序）。模板位于 `app/src/main/assets/variants.json`，仓库根目录的 `variants.json` 为同一份内容，便于查看与版本管理。

格式：键为单字，值为该字允许命中的全部写法；数组内容原样拼成字符类（顺序、重复项均保留，键自身不在数组里也不会自动补上）：

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

1. 搜索框正则恒启用——查询里含 `.` `*` `[` `(` 等元字符时视为用户手写正则，**不做**异体字改写。
2. `𲈛` 是增补平面汉字（UTF-16 代理对），解析与展开均按 Unicode code point 处理，不会被拆坏。

## 构建与签名

### 环境

JDK 17、Android SDK platform `android-35` 与 build-tools `35.0.0`、Gradle 8.11.1。设置 `ANDROID_HOME` / `JAVA_HOME` 后在工程根目录执行：

```bash
gradle assembleRelease        # 产物：app/build/outputs/apk/release/
gradle test                   # JVM 单元测试（JUnit4 + kxml2，仅 testImplementation，不进 APK）
```

工程不含第三方运行时依赖，Gradle 同步与构建都很快；`gradle test` 需要能解析 JUnit4 与 kxml2 的测试依赖。

### 签名

`app/build.gradle.kts` 的 release 签名采用「密钥文件存在即启用」的策略：

- 把 release 密钥放到 `app/keystore.jks`，并通过环境变量注入 `ONLYREAD_KS_PASSWORD`、`ONLYREAD_KEY_ALIAS`、`ONLYREAD_KEY_PASSWORD`，`assembleRelease` 即产出已签名 APK；
- 未放置密钥时产出 `app-release-unsigned.apk`，仅用于本地预览。

密钥文件已列入 `.gitignore`，**严禁提交或外传**。⚠️ 升级安装必须使用同一密钥签名，请务必备份 keystore。

### 测试

`app/src/test/` 下为纯 JVM 单元测试，覆盖 HTML / CSS 改写与注释标记、目录当前项与搜索命中锚点、EPUB 解析（NCX / OPF）、异体字表解析与展开、EPUB 名字 / MIME 识别，不依赖 Android 设备。

## 工程结构

```
onlyread/
├─ variants.json                        异体字表模板（与 assets 内置默认一致）
├─ app/build.gradle.kts                 零运行时依赖构建配置 + release 签名注入
├─ app/src/main/assets/variants.json    异体字表模板（首次启动写出到外部文件夹）
├─ app/src/main/java/com/onlyread/app/
│  ├─ App.kt                            启动迁移、去重、异体字表读盘
│  ├─ MainActivity.kt                   书架（GridView + 封面缓存 + 按需导入）
│  ├─ browser/FileBrowserActivity.kt    应用内文件浏览器 + SAF 选择
│  ├─ data/BookImporter.kt              导入单一入口（严格直引原文件，不复制）
│  ├─ data/BooksDb.kt                   SQLite 书库（books / progress / toc）
│  ├─ epub/EpubArchive.kt · EpubBook.kt zip / OPF / NCX / NAV 解析（流式 + LRU，并发安全）
│  ├─ reader/BookActivity.kt            阅读页（分页、目录、设置、搜索、弹注、图片查看器）
│  ├─ reader/ReaderHtml.kt              HTML / CSS 处理纯函数
│  ├─ reader/ReaderFragments.kt         弹注 HTML/CSS、目录当前项、搜索命中锚点纯函数
│  ├─ reader/ReaderPrefs.kt             阅读偏好（主题 / 排版 / 异体字开关）
│  ├─ reader/Variants.kt · VariantsStore.kt 异体字表解析 / 展开 + 启动读盘缓存
│  ├─ reader/NoMenuWebView.kt           禁用系统文本菜单的 WebView
│  └─ util/                             EPUB 名字识别、身份指纹、Uri 路径解析、日志
├─ app/src/main/res/                    布局、配色、字符串、图标、封面防错位 tag
└─ app/src/test/                        纯 JVM 单元测试
```

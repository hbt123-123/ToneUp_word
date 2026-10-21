# PROJECT KNOWLEDGE BASE

**Generated:** 2026-10-17
**Commit:** fbfeefe
**Branch:** main

## OVERVIEW
CET-4/CET-6 单词记忆 Android 应用（fork 自 CetVocab，更名为「一潼上岸·单词 · ToneUp Word」）。Kotlin 2.4 + Jetpack Compose + Material 3 + Room + DataStore + WorkManager，Gradle 单模块 `:app`。minSdk 26 / targetSdk 36。**离线优先**：学习/复习/错题/考试/统计/导入导出/朗读（系统 TTS）全部离线可用；`INTERNET` 权限**仅**用于自建释义补全网关（ECDICT/FreeDict/LLM 转发）与应用内版本更新（固定 `version.json` + 镜像回退 + SHA-256 校验 + 断点续传下载）。无账号/广告/埋点。支持用户自建词库、粘贴/分享/剪贴板导入、卡片笔记与手动释义。附带 `tools/` Python 离线数据流水线（词库/提示语生成，独立于构建）。

## STRUCTURE
```
ToneUp_word/
├── app/                    # 唯一 Gradle 模块；schemas/ 为 Room 导出 schema（迁移测试依据，含 1/2/3.json）
│   └── src/main/java/top/toneup/word/
│       ├── core/           # Outcome 结果类型、StudyDay(04:00 学习日边界)、JsonCodec、TimeProvider
│       ├── domain/         # 纯 Kotlin 业务引擎（11 子包，零 Android 依赖）
│       ├── data/           # Room + Repository + DataStore + SAF + TTS + 网关 + 应用更新（10 子包）
│       ├── ui/             # Compose 屏幕/组件/主题/导航 + ViewModels.kt 单文件 + ExternalImport/OptIns
│       └── work/           # WorkManager：学习提醒 + 释义补全同步（MeaningSyncWorker）
├── tool/                   # 发布脚本（release.ps1：APK + version.json；非构建依赖）
├── tools/                  # Python 流水线：wordpack 词库、motivation 提示语、pronunciation(历史)
├── docs/                   # 文档树；dev/ 为迭代记录；SHARE_INTENT.md 外部调用文档
├── gradle/libs.versions.toml   # 依赖版本唯一来源（含网关 okhttp/retrofit）
├── build.gradle.kts        # 根脚本：仅声明插件 apply false（AGP 9 内置 Kotlin）
└── keystore.properties.example # 签名模板；真实密钥绝不入库
```

## WHERE TO LOOK
| 任务 | 位置 | 备注 |
|------|------|------|
| 业务规则/算法改动 | `app/src/main/java/top/toneup/word/domain/` | 纯 Kotlin，改必配单测 |
| 数据库 schema/迁移/DAO | `data/local/`（11 表 1 视图 9 DAO，schema v3） | 升版流程见 data/AGENTS.md |
| 仓库/事务/播种 | `data/repository/` + `data/seed/` | 事务只在 Repository 层 |
| 释义补全/网关 | `data/gateway/` + `data/repository/MeaningSyncRepository.kt` + `work/MeaningSyncWorker.kt` | **联网点 1**；蓝图 `docs/dev/PLAN_CUSTOM_PACKS_GATEWAY.md` |
| 应用内版本更新 | `data/update/` + `data/repository/AppUpdateRepository.kt` + `domain/update/` + `tool/release.ps1` | **联网点 2**（固定 `version.json` + 镜像回退 + SHA-256 + 断点续传 + 安装提示）；协议 `docs/UPDATE_PROTOCOL.md` |
| 词库管理/纯文本导入 | `ui/screens/PackScreens.kt` + `domain/importer/PlainTextWordParser.kt` | PACKS/IMPORT_WORDS 路由；导入即返 `PENDING` |
| 剪贴板/分享导入 | `ui/ExternalImport.kt` + `MainActivity.kt` | Share/Process-Text + 焦点剪贴板探测；原文永不出本地（`docs/SHARE_INTENT.md`） |
| 页面/组件/主题 | `ui/screens/`、`ui/components/`、`ui/theme/` | 新屏幕接入 `ui/navigation/ToneUpWordNavHost.kt` |
| 依赖装配 | 根 `AppContainer.kt` | 手写**构造器**注入，**无 Hilt** |
| 词库资产生成 | `tools/wordpack/` → `app/src/main/assets/wordpacks/` | 流水线顺序见 tools/AGENTS.md |
| 提示语资产 | `tools/motivation/` → `assets/motivation/tips.json` | 398 条，禁用词表在脚本内 |
| 测试写法/运行 | `app/src/test/` | 见 app/src/test/AGENTS.md |
| 构建/签名/发布 | `gradle/libs.versions.toml`、`docs/RELEASE_SIGNING.md` | 命令见 COMMANDS |

## CODE MAP
启动链：`ToneUpWordApplication.onCreate` → `AppContainer`（对象图根）→ `MainActivity.onCreate`（`enableEdgeToEdge` + Activity 级 `ExternalImportViewModel` + `setContent`）→ `MainViewModel.init` 调 `container.prepare()`（词库播种+提醒调度）与 `appUpdateRepository.init()/autoCheck()` → `ToneUpWordApp`（ready 门控 + 开屏提示语 overlay + 分享/剪贴板导入 Sheet + 更新对话框）→ `ToneUpWordNavHost`（17 路由，起始 HOME/ONBOARDING）→ Screen → `ViewModelFactories` → Repository → DAO / domain 引擎。
外部输入链：`MainActivity.handleShareIntent`（onCreate 冷启 + onNewIntent）/ `onWindowFocusChanged` 剪贴板探测 → `ExternalImportViewModel` → `PlainTextWordParser` → 导入 Sheet → `WordRepository.addWordsToPack` → `MeaningSyncRepository.enqueueAll`。
后台链：`ReminderScheduler` → WorkManager → `StudyReminderWorker.doWork()` → 通知 → MainActivity；`MeaningSyncWorker.doWork()` → `MeaningLookupService`（ECDICT→FreeDict→LLM 级联）→ 写回 `word` + `meaning_cache`。

| Symbol | Type | Location | Refs | Role |
|--------|------|----------|------|------|
| `AppContainer` | class/DI 根 | `AppContainer.kt` | 全局 | 唯一装配点；`prepare()` 冷启动任务；网关客户端/补全服务装配 |
| `ViewModelFactories` | object | `ui/ViewModels.kt:95` | 各 Screen | UI 侧 VM 工厂（12 个） |
| `Word` | data class | `domain/model/Word.kt:10` | — | 词库核心模型（含 v3 笔记/释义/网关字段，有测试） |
| `WordProgress` | data class | `domain/srs/WordProgress.kt:59` | — | 学习进度模型（有测试） |
| `WordDao` | @Dao | `data/local/dao/WordDao.kt` | 1（DB） | ⚠️ 无直接测试，经 DatabaseTest 间接覆盖 |
| `SpacedRepetition` | object | `domain/srs/SpacedRepetition.kt` | — | SM-2 调度纯函数（26 测试） |
| `ProgressRecorder` | class | `data/repository/ProgressRecorder.kt:38` | — | 全 app 唯一答题事务写入口 |
| `MeaningLookupService` | class | `data/gateway/MeaningLookupService.kt:34` | — | 级联查询 + 24h 共享缓存；`manual` 永不覆盖 |
| `MeaningSyncRepository` | interface | `data/repository/MeaningSyncRepository.kt:50` | — | 补全编排：pending 计数 / unique work 入队 / 单词级即时查 |
| `PlainTextWordParser` | object | `domain/importer/PlainTextWordParser.kt:24` | — | 粘贴/分享/剪贴板纯文本 → 词表（20 000 条 / 64 字符） |
| `Migrations` | object | `data/local/Migrations.kt:23` | — | `CURRENT_VERSION=3` + `ALL_MIGRATIONS`（1→2→3） |

## CONVENTIONS（仅偏离标准处）
- **无 ktlint/detekt/spotless/.editorconfig**：格式靠 IDE `kotlin.code.style=official`；`.gitattributes` 强制全部源码/文档 LF 入库。
- 架构：Composable 不接触 DAO/DataStore/文件系统；ViewModel 只依赖 Repository 接口、暴露不可变 `StateFlow`；`NavController` 不传入页面；页面经回调触发 VM 事件。
- domain 层零 `android.*` import；**时间以参数注入**（`nowMillis`/`today: LocalDate`），不读系统时钟；确定性优先（seed 注入 Random、哈希选句）。
- 失败是值不是异常：文件/IO/DB/网络失败返回 `Outcome<T>`/`AppError`（`core/result/`）；统计无数据用 `null` 而非 0%。
- 枚举以稳定字符串入库、在 `data/mapper/Mappers.kt` 显式映射，**不走 TypeConverter**。
- **学习范围 = `packId`**（schema v2 起，D1）：所有查询/统计/错题/考试/提醒按 `effectivePackId` 隔离，不再按 `level`；`CetLevel` 仅作词库元数据。
- 测试：JUnit4 + Truth + Robolectric（Compose 测试在 JVM 跑，**无 androidTest**）；方法名反引号英文；固定时间戳防 flaky；minSdk 路径用 `@Config(sdk=[26])`。
- 依赖版本只改 `gradle/libs.versions.toml`；**app 版本号只改 `app/build.gradle.kts` 的 `versionName`/`versionCode`**（公式见 `docs/UPDATE_PROTOCOL.md`），仓库集中声明于 `settings.gradle.kts`（`FAIL_ON_PROJECT_REPOS`）。
- 根 `build.gradle.kts` 故意不应用 `org.jetbrains.kotlin.android`（AGP 9 内置 Kotlin）——**勿"修复"**。

## ANTI-PATTERNS (THIS PROJECT)
- **联网仅限两处**：`INTERNET` 权限允许且仅允许用于 ① 自建释义补全网关（ECDICT/FreeDict/LLM 转发，蓝图 `docs/dev/PLAN_CUSTOM_PACKS_GATEWAY.md`）；② 应用内版本更新（固定 `version.json` + APK 断点续传 + SHA-256 校验，`data/update/`，`REQUEST_INSTALL_PACKAGES` 仅用于把下载好的 APK 交给系统安装器，绝无静默安装）。其余联网能力（云同步、统计/埋点 SDK、远程音频下载、任何联网组件）仍**禁止**；剪贴板/分享的原始文本**永不出本地**（只上传待查询的单词 token + 可选 key），`lastClipboardHash` 只存哈希不存原文。
- **`ACCESS_NETWORK_STATE` 仍不声明**：只由 androidx.work 合并带入，app 不读连接状态；WorkManager `NetworkType` 约束（联网/仅 Wi-Fi）不需要我们声明该权限。
- **禁止在本机引入/编译 ktlint、detekt、spotless 等格式化检查**（不加相关依赖与 Gradle 任务）；验证一律 = `.\gradlew.bat :app:testDebugUnitTest` + `.\gradlew.bat :app:assembleDebug`（文件自验证，见 `docs/dev/PLAN_CUSTOM_PACKS_GATEWAY.md` §11/§13-R1）。
- **禁止**对 `word` 表用 `Insert(REPLACE)`：会删行重建、CASCADE 连带清空 progress/事件/错题/考试快照。用 `@Update` 或身份映射 insert-then-update。唯一身份现为 `(pack_id, normalized_word)`（同词可入多包，进度跟行走）。
- **禁止**破坏性数据库迁移：升版必须手写 Migration 入 `ALL_MIGRATIONS` + 同步 `version` 字面量与 `Migrations.CURRENT_VERSION`（有测试断言）+ 导出 `app/schemas/` 快照；迁移 SQL 须兼容 SQLite 3.18，且 v3 视图 SQL 必须与 `3.json` 逐字节一致。
- **禁止**绕过 `meaning_source='manual'` 的保护（`applyLookupToWord`/`idsNeedingLookup`/`recordLookupFailure` 三处守卫）：手动编辑的释义永不被自动补全覆盖。
- **禁止**提交密钥：`keystore.properties`/`*.jks`/`*.keystore` 已 gitignore；网关 key 默认值硬编码可覆盖（Q3），泄露按 `网关使用文档.md` §5 换。
- **禁止**在 domain 读系统时钟/未注入的随机数；**禁止** Composable 直连 DAO。
- **禁止**按 `docs/PRONUNCIATION_VERIFICATION.md` 实现功能——真人录音模块已废弃（迭代 3 起改系统 TTS），文档仅历史证据；在线发音（FreeDict `audio_url`）失败静默回落 TTS，**不落盘缓存**。
- **禁止**引入外部名言/语录库（版权不确定一律原创）；mahavivo、KyleBing 数据源已被否决勿复活。
- 文案禁用词（测试守护）：提示语禁「熬夜/拼命/落后/淘汰/废物/不配/必须/内卷/碾压…」（`tools/motivation/validate_tips.py` BANNED + StartupTipTest）；洞察文案禁「分数/提分/必过/保过/考试/倒数…」（InsightEngineTest）。
- 考试页不可朗读（测试守护）；TTS 读取系统设置失败**不得**覆盖引擎默认值。
- 应用内不出现「美式/英式」字样。

## UNIQUE STYLES
- **单文件聚合约定**：12 个 ViewModel 全在 `ui/ViewModels.kt`（~1990 行，`// ----` 分节，节间无共享状态）；`WordCard.kt` 被 Study/Exam/Review 三屏共用；一文件多屏（ExamScreens 三态、ProfileScreens 双屏、PackScreens 双屏）。
- 开屏提示语是 **overlay 而非导航目的地**（每进程一次）；分享/剪贴板导入 Sheet 也是跨导航图浮层（`ExternalImportSheet`，由 MainActivity 持有 Activity 级 VM）。
- 激励/提示语**确定性选择**（多项式哈希，不用时钟/随机）；中文文案内嵌 domain 枚举/`describe()` 使其可测。
- 演示/内置词库只能清进度不能删除（`deletePack` 守卫内置包）；试卷开考即快照、防重复提交、saveAnswer 不判对错（防答案键泄露）。
- 数据不足时 InsightEngine 只说「数据有限」不给判断（"焦虑机器"原则）。
- 扩展性收口：实验性 API 集中在 `ui/OptIns.kt` 记录并逐处 `@OptIn`（无 `@file:OptIn`）。

## COMMANDS
```bash
.\gradlew.bat :app:assembleDebug          # 调试 APK → app\build\outputs\apk\debug\
.\gradlew.bat :app:installDebug           # 安装到设备/模拟器
.\gradlew.bat :app:testDebugUnitTest      # 全部单测（596 用例 / 43 类）
.\gradlew.bat :app:testDebugUnitTest --tests "*.SpacedRepetitionTest*"   # 单类
.\gradlew.bat :app:assembleRelease -x lintVitalRelease   # R8 发布包（本机约定跳过 lint）
.\gradlew.bat :app:lintVitalRelease       # Android Lint（唯一静态检查；低内存机会 OOM）
powershell -ExecutionPolicy Bypass -File tool\release.ps1 -AppVersion 1.0.3 -Tag   # 出发布物 + 校验版本号
python tools/motivation/validate_tips.py  # 提示语校验（纯标准库）
python tools/wordpack/validate_packs.py   # 词库校验（依赖 cache/ecdict.csv）
```

## NOTES
- **更新/发布三件套必须一致**：Release tag = `v<versionName>`；`versionCode = major*100 + minor*10 + patch`；两个资产名固定为 `ToneUpWord.apk` / `version.json`。`tool/release.ps1` 校验前两项并生成 `version.json`，但**必须在 GitHub 上 Publish**（草稿/Pre-release 客户端看不到）。完整契约见 `docs/UPDATE_PROTOCOL.md`。
- **README 有死链/过期**：`.github/workflows/`、`CONTRIBUTING.md`、`CODE_OF_CONDUCT.md` 被引用但**本地不存在**（fork 未带上 CI），CI 徽章失效；README 模块图写 `com/toneup/app/` 而实际包是 `top.toneup.word`——**以代码为准**。发布流程已改为本机 `tool/release.ps1`，`docs/RELEASE_SIGNING.md` 不再声称有 CI。
- **Windows 路径必须纯 ASCII**：AGP 遇非 ASCII 路径直接终止构建（当前 `E:\project\ToneUp_word` 合规）。
- PowerShell 写词库文件**禁用** `Set-Content`/`Out-File`（破坏 UTF-8）；先 `$env:PYTHONIOENCODING="utf-8"`。
- 构建前提：`local.properties` 配 `sdk.dir`（不入库）；JDK 17+；SDK API 36。
- 测试 JVM 堆被限制 512m + SerialGC（app/build.gradle.kts testOptions）——内存问题会响亮失败而非 swap。
- `tools/*/cache/` gitignored 且本地不存在——复现流水线先 `download.py`；Tatoeba .bz2 需手工下载。
- `attach_examples.py` 重复执行会在 PACK_INFO.json `transformNotes` 累加重复段（现状已有痕迹）——勿反复追跑。
- 网关网络栈版本由 M0 spike 钉死：**okhttp 5.4.0 / retrofit 3.0.0**（5.5.0 的 AAR 要求 compileSdk 37）；`compileSdk=36` 下勿升级。
- 签名双通道：环境变量 `TONEUP_KEYSTORE_*` 优先，其次根 `keystore.properties`；都没有时 release 产出**未签名包而非失败**。
- **本机跑单测前先把 `TEMP`/`TMP` 指到纯 ASCII 路径**（如 `E:\Temp`）：用户目录含中文时，Robolectric 的 `robolectric-nativeruntime.dll`（解压到 `%TEMP%`）JNI_OnLoad 会失败，测试 JVM 在 SQLite 首次 open 时以 EXCEPTION_ACCESS_VIOLATION 崩溃（hs_err 见 `app/`）。做法：`.\gradlew.bat --stop` 后设 `$env:TEMP="E:\Temp"; $env:TMP="E:\Temp"` 再跑 `testDebugUnitTest`（M0 实测，2026-10-14）。
- Kotlin LSP（kotlin-ls）未安装，符号导航靠 codegraph/grep。

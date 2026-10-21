# app/src/test/ — JVM 单元测试（唯一测试层）

## OVERVIEW
43 类 / 596 用例；镜像 main 包结构；无 androidTest——Compose/Room/网关测试全在 JVM（Robolectric）跑。

## STRUCTURE
```
app/src/test/java/top/toneup/word/
├── core/time/StudyDayTest.kt              04:00 学习日边界（21）
├── data/                                  DatabaseTest(22，全 DAO+version 断言), ProgressRecorderTest(20),
│                                          Migration1To2Test(3), Migration2To3Test(2), PackIdsTest(4),
│                                          AddWordsToPackTest(18), WordEditRepositoryTest(11),
│                                          gateway/(MeaningLookupServiceTest 12, MeaningSyncRepositoryTest 10),
│                                          seed/(BuiltInPackSeedTest 7, BuiltInPackAssetTest 8),
│                                          speech/AudioUrlPlayerTest(4),
│                                          update/(ApkUpdateDownloaderTest 13, UpdateManifestSourceTest 11),
│                                          AppUpdateRepositoryTest(18)
├── domain/                                15 文件 319 用例（见下覆盖表）
├── ui/                                    ComposeFlowTest(15), ExternalImportViewModelTest(16), GatewaySettingsTest(5),
│                                          UiStateStabilityTest(5), WordBrowserScreenTest(8),
│                                          components/(WordCardExampleTest 3, StatDefinitionTest 5),
│                                          screens/StartupTipScreenTest(4),
│                                          speech/(SpeechControllerTest 10, SpeechAudioRoutingTest 12),
│                                          theme/SystemBarsTest(5)
└── resources/robolectric.properties       默认 sdk=36
```

## WHERE TO LOOK
| 任务 | 位置 |
|------|------|
| 运行 | `.\gradlew.bat :app:testDebugUnitTest`；单类 `--tests "*.SpacedRepetitionTest*"`（见根 COMMANDS） |
| 答题写入语义 | `data/ProgressRecorderTest.kt` |
| DB 版本/迁移 | `data/DatabaseTest.kt`（断言 version 字面量 == `Migrations.CURRENT_VERSION`）+ `Migration1To2Test`（种子保真/新索引）+ `Migration2To3Test`（视图 DDL） |
| 词库 packId 隔离 | `data/PackIdsTest.kt`、`DatabaseTest` |
| 纯文本/分享解析 | `domain/importer/PlainTextWordParserTest.kt` |
| 网关级联/缓存/manual 保护 | `data/gateway/MeaningLookupServiceTest.kt`、`MeaningSyncRepositoryTest.kt` |
| 分享/剪贴板导入去重 | `ui/ExternalImportViewModelTest.kt` |
| 网关设置项 | `ui/GatewaySettingsTest.kt` |
| 在线发音回落 TTS | `ui/speech/SpeechAudioRoutingTest.kt` |
| 文案禁用词 | `domain/motivation/StartupTipTest.kt`、`domain/insight/InsightEngineTest.kt` |
| TTS 失败路径 | `ui/speech/SpeechControllerTest.kt` |
| 全导航流程 | `ui/ComposeFlowTest.kt`（@Config sdk=26） |
| 应用更新（清单/下载/阶段机） | `data/update/UpdateManifestSourceTest.kt`、`ApkUpdateDownloaderTest.kt`、`data/repository/AppUpdateRepositoryTest.kt` |

## CONVENTIONS（测试特有）
- 断言以 Truth `assertThat` 为主（个别 `org.junit.Assert`）；协程 `runTest`；Compose 用 `createComposeRule`（Robolectric 下运行）；网关用 `MockWebServer`。
- 方法名反引号英文自然语言：``fun `unknown lowers mastery and resets the repetition streak`()``；类内 `// ---` 分组。
- **防 flaky**：固定 epoch 常量，不读系统时钟；Compose 查询用真实语义节点/testTag（产品要求）。
- minSdk 路径验证：DatabaseTest / ProgressRecorderTest / ComposeFlowTest 加 `@Config(sdk = [26])`（JVM 上跑 API 26 android-all 镜像）。
- Room schema 目录 `app/schemas` 挂为 test assets（MigrationTestHelper 需要）——升 DB 版本先导出快照；迁移 SQL（含 v3 视图）须与导出 JSON 逐字节一致。
- 测试堆限 512m + SerialGC + UTF-8（build.gradle.kts testOptions）——内存问题响亮失败。
- 运行前把 `TEMP`/`TMP` 指到纯 ASCII 路径（见根 NOTES），否则 Robolectric 原生库在某些机器上 JNI_OnLoad 崩溃。

## 覆盖表
| 域 | 文件 | @Test |
|----|------|-------|
| domain·insight | InsightEngineTest(42) + SignalsCalculatorTest(8) | 50 |
| domain·importer | WordPackParserTest(34) + PlainTextWordParserTest(28) + CsvCodecTest(16) | 78 |
| domain·exam | ExamGeneratorTest(19) + ExamScoringTest(19) | 38 |
| domain·study | StudySessionEngineTest(28) + NextStepPlannerTest(9) | 37 |
| domain·stats | StatsCalculatorTest | 34 |
| domain·motivation | MotivationEngineTest(21) + StartupTipTest(6) | 27 |
| domain·srs | SpacedRepetitionTest | 26 |
| domain·search | ApproximateWordMatcherTest | 9 |
| domain·update | AppVersionTest | 20 |
| data·core | DatabaseTest(22) + ProgressRecorderTest(20) + AddWordsToPackTest(18) | 60 |
| data·gateway | MeaningLookupServiceTest(12) + MeaningSyncRepositoryTest(10) | 22 |
| data·迁移/pack | Migration1To2Test(3) + Migration2To3Test(2) + PackIdsTest(4) + WordEditRepositoryTest(11) | 20 |
| data·seed/speech | BuiltInPackAssetTest(8) + BuiltInPackSeedTest(7) + AudioUrlPlayerTest(4) | 19 |
| data·update | AppUpdateRepositoryTest(18) + ApkUpdateDownloaderTest(13) + UpdateManifestSourceTest(11) | 42 |
| ui | ComposeFlowTest(15) + ExternalImportViewModelTest(16) + SpeechAudioRoutingTest(12) + SpeechControllerTest(10) + WordBrowserScreenTest(8) + GatewaySettingsTest(5) + UiStateStabilityTest(5) + SystemBarsTest(5) + StatDefinitionTest(5) + StartupTipScreenTest(4) + WordCardExampleTest(3) | 88 |
| core·time | StudyDayTest | 21 |
| domain·mistake/model | —（无专测，间接覆盖） | 0 |

## 覆盖缺口（按优先级）
`ui/ViewModels.kt`（各屏 VM 未单独测）→ 6 个 Repository（Study/Mistake/Exam/Stats/Insight/Backup 无直测）→ `work/MeaningSyncWorker`/`work/StudyReminderWorker` → `core/json`、`core/result`、`data/io`、`data/settings`、`data/mapper`、`ui/navigation`、多数 Screen（Home/Study/Exam/Stats/Profile/Onboarding/DataTransfer/Pack）、`AppContainer`、`MainActivity`。

## ANTI-PATTERNS
- **禁止**删除/改弱失败测试来"通过"；改根 ANTI-PATTERNS 所列守护行为（禁用词、考试页不可朗读、version 双处同步、manual 释义保护、剪贴板原文不出本地）前先看对应测试。
- **禁止**测试读系统时钟或依赖执行顺序（固定时间戳 + 独立用例）。
- 新增 minSdk 相关路径（API 26 分支）须配 `@Config(sdk=[26])` 用例。

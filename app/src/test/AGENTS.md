# app/src/test/ — JVM 单元测试（唯一测试层）

## OVERVIEW
26 类 / 404 用例；镜像 main 包结构；无 androidTest——Compose/Room 测试全在 JVM（Robolectric）跑。

## STRUCTURE
```
app/src/test/java/top/toneup/word/
├── core/time/StudyDayTest.kt              04:00 学习日边界
├── data/                                  DatabaseTest(全 DAO+version 断言), ProgressRecorderTest(28),
│                                          seed/BuiltInPackSeedTest(升级保 id), BuiltInPackAssetTest(资产完整性)
├── domain/                                13 文件 271 用例（见下覆盖表）
├── ui/                                    ComposeFlowTest, UiStateStabilityTest, WordBrowserScreenTest,
│                                          components/(WordCardExampleTest, StatDefinitionTest),
│                                          screens/StartupTipScreenTest, speech/SpeechControllerTest,
│                                          theme/SystemBarsTest
└── resources/robolectric.properties       默认 sdk=36
```

## WHERE TO LOOK
| 任务 | 位置 |
|------|------|
| 运行 | `.\gradlew.bat :app:testDebugUnitTest`；单类 `--tests "*.SpacedRepetitionTest*"`（见根 COMMANDS） |
| 答题写入语义 | `data/ProgressRecorderTest.kt` |
| DB 迁移/版本 | `data/DatabaseTest.kt`（断言 version 字面量 == Migrations.CURRENT_VERSION） |
| 文案禁用词 | `domain/motivation/StartupTipTest.kt`、`domain/insight/InsightEngineTest.kt:616` |
| TTS 失败路径 | `ui/speech/SpeechControllerTest.kt` |
| 全导航流程 | `ui/ComposeFlowTest.kt`（@Config sdk=26） |

## CONVENTIONS（测试特有）
- 断言以 Truth `assertThat` 为主（个别 `org.junit.Assert`）；协程 `runTest`；Compose 用 `createComposeRule`（Robolectric 下运行）。
- 方法名反引号英文自然语言：``fun `unknown lowers mastery and resets the repetition streak`()``；类内 `// ---` 分组。
- **防 flaky**：固定 epoch 常量，不读系统时钟；Compose 查询用真实语义节点/testTag（产品要求）。
- minSdk 路径验证：DatabaseTest / ProgressRecorderTest / ComposeFlowTest 加 `@Config(sdk = [26])`（JVM 上跑 API 26 android-all 镜像）。
- Room schema 目录 `app/schemas` 挂为 test assets（MigrationTestHelper 需要）——升 DB 版本先导出快照。
- 测试堆限 512m + SerialGC + UTF-8（build.gradle.kts testOptions）——内存问题响亮失败。

## 覆盖表
| 域 | 文件 | @Test |
|----|------|-------|
| insight | InsightEngineTest(42) + SignalsCalculatorTest(8) | 50 |
| importer | WordPackParserTest(34) + CsvCodecTest(16) | 50 |
| exam | ExamGeneratorTest(19) + ExamScoringTest(19) | 38 |
| study | StudySessionEngineTest(28) + NextStepPlannerTest(9) | 37 |
| stats | StatsCalculatorTest | 34 |
| srs | SpacedRepetitionTest | 26 |
| motivation | MotivationEngineTest(21) + StartupTipTest(6) | 27 |
| search | ApproximateWordMatcherTest | 9 |
| mistake / model | —（无专测，间接覆盖） | 0 |

## 覆盖缺口（按优先级）
`ui/ViewModels.kt` → 7 个 Repository（仅 ProgressRecorder/seed 有直测）→ `work/StudyReminderWorker` → `core/json`、`core/result`、`data/io`、`data/settings`、`data/speech`、`data/mapper`、`ui/navigation`、多数 Screen（Home/Study/Exam/Stats/Profile/Onboarding/DataTransfer）、`AppContainer`、`MainActivity`。

## ANTI-PATTERNS
- **禁止**删除/改弱失败测试来"通过"；改根 ANTI-PATTERNS 所列守护行为（禁用词、考试页不可朗读、version 双处同步）前先看对应测试。
- **禁止**测试读系统时钟或依赖执行顺序（固定时间戳 + 独立用例）。
- 新增 minSdk 相关路径（API 26 分支）须配 `@Config(sdk=[26])` 用例。

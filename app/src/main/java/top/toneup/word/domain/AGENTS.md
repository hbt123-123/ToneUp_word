# domain/ — 纯 Kotlin 业务引擎

## OVERVIEW
零 `android.*` 依赖的业务规则层；全部可在 JVM 单测直跑（319 个 @Test / 15 文件）。

## STRUCTURE
```
domain/
├── model/       Word(含 v3 笔记/释义/网关字段), WordWithProgress, CetLevel, normalizeWordKey（根，无域依赖）
├── srs/         SpacedRepetition(SM-2), WordProgress, ReviewGrade(UNKNOWN/FUZZY/MASTERED), MasteryStatus, SrsConstants（根）
├── study/       StudyQueueBuilder, StudySessionEngine 状态机, NextStepPlanner(中文文案), TodayPlan/SessionSummary
├── exam/        ExamGenerator(ExamBlueprint.seed 确定性), ExamScoring 纯函数, ExamQuestionType
├── insight/     InsightEngine(R1–R8, 阈值全 const val), SignalsCalculator(7/14/21 天窗口)
├── motivation/  MotivationEngine(多项式哈希选句), StartupTipParser/Picker（读 assets）
├── importer/    WordPackParser→Outcome<ImportPreview>, PlainTextWordParser(粘贴/分享/剪贴板), 手写 RFC4180 CsvCodec, ImportModels
├── mistake/     MistakeCategory(6 类)/MistakeFilter —— 纯模型，无引擎
├── search/      ApproximateWordMatcher（OSA 编辑距离，含相邻换位）
├── stats/       StatsCalculator（连续天数/百分比 null≠0%）, DailyStudyPoint
└── update/      AppVersion 解析/比较 + AppUpdatePolicy（网络无关的更新决策）
```

## WHERE TO LOOK
| 任务 | 位置 |
|------|------|
| 改间隔重复规则 | `srs/SpacedRepetition.kt`（26 测试守护） |
| 改出题/评分 | `exam/ExamGenerator.kt` + `ExamScoring.kt` |
| 改首页「下一步」 | `study/NextStep.kt`（文案内嵌） |
| 改 8 条学习分析 | `insight/InsightEngine.kt`（阈值/禁用词测试守护） |
| 改文件导入校验限额 | `importer/WordPackParser.kt`（8 MiB / 20000 条 / 64 字符） |
| 改纯文本/分享/剪贴板解析 | `importer/PlainTextWordParser.kt`（分隔符宽容、token 严格、大小写去重） |
| 改选句/选语逻辑 | `motivation/MotivationEngine.kt`（哈希确定性） |
| 改更新判定规则 | `update/AppUpdate.kt`（versionCode 优先，语义版本仅回落；有 20 测试） |

## CONVENTIONS
- **依赖图（无环）**：`model`、`srs` 为根；`study→srs+model`；`mistake→model+srs`；`stats→model+srs`；`insight→stats`（唯一跨业务模块依赖）；`importer→model+core.result+core.json`；`exam`/`search`/`MotivationEngine` 无域依赖（考试结果经 `srs.ReviewGrade.fromExam` 回写）。
- domain **从不** import `data`/`ui`/`work`（grep 验证为 0）；仅有的 core 依赖是 `core.result` 与 `core.json`（限于 importer 与 StartupTip）。
- JVM 例外：`java.time.LocalDate`（stats/insight/mistake）、`kotlinx.serialization`（importer/StartupTip DTO）、`kotlin.random.Random`（study/exam/motivation）。
- 引擎 = 无状态 `object`；输入输出 = `data class`；时间/随机一律**参数注入**（`nowMillis`/`today`/seed），domain 不 import `core.time`——04:00 学习日边界由 data 层对齐后喂入。
- 魔数提升为对象 `const val`（InsightEngine R1–R8 阈值、SrsConstants、`PlainTextWordParser.MAX_WORDS/MAX_WORD_LENGTH`）；测试读常量不抄数字。
- 中文 UI 文案内嵌枚举/`describe()`，使文案可测。
- 文件/文本解析失败用 `Outcome<T>` 值返回（**绝不抛异常给 UI**）；统计无数据 = `null` 非 0%（「焦虑机器」原则：数据不足只说「数据有限」）。
- KDoc 写「为什么这样设计」的产品理由，非代码复述。

## ANTI-PATTERNS
- **禁止**在引擎内抛异常给 UI；解析类一律 `Outcome`，其余靠 `coerce*` 与默认值防御。
- **禁止**给 `mistake/`、`model/` 加逻辑却无测试（当前 0 专测，间接覆盖）。
- 洞察文案禁用词由 `InsightEngineTest` 守护（分数/提分/必过/保过/考试/还差/剩余/倒数）——改文案先看测试。

## 测试速览
insight 50 · importer 78 · exam 38 · study 37 · stats 34 · motivation 27 · srs 26 · search 9 · update 20；mistake/model 0（间接覆盖）。运行见根 COMMANDS。

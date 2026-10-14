# data/ — Room + Repository + 数据适配层

## OVERVIEW
单库 `toneup_word.db` v1（10 表 1 视图 8 DAO）；Repository 是唯一事务边界与失败收敛点。

## STRUCTURE
```
data/
├── local/       ToneUpWordDatabase, Migrations(ALL_MIGRATIONS 空), Converters(仅结构 JSON)
│   ├── entity/  WordEntities(word_pack,word) / ProgressEntities(progress,event,wrong) / SessionEntities(5 表)
│   └── dao/     10 文件 = 8 @Dao + WordWithProgressView(视图) + QueryRows(行投影) ← 非全是 DAO
├── repository/  Word/Study/Mistake/Exam/Stats/Insight/Backup + ProgressRecorder(具体类)
├── seed/        BuiltInPackLoader(manifest), BuiltInWordPacks(CC0 回退), SeedWord
├── settings/    DataStore "toneup_settings"（IOException 才降级空偏好）
├── speech/      SpeechService 接口, SystemTtsSpeechService(单实例), SpeechDiagnosticsStore(仅内存计数)
├── io/          DocumentIo（SAF，大小上限，无存储权限）
├── mapper/      Mappers.kt 单文件显式枚举映射
└── motivation/  StartupTipSource（ASSET_PATH="motivation/tips.json"）
```

## WHERE TO LOOK
| 任务 | 位置 | 备注 |
|------|------|------|
| 答题写入语义 | `repository/ProgressRecorder.kt` | 全 app 唯一答题事务写入口（progress+event+wrong+日汇总）；AUTO_RESOLVE_STREAK=3 |
| 播种/升级内置词库 | `repository/WordRepository.ensureBuiltInPacks/seedPack` + `seed/BuiltInPackLoader` | 400 行/事务，身份映射保 row id |
| 升数据库版本 | `local/Migrations.kt` + `ToneUpWordDatabase` version 字面量 | 两处同步（有测试断言）+ 导出 `app/schemas/` 快照 |
| 考试防重复交卷 | `repository/ExamRepository` | createDraft 先弃旧草稿；saveAnswer 不判对错（防答案键泄露） |
| 备份导出/恢复 | `repository/BackupRepository.kt`（657 行热点） | restore 按 level 先删后插（幂等） |
| 搜索常量 | `WordRepository.Companion` | SEARCH_LIMIT=200, APPROX_LIMIT=12, STRICT=8, CANDIDATE=4000 |

## CONVENTIONS
- **事务只在 Repository 层**用 `withTransaction`；跨表写必同事务；DAO 自身不开事务。
- DAO 全 `suspend`；观察用 `Flow`；`Dispatchers.IO` 仅资产/文件（BuiltInPackLoader/DocumentIo/StartupTipSource），`Dispatchers.Default` 仅编辑距离排序；播种 chunk 间 `yield()`。
- 时间统一注入 `TimeProvider`；学习日边界 `core/time/StudyDay`（04:00）。
- 可能失败的仓库方法返回 `Outcome<T>`；异常 `catch(Throwable)` → `AppError.database/io`。
- SQL 兼容 SQLite 3.18/API 26：**无 SQL UPSERT 子句/窗口函数/RETURNING**（Room `@Upsert` 注解可用——它是查后插改）。
- `LIKE ... ESCAPE '!'`，转义实现重复于 `WordRepository.likePattern` 与 `MistakeRepository.likePattern`。
- 播种「已最新」判定：pack 存在 **且** version 相等 **且** storedCount ≥ manifest.wordCount（wordCount=0 跳过计数）。

## ANTI-PATTERNS / 坑点
- `user_word_progress` **无 level 列**，按 level 过滤必须 JOIN `word`；视图 `status IS NULL` = 新词。
- `daily_study_summary` 读改写只在调用方事务内安全（`bumpDailySummary` 注释明示）。
- 失败导入的 `import_history` 记录写在**已回滚事务之外**（runCatching）——故意，勿"修"。
- `clearAllData()` 只删 `word` 不删 `word_pack` 行（导入包记录残留，内置包靠计数判定重播）。
- `StatsRepository.stats()`（一次性）`highMistakes` 恒 0——完整数据走 `observeStats()`。
- `BackupRepository.buildFullBackup` 有 N+1 查询（逐 event `byId`）。
- `setFavorite` 会为未学过的词补建 progress 行。
- SQLite 3.18 约束：升级迁移勿用新语法。

## 测试
`data/` 下：DatabaseTest（全 DAO + version 断言）、ProgressRecorderTest（28）、BuiltInPackSeedTest（升级保 id）、BuiltInPackAssetTest（资产完整性）。其余 7 个 Repository **无直接测试**。

# data/ — Room + Repository + 数据适配层

## OVERVIEW
单库 `toneup_word.db` v3（11 表 1 视图 9 DAO）；Repository 是唯一事务边界与失败收敛点。学习范围自 v2 起按 **`packId`**（不再按 `level`）隔离。`data/gateway/` 与 `data/update/` 是**全 app 仅有的两个联网点**（网关释义补全 / 应用内版本更新）。

## STRUCTURE
```
data/
├── local/       ToneUpWordDatabase(version=3), Migrations(MIGRATION_1_2, MIGRATION_2_3), Converters(仅结构 JSON)
│   ├── entity/  WordEntities(word_pack, word, meaning_cache) / ProgressEntities(progress,event,wrong) / SessionEntities(5 表)
│   └── dao/     11 文件 = 9 @Dao + WordWithProgressView(视图) + QueryRows(行投影) ← 非全是 DAO
├── repository/  Word/Study/Mistake/Exam/Stats/Insight/Backup/MeaningSync/AppUpdate + ProgressRecorder(具体类)
├── gateway/     GatewayConfig/Defaults/Dtos/Api/ClientProvider + MeaningLookupService + LookupResult + EnResponseSerializer（联网点 1）
├── update/      UpdateManifestSource(固定 version.json + 镜像回退) + AppUpdateModels/Defaults/Store + ApkDownloader(断点续传 + SHA-256) + ApkInstaller（联网点 2，应用内版本更新）
├── seed/        BuiltInPackLoader(manifest), BuiltInWordPacks(CC0 回退), SeedWord
├── settings/    DataStore "toneup_settings"（IOException 才降级空偏好）+ PackIds(内置/保留包 id + level↔pack 映射)
├── speech/      SpeechService 接口, SystemTtsSpeechService(单实例), AudioUrlPlayer(在线发音失败回落 TTS), SpeechDiagnosticsStore(仅内存计数)
├── io/          DocumentIo（SAF，大小上限，无存储权限）
├── mapper/      Mappers.kt 单文件显式枚举映射
└── motivation/  StartupTipSource（ASSET_PATH="motivation/tips.json"）
```

## WHERE TO LOOK
| 任务 | 位置 | 备注 |
|------|------|------|
| 答题写入语义 | `repository/ProgressRecorder.kt` | 全 app 唯一答题事务写入口（progress+event+wrong+日汇总）；`packIdFor(wordId)` 读 `word.pack_id`；AUTO_RESOLVE_STREAK=3 |
| 播种/升级内置词库 | `repository/WordRepository.ensureBuiltInPacks/seedPack` + `seed/BuiltInPackLoader` | 400 行/事务，身份映射保 row id |
| 词库 CRUD/导入 | `repository/WordRepository` | `observePacks/createPack/renamePack/deletePack/addWordsToPack`；内置包禁删 |
| 单词级编辑 | `repository/WordRepository.saveUserNote/saveManualMeaning` | 手动释义置 `meaning_source='manual'`（永久保护） |
| 升数据库版本 | `local/Migrations.kt` + `ToneUpWordDatabase` version 字面量 | 两处同步（有测试断言）+ 导出 `app/schemas/` 快照 |
| 释义补全编排 | `repository/MeaningSyncRepository.kt` | pending 计数 / unique work 入队 / 单词级即时查 / `applyLookupToWord`（3 处 manual 守卫） |
| 级联查询 + 缓存 | `gateway/MeaningLookupService.kt` + `gateway/GatewayClientProvider.kt` | ECDICT→FreeDict→LLM；`meaning_cache` 24h 负缓存；https 校验 |
| 应用内更新 | `update/` + `repository/AppUpdateRepository.kt` | 固定 `version.json` + 镜像回退 + SHA-256 + 断点续传；`StateFlow<AppUpdateUiState>`；契约 `docs/UPDATE_PROTOCOL.md` |
| 考试防重复交卷 | `repository/ExamRepository` | createDraft 先弃旧草稿；saveAnswer 不判对错（防答案键泄露） |
| 备份导出/恢复 | `repository/BackupRepository.kt`（741 行热点） | restore 按 pack 先删后插（幂等） |
| 搜索常量 | `WordRepository.Companion` | SEARCH_LIMIT=200, APPROX_LIMIT=12, STRICT=8, CANDIDATE=4000 |

## CONVENTIONS
- **事务只在 Repository 层**用 `withTransaction`；跨表写必同事务；DAO 自身不开事务。
- **`packId` 是唯一学习范围**：DAO SQL 全用 `WHERE pack_id`（`word`/视图已带 pack_id，其余表走 v2 新列）；`UserSettings.effectivePackId` 由 `activePackId` 或 `PackIds.defaultPackIdFor(level)` 派生。
- DAO 全 `suspend`；观察用 `Flow`；`Dispatchers.IO` 仅资产/文件（BuiltInPackLoader/DocumentIo/StartupTipSource），`Dispatchers.Default` 仅编辑距离排序；播种 chunk 间 `yield()`。
- 时间统一注入 `TimeProvider`；学习日边界 `core/time/StudyDay`（04:00）。
- 可能失败的仓库方法返回 `Outcome<T>`；异常 `catch(Throwable)` → `AppError.database/io/config`（`config` = 网关 https/401 配置错误）。
- 网关：`GatewayClientProvider` 监听配置变化自动重建 HTTP 栈；`baseUrl` 强制 **https**（http → `AppError.config`）；仅 debug 构建打印 body 日志且脱敏 `Authorization`。
- SQL 兼容 SQLite 3.18/API 26：**无 SQL UPSERT 子句/窗口函数/RETURNING**（Room `@Upsert` 注解可用——它是查后插改）。
- `LIKE ... ESCAPE '!'`，转义实现重复于 `WordRepository.likePattern` 与 `MistakeRepository.likePattern`。
- 播种「已最新」判定：pack 存在 **且** version 相等 **且** storedCount ≥ manifest.wordCount（wordCount=0 跳过计数）。

## ANTI-PATTERNS / 坑点
- `word` 唯一身份是 **`(pack_id, normalized_word)`**（v2 起；旧 `(level, normalized_word)` 已 DROP）——同词可入多包，进度跟行走、按包隔离。
- `user_word_progress` **无 level 列**，按 level 过滤必须 JOIN `word`；视图 `status IS NULL` = 新词。
- `daily_study_summary` PK 现为 **`(day_key, pack_id)`**（v2 重建表），保留 `level` 列仅作迁移/展示；读改写只在调用方事务内安全（`bumpDailySummary`）。
- 迁移无法归属的历史行归保留 id `legacy-other`（`PackIds.LEGACY_OTHER`，D9/Q1）——保数据不丢，但**不参与任何词库统计**。
- v3 视图迁移为纯 DDL（DROP+CREATE），SQL 必须与 `WordWithProgressView` 的 `@DatabaseView` 注解**逐字节一致**（Room 校验）——改视图必须同步 `Migrations.WORD_WITH_PROGRESS_VIEW_SQL` 与 `3.json`。
- 失败导入的 `import_history` 记录写在**已回滚事务之外**（runCatching）——故意，勿"修"。
- `clearAllData()` 只删 `word` 不删 `word_pack` 行（导入包记录残留，内置包靠计数判定重播）。
- `StatsRepository.stats()`（一次性）`highMistakes` 恒 0——完整数据走 `observeStats()`。
- `BackupRepository.buildFullBackup` 有 N+1 查询（逐 event `byId`）。
- `setFavorite` 会为未学过的词补建 progress 行。
- `meaning_cache` 是**全局按 `normalized_word` 共享**的查询缓存（跨词库命中）；`status=FAILED` 为负缓存，TTL 24h；手动释义的词不查（manual 守卫）。
- SQLite 3.18 约束：升级迁移勿用新语法。

## 测试
`data/` 下：DatabaseTest（全 DAO + version 断言）、ProgressRecorderTest(20)、Migration1To2Test(3，种子保真+索引语义)、Migration2To3Test(2，视图 DDL)、PackIdsTest(4)、AddWordsToPackTest(18)、WordEditRepositoryTest(11)、gateway/MeaningLookupServiceTest(12)、gateway/MeaningSyncRepositoryTest(10)、seed/BuiltInPackSeedTest(7)、seed/BuiltInPackAssetTest(8)、speech/AudioUrlPlayerTest(4)、update/ApkUpdateDownloaderTest(10)、update/UpdateManifestSourceTest(7)、AppUpdateRepositoryTest(14)、domain/update/AppVersionTest(20)。其余 Repository（Study/Mistake/Exam/Stats/Insight/Backup）**无直接测试**。

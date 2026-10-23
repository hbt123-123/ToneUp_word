# data/ — Room + Repository + 数据适配层

## OVERVIEW
单库 `toneup_word.db` v4（12 表 1 视图 10 DAO）；Repository 是唯一事务边界与失败收敛点。学习范围自 v4 起按 **`planId`**（计划内所有词库合并、视图按 `(plan_id, normalized_word)` 去重），`packId` 只用于词库管理与导入这类 pack 维度操作。`data/gateway/` 与 `data/update/` 是**全 app 仅有的两个联网点**（网关释义补全 / 应用内版本更新）。

## STRUCTURE
```
data/
├── local/       ToneUpWordDatabase(version=4), Migrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4), Converters(仅结构 JSON)
│   ├── entity/  PlanEntities(plan) / WordEntities(word_pack, word, meaning_cache) / ProgressEntities(progress,event,wrong) / SessionEntities(5 表)
│   └── dao/     12 文件 = 10 @Dao + WordWithProgressView(视图) + QueryRows(行投影) ← 非全是 DAO
├── repository/  Word/Study/Mistake/Exam/Stats/Insight/Backup/MeaningSync/AppUpdate + ProgressRecorder(具体类)
├── gateway/     GatewayConfig/Defaults/Dtos/Api/ClientProvider + MeaningLookupService + LookupResult + EnResponseSerializer（联网点 1）
├── update/      UpdateManifestSource(固定 version.json + 镜像回退) + AppUpdateModels/Defaults/Store + ApkDownloader(断点续传 + SHA-256) + ApkInstaller（联网点 2，应用内版本更新）
├── seed/        BuiltInPackLoader(manifest), BuiltInWordPacks(CC0 回退), SeedWord
├── settings/    DataStore "toneup_settings"（IOException 才降级空偏好）+ PackIds(内置/保留包 id + level↔pack 映射) + PlanIds(默认计划/手写词库 id/计划→等级)
├── speech/      SpeechService 接口, SystemTtsSpeechService(单实例), AudioUrlPlayer(在线发音失败回落 TTS), SpeechDiagnosticsStore(仅内存计数)
├── io/          DocumentIo（SAF，大小上限，无存储权限）
├── mapper/      Mappers.kt 单文件显式枚举映射
└── motivation/  StartupTipSource（ASSET_PATH="motivation/tips.json"）
```

## WHERE TO LOOK
| 任务 | 位置 | 备注 |
|------|------|------|
| 答题写入语义 | `repository/ProgressRecorder.kt` | 全 app 唯一答题事务写入口（progress+event+wrong+日汇总）；`WordScope(planId, packId)` 读自 `word` 行（`planId` 缺省回落 `PlanIds.DEFAULT_PLAN`）；AUTO_RESOLVE_STREAK=3 |
| 计划 CRUD/迁移词库 | `repository/WordRepository.kt`（plan 段）+ `local/dao/PlanDao.kt` | `ensureDefaultPlan/observePlans/createPlan/renamePlan/deletePlan/movePackToPlan/ensureWritePack`；默认计划禁删、含词库的计划拒删 |
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
- **`planId` 是唯一学习范围**：DAO SQL 全用 `WHERE plan_id`（`word`/视图已带 `plan_id`，其余表走 v4 新列）；`UserSettings.effectivePlanId` 由 `activePlanId` 或默认计划派生。**包维度**（`countInPack`/`allForPack`/`byNormalized`/`idsForPack`）继续用 `pack_id`。
- DAO 全 `suspend`；观察用 `Flow`；`Dispatchers.IO` 仅资产/文件（BuiltInPackLoader/DocumentIo/StartupTipSource），`Dispatchers.Default` 仅编辑距离排序；播种 chunk 间 `yield()`。
- 时间统一注入 `TimeProvider`；学习日边界 `core/time/StudyDay`（04:00）。
- 可能失败的仓库方法返回 `Outcome<T>`；异常 `catch(Throwable)` → `AppError.database/io/config`（`config` = 网关 https/401 配置错误）。
- 网关：`GatewayClientProvider` 监听配置变化自动重建 HTTP 栈；`baseUrl` 强制 **https**（http → `AppError.config`）；仅 debug 构建打印 body 日志且脱敏 `Authorization`。
- SQL 兼容 SQLite 3.18/API 26：**无 SQL UPSERT 子句/窗口函数/RETURNING**（Room `@Upsert` 注解可用——它是查后插改）。
- `LIKE ... ESCAPE '!'`，转义实现重复于 `WordRepository.likePattern` 与 `MistakeRepository.likePattern`。
- 播种「已最新」判定：pack 存在 **且** version 相等 **且** storedCount ≥ manifest.wordCount（wordCount=0 跳过计数）。

## ANTI-PATTERNS / 坑点
- `word` 行唯一身份是 **`(pack_id, normalized_word)`**（v2 起）；学习可见性由视图在 **`(plan_id, normalized_word)`** 内只投影一行（优先已有进度行，其次最小 id）——同词可入多包，同一计划内共用一份进度。
- `user_word_progress` **无 level 列**，按 level 过滤必须 JOIN `word`；视图 `status IS NULL` = 新词。
- `daily_study_summary` PK 现为 **`(day_key, plan_id)`**（v4 重建表，旧 `pack_id` 列已移除），保留 `level` 列仅作迁移/展示；读改写只在调用方事务内安全（`bumpDailySummary`）。
- 迁移无法归属的历史行归保留 id `legacy-other`（`PackIds.LEGACY_OTHER`，D9/Q1）——保数据不丢，但**不参与任何词库统计**。
- v4 视图迁移为纯 DDL（DROP+CREATE），SQL 必须与 `WordWithProgressView` 的 `@DatabaseView` 注解**逐字节一致**（Room 校验）——改视图必须同步 `Migrations.WORD_WITH_PROGRESS_VIEW_SQL` 与 `4.json`。v3 常量保留给 2→3 的路径，不要删。
- 失败导入的 `import_history` 记录写在**已回滚事务之外**（runCatching）——故意，勿"修"。
- `clearAllData()` 只删 `word` 不删 `word_pack` 行（导入包记录残留，内置包靠计数判定重播）。
- `StatsRepository.stats()`（一次性）`highMistakes` 恒 0——完整数据走 `observeStats()`。
- `BackupRepository.buildFullBackup` 有 N+1 查询（逐 event `byId`）。
- `setFavorite` 会为未学过的词补建 progress 行。
- `meaning_cache` 是**全局按 `normalized_word` 共享**的查询缓存（跨词库命中）；`status=FAILED` 为负缓存，TTL 24h；手动释义的词不查（manual 守卫）。
- SQLite 3.18 约束：升级迁移勿用新语法。

## 测试
`data/` 下：DatabaseTest（全 DAO + version 断言）、ProgressRecorderTest(20)、Migration1To2Test(3，链式 1→4 种子保真)、Migration2To3Test(2，视图 DDL)、Migration3To4Test(2，plan 回填+日汇总合并+视图去重)、PackIdsTest(4)、PlanIdsTest(3，默认计划/等级派生/手写词库 id)、PlanRepositoryTest(8，计划 CRUD/移动词库/去重计数/进度移交/ensureWritePack)、AddWordsToPackTest(21)、WordEditRepositoryTest(11)、gateway/MeaningLookupServiceTest(12)、gateway/MeaningSyncRepositoryTest(10)、seed/BuiltInPackSeedTest(7)、seed/BuiltInPackAssetTest(6)、speech/AudioUrlPlayerTest(4)、update/ApkUpdateDownloaderTest(13)、update/UpdateManifestSourceTest(11)、AppUpdateRepositoryTest(18)、domain/update/AppVersionTest(23)。其余 Repository（Study/Mistake/Exam/Stats/Insight/Backup）**无直接测试**。

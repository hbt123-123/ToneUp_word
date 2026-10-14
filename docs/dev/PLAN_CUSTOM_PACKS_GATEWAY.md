# 自建词库 + 网关释义补全 — 开发计划

> 状态：**待评审** · 起草：架构评审会 · 基线：commit e98ea83（Room v1，404 单测）
> 本文档是后续编码实现的唯一指导蓝图；实现过程中若偏离，须回写本文档。

---

## 1. 背景与目标

原 CetVocab（fork 后更名 ToneUp Word，包名 `top.toneup.word`）是纯离线四六级词典应用。本计划将其改造为**用户完全自定义词库**的单词学习工具：

1. 用户创建/重命名/删除自己的词库，粘贴或 Share Intent 导入单词（通常只有英文单词，无释义）。
2. App 通过**自建 Cloudflare 翻译网关**（API 文档：仓库根 `网关使用文档.md`）自动补全释义、音标、词性。
3. 剪贴板自动识别 + Share Intent 快速导入。
4. **离线优先**：联网仅用于释义补全；网关地址/密钥/路径可配置；失败不阻塞入库。

### 硬性约束变更（需同步修订项目文档）

- 现行 AGENTS.md 反模式「禁止新增 INTERNET 权限」与本需求**直接冲突**。本计划即解禁依据：
  - M0 阶段更新 `AndroidManifest.xml` 注释、`AGENTS.md`、`docs/dev/PRODUCT_OPTIMIZATION_PLAN.md`，将约束改写为
    「仅 `INTERNET` 用于释义补全网关；无账号/广告/埋点；剪贴板原文永不出本地」。
  - **禁止**再扩展到其他联网能力（同步、统计、远程音频下载仍不做）。
- 隐私红线：只上传**需要查询的单词本身**（及可选 `?key=`）；剪贴板/分享的原始文本仅本地解析。
- **本机构建规则**：禁止在本机编译/引入 ktlint（及 detekt/spotless 等同类格式化检查）；验证一律走现有文件自验证 = `.\gradlew.bat :app:testDebugUnitTest` + `:app:assembleDebug`（见 §11）。
- **无历史包袱**：本项目未上线，不存在真实用户数据——迁移回填按「内置包映射 + 其余归 `legacy-other`」一次写完即可，不做迁移工具（§13-Q1 已关闭）。

---

## 2. 现状盘点（与改造相关的代码事实）

| 事实 | 位置 | 影响 |
|------|------|------|
| Room v1，`ALL_MIGRATIONS` 为空，schema 快照在 `app/schemas/` | `data/local/Migrations.kt`、`ToneUpWordDatabase.kt:71` | 需手写 Migration 1→2 + 导出 2.json |
| `word` 唯一索引 `(level, normalized_word)` | `entity/WordEntities.kt:46` | 自定义词库按 pack 去重前必须改索引 |
| `word_with_progress` 视图已暴露 `pack_id` | `dao/WordWithProgressView.kt:30` | 按 pack 过滤可直接查视图 |
| `review_event`/`wrong_answer`/`study_session`/`exam_session` 均冗余 `level` 列；`daily_study_summary` PK=(day_key, level) | `entity/ProgressEntities.kt:84,127`、`SessionEntities.kt:22,55,126` | 纯按词库学习必须补 `pack_id` 维度 |
| 过滤按 level：`ProgressDao`/`MistakeBookDao`/`ReviewEventDao`/`DailySummaryDao` 全部 `WHERE level=:level` | `data/local/dao/*` | SQL 全量改为 `pack_id` |
| Repository/VM/Worker 以 `settings.level` 为学习范围（~59 处） | `StudyRepository.kt:123`、`StatsRepository.kt:49`、`MistakeRepository.kt:62`、`InsightRepository.kt:48`、`ExamRepository`、`work/StudyReminderWorker.kt:59`、`ui/ViewModels.kt` 多处 | level→packId 全仓扫描 |
| `ProgressRecorder.levelIdFor(wordId)` 读 `word.level` | `ProgressRecorder.kt:99` | 改读 `pack_id` |
| `Routes.PACKS` 已声明但 NavHost 未接线 | `navigation/Routes.kt:37`、`ToneUpWordNavHost.kt` | 词库管理页现成挂点 |
| 词库实体已支持自定义包（`is_built_in=0`）、`importPack`/`deleteImportedPack` 已存在 | `WordRepository.kt:392,501` | 新建/重命名/删除补齐即可 |
| 导入解析器已有 JSON/CSV（`WordPackParser`，20000 行/8MiB 限额） | `domain/importer/` | 新增纯文本解析器并列 |
| Manifest 仅 `POST_NOTIFICATIONS`，MainActivity 无 `onResume`/`onNewIntent`，仅 LAUNCHER filter | `AndroidManifest.xml:17,36` | 加 INTERNET、Share filter、singleTop、剪贴板读取 |
| 依赖无任何 Retrofit/OkHttp；已有 kotlinx-serialization 1.11.0、coroutines 1.11.0 | `gradle/libs.versions.toml` | 网络栈从零引入 |
| `CetLevel.OTHER`「自定义词库」已存在但 `selectable` 只有 CET4/CET6 | `domain/model/CetLevel.kt:12,19` | 保留作词库 level 元数据 |
| 单测基线 404 用例 / 26 类；JVM 堆 512m；SQL 须兼容 SQLite 3.18（无 UPSERT 语句/窗口函数） | `app/build.gradle.kts`、data/AGENTS.md | 迁移 SQL 与测试约束 |

---

## 3. 关键架构决策

| # | 决策 | 理由 |
|---|------|------|
| D1 | **纯按词库学习（已确认）**：学习范围 = `packId`。Repository/DAO/VM/Worker 的 level 参数全部替换为 packId；`CetLevel` 保留为词库元数据（内置包标 CET4/CET6，自定义包标 OTHER）。 | 用户已选定；统计/错题/考试天然按词库隔离，语义最清晰。 |
| D2 | 单词行按 **(pack_id, normalized_word)** 唯一；同一单词可出现在多个词库，**进度跟行走、按包隔离**。 | 「每个词库独立存储单词及其学习进度」的直接落地；旧索引按 level 会让第二个自定义包"吞并"第一个包的行。 |
| D3 | 释义查询结果缓存在独立表 `meaning_cache`（按 normalized_word 全局共享），写回 word 行。 | 同一单词跨词库/重复导入不重复请求；不覆盖手动编辑。 |
| D4 | 释义状态机挂在 word 行：`lookup_state` ∈ `NONE/OK/PENDING/FAILED`；`meaning_source` ∈ `ecdict/freecdict/mt/manual`。`manual` 永不被自动覆盖。 | 满足「待补充」「修改后不再被覆盖」。 |
| D5 | 后台补全用 **WorkManager 唯一任务**（复用现有 `work/` 设施），约束 = 联网 + 可选「仅 Wi-Fi」（`NetworkType.UNMETERED`）；顺序节流 250ms/词，401 立即停机并提示配置错误。 | 网关免费额度 10 万次/日；避免导出大批单词时打爆。 |
| D6 | 网络栈：**Retrofit + OkHttp + kotlinx-serialization converter**，全部配置（baseUrl、key、三个 path）走 DataStore 设置项，默认值 = 文档网关。 | 需求指定；可配置满足「用户可更换网关」。 |
| D7 | `baseUrl` 校验强制 **https**（默认 `https://gateway.lztfirefly.top`）。 | 个人应用不引入 cleartext 放开整包的安全代价；如确需 http 后续再评估 networkSecurityConfig。 |
| D8 | 新 ViewModel 继续按约定**追加到 `ui/ViewModels.kt` 分节**（不另开文件），工厂追加到 `ViewModelFactories`。 | 遵守项目单文件聚合约定。 |
| D9 | 迁移中无法归属的历史 OTHER 级汇总/会话行，归入保留 id `legacy-other`，不参与任何词库统计展示，仅保数据不丢。 | daily_summary 的 OTHER 行无法拆回各包；宁可隐藏不可删史。 |

---

## 4. 数据层改造（Room v1 → v2）— 关键路径

### 4.1 `word` 表加列（`Migrations.kt` 追加 `MIGRATION_1_2`，SQL 全部兼容 SQLite 3.18）

```sql
ALTER TABLE word ADD COLUMN user_note TEXT NOT NULL DEFAULT '';
ALTER TABLE word ADD COLUMN lookup_state TEXT NOT NULL DEFAULT 'OK';   -- NONE/OK/PENDING/FAILED
ALTER TABLE word ADD COLUMN meaning_source TEXT NOT NULL DEFAULT '';   -- ecdict/freecdict/mt/manual
ALTER TABLE word ADD COLUMN en_definition TEXT NOT NULL DEFAULT '';    -- FreeDict 英文释义
ALTER TABLE word ADD COLUMN audio_url TEXT NOT NULL DEFAULT '';        -- FreeDict 发音（在线播放，失败回落 TTS）
ALTER TABLE word ADD COLUMN lookup_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE word ADD COLUMN last_lookup_at INTEGER NOT NULL DEFAULT 0;
-- 既有行回填：有释义 → OK/ecdict（内置包与文件导入）；空释义 → PENDING（留给补全）
UPDATE word SET lookup_state = CASE WHEN meanings = '[]' AND example_sentence = '' THEN 'PENDING' ELSE 'OK' END;
UPDATE word SET meaning_source = CASE WHEN lookup_state = 'OK' THEN 'file' ELSE '' END;
-- D2：保证 pack_id 非空后换唯一索引
UPDATE word SET pack_id = CASE level WHEN 'CET4' THEN 'builtin-cet4' WHEN 'CET6' THEN 'builtin-cet6' ELSE 'legacy-' || level END WHERE pack_id IS NULL;
DROP INDEX index_word_level_normalized_word;
CREATE UNIQUE INDEX index_word_pack_normalized_word ON word(pack_id, normalized_word);
CREATE INDEX index_word_normalized ON word(normalized_word);
```

### 4.2 新表 `meaning_cache`（跨包查询缓存）

```sql
CREATE TABLE meaning_cache (
  normalized_word TEXT NOT NULL PRIMARY KEY,
  payload         TEXT NOT NULL,   -- JSON：音标/词性/中英释义/例句/同义词/audio_url
  source          TEXT NOT NULL,   -- ecdict/freecdict/mt
  status          TEXT NOT NULL,   -- OK/FAILED（负缓存，避免反复打未命中词）
  fetched_at      INTEGER NOT NULL
);
```

### 4.3 各表补 `pack_id` 维度

| 表 | 变更 | 回填 |
|----|------|------|
| `review_event` | `ADD pack_id TEXT NOT NULL DEFAULT ''` + 索引 | `UPDATE review_event SET pack_id = (SELECT pack_id FROM word WHERE word.id = review_event.word_id)`（FK CASCADE 保证命中） |
| `wrong_answer` | 同上 | 同上 |
| `study_session` | `ADD pack_id` | 按 level 映射：CET4→builtin-cet4，CET6→builtin-cet6，OTHER→`legacy-other` |
| `exam_session` | 同上 | 同上 |
| `daily_study_summary` | **重建表**：PK 改 `(day_key, pack_id)`，保留 level 列仅作旧数据迁移映射 | 同上映射；CET4/CET6 行归内置包，OTHER 行归 `legacy-other`（D9） |
| `import_history` | 已有 pack_id，不动 | — |

### 4.4 版本与测试同步（三处一致，有测试断言）

1. `ToneUpWordDatabase` `version = 2`；2. `Migrations.CURRENT_VERSION = 2`、`ALL_MIGRATIONS += MIGRATION_1_2`；3. 导出 `app/schemas/.../2.json`。
4. 迁移测试：装入 1.json 灌种子数据 → 跑 1→2 → 断言 word 行 id 不变、进度/事件/汇总不丢、新唯一索引生效（同词可入两包、同包内幂等）。
5. `DatabaseTest` 版本断言同步更新。

### 4.5 level → packId 全仓扫描（M1 主体工作量）

- **DAO SQL**：`ProgressDao`（due/fragile/new/mostWrong/statusCounts/favorites/… 14+ 查询）、`MistakeBookDao`（10+）、`ReviewEventDao`（10+）、`DailySummaryDao`（8）、`WordDao`（`byNormalized`/`search`/`countInPack`/`idsForLevel`/`deletePackWords`/`liteForLevel`…）、`ExamDao`、`StudySessionDao` 的 `WHERE level` → `WHERE pack_id`（word/视图已带 pack_id，其余表走 4.3 新列）。
- **Repository 签名**：`WordRepository`（observeWordCount/hasWords/examSources/allWords/search/searchApproximate/deleteImportedPack…）、`StudyRepository`、`StatsRepository`、`MistakeRepository`、`InsightRepository`、`BackupRepository.export(…, level)` → `packId: String`。
- **写路径**：`ProgressRecorder.levelIdFor` → `packIdFor(wordId)`（读 `word.pack_id`）；`bumpDailySummary`/`addStudyDuration`/`addExamResult` 的 levelId → packId。
- **设置**：`UserSettings` 新增 `activePackId: String`（默认按原 `level` 映射为 builtin 包）；`level` 字段暂保留用于引导默认词库选择，后续仅在 onboarding/词库创建处消费。
- **VM/Worker**：`HomeViewModel`、`StudyViewModel`、`ReviewCenterViewModel`、`ExamViewModel`、`StatsViewModel`、`WordBrowserViewModel`、`DataTransferViewModel`、`ProfileViewModel`、`StudyReminderWorker` 全部改用 `activePackId`；`DailyStudySummary` 观察按 packId。
- **备份/恢复**：`BackupRepository.restore` 的「按 level 先删后插」改为「按 pack 先删后插」，导出文件名携带 pack 名。

**M1 验收**：`.\gradlew.bat :app:testDebugUnitTest` 全绿（含迁移测试新增用例）；`assembleDebug` 成功；手工冒烟——四级/六级学习、错题、考试、统计、提醒、备份恢复与改造前行为一致。

---

## 5. 词库管理（M2）

- 接线 `Routes.PACKS` → 新屏幕 `PackListScreen`（`ui/screens/PackLists.kt` 或并入现有 Profile 文件，按行数定）：
  - 列表：`WordRepository.observePacks()`（内置在前、自定义在后），显示名称/词数/当前选中；行内菜单：重命名、删除（**内置包禁删**，已有 `deleteImportedPack` 守卫）、设为当前词库。
  - 新建：对话框输入名称 → `WordDao.upsertPack(id="user-" + 时间戳或 UUID, level="OTHER", is_built_in=false)` → 立即置为 `activePackId` 并进入导入页。
  - 删除确认必须提示：**词与学习进度一并删除**（FK CASCADE 会连带 progress/event/wrong）。
- 入口：首页当前词库 chip（点击弹选择器）+ Profile 页「词库管理」项。
- `word_pack.word_count` 在导入/删除后刷新（现有 `updatePackCount` 已有）。

---

## 6. 单词导入管线（M3）

### 6.1 纯文本解析器（domain 层，纯 Kotlin，可测）

新增 `domain/importer/PlainTextWordParser.kt`：

- 输入整段文本 → 按 `[,\s;\n\r\t]+` 切分 → 每 token 校验 `^[A-Za-z][A-Za-z'-]*{0,63}$`（保留 `a-zA-Z-'`，过滤 URL/中文/数字串/单字母噪声可配置）。
- 过滤即丢弃不合法项并计数（`dropped` 展示给用户）；**大小写不敏感去重**（沿用 `Word.normalizeWordKey`），保持首次出现顺序。
- 上限与 `WordPackParser` 对齐：20 000 条、单 token ≤64 字符；返回 `Outcome<PlainTextImportPreview>`（含 words/dropped/duplicates）。
- 单测覆盖：逗号/分号/空格/换行/混合分隔、大小写重复、非法字符、超长、空输入、全角标点。

### 6.2 导入界面

- 新路由 `Routes.IMPORT_WORDS = "import-words?packId={packId}"`（或从 PACKS 页内嵌）：多行 TextField 粘贴 → 实时解析预览（成功 N / 过滤 M / 重复 K）→ 确认 → 事务入库。
- 入库复用 `WordRepository` 事务模式（**不用 `Insert(REPLACE)`**）：`(pack_id, normalized_word)` 存在则跳过（或合并更新空字段），新行 `lookup_state='PENDING'`、`meaning_source=''`、phonetic 空。
- 入库成功后立即 `enqueueMeaningSync()`（见 §7）。
- 失败语义：解析失败 → `Outcome` 错误展示；网络不可用**不影响**入库（词先进，释义后补）。

---

## 7. 网关释义补全（M4，核心）

### 7.1 网络栈

- `libs.versions.toml` 增：okhttp、retrofit、retrofit-converter-kotlinx-serialization（版本实现期以最新稳定为准，AGP 9 / Kotlin 2.4 兼容性在 M0 spike 验证）；测试加 mockwebserver。
- `data/gateway/`（新包）：
  - `GatewaySettings`（从 SettingsRepository 读）：`baseUrl`、`key`、`dictPath=/dict`、`enPath=/en/{word}`、`mtPath=/v1/chat/completions`、`syncOnlyUnmetered`、`clipboardDetectEnabled`。
  - `GatewayApi`（Retrofit 接口）：`GET dict?word=`、`GET en/{word}`、`POST v1/chat/completions`；`Authorization: Bearer` 拦截器注入 key。
  - DTO 全部 `@Serializable`，字段以 `网关使用文档.md` §1–§3 响应为准（`found/entry{…}`、数组或 `ecdict-fallback` 对象两种形态都要能解、`choices[0].message.content`）。
  - 错误映射：401→`AppError.config`（设置页红字提示）、404→词不存在、400/500/超时→可重试。全部收敛为 `Outcome<T>`，符合「失败是值不是异常」。

### 7.2 级联查询服务 `MeaningLookupService`（data 层）

优先级与降级（严格按需求）：

1. **ECDICT** `GET /dict?word=X`：`found=true` → 中文 `translation` 为标准释义，附 `phonetic/pos/definition/tag/exchange`；`found=false` → 进入 2（`suggestions` 本期不用，仅可做后续输入联想）。
2. **FreeDictionary** `GET /en/X`：数组非空 → 取 `meanings[0]` 英文释义入 `en_definition`、`phonetics[].text`、首个 `example`、同义词、`audio` URL；兼容 `source=ecdict-fallback` 形态。**空/404 → 进入 3**。
**实测补齐（2026-10-14，M6a/M6b 交付后）**：
- 落地的级联是 ECDICT(中文优先) → FreeDict(辅助字段 en_definition/audio_url) → LLM 兜底。默认路径与网关文档一致：`/dict`、`/en/{word}`、`/v1/chat/completions`。
- 为让学习卡片/词详情拿到 `user_note`/`en_definition`/`audio_url`/`lookup_state`/`meaning_source`，`WordWithProgressView` 在 v3 补列（纯视图迁移，不碰数据）——本计划原设计遗漏了视图层。
- domain `Word` 新增 5 个默认字段承载上述信息；手动编辑置 `meaning_source='manual'` 后三处守卫（`applyLookupToWord`/`idsNeedingLookup`/`recordLookupFailure`）全部永久跳过。
- Room 对视图做逐字节 schema 校验：迁移 SQL 必须与导出的 `3.json` 视图 SQL 完全一致（反引号表名+trim 后 body）。
- `SpeechRequest` 承载 `audioUrl`，朗读路由在 SpeechController 内：在线播放失败静默回落 TTS（不进 FAILED），不落盘缓存。

3. **翻译模型** `POST /v1/chat/completions`（**LLM 兜底，已确认**）：prompt 模板（常量，可测）如：`把单词「X」的常用词性和中文释义翻译出来，一行，格式：词性. 释义`；解析 `content` 入标准释义。
4. 全失败 → word 行 `lookup_state='FAILED'`（UI 显示「待补充」+ 重试按钮），`meaning_cache` 记负缓存（TTL 由 `fetched_at` + 24h 判断，FAILED 允许手动强制重查）。

**释义语言策略（Q2 已关闭）**：标准释义始终优先为**中文**——ECDICT `translation` 直接满足；FreeDict 仅贡献英文释义/例句/同义词/发音（入 `en_definition` 等辅助字段），**不需要**「英文释义自动补中文」的额外设置项；ECDICT 未命中时由 LLM 直接生成中文释义兜底。

写回规则（事务）：先查 `meaning_cache` 命中直接写 word；写 word 前检查 `meaning_source != 'manual'`；`lookup_state ∈ {PENDING,FAILED}` 或源为空才写。

### 7.3 同步 Worker 与触发点

- `work/MeaningSyncWorker`：唯一任务 `UniqueWork("meaning-sync")`，`ExistingWorkPolicy.APPEND_OR_REPLACE`；约束 `NetworkType.CONNECTED`（设置开「仅 Wi-Fi」则 `UNMETERED`）；退避指数。
- 每次运行取 `lookup_state='PENDING' OR ('FAILED' AND lookup_attempts < 3)` 且 `meaning_source != 'manual'` 的行，批 50 条、词间 250ms 节流；401 立即终止并上报。
- 触发点：① 导入成功后；② 设置页「重试全部待补充」；③ 词详情页单词级「重试」；④ App 前台恢复时若有 PENDING 且网络可用（`MainViewModel`）。
- 优先级说明：`/dict` 与 `/en` 是边缘缓存的轻请求，MT 是 LLM 转发（慢且计费），仅在前两者都空时才走——顺序即降级策略，无并行竞速。

### 7.4 隐私与配额

- 上传内容仅：单词 token + key（header）。日志不打印剪贴板原文。
- 估算：1 万词词库全量补全 ≈ 1–2 万请求，远低于 10 万/日额度；节流与负缓存兜底。

---

## 8. 剪贴板识别与 Share Intent（M5）

### 8.1 剪贴板

- 读取时机：`MainActivity.onWindowFocusChanged(hasFocus=true)`（Android 12+ 焦点读取可减少系统「已粘贴」提示干扰）+ 仅 `RESUMED` 状态；满足 Android 10+ 后台限制。
- 流程：读 `ClipboardManager.primaryClip` → 复用 `PlainTextWordParser` → 非空且 `sha256(归一化文本)` ≠ DataStore `lastClipboardHash` → 底部弹层「检测到 N 个单词 → 选择/新建词库 → 导入并补全」；用户处理（导入或关闭）后写入 hash，同内容不再弹。
- 开关：设置页「剪贴板识别」（`clipboardDetectEnabled`，默认开）；关闭时不再读取剪贴板。

### 8.2 Share Intent

- Manifest：`MainActivity` 增 `android:launchMode="singleTop"`；两组 filter：
  - `ACTION_SEND` + `mimeType="text/plain"`；（可选 `ACTION_PROCESS_TEXT` + `text/plain`，供浏览器「翻译/搜索」入口）
- 生命周期：`onCreate` 解析启动 intent + `onNewIntent`（`setIntent` 后）统一走 `handleSharedText(text)` → 同一解析/词库选择弹层（与剪贴板共用 Composable 与 ViewModel 方法）。
- 一次分享多单词：整段文本走 `PlainTextWordParser`，天然支持换行/逗号混合。
- 弹层词库选择默认项 = `activePackId`，支持「新建词库并导入」。

---

## 9. 学习卡片与编辑（M6）

- 卡片背面 = 标准释义（`meanings` JSON 首条为主）+ `en_definition`（有则小字）+ **用户笔记 `user_note`**（有则展示）。
- 「卡片上直接编辑」：卡片背面编辑图标 → 弹编辑对话框（笔记/释义）→ 写 `user_word_note`；**编辑释义则置 `meaning_source='manual'`**，同步服务与后续查询永不覆盖（§7.2 写回规则保证）。
- WordDetail 页同步提供编辑入口（现 `ReviewScreens.kt:447` 区域扩展）。
- TTS 朗读与音标展示沿用现有 `speech/` 设施；`audio_url` 非空时优先在线播放，失败静默回落 TTS（**不下载缓存音频**，维持离线优先与无存储膨胀）。
- 间隔重复算法（`SpacedRepetition`，26 测试）**不改**——它只依赖 `WordProgress`，与学习范围无关；适配工作全部在「队列按 packId 构建」（§4.5 已含）。

---

## 10. 设置项汇总（SettingsScreen 扩一组「词库与联网」）

| 设置项 | 类型 | 默认 |
|--------|------|------|
| 网关地址 baseUrl | String（https 校验） | `https://gateway.lztfirefly.top` |
| 网关密钥 key | String（密码式可见性切换） | 预置文档默认 key |
| dict/en/mt 路径 | String（高级折叠区） | `/dict` · `/en/{word}` · `/v1/chat/completions` |
| 仅 Wi-Fi 补全 | Boolean | 关 |
| 剪贴板识别 | Boolean | 开 |
| 重试全部待补充 | 按钮（显示 PENDING/FAILED 计数） | — |
| 连通性测试 | 按钮（`GET /` 健康检查） | — |

`UserSettings`/`SettingsRepository`/`DataStoreSettingsRepository.Keys`/`SettingsScreen`/`ProfileViewModel` 四处同步新增（沿用现有 setter→`launchSettings` 模式）。

---

## 11. 测试与验证计划

| 层 | 新增/改动 | 工具 |
|----|-----------|------|
| 解析器 | `PlainTextWordParserTest`（分隔/过滤/去重/限额，≥20 用例） | JUnit4 + Truth（纯 JVM） |
| 迁移 | `Migration1To2Test`：种子数据保真、新索引语义、回填正确性 | Robolectric + schema 1.json |
| 查询 | packId 过滤回归（due/new/mistake/stats 按包隔离：两包同词进度互不串） | 扩展 `DatabaseTest`/新增 DAO 测试 |
| 网关 | `MeaningLookupServiceTest`：级联顺序、fallback 形态解析、401/超时/负缓存/manual 不覆盖 | MockWebServer + coroutines-test |
| Worker | 批量/节流/401 终止（虚拟时间） | coroutines-test |
| VM | 剪贴板弹层去重（hash）、Share intent 解析路由 | Robolectric Compose |
| 守护 | 现有 404 用例全绿；禁用词测试不受新 UI 文案影响 | 既有套件 |
| 命令 | `.\gradlew.bat :app:testDebugUnitTest` · `:app:assembleDebug` · 迁移后必查 `app/schemas/` 新快照 | — |

---

## 12. 里程碑与并行泳道

```
M0 基建解禁 ──► M1 数据层v2+packId扫描(关键路径) ──┬──► M2 词库管理UI ──► M5 剪贴板+Share
                                    │              └──► M3 纯文本导入 ─┘
                                    └──────────────► M4 网关补全(可与M2/M3并行)
                                                             │
                                             M6 卡片笔记/编辑/导出扩展/收尾 ◄─┘
```

| 里程碑 | 内容 | 验收 |
|--------|------|------|
| **M0** ✅ | 解禁 INTERNET（改 Manifest 注释+AGENTS+产品文档）；引入 retrofit/okhttp/mockwebserver 依赖；R8 keep 规则（Signature/retrofit 注解/serialization）spike；`assembleDebug`+全测绿 | 冷启动/全测无回归 |
| **M1** ✅ | §4 全部：迁移+索引重建+DAO/Repository/VM/Worker packId 化+`activePackId` 设置 | 全测绿+迁移测试+手工冒烟（四类学习功能） |
| **M2** ✅ | PACKS 路由接线、词库 CRUD/切换/删除确认、首页 chip 入口 | 手工验收 CRUD + 进度隔离 |
| **M3** ✅ | `PlainTextWordParser` + 导入页（粘贴→预览→确认→PENDING 入库） | 解析器单测；导入不依赖网络 |
| **M4** ✅ | §7 网络栈/级联/缓存/Worker/设置项 | MockWebServer 测试；真机对默认网关端到端补全 |
| **M5** ✅ | 剪贴板焦点读取+hash 去重+弹层；Share/ProcessText filter+singleTop+onNewIntent | 真机：从浏览器/备忘录分享导入；回前台弹层去重 |
| **M6** ✅ | 卡片笔记/释义编辑（manual 保护）、按词库导出、待补充重试入口、文档回写 | 端到端回归 + `lintVitalRelease`（低内存机器可跳过） |

> **交付状态（2026-10-14）**：M0–M6 全部实现并提交；测试从基线 404 增至 513（M6a 完成点，M6b 后另增）。实际泳道划分与计划略有偏差：M0/M1 并行开工；M4 拆为 backend/UI 两段；M5 前置在 M4-UI 期间并行；M6 拆为 M6a（编辑+展示+v3 迁移+首页入口）与 M6b（audio_url 播放回落）。M6b 完成后的最终测试数以此节之后的更新为准。

并行策略：M1 是关键路径，**不与其他写入泳道并行**（全仓扫描冲突面太大）；M1 完成后 M2/M3/M4 三泳道可并行（不同包：`ui/screens+Routes` vs `domain/importer` vs `data/gateway+work`），M5 依赖 M2（词库选择弹层）与 M3（解析器）。各泳道单独提交，评审人=泳道作者之外的另一泳道负责人。

---

## 13. 已关闭决策（评审确认）与残留风险

| # | 问题 | 结论 |
|---|------|------|
| Q1 | 改造前 OTHER 级历史数据 | **关闭**：未上线、无真实历史数据。迁移回填一次写完（内置包映射 + `legacy-other` 兜底），不做迁移工具，`legacy-other` 行不展示也无需清理工具。 |
| Q2 | FreeDict 仅英文释义 | **关闭**：标准释义以中文为准——ECDICT `translation` 优先，LLM 兜底自动生成中文释义；FreeDict 仅作辅助字段（英文释义/例句/同义词/发音）。不加设置项。 |
| Q3 | 网关 key | **关闭**：硬编码为默认值（`BuildConfig`/常量），设置页仍可覆盖；接受可被逆向的现实，泄露则按 `网关使用文档.md` §5 换密钥。 |
| R1 | 本机禁止编译 ktlint | **规则**：不引入 ktlint/detekt/spotless 依赖与任务；验证 = `testDebugUnitTest` + `assembleDebug` 文件自验证。 |
| R2 | AGP 9 + Kotlin 2.4 下 Retrofit/OkHttp/serialization-converter 版本 | M0 spike 实测；若 converter 不兼容则退化为 OkHttp + `kotlinx.serialization` 手写请求（保持 D6 分层不变）。 |
| R3 | Android 12+ 剪贴板读取提示 | 焦点时机读取缓解；无法消除时用户可关「剪贴板识别」。 |
| R4 | Share sheet 显示名 | 用 `app_name`，M5 实现时确认是否需要独立 `android:label`。 |

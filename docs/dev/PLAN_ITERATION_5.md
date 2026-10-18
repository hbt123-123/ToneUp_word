# 迭代 5 方案：四级词库下线 / 手写「录入」页 / 错题并入首页 / 网关密钥内置

> 状态：**草案**（§0 有 2 处待拍板、2 处理解待确认）· 基线 commit `fbfeefe`（529 单测 / 38 类）
> 本文档是本轮改造的指导蓝图；实现过程中的有意偏离需回写本文档；描述与代码不一致时**以代码为准**。

---

## 0. 先说必须确认的四件事（前两件会改变实现，后两件是我对需求的理解）

### 0.1 四级词库「删除」删到哪一层？（推荐 A）

| 方案 | 内容 | 代价 |
| --- | --- | --- |
| **A（推荐）** | 产品层彻底移除：不再打包 / 播种 / 可选；老安装中已存在的 `builtin-cet4` 包与其学习数据（进度 / 错题 / 统计）启动时做**一次幂等清理**；`level` / `activePackId` 指到四级时改指六级 | 已学的四级进度会丢。依据：本项目自述未上线、无真实用户数据（`PLAN_CUSTOM_PACKS_GATEWAY.md` §1） |
| B（保守） | 只移除打包 / 播种 / 入口；老安装的 `builtin-cet4` 数据保留在库内并从产品隐藏（PackList 过滤、统计不可达） | 库里留死数据；需要多处「已下架内置包」特判 |
| C | 只改代码：新装不含四级；老安装照旧显示与使用（开发机自行清数据） | 需求字面上没完成 |

### 0.2 错题本并入首页后，完整错题本页保不保？（推荐 A）

| 方案 | 内容 |
| --- | --- |
| **A（推荐）** | 首页新增「错题」区块（待巩固 / 今日待复习 + 最近错题 + 一键巩固 + 查看全部）；`ReviewCenterScreen` 保留（六分类筛选 / 搜索 / 批量 / 收藏都在），只是不再占底栏 |
| B（精简） | 取消独立页：仅首页区块 + 巩固入口；筛选 / 批量 / 收藏 / 搜索一并移除或降级 |

### 0.3 「录入」页的挂载方式（理解确认）

按这样理解：**底栏原「错题本」槽位 → 新「录入」页**（逐词手写、可附中文注释）；错题以首页区块存在。
若你想要的是「录入表单直接嵌在首页、不新开页」，请指出，改动会落在 §3.2 / §3.3。

### 0.4 「自建词库窗口放大」的对象（理解确认）

按两条一起做：① 词库管理页的新建入口放大（显著引导卡 + 更大的创建流程）；② 粘贴导入的输入窗口放大。
若你指别的窗口（例如录入页本身），指出即可。

---

## 1. 本轮目标（需求 → 落地章节）

| # | 需求 | 章节 |
| --- | --- | --- |
| 1 | 删除现有的大学英语四级词库部分 | §3.1 |
| 2 | 自建词库「设计窗口」更大 / 更明显 | §3.5 |
| 3 | 「错题本」页面缩小为首页的一部分，腾出的位置改为「写入词库」页 | §3.2 / §3.3 |
| 4 | 手写录入：逐个输入单词，可附中文注释 | §3.3 |
| 5 | 学习时优先展示用户注释，其次词库 / 网关释义；未输入注释时按《网关使用文档.md》 | §3.4 |
| 6 | 网关密钥直接硬编码进代码、随构建产出 | §3.6 |

---

## 2. 现状盘点（与本轮相关的事实，全部来自当前代码）

* **导航**：底栏 5 页 `首页 / 错题本 / 考试 / 统计 / 我的`（`ui/navigation/Routes.kt:59-69`）；
  「错题本」路由指向 `ReviewCenterScreen`（`ui/screens/ReviewScreens.kt:86`）。
* **首页**：`HomeScreen.kt` 九段式 LazyColumn；`HomeViewModel` 由 5 路流 `combine`
  （`ui/ViewModels.kt:412`）。错题入口分散在：首页「最近学习记录」头条目（`HomeScreen.kt:347`）、
  学习总结文案与按钮（`StudyScreen.kt:289/293/338`）、考试结果文案（`ExamScreens.kt:481-494`）、
  「下一步」行动（`domain/study/NextStep.kt:34`）。
* **内置词库**：`assets/wordpacks/manifest.json` 目前只发两包（`builtin-cet4` 3849 词 / `builtin-cet6` 5407 词）；
  `BuiltInPackLoader`（manifest → `DEMO_FALLBACK` 回退，`data/seed/BuiltInPackLoader.kt:131-148`）；
  源码内演示包 `BuiltInWordPacks`（`:216` 的 `all` 映射）；`PackIds.BUILTIN_CET4`（`data/settings/PackIds.kt:20`）；
  `UserSettings.level` 默认 `CET4`（`data/settings/UserSettings.kt:29`）；
  `CetLevel.selectable = [CET4, CET6]`（`domain/model/CetLevel.kt:19`）。
* **单词写入**：`addWordsToPack` 事务内按 `(pack_id, normalized_word)` 查重、**不 REPLACE**、
  新词 `lookup_state='PENDING'` 待补全（`data/repository/WordRepository.kt:660-712`）；
  `saveUserNote(wordId, note)` 已存在（`:719`）；`user_note` 是 v3 已有列（schema 不动）。
* **展示**：`WordMeaningBlock` 先渲染 `meanings`，再渲染 `WordExtraInfo`（英文释义 + 笔记）
  （`ui/components/WordCard.kt:307-390`）；学习 / 复习 / 单词详情三屏共用；考试卡不展示释义。
* **网关**：`GatewayDefaults.KEY` 已是硬编码常量（`data/gateway/GatewayDefaults.kt:17`，与
  `网关使用文档.md` §1 同值）；`UserSettings.gatewayKey` 默认继承它（`UserSettings.kt:79`），
  DataStore 读不到时也回退到默认（`SettingsRepository.kt:309`）→ **装包即用、无需配置**。

---

## 3. 方案

### 3.1 M1 四级词库下线

**代码与资产**

* 删 `app/src/main/assets/wordpacks/cet4.json`；`manifest.json` 只留 `builtin-cet6`；
  `assets/wordpacks/PACK_INFO.json` 同步去四级（或下次跑流水线时重生成）。
* `BuiltInPackLoader.DEMO_FALLBACK` 去四级项；`BuiltInWordPacks` 去 `cet4` 列表与 `all` 映射中的四级。
* `CetLevel.selectable` → 只留六级（`OTHER` 仍是导入包的元数据，不进入选择器）；
  `UserSettings.level` 默认 → `CET6`；Onboarding「选择词库」步骤按六级改写。
* 兜底审计（逐处处理，全仓 grep `CetLevel.CET4` + `builtin-cet4`）：
  `ProgressRecorder.kt:110` 的无包兜底、`WordRepository.kt:328/363` 演示包命名与级别推断、
  UI 注释与 fixture（`HomeScreen.kt:137`、`ViewModels.kt:447`、`StartupTipScreen.kt:154` 等，逐步替换示例值）。
* 兼容层**保留**：`CetLevel.CET4` 枚举、`PackIds.BUILTIN_CET4` 常量与 level ↔ pack 映射
  （迁移测试里的历史字符串 `'builtin-cet4'` 一律不动，用于旧数据渲染）。

**文案与文档**

* `OnboardingScreen.kt:105`、`DataTransferScreens.kt:240/373`、About、首页空态
  （`HomeScreen.kt:220`）、`ViewModels.kt:645` 等提到「四级 / CET-4」的地方逐条修订。
* `tools/wordpack/`：`build_packs.py` 的 PACKS 清单、`attach_examples.py` / `normalize_phonetics.py` /
  `validate_packs.py` 的目标文件清单去四级（防止复跑流水线把 `cet4.json` 带回来）；
  `tools/AGENTS.md` 的流水线说明同步。
* `docs/WORD_PACK_VALIDATION.md` 更新为「现行内置包 = 六级」的口径（历史数据保留出处说明）。

**存量清理（仅方案 A）**

* `WordRepository` 新增 `retireUnshippedBuiltInPacks()`：对 `word_pack` 中 `is_built_in=1`
  但已不在当前 manifest 的包，复用 `deleteForPack + deletePackRow` 路径清理（绕过「内置包禁删」守卫），
  并做设置修正（`level=CET4 → CET6`；`activePackId` 指向被清理包 → 置空按 level 派生）。
* `AppContainer.prepare()` 在 `ensureBuiltInPacks()` 之后调用一次；幂等（无四级包时为 no-op）。
* 旧完整备份恢复若带回 `builtin-cet4`，下次启动的幂等清理会兜底，不需要恢复特判。
* 新测试 `RetiredBuiltInPackCleanupTest`：清理只动下架包、级联删学习数据、设置被修正、幂等、不影响六级。

### 3.2 M2 底栏调整 + 错题并入首页

* 底栏：`首页 / 录入 / 考试 / 统计 / 我的`。`TopLevelDestination` 移除 `REVIEW`、新增 `WRITE`；
  `Routes.REVIEW` 路由与 `ReviewCenterScreen` 保留（方案 A）。
* 首页新增「错题」区块（位置建议紧跟「下一步」卡之后、保持「行动区」语义；视觉评审可再调）：
  * 标题 + 「待巩固 N · 今日待复习 M」；
  * 最近错题 2–3 行（词 + 错误次数 / 最近一次）；
  * 主按钮「巩固错题」（进 drill，复用现有 `onStartMistakeDrill`）；文字按钮「查看全部」→ 错题本页。
* 数据源：`MistakeRepository.observeOverview / observeItems / drillIds`（`data/repository/MistakeRepository.kt`），
  接入 `HomeViewModel` 的 `combine` 与 `HomeUiState`（新增 `mistakeOverview` / `recentMistakes` 字段）。
* 入口链修订：`StudyScreen` 总结（「再练一次错词」保留；文案里的「进入错题本」保持）、
  考试结果文案、`NextStep` 的「巩固错题」目标、首页「最近学习记录」头条目 —— 全部仍可达错题本 / drill。
* 测试：`ComposeFlowTest` 等涉及底栏 / 路由的断言同步更新；新增首页错题区块的渲染断言。

### 3.3 M3 新「录入」页（写入词库）

**页面与交互**

* 路由 `Routes.WRITE_WORDS = "write-words"`，底栏第三项之前的位置（原错题本槽位），标签「录入」；
  新屏幕 `ui/screens/WriteWordsScreen.kt`，VM 追加到 `ui/ViewModels.kt` 新分节 + `ViewModelFactories`。
* 逐词输入：**单词**（必填）+ **注释（可选，中文自由文本）** + **目标词库**（默认当前词库
  `effectivePackId`，可切换；无自建词库时给出「新建词库」入口 → §3.5）。
* 交互：回车 / 「保存并继续」→ 清空输入并聚焦下一个单词；页内下方列「本次录入」记录
  （词 + 注释 + 状态徽标），点一行可复用 `WordEditDialog` 再编辑注释 / 释义。
* 校验：复用 `PlainTextWordParser` 的 token 规则与常量（英文 token、≤64 字符、必须含字母，
  按 `Word.normalizeWordKey` 归一）；注释长度设上限（建议 300 字，超限给出提示）。
* 反馈：区分三种结果——**新增成功** / **已存在·注释已更新** / **已存在·无变化**；
  失败是值（`Outcome`），不抛异常。
* 离线可用：保存不依赖网络；注释不阻断补全（见 §3.4）。

**数据层**

* `WordRepository` 新增事务内方法 `addWordWithNote(packId, rawWord, note, now): Outcome<WordEntryResult>`：
  * 身份 `(pack_id, normalized_word)`：不存在则插入（`PENDING`）；存在则复用行；
  * `note` 非空时写 `user_note`（`saveUserNote` 同款更新），返回 `wordId / created / noteUpdated`；
  * **严禁** `Insert(REPLACE)`；不触碰 `meaning_source='manual'` 的数据；
  * 更新 `word_pack.word_count`。
* 成功写入后调用 `meaningSync.enqueueAll()`（与 `ImportWordsViewModel.kt:1972`、`ExternalImport.kt:290` 同款
  「失败静默、稍后重试」策略）。
* 新测试 `AddWordWithNoteTest`（新增 / 重复更新注释 / 注释为空 / 非法 token / 大小写归一 / manual 行不被覆盖）。

**注释的语义边界**

* 注释 = `user_note`（附加信息，**展示优先级最高**，不参与 `meaning_source` 状态机）；
* 「整条替换词义」仍走编辑对话框的「释义」字段（`saveManualMeaning` → `manual` 永久保护），两者并存、互不干扰。

### 3.4 M4 展示优先级（学习 / 复习 / 详情）

* `WordCard.WordMeaningBlock`：`userNote` 非空时把注释块**提到释义之前**（显著样式，标签建议
  「我的注释」）；其后照常渲染 `meanings`（词库内置 / 网关补全结果）；`enDefinition` 维持小字辅助位；
  三屏（学习 / 复习 / 详情）共用同一组件，自动一致。
* 补全策略不变：无注释 → 完全按《网关使用文档.md》级联（ECDICT → FreeDict → LLM）；
  有注释 → 依然后台补全，只是展示顺序让位（原先的「优先展示输入、再展示搜索」）。
* 考试流程**维持不展示**（闭卷规则不破）。
* 新增展示顺序断言测试（`userNote` 在 `meanings` 之前渲染）。

### 3.5 M5 自建词库入口放大

* `PackListScreen`：顶部「新建词库」按钮升级为**显著引导卡**（标题 + 一句说明 + 主按钮）；
  创建流程从「只填名称的小对话框」放大为更大的对话框（或全屏页）：名称（必填 ≤40）+ 可选说明 +
  创建后直达「录入」或「粘贴导入」二选一。
* `ImportWordsScreen`：粘贴窗口放大（行数提升 / 占满可用高度），预览与导入逻辑不变。
* 入口联动：首页空态、录入页「无可用词库」状态、`Profile` 的「词库管理」、首页当前词库 chip。
* 视觉与组件细节实现时按现有设计系统（`AppTheme` / `Spacing` / 既有组件）执行，本方案只锁行为与信息层级。

### 3.6 M6 网关密钥内置（核对项）

* 现状**已满足**需求：`GatewayDefaults.KEY` 硬编码（与文档同值）、设置默认继承、读不到时回退默认；
  构建产出即带密钥，无需任何本地配置或用户操作。
* 本轮做：① 回归测试（默认设置 `gatewayKey` 非空且等于内置值；空 DataStore 时走默认）；② 文档注明
  「密钥随 APK 分发、可被提取（既定 Q3 决策），泄露按《网关使用文档.md》§5 轮换」；③ 保留设置页覆盖能力。
* 如需更换密钥值或禁止覆盖，请在回复中说明。

---

## 4. 数据库与兼容

* **无 schema 变更**：Room 仍 v3，`app/schemas/` 不动，不新增 Migration；四级清理是**数据级幂等清理**，
  不是版本迁移（方案 A）。若你更希望走 v3→v4 迁移式清理，说一声，两者都能做，但前者不动 schema 快照。
* `manual` 保护、不 REPLACE、身份 `(pack_id, normalized_word)` 三条红线全部不动。
* 备份 / 恢复：旧备份可能带回四级包，由 §3.1 的幂等清理兜底；其余逻辑不变。

## 5. 测试与验证

* 新增：`RetiredBuiltInPackCleanupTest`、`AddWordWithNoteTest`、录入页 VM 测试、展示顺序断言、
  首页错题区块断言、网关默认 key 回归测试。
* 更新：`BuiltInPackAssetTest`（期望集只剩六级）、`BuiltInPackSeedTest`、`PackIdsTest`、
  `ComposeFlowTest`（底栏 / 路由）、`WordBrowserScreenTest`、预览 fixture。
* 验证命令（本机约定）：`.\gradlew.bat :app:testDebugUnitTest` + `.\gradlew.bat :app:assembleDebug`；
  Windows 跑测前 `TEMP`/`TMP` 指到纯 ASCII 目录（见 AGENTS.md NOTES）。
* 文案禁用词守测试（StartupTip / Insight / SpeechController）逐条自查；不引入 ktlint / detekt。

## 6. 里程碑与验收

| 里程碑 | 内容 | 验收 |
| --- | --- | --- |
| M0 | 本文档确认（§0 四项） | 你回复口径 |
| M1 | 四级下线（含存量清理按 0.1） | 新装无四级；老装清理后列表只剩六级；测试绿 |
| M2 | 底栏调整 + 首页错题区块 | 底栏 5 项新布局；错题可从首页一键巩固 / 查看全部 |
| M3 | 录入页 + `addWordWithNote` | 逐词录入含注释；重复词正确反馈；离线可用 |
| M4 | 展示优先级 | 有注释的卡先注释后释义；无注释行为不变 |
| M5 | 自建词库入口放大 | 新建入口显著、创建流程放大、粘贴窗口放大 |
| M6 | 密钥核对 | 回归测试通过；无需配置即可补全 |
| M7 | 全量验证 + 文档同步 + 迭代 5 报告 | 529+ 单测全绿；`assembleDebug` 成功；docs/README 与 AGENTS.md 同步 |

## 7. 风险与红线

* **数据风险**：方案 A 会丢失四级学习数据 —— 需你明确同意；否则选 B/C。
* 底栏项移除会牵动既有 Compose 测试与文案，需同步更新（列表见 §5）。
* 录入边界（大小写、连字符、重复词）统一复用解析器的规则与常量，防止两套口径。
* 注释与「手动释义」语义易混，界面文案要说清：注释 = 附加优先展示；替换整条词义 = 编辑对话框。
* 密钥随 APK 可被提取是既定决策；本轮不新增任何其他联网行为。

## 8. 交付物

* 代码 + 测试（上述清单）；
* 文档：本方案（定稿回写）、`docs/README.md` 索引、`USER_GUIDE.md`（录入 / 注释 / 错题位置变化）、
  `WORD_PACK_VALIDATION.md`、`AGENTS.md` 相关注释与地图；
* 交付后出 `docs/dev/ITERATION_5_REPORT.md`（需求 → 实现对照、实测数据、未解决事项）。

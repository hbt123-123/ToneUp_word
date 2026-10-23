# ui/ — Compose 屏幕 / 组件 / 主题 / 导航

## OVERVIEW
单 Activity + 单导航图；14 个 ViewModel 聚合在 `ViewModels.kt`（~2425 行，`// ----` 分节）。外部文本入口（Share/Process-Text + 剪贴板）由 `ExternalImport.kt` 的 Activity 级 VM + 浮层 Sheet 承担。层级为**计划 → 词库 → 单词**：首页/复习/考试/统计都以「当前计划」为范围。

## STRUCTURE
```
ui/
├── ToneUpWordApp.kt     根 Composable：主题 → ready 门控 → NavHost/LoadingState → 开屏 overlay + 导入 Sheet
├── ViewModels.kt        ViewModelFactories(:100) + 14 VM 分节（节间无共享状态）
├── ExternalImport.kt    ClipboardHash + ExternalImportViewModel + ExternalImportSheet（M5）
├── OptIns.kt            ExperimentalApiPolicy —— 实验性 API 集中记录（逐处 @OptIn，无 @file:OptIn）
├── navigation/          Routes.kt(19 路由), ToneUpWordNavHost.kt(351 行), AppBottomNavigation.kt
├── screens/             11 文件（Home/Study/Exam/Profile/Review/Pack/DataTransfer… 一文件多态/多屏）
├── components/          12 文件（WordCard.kt 745 行跨三屏共用；WordEditDialog.kt 笔记/释义编辑）
├── theme/                Theme.kt(AppColors/AppMotion/LocalDarkTheme), Color/Typography/Shape, SystemBars
└── speech/               SpeechController（空闲/加载/播放/失败 四态）, SpeechActions(rememberSpeechActions)
```

## WHERE TO LOOK
| 任务 | 位置 |
|------|------|
| 新屏幕接入 | `navigation/Routes.kt` + `ToneUpWordNavHost.kt`（start = onboardingCompleted ? HOME : ONBOARDING） |
| 改某屏逻辑 | `ViewModels.kt` 对应分节（节行号见下）+ `screens/` |
| 改主题/动态色/动画开关 | `theme/Theme.kt`（`AppTheme.motion.durationMillis` 归零即禁用） |
| 改状态栏反色 | `theme/` ApplySystemBarAppearance——跟随**应用内**主题而非系统夜间位 |
| 改单词卡/考试卡 | `components/WordCard.kt` |
| 改笔记/手动释义编辑 | `components/WordEditDialog.kt` → `WordRepository.saveUserNote/saveManualMeaning`（manual 保护） |
| 改计划/词库管理页 | `screens/PackScreens.kt`（PlanListScreen + PlanDetailScreen + ImportWordsScreen + WriteWordScreen） |
| 改分享/剪贴板导入浮层 | `ExternalImport.kt`（Activity 级 VM；`MainActivity` 触发） |
| 改网关设置/连通性测试 | `screens/ProfileScreens.kt` 「词库与联网」分组 → `ProfileViewModel` |
| 改朗读按钮/在线发音 | `speech/SpeechController` + `SpeechActions`（线上失败回落 TTS；SpeechControllerTest 守护） |

## CONVENTIONS
- `ViewModels.kt` 分节地图（行为文件真实行号）：Factories 100-158 · Main 161-376（init 调 `container.prepare()`）· Onboarding 379-452 · Home 455-714 · Study 717-999 · Review 1002-1143 · Exam 1146-1414（3 态状态机+草稿+计时器）· Stats 1417-1446 · WordBrowser 1449-1537 · Profile 1540-1779（含 setter 经 `launchSettings` 委托 **+ 网关设置**）· DataTransfer 1782-1951 · PlanList+PlanDetail 1954-2148 · ImportWords 2151-2241 · WriteWord 2244-2425。
- 所有 VM 只依赖 `AppContainer`；工厂消费方 = MainActivity + 各 Screen 顶部 + NavHost（exam 共享）。`ExternalImportViewModel` 是**唯一 Activity 级** VM（工厂 `ExternalImportViewModel.factory`）。
- 开屏提示语是 **AnimatedVisibility overlay**（每进程一次），闪屏与播种进度共用一屏（`state.seedProgress` 显示真实数字）；分享/剪贴板导入 Sheet 也是跨图浮层。
- 动效统一走 `AppTheme.motion.durationMillis(n)`——用户关动画或系统 reduce-motion 时自动归零；导航转场自定义为 300ms（fade + 1/16 水平 slide），避免 Navigation 默认 700ms 交叉淡入。
- 冷启动：`state.ready` 门控 NavHost 与 LoadingState（首次播种数千行，显示进度非转圈）。
- 实验性 API（当前仅 `ExperimentalMaterial3Api` 的 TopAppBar/Scaffold 顶栏）逐处 `@OptIn`，理由集中记于 `OptIns.kt`。

## 复杂度热点（真实行数）
| 文件 | 行 | 说明 |
|------|----|------|
| `ViewModels.kt` | ~2425 | 14 节聚合；拆分低风险（节间无共享状态） |
| `screens/ProfileScreens.kt` | 921 | ProfileScreen + **SettingsScreen（含网关 baseUrl/key/路径、连通性测试、剪贴板开关，最热函数）** |
| `components/WordCard.kt` | 745 | WordCard+AnswerActionBar（Study/Review）+ ExamQuestionCard+AnswerResultRow（Exam）+ SectionHeader + preview |
| `screens/ReviewScreens.kt` | 686 | ReviewCenterScreen + BatchActionBar + MistakeRow + WordDetailScreen（含笔记/释义编辑入口） |
| `ExternalImport.kt` | 612 | ClipboardHash + Activity 级 VM + 分享/剪贴板导入 Sheet |
| `screens/HomeScreen.kt` | 630 | 首页四段式 + 当前计划 chip（点击切换） |
| `screens/ExamScreens.kt` | 555 | Setup/Run/Result 三态 |
| `screens/PackScreens.kt` | 1120 | PlanListScreen + PlanDetailScreen（词库增删/移动）+ ImportWordsScreen（粘贴预览）+ WriteWordScreen |

拆分优先级：SettingsScreen 内部分组 > ViewModels 按节拆 > WordCard 考试组件移出到 `ExamCards.kt` > WordDetailScreen 独立。

## ANTI-PATTERNS
- 无数据显示友好空状态，不用 0% 硬填；洞察数据不足只说「数据有限」。
- 改动 UI 文案注意禁用词守护测试（StartupTip/Insight/SpeechController）。
- 动效时长勿硬编码毫秒，走 `AppTheme.motion`。
- Composable 不直连 DAO/DataStore；导航不传 `NavController`；剪贴板/分享原文只进本地解析，UI 层不回传原文本原文（`docs/SHARE_INTENT.md`）。

# ui/ — Compose 屏幕 / 组件 / 主题 / 导航

## OVERVIEW
单 Activity + 单导航图；11 个 ViewModel 聚合在 `ViewModels.kt`（~1609 行，`// ----` 分节）。

## STRUCTURE
```
ui/
├── ToneUpWordApp.kt     根 Composable：主题 → ready 门控 → NavHost/LoadingState → 开屏 overlay
├── ViewModels.kt        ViewModelFactories(:91) + 11 VM 分节（节间无共享状态）
├── navigation/          Routes.kt(17 路由), ToneUpWordNavHost.kt, AppBottomNavigation.kt
├── screens/             10 文件（Study/Exam/Profile/Review 等一文件多态/多屏）
├── components/           11 文件（WordCard.kt 649 行跨三屏共用）
├── theme/                Theme.kt(AppColors/AppMotion/LocalDarkTheme), Colors/Typography/Shapes, SystemBars
└── speech/               SpeechController（空闲/加载/播放/失败 四态）
```

## WHERE TO LOOK
| 任务 | 位置 |
|------|------|
| 新屏幕接入 | `navigation/Routes.kt` + `ToneUpWordNavHost.kt`（start = onboardingCompleted ? HOME : ONBOARDING） |
| 改某屏逻辑 | `ViewModels.kt` 对应分节（节行号见下）+ `screens/` |
| 改主题/动态色/动画开关 | `theme/Theme.kt`（`AppTheme.motion.durationMillis` 归零即禁用） |
| 改状态栏反色 | `theme/` ApplySystemBarAppearance——跟随**应用内**主题而非系统夜间位 |
| 改单词卡/考试卡 | `components/WordCard.kt` |
| 改朗读按钮状态机 | `speech/SpeechController`（SpeechControllerTest 守护三类失败路径） |

## CONVENTIONS
- `ViewModels.kt` 分节地图（行号为近似）：Factories 91-133 · Main 135-240（init 调 `container.prepare()`）· Onboarding 242-317 · Home 318-551 · Study 552-812 · Review 813-947 · Exam 948-1215（3 态状态机+草稿+计时器 ~30 方法）· Stats 1216-1247 · WordBrowser 1248-1338 · Profile 1339-1441（12 setter 经 launchSettings 委托）· DataTransfer 1442-1609。
- 所有 VM 只依赖 `AppContainer`；工厂消费方 = MainActivity + 各 Screen 顶部 + NavHost（exam 共享）。
- 开屏提示语是 **AnimatedVisibility overlay**（每进程一次），闪屏与播种进度共用一屏（`state.seedProgress` 显示真实数字）；fade 220ms。
- 动效统一走 `AppTheme.motion.durationMillis(n)`——用户关动画或系统 reduce-motion 时自动归零。
- 冷启动：`state.ready` 门控 NavHost 与 LoadingState（首次播种数千行，显示进度非转圈）。

## 复杂度热点（真实行数）
| 文件 | 行 | 说明 |
|------|----|------|
| `ViewModels.kt` | ~1609 | 11 节聚合；拆分低风险（节间无共享状态） |
| `components/WordCard.kt` | 649 | WordCard+AnswerActionBar（Study/Review 用）+ ExamQuestionCard+AnswerResultRow（Exam 用）+ SectionHeader + 4 preview |
| `screens/ReviewScreens.kt` | 607 | ReviewCenterScreen + BatchActionBar + MistakeRow + WordDetailScreen（447-578，宜独立成文件） |
| `screens/ProfileScreens.kt` | 595 | ProfileScreen + **SettingsScreen 单函数 ~312 行（最热函数，宜按分组拆子 Composable）** |
| `screens/ExamScreens.kt` | 555 | Setup(75-193)/Run(202-349)/Result(353-543) 三态 |

拆分优先级：SettingsScreen 内部分组 > ViewModels 按节拆 > WordCard 考试组件移出到 `ExamCards.kt` > WordDetailScreen 独立。

## ANTI-PATTERNS
- 无数据显示友好空状态，不用 0% 硬填；洞察数据不足只说「数据有限」。
- 改动 UI 文案注意禁用词守护测试（StartupTip/Insight/SpeechController）。
- 动效时长勿硬编码毫秒，走 `AppTheme.motion`。

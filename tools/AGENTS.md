# tools/ — Python 离线数据流水线

## OVERVIEW
纯 Python 3.8+ 标准库（零第三方依赖，无 requirements/venv）；产物写入 `app/src/main/assets/`，CI 不跑、由 Kotlin 单测守卫。

## STRUCTURE
```
tools/
├── wordpack/      10 文件：词库生成/校验流水线 + 2 个一次性调研脚本
├── motivation/    build_tips.py + validate_tips.py + parts/part1,2.json（撰写稿）
└── pronunciation/ verify_audio.py(1411 行) + supplementary_uk_probe.py —— 历史证据，不产资产
```

## wordpack 流水线（顺序执行，勿乱序）
```bash
① python tools/wordpack/download.py            # ECDICT@bc015ed → cache/（已存在跳过；可传文件名参数）
② python tools/wordpack/build_packs.py         # cache/ecdict.csv → assets/wordpacks/cet4,cet6,PACK_INFO.json (v2)
③ python tools/wordpack/normalize_phonetics.py # 原地改音标（西里尔→IPA、'→ˈ、:→ː）；须在②后；幂等
   ── 例句子流水线 ──
④ python tools/wordpack/build_tatoeba_pairs.py # 3 个 .bz2 → cache/tatoeba/cmn-eng-pairs.tsv（.bz2 需手工下载！）
⑤ python tools/wordpack/attach_examples.py     # 重写资产 (v3) + manifest + docs/EXAMPLE_SENTENCE_SOURCES.tsv + report
⑥ python tools/wordpack/validate_packs.py      # 只读校验 → validation_report.json（依赖 cache/ecdict.csv，缺则崩）
```
调研脚本（不入流水线）：`measure_sources.py`（选源对比）、`check_pos.py`（词性可提取率）。
motivation：`build_tips.py`（parts→tips.json 398 条，<370 退出 1）→ `validate_tips.py`（数量/禁用词/长度/近重复≤0.60，与 StartupTipTest 同断言）。

## WHERE TO LOOK
| 任务 | 位置 |
|------|------|
| 改词库内容/字段 | `wordpack/build_packs.py`（上游 ECDICT 字段映射） |
| 改例句选取 | `wordpack/attach_examples.py`（屈折还原+OpenCC 繁简+质量分层常量在文件头） |
| 改音标转写 | `wordpack/normalize_phonetics.py` |
| 改提示语文案 | `motivation/parts/*.json` → `build_tips.py` |
| 禁用词表 | `motivation/validate_tips.py:32-35`（与 StartupTipTest 一致，改一处须同步） |

## CONVENTIONS
- shebang + 模块 docstring（写清用途与规则）+ `if __name__ == "__main__"`；路径用 `__file__` 相对定位（老脚本 os.path / 新脚本 pathlib 并存）。
- 输出一律 UTF-8 + LF（`newline="\n"`；报告 JSON 用 `newline=""` 防 CRLF diff）。Windows：先 `$env:PYTHONIOENCODING="utf-8"`。
- 脚本会改 git 工作区（资产/报告/docs）——跑完 `git status` 检查；资产 JSON 在 `.gitattributes` 标 `linguist-generated`。

## 喂给应用
- `assets/wordpacks/manifest.json`（version 2；builtin-cet4 v3/3849 词、builtin-cet6 v3/5407 词）+ `cet4.json`/`cet6.json`/`PACK_INFO.json` ← `BuiltInPackLoader`（MANIFEST_PATH，用与用户导入同一个 WordPackParser，version 触发原地升级保进度，wordCount 防截断，缺失回退内置演示词库）。
- `assets/motivation/tips.json`（398 条）← `StartupTipSource`（缺失降级内置默认句）。
- 改资产后同步：version、`docs/EXAMPLE_SENTENCE_SOURCES.tsv`（Tatoeba 署名）、`THIRD-PARTY-NOTICES.md`、`docs/WORD_PACK_VALIDATION.md` 实测数字。

## 坑点
- `cache/`（wordpack + pronunciation）**全部 gitignored 且本地不存在**——复现先 `download.py`；**Tatoeba 3 个 .bz2 不在 download.py 内**，需从 downloads.tatoeba.org 按 `PROVENANCE.json` 快照手工取（Tatoeba 持续编辑，重下会变 hash）。
- `attach_examples.py` **重复执行**会在 PACK_INFO.json `transformNotes` 累加重复段（现状已有 4 次痕迹）——勿反复追跑。
- 联网脚本：download / attach_examples（首次下 OpenCC）/ verify_audio / uk_probe；verify_audio 限速 1 req/s + 429 退避，findings 有熔断记录，结论随网络而异。
- pronunciation/ 是**历史决策记录**（真人录音已移除、改系统 TTS）——勿把其产物接入资产。
- validate_packs 依赖 `cache/ecdict.csv` 做完整性证明，缺失直接崩。

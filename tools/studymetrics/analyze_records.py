#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""学习记录离线分析：从「完整备份」JSON 评估 SM-2 调度的真实记忆效果。

用法（PowerShell，先设 UTF-8）：
    $env:PYTHONIOENCODING = "utf-8"
    python -I tools/studymetrics/analyze_records.py <完整备份.json> [--out 输出目录]

输出（写到 --out 目录，默认 tools/studymetrics/cache/）：
    report.md   人读报告：五个问题的表格与初步观察
    report.json 机器可读明细：表格原始数据 + findings

分析的问题与口径见 README.md 与 core.py 模块 docstring。只报告，不修改任何算法参数。

退出码：0 正常（含数据不足的降级报告）；2 文件不存在 / JSON 损坏 / 不是完整备份；1 其他错误。
"""

import argparse
import json
import sys
from pathlib import Path

# `python -I`（隔离模式）不把脚本目录放进 sys.path，这里手动补上，保证 `import core` 可用。
sys.path.insert(0, str(Path(__file__).resolve().parent))

import core  # noqa: E402

DATA_SUFFICIENT_NOTE = (
    "学习作答还不到 {} 条或活跃天不足 {} 天：数据还不够多，以下只列样本量，暂不给判断。"
).format(core.MIN_TOTAL_ANSWER_EVENTS, core.MIN_TOTAL_ACTIVE_DAYS)


# --- 表格渲染 -----------------------------------------------------------------------------

def rate_cell(value):
    return core.rate_str(value)


def render_interval_table(section):
    lines = [
        "| 间隔 | 作答 | 已掌握 | 模糊 | 不认识 | 正确率 |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for b in section["buckets"]:
        c = b["counts"]
        lines.append("| {} | {} | {} | {} | {} | {} |".format(
            b["bucket"], b["n"], c[core.GRADE_CORRECT], c[core.GRADE_FUZZY],
            c[core.GRADE_WRONG], rate_cell(b["correct_rate"]),
        ))
    return lines


def render_fuzzy_table(section):
    lines = ["模糊之后，下一次同词作答：", ""]
    lines += [
        "| 间隔 | 次数 |",
        "|---|---:|",
    ]
    for kind, count in section["next_answer_gap"].items():
        lines.append("| {} | {} |".format(kind, count))
    lines.append("")
    results = section["next_result"]
    lines.append(
        "下一次作答的结果：已掌握 {} / 模糊 {} / 不认识 {}；模糊词共涉及 {} 个词。".format(
            results[core.GRADE_CORRECT], results[core.GRADE_FUZZY], results[core.GRADE_WRONG],
            section["words_involved"],
        )
    )
    if section["top_words"]:
        tops = "、".join("{}（{} 次）".format(t["word"], t["count"]) for t in section["top_words"])
        lines.append("反复模糊的词：{}".format(tops))
    return lines


def render_retention_table(section):
    lines = [
        "口径说明：事件流里没有 masteryLevel，这里用「首次调到 ≥21 天间隔」近似毕业线。",
        "",
        "| 毕业后窗口 | 观察词数 | 窗口内遗忘 | 保持率 |",
        "|---|---:|---:|---:|",
    ]
    for w in section["retention_windows"]:
        lines.append("| {} 天 | {} | {} | {} |".format(
            w["window_days"], w["eligible_words"], w["lapsed_words"], rate_cell(w["retention_rate"]),
        ))
    lines.append("")
    lines.append(
        "当前已掌握 {} 个词，其中 {} 个已走过毕业线、{} 个毕业后又忘记过。".format(
            section["mastered_now"], section["mastered_graduated"],
            section["mastered_graduated_with_lapse"],
        )
    )
    if section["worst_lapsed_words"]:
        worst = "、".join(
            "{}（毕业 {:.0f} 天后忘，共 {} 次）".format(
                item["word"], item["first_lapse_days_after_graduation"], item["lapses"],
            )
            for item in section["worst_lapsed_words"][:5]
        )
        lines.append("毕业后仍反复忘记的词：{}".format(worst))
    return lines


def render_new_review_table(section):
    lines = [
        "| 当日新词 | 天数 | 复习作答 | 复习正确率 |",
        "|---|---:|---:|---:|",
    ]
    for b in section["buckets"]:
        lines.append("| {} | {} | {} | {} |".format(
            b["bucket"], b["days"], b["review_answers"], rate_cell(b["review_correct_rate"]),
        ))
    r = section["pearson_r"]
    lines.append("")
    if r is None:
        lines.append("复习作答 ≥5 次的天数不足，算不出新词量与复习正确率的相关性。")
    else:
        lines.append("新词量与复习正确率的 Pearson 相关系数：r={:.2f}（n={} 个学习日）。".format(
            r, section["pearson_days"],
        ))
    return lines


def render_fatigue_table(section):
    lines = [
        "| 会话内位置 | 作答 | 正确率 | 新词正确率 | 复习正确率 | 中位耗时 |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for b in section["buckets"]:
        median = b["median_response_ms"]
        lines.append("| {} | {} | {} | {} | {} | {} |".format(
            b["bucket"], b["n"], rate_cell(b["correct_rate"]),
            rate_cell(b["new_correct_rate"]), rate_cell(b["review_correct_rate"]),
            "{:.0f} 秒".format(median / 1000) if median is not None else "—",
        ))
    lines.append("")
    lines.append(
        "共 {} 个会话，其中 {} 次超过 45 分钟。".format(
            section["sessions"], section["long_sessions_over_45min"],
        )
    )
    return lines


# --- 报告拼装 -----------------------------------------------------------------------------

def section_lines(title, table_lines, findings, insufficient, data_sufficient):
    lines = ["## " + title, ""]
    lines += table_lines
    lines.append("")
    if insufficient and not findings:
        lines.append("_样本还不足，暂不给判断。_")
        lines.append("")
        return lines
    if not data_sufficient and not findings:
        lines.append("_{}_".format(DATA_SUFFICIENT_NOTE))
        lines.append("")
        return lines
    if findings:
        lines.append("**初步观察**")
        lines.append("")
        for f in findings:
            lines.append("- {}".format(f))
        lines.append("")
    return lines


def render_markdown(result):
    ov = result["overview"]
    lines = []
    ap = lines.append

    ap("# 学习记录分析报告")
    ap("")
    ap("- 备份导出时间：{}".format(result["exportedAtIso"] or "未知"))
    ap("- 数据文件：`{}`".format(result["source_file"]))
    span = "（{} ~ {}）".format(ov["first_day"], ov["last_day"]) if ov["first_day"] else ""
    ap("- 学习跨度：{} 个学习日{}".format(ov["active_days"], span))
    immediate_total = ov["immediate_events"]["tagged"] + ov["immediate_events"]["inferred"]
    ap(
        "- 答题事件：共 {} 条 = 学习作答 {} 条 + 即时巩固 {} 条 + 考试 {} 条"
        "（涉及 {} 个词、{} 个会话）".format(
            ov["total_events"], ov["study_sample_events"], immediate_total,
            ov["exam_events"], ov["distinct_words"], ov["sessions"],
        )
    )
    if not ov["data_sufficient"]:
        ap("")
        ap("> {}".format(DATA_SUFFICIENT_NOTE))
    ap("")

    s = result["interval_retention"]
    lines += section_lines("1. 间隔-保持率（间隔是否过频/过疏？）", render_interval_table(s), s["findings"], s["insufficient"], ov["data_sufficient"])

    s = result["fuzzy_consolidation"]
    lines += section_lines("2. 模糊词巩固（模糊词得到足够巩固了吗？）", render_fuzzy_table(s), s["findings"], s["insufficient"], ov["data_sufficient"])

    s = result["long_term_retention"]
    lines += section_lines("3. 已掌握词的长期保持（真的记住了吗？）", render_retention_table(s), s["findings"], s["insufficient"], ov["data_sufficient"])

    s = result["new_vs_review"]
    lines += section_lines("4. 每日新词量与复习质量（新词多了影响复习吗？）", render_new_review_table(s), s["findings"], s["insufficient"], ov["data_sufficient"])

    s = result["session_fatigue"]
    lines += section_lines("5. 学习队列与疲劳（顺序引起机械疲劳吗？）", render_fatigue_table(s), s["findings"], s["insufficient"], ov["data_sufficient"])

    ap("## 数据缺口")
    ap("")
    gaps = result["data_gaps"]
    if gaps:
        for g in gaps:
            ap("- {}".format(g))
    else:
        ap("- 当前数据没有已知缺口。")
    ap("")

    ap("## 可选的后续方向（本工具只报告，不改算法）")
    ap("")
    for step in result["next_steps"]:
        ap("- {}".format(step))
    ap("")
    return "\n".join(lines)


def write_text(path, text, newline):
    with open(path, "w", encoding="utf-8", newline=newline) as f:
        f.write(text)


# --- CLI ----------------------------------------------------------------------------------

def main(argv=None):
    parser = argparse.ArgumentParser(
        description="分析应用的完整备份 JSON，评估记忆保持效果（详见 README.md）。",
    )
    parser.add_argument("backup", help="完整备份 JSON 文件路径")
    parser.add_argument("--out", default=None, help="输出目录（默认 tools/studymetrics/cache）")
    args = parser.parse_args(argv)

    backup_path = Path(args.backup)
    if not backup_path.is_file():
        print("找不到备份文件：{}".format(backup_path), file=sys.stderr)
        return 2

    try:
        backup = core.load_backup(backup_path)
    except core.BackupFormatError as exc:
        print("备份文件格式不符：{}".format(exc), file=sys.stderr)
        return 2
    except (OSError, UnicodeDecodeError, ValueError, TypeError) as exc:
        print("读取备份失败：{}".format(exc), file=sys.stderr)
        return 2

    try:
        groups = core.assign_sessions(backup.events)
        result = core.analyze(backup, groups)
        markdown = render_markdown(result)
    except Exception as exc:  # noqa: BLE001 — 工具入口统一兜底，报告而非崩溃
        print("分析失败：{}: {}".format(type(exc).__name__, exc), file=sys.stderr)
        return 1

    out_dir = Path(args.out) if args.out else Path(__file__).resolve().parent / "cache"
    out_dir.mkdir(parents=True, exist_ok=True)
    json_path = out_dir / "report.json"
    md_path = out_dir / "report.md"
    write_text(json_path, json.dumps(result, ensure_ascii=False, indent=2) + "\n", newline="")
    write_text(md_path, markdown, newline="\n")

    ov = result["overview"]
    print("分析完成：{} 条答题事件 / {} 个学习日".format(ov["total_events"], ov["active_days"]))
    print("报告：{}".format(md_path))
    print("明细：{}".format(json_path))
    if not ov["data_sufficient"]:
        print("注意：" + DATA_SUFFICIENT_NOTE)
    return 0


if __name__ == "__main__":
    sys.exit(main())

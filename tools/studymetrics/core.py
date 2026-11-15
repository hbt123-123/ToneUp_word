#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""学习记录清洗与指标计算（供 analyze_records.py 调用，纯标准库）。

输入是应用的「完整备份」JSON（数据传输 → 完整备份，format = "ToneUpWord 完整备份 (JSON)"）。

清洗口径（与 README.md 保持一致）：
- 主样本 = source ∈ {STUDY, DRILL} 的自评作答；EXAM 只作参考统计；IMMEDIATE 是即时巩固，
  从「间隔保持率」「新词量」分析中剔除。
- 旧备份没有 IMMEDIATE 标记：同词与上一条作答间隔 ≤ 15 分钟、且上一条评了不认识/模糊的，
  视为即时重复（调度常量 10 分钟 + 5 分钟容差）。
- 新词首答 = 该词在事件流中的第一条记录且 previous_interval_days == 0。不能只看
  previous_interval_days == 0：不认识之后调度会把间隔清零，10 分钟内的重答同样满足，
  会把「复习失败」误计成「学到新词」。
- 会话切分：≥ 一半学习作答带 sessionId 时按 sessionId 分组（缺 id 的按时间间隙独立成组），
  否则全部按答题时间间隙 > 10 分钟切分。
- 「数据有限」原则与 InsightEngine 对齐：学习作答 < 20 条或活跃天 < 3 天时不给判断，
  只报样本量。
"""

import json
import math
import statistics
from dataclasses import dataclass, field
from pathlib import Path

FORMAT_FULL_BACKUP = "ToneUpWord 完整备份 (JSON)"

GRADE_CORRECT = "MASTERED"
GRADE_FUZZY = "FUZZY"
GRADE_WRONG = "UNKNOWN"

SOURCE_STUDY = "STUDY"
SOURCE_IMMEDIATE = "IMMEDIATE"
SOURCE_DRILL = "DRILL"
SOURCE_EXAM = "EXAM"
STUDY_SOURCES = (SOURCE_STUDY, SOURCE_DRILL)

DAY_MS = 24 * 60 * 60 * 1000
IMMEDIATE_GAP_MS = 15 * 60 * 1000
SESSION_GAP_MS = 10 * 60 * 1000
LONG_SESSION_MS = 45 * 60 * 1000

# 与 InsightEngine 相同的「数据不够多」门槛。
MIN_TOTAL_ANSWER_EVENTS = 20
MIN_TOTAL_ACTIVE_DAYS = 3

# 单个分桶至少要有这么多作答才参与判读。
MIN_BUCKET_N = 10
MIN_BUCKET_N_TIGHT = 30

GRADUATION_INTERVAL_DAYS = 21
RETENTION_WINDOWS_DAYS = (7, 14, 30, 60)

# (标签, 下界, 上界)。「新词首答」与「失败后重置」两桶在 interval_retention 里单独构造，
# 这里只列 previous_interval_days >= 1 的复习间隔。
INTERVAL_BUCKETS = (
    ("1 天", 1, 1),
    ("2-3 天", 2, 3),
    ("4-7 天", 4, 7),
    ("8-14 天", 8, 14),
    ("15-30 天", 15, 30),
    ("31-60 天", 31, 60),
    ("61-180 天", 61, 180),
    ("180 天以上", 181, 10 ** 9),
)

FATIGUE_BUCKETS = (
    ("1-5", 1, 5),
    ("6-15", 6, 15),
    ("16-30", 16, 30),
    ("31-60", 31, 60),
    ("61+", 61, 10 ** 9),
)

NEW_COUNT_BUCKETS = (
    ("0", 0, 0),
    ("1-10", 1, 10),
    ("11-20", 11, 20),
    ("21-40", 21, 40),
    ("41+", 41, 10 ** 9),
)


class BackupFormatError(ValueError):
    """文件不是完整备份 JSON（损坏、缺 format 或 format 不符）。"""


@dataclass
class Event:
    word: str
    grade: str
    source: str
    answered_at: int
    day_key: str
    prev_interval: int
    new_interval: int
    session_id: object = None
    response_ms: object = None
    is_immediate: bool = False
    is_new_first: bool = False
    session_key: int = 0
    session_position: int = 0

    @property
    def is_exam(self):
        return self.source == SOURCE_EXAM

    @property
    def is_study_sample(self):
        """主样本：学习/错题巩固的自评作答，不含即时巩固与考试。"""
        return self.source in STUDY_SOURCES and not self.is_immediate


@dataclass
class Backup:
    path: object
    exported_at_millis: int
    exported_at_iso: str
    events: list = field(default_factory=list)
    progress: list = field(default_factory=list)
    days: list = field(default_factory=list)


# --- 加载与清洗 -------------------------------------------------------------------------

def load_backup(path):
    """读取完整备份 JSON。格式不符抛 BackupFormatError，文件问题抛 OSError。"""
    path = Path(path)
    with open(path, "r", encoding="utf-8") as f:
        try:
            raw = json.load(f)
        except json.JSONDecodeError as exc:
            raise BackupFormatError("JSON 解析失败：{}".format(exc)) from exc
    if not isinstance(raw, dict):
        raise BackupFormatError("备份顶层应是 JSON 对象")
    if raw.get("format") != FORMAT_FULL_BACKUP:
        raise BackupFormatError(
            "这不是完整备份文件（format={!r}，需要 {!r}）。"
            "请在应用内「数据传输 → 完整备份」导出。".format(raw.get("format"), FORMAT_FULL_BACKUP)
        )
    events = clean_events(raw.get("events") or [])
    return Backup(
        path=path,
        exported_at_millis=int(raw.get("exportedAtMillis") or 0),
        exported_at_iso=str(raw.get("exportedAtIso") or ""),
        events=events,
        progress=raw.get("progress") or [],
        days=raw.get("days") or [],
    )


def clean_events(raw_events):
    """原始事件 → 排序、标记即时重复与新词首答的 Event 列表。"""
    events = []
    for r in raw_events:
        events.append(Event(
            word=str(r.get("word") or ""),
            grade=str(r.get("grade") or ""),
            source=str(r.get("source") or ""),
            answered_at=int(r.get("answeredAt") or 0),
            day_key=str(r.get("dayKey") or ""),
            prev_interval=int(r.get("previousIntervalDays") or 0),
            new_interval=int(r.get("newIntervalDays") or 0),
            session_id=r.get("sessionId"),
            response_ms=r.get("responseTimeMillis"),
        ))
    events.sort(key=lambda e: e.answered_at)

    last_by_word = {}
    for e in events:
        prev = last_by_word.get(e.word)
        if e.source == SOURCE_IMMEDIATE:
            e.is_immediate = True
        elif (
            prev is not None
            and prev.grade in (GRADE_FUZZY, GRADE_WRONG)
            and prev.source != SOURCE_EXAM
            and e.source in STUDY_SOURCES
            and 0 < e.answered_at - prev.answered_at <= IMMEDIATE_GAP_MS
        ):
            # 旧备份没有 IMMEDIATE 标记时的启发式：紧跟着不认识/模糊的重答。
            e.is_immediate = True
        e.is_new_first = (
            prev is None
            and e.prev_interval == 0
            and not e.is_immediate
            and e.source in STUDY_SOURCES
        )
        last_by_word[e.word] = e
    return events


def assign_sessions(events):
    """给学习作答（不含考试）填会话键与会话内序号，返回按开始时间排序的会话列表。"""
    study = [e for e in events if not e.is_exam]
    if not study:
        return []
    with_id = sum(1 for e in study if e.session_id is not None)
    if with_id >= len(study) / 2:
        by_id = {}
        for e in study:
            if e.session_id is not None:
                by_id.setdefault(e.session_id, []).append(e)
        groups = list(by_id.values()) + _split_by_gap([e for e in study if e.session_id is None])
    else:
        groups = _split_by_gap(study)
    groups = [g for g in groups if g]
    groups.sort(key=lambda g: min(e.answered_at for e in g))
    for key, group in enumerate(groups, start=1):
        group.sort(key=lambda e: e.answered_at)
        for pos, e in enumerate(group, start=1):
            e.session_key = key
            e.session_position = pos
    return groups


def _split_by_gap(events):
    ordered = sorted(events, key=lambda e: e.answered_at)
    groups = []
    current = []
    last_at = None
    for e in ordered:
        if current and last_at is not None and e.answered_at - last_at > SESSION_GAP_MS:
            groups.append(current)
            current = []
        current.append(e)
        last_at = e.answered_at
    if current:
        groups.append(current)
    return groups


def events_by_word(events):
    by_word = {}
    for e in events:
        by_word.setdefault(e.word, []).append(e)
    return by_word


# --- 小工具 -----------------------------------------------------------------------------

def rate(correct, total):
    return correct / total if total else None


def rate_str(value):
    return "{:.0f}%".format(value * 100) if value is not None else "—"


def pearson(xs, ys):
    n = len(xs)
    if n < 3:
        return None
    mean_x = sum(xs) / n
    mean_y = sum(ys) / n
    cov = sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys))
    var_x = sum((x - mean_x) ** 2 for x in xs)
    var_y = sum((y - mean_y) ** 2 for y in ys)
    if var_x <= 0 or var_y <= 0:
        return None
    return cov / math.sqrt(var_x * var_y)


def days_since(from_millis, to_millis):
    return (to_millis - from_millis) / DAY_MS


# --- 概览 -------------------------------------------------------------------------------

def overview(backup, groups):
    events = backup.events
    main = [e for e in events if e.is_study_sample]
    exam = [e for e in events if e.is_exam]
    explicit_immediate = [e for e in events if e.source == SOURCE_IMMEDIATE]
    inferred_immediate = [e for e in events if e.is_immediate and e.source != SOURCE_IMMEDIATE]
    active_days = sorted({e.day_key for e in main if e.day_key})
    words = {e.word for e in events if e.word}
    with_response = sum(1 for e in events if e.response_ms is not None)
    study = [e for e in events if not e.is_exam]
    with_session = sum(1 for e in study if e.session_id is not None)

    grade_counts = {g: 0 for g in (GRADE_CORRECT, GRADE_FUZZY, GRADE_WRONG)}
    for e in main:
        if e.grade in grade_counts:
            grade_counts[e.grade] += 1

    return {
        "total_events": len(events),
        "study_sample_events": len(main),
        "exam_events": len(exam),
        "immediate_events": {
            "tagged": len(explicit_immediate),
            "inferred": len(inferred_immediate),
        },
        "active_days": len(active_days),
        "first_day": active_days[0] if active_days else None,
        "last_day": active_days[-1] if active_days else None,
        "distinct_words": len(words),
        "sessions": len(groups),
        "grade_counts": grade_counts,
        "response_time_coverage": rate(with_response, len(events)),
        "session_id_coverage": rate(with_session, len(study)) if study else None,
        "data_sufficient": len(main) >= MIN_TOTAL_ANSWER_EVENTS and len(active_days) >= MIN_TOTAL_ACTIVE_DAYS,
    }


# --- M1 间隔-保持率 -----------------------------------------------------------------------

def interval_retention(events):
    main = [e for e in events if e.is_study_sample]

    def make_bucket(label, rows):
        counts = {g: 0 for g in (GRADE_CORRECT, GRADE_FUZZY, GRADE_WRONG)}
        for e in rows:
            if e.grade in counts:
                counts[e.grade] += 1
        return {
            "bucket": label,
            "n": len(rows),
            "counts": counts,
            "correct_rate": rate(counts[GRADE_CORRECT], len(rows)),
            "keep_for_judgement": len(rows) >= MIN_BUCKET_N,
        }

    # previous_interval_days == 0 有两种含义：新词首答，与「不认识后间隔被清零、隔天再答」。
    # 两者的记忆语义不同（后者是刚失败过的复习），拆开统计。
    buckets = [
        make_bucket("新词首答", [e for e in main if e.is_new_first]),
        make_bucket("失败后重置", [e for e in main if e.prev_interval == 0 and not e.is_new_first]),
    ]
    for label, lo, hi in INTERVAL_BUCKETS:
        buckets.append(make_bucket(label, [e for e in main if lo <= e.prev_interval <= hi]))

    # 判读只看真正的复习间隔桶（跳过前两个特殊桶）。
    judgable = [b for b in buckets[2:] if b["n"] >= MIN_BUCKET_N]
    findings = []
    for b in judgable:
        if b["n"] >= MIN_BUCKET_N_TIGHT and b["correct_rate"] >= 0.95:
            findings.append(
                "间隔 {} 的作答几乎全对（{}/{} 次，{}）：这一段可能偏频，间隔还有拉长的余地。".format(
                    b["bucket"], b["counts"][GRADE_CORRECT], b["n"], rate_str(b["correct_rate"])
                )
            )
    for prev_b, cur_b in zip(judgable, judgable[1:]):
        drop = (prev_b["correct_rate"] or 0) - (cur_b["correct_rate"] or 0)
        if drop >= 0.15 and prev_b["n"] >= MIN_BUCKET_N and cur_b["n"] >= MIN_BUCKET_N:
            findings.append(
                "正确率从 {}（{}）跌到 {}（{}）：间隔跨过这一步后遗忘明显增多，间隔增长可能偏快。".format(
                    prev_b["bucket"], rate_str(prev_b["correct_rate"]),
                    cur_b["bucket"], rate_str(cur_b["correct_rate"]),
                )
            )
    if not findings and judgable:
        findings.append("现有数据下各区间保持率没有明显的过频/过疏信号。")
    return {
        "buckets": buckets,
        "findings": findings,
        "insufficient": not judgable,
    }


# --- M2 模糊词巩固 -----------------------------------------------------------------------

def fuzzy_consolidation(events):
    main = [e for e in events if e.is_study_sample]
    starts = [e for e in main if e.grade == GRADE_FUZZY]
    by_word = events_by_word(events)

    gap_kinds = ("10 分钟内（即时巩固）", "当日稍后", "次日", "2-3 天", "4 天以上", "无后续作答")
    gap_counts = {k: 0 for k in gap_kinds}
    next_results = {g: 0 for g in (GRADE_CORRECT, GRADE_FUZZY, GRADE_WRONG)}
    with_next = 0
    again_within_3d = 0
    two_correct_after = 0

    for start in starts:
        later = [e for e in by_word.get(start.word, []) if e.answered_at > start.answered_at]
        if not later:
            gap_counts["无后续作答"] += 1
            continue
        with_next += 1
        nxt = later[0]
        gap_ms = nxt.answered_at - start.answered_at
        if gap_ms <= IMMEDIATE_GAP_MS:
            gap_counts["10 分钟内（即时巩固）"] += 1
        elif nxt.day_key == start.day_key:
            gap_counts["当日稍后"] += 1
        elif gap_ms <= 1 * DAY_MS:
            gap_counts["次日"] += 1
        elif gap_ms <= 3 * DAY_MS:
            gap_counts["2-3 天"] += 1
        else:
            gap_counts["4 天以上"] += 1
        if nxt.grade in next_results:
            next_results[nxt.grade] += 1

        window = [e for e in later if e.answered_at - start.answered_at <= 3 * DAY_MS]
        if any(e.grade in (GRADE_FUZZY, GRADE_WRONG) for e in window):
            again_within_3d += 1
        streak = 0
        hit_two = False
        for e in later[:4]:
            streak = streak + 1 if e.grade == GRADE_CORRECT else 0
            if streak >= 2:
                hit_two = True
                break
        if hit_two:
            two_correct_after += 1

    word_counts = {}
    for e in starts:
        word_counts[e.word] = word_counts.get(e.word, 0) + 1
    top_words = sorted(word_counts.items(), key=lambda kv: (-kv[1], kv[0]))[:5]

    findings = []
    if starts and with_next:
        stuck_rate = again_within_3d / with_next
        solid_rate = two_correct_after / with_next
        if with_next >= MIN_BUCKET_N:
            if stuck_rate >= 0.4:
                findings.append(
                    "模糊后 3 天内再次模糊/不认识的比例偏高（{}/{}，{}）：模糊词的后续巩固可能不够。".format(
                        again_within_3d, with_next, rate_str(stuck_rate)
                    )
                )
            elif solid_rate >= 0.6:
                findings.append(
                    "多数模糊词在后续作答中稳定记牢（{}/{}，{}）：即时巩固 + 缩短间隔在起作用。".format(
                        two_correct_after, with_next, rate_str(solid_rate)
                    )
                )
            else:
                findings.append("模糊词的后续表现居中，暂无明确信号。")
    return {
        "fuzzy_events": len(starts),
        "words_involved": len(word_counts),
        "avg_per_word": rate(len(starts), len(word_counts)) if word_counts else None,
        "next_answer_gap": gap_counts,
        "next_result": next_results,
        "again_fuzzy_or_wrong_within_3d": again_within_3d,
        "two_correct_in_followup": two_correct_after,
        "top_words": [{"word": w, "count": c} for w, c in top_words],
        "findings": findings,
        "insufficient": with_next < MIN_BUCKET_N,
    }


# --- M3 已掌握词的长期保持 ------------------------------------------------------------------

def long_term_retention(events, progress, exported_at_millis):
    by_word = events_by_word(events)
    graduates = {}
    for word, evs in by_word.items():
        grad = next((e for e in evs if e.new_interval >= GRADUATION_INTERVAL_DAYS), None)
        if grad is not None:
            graduates[word] = grad

    lapses_total = 0
    lapsed_words = []
    windows = {}
    for w in RETENTION_WINDOWS_DAYS:
        windows[w] = {"eligible": 0, "lapsed": 0}
    now_ms = exported_at_millis
    for word, grad in graduates.items():
        after = [e for e in by_word[word] if e.answered_at > grad.answered_at]
        lapse_events = [e for e in after if e.grade == GRADE_WRONG]
        if lapse_events:
            lapses_total += len(lapse_events)
            lapsed_words.append({
                "word": word,
                "graduated_days_ago": round(days_since(grad.answered_at, now_ms), 1),
                "first_lapse_days_after_graduation": round(days_since(grad.answered_at, lapse_events[0].answered_at), 1),
                "lapses": len(lapse_events),
            })
        for w in RETENTION_WINDOWS_DAYS:
            if days_since(grad.answered_at, now_ms) >= w:
                windows[w]["eligible"] += 1
                if any(days_since(grad.answered_at, e.answered_at) <= w for e in lapse_events):
                    windows[w]["lapsed"] += 1

    window_rows = []
    for w in RETENTION_WINDOWS_DAYS:
        cell = windows[w]
        window_rows.append({
            "window_days": w,
            "eligible_words": cell["eligible"],
            "lapsed_words": cell["lapsed"],
            "retention_rate": rate(cell["eligible"] - cell["lapsed"], cell["eligible"]),
            "keep_for_judgement": cell["eligible"] >= MIN_BUCKET_N,
        })

    mastered_now = {p.get("word") for p in progress if p.get("status") == GRADE_CORRECT}
    mastered_graduated = mastered_now & set(graduates)
    mastered_lapsed = {item["word"] for item in lapsed_words} & mastered_graduated
    lapsed_words.sort(key=lambda item: -item["lapses"])

    findings = []
    long_window = next((r for r in window_rows if r["window_days"] == 30), None)
    if long_window and long_window["keep_for_judgement"]:
        retention = long_window["retention_rate"] or 0
        if retention >= 0.85:
            findings.append(
                "毕业 30 天后仍有 {} 的词没有忘记（{}/{} 个词）：长期保持良好。".format(
                    rate_str(retention),
                    long_window["eligible_words"] - long_window["lapsed_words"],
                    long_window["eligible_words"],
                )
            )
        elif retention <= 0.70:
            findings.append(
                "毕业 30 天内的遗忘率不低（{}/{} 个词在窗口内忘记）：21 天毕业线可能偏松，或毕业后的复现间隔偏长。".format(
                    long_window["lapsed_words"],
                    long_window["eligible_words"],
                )
            )
    return {
        "graduated_words": len(graduates),
        "avg_graduation_interval_days": (
            statistics.median([g.new_interval for g in graduates.values()]) if graduates else None
        ),
        "lapses_after_graduation": lapses_total,
        "lapsed_word_count": len(lapsed_words),
        "retention_windows": window_rows,
        "mastered_now": len(mastered_now),
        "mastered_graduated": len(mastered_graduated),
        "mastered_graduated_with_lapse": len(mastered_lapsed),
        "worst_lapsed_words": lapsed_words[:10],
        "findings": findings,
        "insufficient": len(graduates) < MIN_BUCKET_N,
    }


# --- M4 每日新词量与复习质量 -----------------------------------------------------------------

def new_vs_review(events):
    main = [e for e in events if e.is_study_sample]
    by_day = {}
    for e in main:
        cell = by_day.setdefault(e.day_key, {"new": 0, "review_n": 0, "review_correct": 0})
        if e.is_new_first:
            cell["new"] += 1
        else:
            cell["review_n"] += 1
            if e.grade == GRADE_CORRECT:
                cell["review_correct"] += 1

    buckets = []
    for label, lo, hi in NEW_COUNT_BUCKETS:
        rows = [c for c in by_day.values() if lo <= c["new"] <= hi and c["review_n"] > 0]
        review_n = sum(c["review_n"] for c in rows)
        review_correct = sum(c["review_correct"] for c in rows)
        buckets.append({
            "bucket": label,
            "days": len(rows),
            "review_answers": review_n,
            "review_correct_rate": rate(review_correct, review_n),
            "keep_for_judgement": review_n >= MIN_BUCKET_N,
        })

    xs, ys = [], []
    for c in by_day.values():
        if c["review_n"] >= 5:
            xs.append(c["new"])
            ys.append(c["review_correct"] / c["review_n"])
    r = pearson(xs, ys)

    findings = []
    # 相关性在小样本下很不稳定：天数不足时与「数据有限」原则一致，只算不判。
    if r is not None and len(xs) >= 8:
        direction = None
        if r <= -0.3:
            direction = "新词学得多的日子，复习正确率反而更低（r={:.2f}，n={} 天）：新词量可能挤占了复习质量，可以试着把每日新词调低一档观察。".format(r, len(xs))
        elif r >= 0.3:
            direction = "新词量与复习正确率呈正相关（r={:.2f}，n={} 天）：目前的新词量没有压垮复习。".format(r, len(xs))
        else:
            direction = "新词量与复习正确率没有明显相关（r={:.2f}，n={} 天）。".format(r, len(xs))
        if direction:
            findings.append(direction)
    return {
        "by_day": [
            {"dayKey": k, "new": c["new"], "review_n": c["review_n"], "review_correct": c["review_correct"]}
            for k, c in sorted(by_day.items())
        ],
        "buckets": buckets,
        "pearson_r": r,
        "pearson_days": len(xs),
        "findings": findings,
        "insufficient": len(xs) < 8,
    }


# --- M5 学习队列与疲劳 ----------------------------------------------------------------------

def session_fatigue(groups):
    buckets = []
    for label, lo, hi in FATIGUE_BUCKETS:
        cell = {
            "bucket": label,
            "n": 0,
            "correct": 0,
            "response_ms": [],
            "new": {"n": 0, "correct": 0},
            "review": {"n": 0, "correct": 0},
        }
        for group in groups:
            for e in group:
                if not (lo <= e.session_position <= hi):
                    continue
                if not e.is_study_sample:
                    continue
                cell["n"] += 1
                if e.grade == GRADE_CORRECT:
                    cell["correct"] += 1
                if e.response_ms is not None:
                    cell["response_ms"].append(e.response_ms)
                target = cell["new"] if e.is_new_first else cell["review"]
                target["n"] += 1
                if e.grade == GRADE_CORRECT:
                    target["correct"] += 1
        cell["correct_rate"] = rate(cell["correct"], cell["n"])
        if len(cell["response_ms"]) >= max(1, int(0.6 * cell["n"])):
            cell["median_response_ms"] = statistics.median(cell["response_ms"])
        else:
            cell["median_response_ms"] = None
        cell["new_correct_rate"] = rate(cell["new"]["correct"], cell["new"]["n"]) if cell["new"]["n"] else None
        cell["review_correct_rate"] = rate(cell["review"]["correct"], cell["review"]["n"]) if cell["review"]["n"] else None
        del cell["response_ms"]
        buckets.append(cell)

    durations = []
    for group in groups:
        study = [e for e in group if e.is_study_sample]
        if len(study) >= 2:
            durations.append(study[-1].answered_at - study[0].answered_at)
    long_sessions = sum(1 for d in durations if d >= LONG_SESSION_MS)

    findings = []
    first = next((b for b in buckets if b["n"] >= MIN_BUCKET_N), None)
    late = next((b for b in reversed(buckets) if b["n"] >= MIN_BUCKET_N), None)
    if first and late and first is not late:
        drop = (first["correct_rate"] or 0) - (late["correct_rate"] or 0)
        if drop >= 0.10:
            findings.append(
                "会话前段（第 {} 张）正确率 {}，后段（第 {} 张）只有 {}：越往后越容易出错，可能有疲劳或机械作答。".format(
                    first["bucket"], rate_str(first["correct_rate"]), late["bucket"], rate_str(late["correct_rate"])
                )
            )
        elif drop <= -0.10:
            findings.append(
                "会话后段正确率（{}）反而不低于前段（{}）：队列顺序没有明显疲劳信号。".format(
                    rate_str(late["correct_rate"]), rate_str(first["correct_rate"])
                )
            )
    if long_sessions and sum(b["n"] for b in buckets) >= MIN_BUCKET_N * 3:
        findings.append(
            "有 {} 次学习超过 45 分钟：单次会话偏长时，后段作答质量更值得警惕。".format(long_sessions)
        )
    return {
        "buckets": buckets,
        "sessions": len(groups),
        "long_sessions_over_45min": long_sessions,
        "findings": findings,
        "insufficient": sum(b["n"] for b in buckets) < MIN_BUCKET_N * 2,
    }


# --- 缺口与后续方向 -------------------------------------------------------------------------

def data_gaps(ov):
    gaps = []
    if (ov["response_time_coverage"] or 0) < 0.6:
        gaps.append(
            "当前备份基本不含答题耗时（response_time_ms）：只有学习路径记录它，旧版应用导出时会丢掉这个字段。"
            "更新到导出修复后的版本再导出一次，疲劳分析才有耗时维度。"
        )
    if (ov["session_id_coverage"] or 0) < 0.5:
        gaps.append("答题事件缺少会话 id，会话只能按 10 分钟时间间隙近似切分，跨间隙的长会话会被拆开。")
    if ov["immediate_events"]["inferred"] > ov["immediate_events"]["tagged"]:
        gaps.append(
            "大量即时重复是靠「同词 15 分钟内重答」启发式认出来的：数据来自修复前的版本，个别高频自测可能被误标。"
        )
    if ov["active_days"] < 14:
        gaps.append("观察期不足两周：已掌握词的长期保持（30/60 天窗口）还没有足够的观察样本。")
    if ov["exam_events"] == 0:
        gaps.append("还没有闭卷考试记录：考试是另一种检索练习，可作为保持效果的交叉验证。")
    return gaps


NEXT_STEPS = (
    "用新版应用（导出已补齐耗时与会话 id）重新导出完整备份，重跑本工具，补齐第 5 项分析的精度。",
    "让数据继续积累到 30 天以上再评估：间隔阶梯（1/3/7/14 → ×EF）是否需要调整。",
    "如果第 1 项长期显示某区间正确率 ≥95%，可以考虑拉长该区间之后的间隔（domain/srs/SpacedRepetition.kt 的阶梯常量）——另行迭代，勿随分析一起改。",
    "如果第 3 项的 30/60 天遗忘率偏高，可以考虑提高毕业线（MASTERED_MIN_INTERVAL_DAYS）或加强毕业词的低频复现——同样另行迭代。",
)


# --- 总入口 ------------------------------------------------------------------------------

def analyze(backup, groups):
    ov = overview(backup, groups)
    result = {
        "source_file": str(backup.path),
        "exportedAtMillis": backup.exported_at_millis,
        "exportedAtIso": backup.exported_at_iso,
        "overview": ov,
        "interval_retention": interval_retention(backup.events),
        "fuzzy_consolidation": fuzzy_consolidation(backup.events),
        "long_term_retention": long_term_retention(
            backup.events, backup.progress, backup.exported_at_millis
        ),
        "new_vs_review": new_vs_review(backup.events),
        "session_fatigue": session_fatigue(groups),
        "data_gaps": data_gaps(ov),
        "next_steps": list(NEXT_STEPS),
    }
    return result

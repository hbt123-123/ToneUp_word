package top.toneup.word.data.mapper

import top.toneup.word.core.json.JsonCodec
import top.toneup.word.data.local.dao.RecentEventRow
import top.toneup.word.data.local.dao.WordWithProgressView
import top.toneup.word.data.local.dao.WrongAnswerRow
import top.toneup.word.data.local.entity.DailyStudySummaryEntity
import top.toneup.word.data.local.entity.ExamQuestionEntity
import top.toneup.word.data.local.entity.ExamSessionEntity
import top.toneup.word.data.local.entity.ReviewEventEntity
import top.toneup.word.data.local.entity.StudySessionEntity
import top.toneup.word.data.local.entity.UserWordProgressEntity
import top.toneup.word.data.local.entity.WordEntity
import top.toneup.word.data.local.entity.WrongAnswerEntity
import top.toneup.word.domain.exam.ExamQuestion
import top.toneup.word.domain.exam.ExamQuestionType
import top.toneup.word.domain.exam.ExamScore
import top.toneup.word.domain.exam.TypeAccuracy
import top.toneup.word.domain.importer.ParsedWordRecord
import top.toneup.word.domain.model.CetLevel
import top.toneup.word.domain.model.Word
import top.toneup.word.domain.model.WordWithProgress
import top.toneup.word.domain.srs.MasteryStatus
import top.toneup.word.domain.srs.ReviewGrade
import top.toneup.word.domain.srs.SpacedRepetition
import top.toneup.word.domain.srs.WordProgress
import top.toneup.word.domain.stats.DailySummaryInput
import top.toneup.word.domain.study.StudySessionState

/**
 * Explicit, one-directional mapping between storage rows and domain models.
 *
 * Enums are stored as stable string ids and converted here (never by a Room TypeConverter),
 * so the database stays readable and an enum rename can be handled with a single mapping
 * function instead of a schema migration.
 */

// --- Enums ---------------------------------------------------------------------------------

fun MasteryStatus.toId(): String = name

fun masteryStatusFromId(id: String?): MasteryStatus =
    MasteryStatus.entries.firstOrNull { it.name == id } ?: MasteryStatus.NEW

fun ReviewGrade.toId(): String = name

fun reviewGradeFromId(id: String?): ReviewGrade? =
    ReviewGrade.entries.firstOrNull { it.name == id }

// --- Words ---------------------------------------------------------------------------------

fun WordEntity.toDomain(): Word = Word(
    id = id,
    word = word,
    phonetic = phonetic,
    partOfSpeech = partOfSpeech,
    meanings = JsonCodec.decodeStrings(meanings),
    exampleSentence = exampleSentence,
    exampleTranslation = exampleTranslation,
    collocations = JsonCodec.decodeStrings(collocations),
    level = CetLevel.fromId(level),
    source = source,
    license = license,
    audioUri = audioUri,
    packId = packId,
)

fun WordWithProgressView.toWord(): Word = Word(
    id = wordId,
    word = word,
    phonetic = phonetic,
    partOfSpeech = partOfSpeech,
    meanings = JsonCodec.decodeStrings(meanings),
    exampleSentence = exampleSentence,
    exampleTranslation = exampleTranslation,
    collocations = JsonCodec.decodeStrings(collocations),
    level = CetLevel.fromId(level),
    source = source,
    license = license,
    audioUri = audioUri,
    packId = packId,
)

fun WordWithProgressView.toProgress(): WordProgress = WordProgress(
    wordId = wordId,
    status = masteryStatusFromId(status),
    masteryLevel = masteryLevel ?: 0,
    repetitions = repetitions ?: 0,
    intervalDays = intervalDays ?: 0,
    easeFactor = easeFactor ?: SpacedRepetition.DEFAULT_EASE,
    dueAt = dueAt ?: 0L,
    lastReviewedAt = lastReviewedAt,
    lastAnswer = reviewGradeFromId(lastAnswer),
    lapseCount = lapseCount ?: 0,
    wrongCount = wrongCount ?: 0,
    fuzzyCount = fuzzyCount ?: 0,
    consecutiveCorrectCount = consecutiveCorrectCount ?: 0,
    isFavorite = (isFavorite ?: 0) == 1,
    introducedAt = introducedAt,
)

fun WordWithProgressView.toWordWithProgress(): WordWithProgress =
    WordWithProgress(word = toWord(), progress = toProgress())

fun UserWordProgressEntity.toDomain(): WordProgress = WordProgress(
    wordId = wordId,
    status = masteryStatusFromId(status),
    masteryLevel = masteryLevel,
    repetitions = repetitions,
    intervalDays = intervalDays,
    easeFactor = easeFactor,
    dueAt = dueAt,
    lastReviewedAt = lastReviewedAt,
    lastAnswer = reviewGradeFromId(lastAnswer),
    lapseCount = lapseCount,
    wrongCount = wrongCount,
    fuzzyCount = fuzzyCount,
    consecutiveCorrectCount = consecutiveCorrectCount,
    isFavorite = isFavorite,
    introducedAt = introducedAt,
)

fun WordProgress.toEntity(nowMillis: Long): UserWordProgressEntity = UserWordProgressEntity(
    wordId = wordId,
    status = status.toId(),
    masteryLevel = masteryLevel,
    repetitions = repetitions,
    intervalDays = intervalDays,
    easeFactor = easeFactor,
    dueAt = dueAt,
    lastReviewedAt = lastReviewedAt,
    lastAnswer = lastAnswer?.toId(),
    lapseCount = lapseCount,
    wrongCount = wrongCount,
    fuzzyCount = fuzzyCount,
    consecutiveCorrectCount = consecutiveCorrectCount,
    isFavorite = isFavorite,
    introducedAt = introducedAt,
    updatedAt = nowMillis,
)

/** A brand new progress row for a word that was just introduced. */
fun newProgressFor(wordId: Long, nowMillis: Long): WordProgress = WordProgress(
    wordId = wordId,
    status = MasteryStatus.NEW,
    dueAt = nowMillis,
    introducedAt = nowMillis,
)

fun ParsedWordRecord.toEntity(packId: String, nowMillis: Long): WordEntity = WordEntity(
    packId = packId,
    externalId = externalId,
    word = word,
    normalizedWord = Word.normalizeWordKey(word),
    phonetic = phonetic,
    partOfSpeech = partOfSpeech,
    meanings = JsonCodec.encodeStrings(meanings),
    exampleSentence = exampleSentence,
    exampleTranslation = exampleTranslation,
    collocations = JsonCodec.encodeStrings(collocations),
    level = level.id,
    source = source,
    license = license,
    audioUri = audioUri,
    createdAt = nowMillis,
    updatedAt = nowMillis,
)

// --- Mistakes ------------------------------------------------------------------------------

fun WrongAnswerRow.toWord(): Word = Word(
    id = wordId,
    word = word,
    phonetic = phonetic,
    partOfSpeech = partOfSpeech,
    meanings = JsonCodec.decodeStrings(meanings),
    exampleSentence = exampleSentence,
    level = CetLevel.fromId(level),
)

fun WrongAnswerRow.toProgress(): WordProgress = WordProgress(
    wordId = wordId,
    status = masteryStatusFromId(status),
    masteryLevel = masteryLevel ?: 0,
    dueAt = dueAt ?: 0L,
    lastReviewedAt = lastReviewedAt,
    lastAnswer = reviewGradeFromId(lastAnswer),
    wrongCount = wrongCount,
    isFavorite = (isFavorite ?: 0) == 1,
)

fun WrongAnswerRow.toEntity(nowMillis: Long): WrongAnswerEntity = WrongAnswerEntity(
    wordId = wordId,
    wrongCount = wrongCount,
    fuzzyCount = fuzzyCount,
    firstWrongAt = firstWrongAt,
    lastWrongAt = lastWrongAt,
    lastSource = lastSource,
    correctSinceWrong = correctSinceWrong,
    resolvedAt = resolvedAt,
    isFavorite = (isFavorite ?: 0) == 1,
    level = level,
    updatedAt = nowMillis,
)

fun RecentEventRow.toReviewEventDisplay(): top.toneup.word.domain.study.RecentEvent = top.toneup.word.domain.study.RecentEvent(
    word = word,
    meaning = JsonCodec.decodeStrings(meanings).firstOrNull().orEmpty(),
    grade = reviewGradeFromId(grade) ?: ReviewGrade.FUZZY,
    answeredAt = answeredAt,
)

// --- Sessions ------------------------------------------------------------------------------

fun StudySessionEntity.toState(reasons: Map<Long, top.toneup.word.domain.study.QueueReason>): StudySessionState =
    StudySessionState(
        sessionId = id,
        levelId = level,
        queue = JsonCodec.decodeLongs(queue),
        reasons = reasons,
        newWordIds = emptySet(),
        position = queuePosition,
        repeatCounts = JsonCodec.decodeIntCounts(immediateRepeatCounts),
        answered = answeredCount,
        masteredCount = masteredCount,
        fuzzyCount = fuzzyCount,
        unknownCount = unknownCount,
        newSeen = newWordsSeen,
        reviewSeen = reviewWordsSeen,
        startedAt = startedAt,
        updatedAt = updatedAt,
    )

fun ReviewEventEntity.toDisplay(): top.toneup.word.domain.study.RecentEvent =
    top.toneup.word.domain.study.RecentEvent(
        word = "",
        meaning = "",
        grade = reviewGradeFromId(grade) ?: ReviewGrade.FUZZY,
        answeredAt = answeredAt,
    )

// --- Exam ----------------------------------------------------------------------------------

fun ExamQuestionEntity.toDomain(): ExamQuestion = ExamQuestion(
    id = id,
    orderIndex = orderIndex,
    wordId = wordId,
    type = ExamQuestionType.fromId(type),
    prompt = prompt,
    promptPhonetic = promptPhonetic,
    options = JsonCodec.decodeStrings(options),
    correctOptionIndex = correctOptionIndex,
    correctAnswer = correctAnswer,
    givenAnswer = givenAnswer,
    isCorrect = isCorrect,
)

fun ExamQuestion.toEntity(examId: Long): ExamQuestionEntity = ExamQuestionEntity(
    examId = examId,
    orderIndex = orderIndex,
    wordId = wordId,
    type = type.id,
    prompt = prompt,
    promptPhonetic = promptPhonetic,
    options = JsonCodec.encodeStrings(options),
    correctOptionIndex = correctOptionIndex,
    correctAnswer = correctAnswer,
    givenAnswer = givenAnswer,
    isCorrect = isCorrect,
)

/** Serialises the per-type accuracy table into the exam row. */
fun List<TypeAccuracy>.toJson(): String = JsonCodec.json.encodeToString(
    kotlinx.serialization.builtins.ListSerializer(TypeAccuracyDto.serializer()),
    map { TypeAccuracyDto(it.type.id, it.correct, it.total) },
)

fun typeAccuracyFromJson(raw: String?): List<TypeAccuracy> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        JsonCodec.json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(TypeAccuracyDto.serializer()),
            raw,
        )
    }.getOrDefault(emptyList()).map { TypeAccuracy(ExamQuestionType.fromId(it.type), it.correct, it.total) }
}

@kotlinx.serialization.Serializable
private data class TypeAccuracyDto(val type: String, val correct: Int, val total: Int)

fun ExamSessionEntity.toScore(questions: List<ExamQuestion>): ExamScore? {
    val correct = correctCount ?: return null
    val total = questionCount
    return ExamScore(
        totalQuestions = total,
        correctCount = correct,
        wrongCount = total - correct,
        unansweredCount = questions.count { it.givenAnswer == null },
        score = score ?: 0,
        accuracyPercent = accuracyPercent ?: 0,
        durationMillis = timeSpentMs ?: 0L,
        perType = typeAccuracyFromJson(typeBreakdown),
        wrongWordIds = questions.filter { it.isCorrect == false }.map { it.wordId }.distinct(),
        weakTypes = typeAccuracyFromJson(typeBreakdown)
            .filter { it.percent < ExamScore.WEAK_TYPE_THRESHOLD_PERCENT }
            .map { it.type },
        results = emptyList(),
    )
}

// --- Stats ---------------------------------------------------------------------------------

fun DailyStudySummaryEntity.toInput(): DailySummaryInput = DailySummaryInput(
    dayKey = dayKey,
    studiedWords = studiedWords,
    newWords = newWords,
    reviewWords = reviewWords,
    masteredCount = masteredCount,
    fuzzyCount = fuzzyCount,
    unknownCount = unknownCount,
    reviewEvents = reviewEvents,
    durationMillis = studyDurationMs,
    sessionsCompleted = sessionsCompleted,
    examsCompleted = examsCompleted,
    examCorrect = examCorrect,
    examTotal = examTotal,
)

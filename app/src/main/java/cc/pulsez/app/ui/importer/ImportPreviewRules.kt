package cc.pulsez.app.ui.importer

import cc.pulsez.app.importer.deepseek.DeepSeekSessionPreview
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The visible date filter only changes which preview rows are shown; it never changes assignments. */
internal enum class ImportPreviewDateFilter {
    ALL,
    OLDER_THAN_ONE_YEAR,
    UNKNOWN,
}

/** SOURCE_ORDER preserves the parser's existing order until the user explicitly chooses a date order. */
internal enum class ImportPreviewDateSort {
    SOURCE_ORDER,
    LAST_MESSAGE_NEWEST_FIRST,
    LAST_MESSAGE_OLDEST_FIRST,
}

internal val DEFAULT_IMPORT_PREVIEW_DATE_FILTER = ImportPreviewDateFilter.ALL
internal val DEFAULT_IMPORT_PREVIEW_DATE_SORT = ImportPreviewDateSort.SOURCE_ORDER
internal const val IMPORT_PREVIEW_ACTIVE_DAYS = 7L
internal const val IMPORT_PREVIEW_FALLBACK_RECENT_LIMIT = 5

internal data class ImportPreviewBuckets(
    val expandedActive: List<DeepSeekSessionPreview>,
    val olderThanSevenDays: List<DeepSeekSessionPreview>,
    val toolNoise: List<DeepSeekSessionPreview>,
    val unknownOrDamaged: List<DeepSeekSessionPreview>,
    val usedRecentFallback: Boolean = false,
)

/**
 * Compares source message timestamps against the same local calendar time seven days earlier.
 * Equality stays active: only a last message strictly before the cutoff is older than seven days.
 */
internal fun sevenDaysBeforeLocalTime(now: Instant, zone: ZoneId): Instant =
    now.atZone(zone).minusDays(IMPORT_PREVIEW_ACTIVE_DAYS).toInstant()

internal fun isWithinLastSevenDays(lastMessageAt: Long?, now: Instant, zone: ZoneId): Boolean {
    if (lastMessageAt == null) return false
    return !Instant.ofEpochMilli(lastMessageAt).isBefore(sevenDaysBeforeLocalTime(now, zone))
}

internal fun bucketImportPreviews(
    previews: List<DeepSeekSessionPreview>,
    now: Instant,
    zone: ZoneId,
): ImportPreviewBuckets {
    val activeRecent = mutableListOf<DeepSeekSessionPreview>()
    val olderCandidates = mutableListOf<IndexedValue<DeepSeekSessionPreview>>()
    val toolNoise = mutableListOf<DeepSeekSessionPreview>()
    val unknownOrDamaged = mutableListOf<DeepSeekSessionPreview>()

    previews.forEachIndexed { index, preview ->
        when {
            preview.damageCode != null || preview.lastMessageAt == null -> unknownOrDamaged += preview
            preview.looksLikeToolNoise -> toolNoise += preview
            isWithinLastSevenDays(preview.lastMessageAt, now, zone) -> activeRecent += preview
            else -> olderCandidates += IndexedValue(index, preview)
        }
    }

    if (activeRecent.isNotEmpty() || olderCandidates.isEmpty()) {
        return ImportPreviewBuckets(
            expandedActive = activeRecent,
            olderThanSevenDays = olderCandidates.map { it.value },
            toolNoise = toolNoise,
            unknownOrDamaged = unknownOrDamaged,
            usedRecentFallback = false,
        )
    }

    val topRecentIndices = olderCandidates
        .sortedWith(
            compareByDescending<IndexedValue<DeepSeekSessionPreview>> { it.value.lastMessageAt ?: Long.MIN_VALUE }
                .thenBy { it.index },
        )
        .take(IMPORT_PREVIEW_FALLBACK_RECENT_LIMIT)
    val promotedIndexSet = topRecentIndices.mapTo(HashSet()) { it.index }
    val remainingOlder = olderCandidates.filter { it.index !in promotedIndexSet }.map { it.value }
    return ImportPreviewBuckets(
        expandedActive = topRecentIndices.map { it.value },
        olderThanSevenDays = remainingOlder,
        toolNoise = toolNoise,
        unknownOrDamaged = unknownOrDamaged,
        usedRecentFallback = true,
    )
}

/**
 * Compares source message timestamps against the same local calendar time one year earlier.
 * Equality is not old: only a last message strictly before the cutoff is more than one year old.
 *
 * 依据：Java SE 17 ZonedDateTime.minusYears API documents calendar-year adjustment, including moving
 * an invalid day to the previous valid day (for example, a leap-day result).
 * https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/ZonedDateTime.html#minusYears(long)
 */
internal fun oneYearBeforeLocalTime(now: Instant, zone: ZoneId): Instant =
    now.atZone(zone).minusYears(1).toInstant()

internal fun isOlderThanOneYear(lastMessageAt: Long?, now: Instant, zone: ZoneId): Boolean {
    if (lastMessageAt == null) return false
    return Instant.ofEpochMilli(lastMessageAt).isBefore(oneYearBeforeLocalTime(now, zone))
}

internal fun filterAndSortImportPreviews(
    previews: List<DeepSeekSessionPreview>,
    now: Instant,
    zone: ZoneId,
    filter: ImportPreviewDateFilter = DEFAULT_IMPORT_PREVIEW_DATE_FILTER,
    sort: ImportPreviewDateSort = DEFAULT_IMPORT_PREVIEW_DATE_SORT,
): List<DeepSeekSessionPreview> {
    val indexed = previews.withIndex().filter { (_, preview) ->
        when (filter) {
            ImportPreviewDateFilter.ALL -> true
            ImportPreviewDateFilter.OLDER_THAN_ONE_YEAR -> isOlderThanOneYear(preview.lastMessageAt, now, zone)
            ImportPreviewDateFilter.UNKNOWN -> preview.lastMessageAt == null
        }
    }
    val ordered = when (sort) {
        ImportPreviewDateSort.SOURCE_ORDER -> indexed
        ImportPreviewDateSort.LAST_MESSAGE_NEWEST_FIRST -> indexed.sortedWith { left, right ->
            comparePreviewDates(left.value.lastMessageAt, right.value.lastMessageAt, descending = true)
                .takeIf { it != 0 }
                ?: left.index.compareTo(right.index)
        }
        ImportPreviewDateSort.LAST_MESSAGE_OLDEST_FIRST -> indexed.sortedWith { left, right ->
            comparePreviewDates(left.value.lastMessageAt, right.value.lastMessageAt, descending = false)
                .takeIf { it != 0 }
                ?: left.index.compareTo(right.index)
        }
    }
    return ordered.map { it.value }
}

/** Unknown dates stay last in either date order; source order breaks ties. */
private fun comparePreviewDates(left: Long?, right: Long?, descending: Boolean): Int {
    if (left == null && right == null) return 0
    if (left == null) return 1
    if (right == null) return -1
    val chronological = left.compareTo(right)
    return if (descending) -chronological else chronological
}

internal fun assignablePreviewIds(previews: List<DeepSeekSessionPreview>): Set<String> =
    previews.groupBy { it.sourceSessionId }
        .filterValues { sameSourceRows -> sameSourceRows.size == 1 && sameSourceRows.single().damageCode == null }
        .keys

/** Returns the assignment map after changing only explicitly selected source IDs. */
internal fun assignmentsAfterBatchChange(
    current: Map<String, String?>,
    selectedSourceIds: Set<String>,
    groupId: String?,
): Map<String, String?> =
    if (selectedSourceIds.isEmpty()) current else current + selectedSourceIds.associateWith { groupId }

internal fun formatImportLastMessageDate(lastMessageAt: Long?, zone: ZoneId): String {
    if (lastMessageAt == null) return "日期未知"
    val formatter = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)
    return formatter.format(Instant.ofEpochMilli(lastMessageAt).atZone(zone))
}

internal fun formatImportPreviewCutoff(now: Instant, zone: ZoneId): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA)
    return formatter.format(now.atZone(zone).minusYears(1))
}

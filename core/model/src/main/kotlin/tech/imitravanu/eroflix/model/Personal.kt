package tech.imitravanu.eroflix.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** All positions/durations and wall-clock timestamps are milliseconds, explicitly named. */
data class PersonalState(val favorite: Boolean = false, val saved: Boolean = false,
    val hidden: Boolean = false, val hiddenAtEpochMs: Long? = null, val positionMs: Long = 0,
    val durationMs: Long? = null, val lastWatchedEpochMs: Long? = null)
data class LibraryItem(val title: TitleSummary, val personal: PersonalState)
enum class LibraryShelf { FAVORITES, SAVED, HISTORY, CONTINUE_WATCHING, HIDDEN }
data class PlaybackObservation(val title: TitleSummary, val sourceId: SourceId, val timelineId: String,
    val positionMs: Long, val durationMs: Long?, val atEpochMs: Long,
    val playedFromMs: Long? = null, val discontinuity: Boolean = false, val speed: Float = 1f) {
    init { require(speed.isFinite() && speed>0); require(positionMs >= 0); require(durationMs == null || durationMs >= 0); require(playedFromMs == null || playedFromMs >= 0) }
}
interface PersonalRepository {
    val revision: Flow<Long>
    fun observeState(titleId: TitleId): Flow<PersonalState>
    fun observeShelf(shelf: LibraryShelf): Flow<List<LibraryItem>>
    /** Bounded page; query is a literal, case-insensitive title substring. */
    fun observeShelfPage(shelf: LibraryShelf, query: String, offset: Int, limit: Int): Flow<List<LibraryItem>> =
        observeShelf(shelf).map { entries -> entries.filter { it.title.title.contains(query, ignoreCase=true) }.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 121)) }
    suspend fun setFavorite(title: TitleSummary, enabled: Boolean): Outcome<Unit>
    suspend fun setSaved(title: TitleSummary, enabled: Boolean): Outcome<Unit>
    suspend fun setHidden(title: TitleSummary, enabled: Boolean, atEpochMs: Long): Outcome<Unit>
    suspend fun unhideAll(): Outcome<Unit>
    suspend fun recordProgress(observation: PlaybackObservation): Outcome<Unit>
    suspend fun startWatchCycle(title: TitleSummary): Outcome<Unit> = Outcome.Failure(AppError(ErrorCode.UNSUPPORTED,"Replay is unavailable."))
    suspend fun setAutoHide(enabled: Boolean): Outcome<Unit> = Outcome.Failure(AppError(ErrorCode.UNSUPPORTED,"Auto-hide is unavailable."))
    fun observeAutoHide(): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)
}

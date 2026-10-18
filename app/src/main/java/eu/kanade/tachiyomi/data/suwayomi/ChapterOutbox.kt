// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
enum class ChapterIntentKind { PROGRESS, MARK_READ, MARK_UNREAD, BOOKMARK }

@Serializable
enum class ChapterIntentStatus { PENDING, SENDING, AMBIGUOUS, FAILED }

@Serializable
data class ChapterIntent(
    val id: String = UUID.randomUUID().toString(),
    val chapterId: Int,
    val kind: ChapterIntentKind,
    val patch: ChapterPatch,
    val readingSession: String? = null,
    val barrier: Long = 0,
    val status: ChapterIntentStatus = ChapterIntentStatus.PENDING,
)

@Serializable
internal data class OutboxState(
    val operations: List<ChapterIntent> = emptyList(),
    val barriers: Map<Int, Long> = emptyMap(),
)

data class ReadingTicket(val sessionId: String, val barriers: Map<Int, Long>)

/** Pure ordering policy. Persistence is synchronous and atomic before an enqueue is acknowledged. */
internal class ChapterOutbox(initial: String?, private val save: (String) -> Unit) {
    private var state = initial?.let { Json.decodeFromString<OutboxState>(it) } ?: OutboxState()

    init {
        // A process could die after the server committed but before receiving its response.
        state = state.copy(
            operations = state.operations.map {
                if (it.status == ChapterIntentStatus.SENDING) it.copy(status = ChapterIntentStatus.AMBIGUOUS) else it
            },
        )
        persist()
    }

    @Synchronized fun operations(): List<ChapterIntent> = state.operations.toList()

    @Synchronized fun ticket(chapters: List<Int>) = ReadingTicket(
        UUID.randomUUID().toString(),
        chapters.associateWith { state.barriers[it] ?: 0 },
    )

    @Synchronized
    fun enqueue(chapterId: Int, kind: ChapterIntentKind, patch: ChapterPatch, ticket: ReadingTicket? = null): Boolean {
        val barrier = state.barriers[chapterId] ?: 0
        if (kind == ChapterIntentKind.PROGRESS && (ticket == null || ticket.barriers[chapterId] != barrier)) return false
        val resets = kind == ChapterIntentKind.MARK_UNREAD || kind == ChapterIntentKind.MARK_READ
        val operations = state.operations.filterNot {
            it.chapterId == chapterId && (
                (resets && it.kind != ChapterIntentKind.BOOKMARK && it.status != ChapterIntentStatus.SENDING) ||
                    (kind == ChapterIntentKind.PROGRESS && it.kind == kind && it.readingSession == ticket?.sessionId && it.status == ChapterIntentStatus.PENDING) ||
                    (kind == ChapterIntentKind.BOOKMARK && it.kind == kind && it.status == ChapterIntentStatus.PENDING)
                )
        }
        state = state.copy(
            operations = operations + ChapterIntent(
                chapterId = chapterId,
                kind = kind,
                patch = patch,
                readingSession = ticket?.sessionId,
                barrier = barrier,
            ),
            barriers = if (resets) state.barriers + (chapterId to barrier + 1) else state.barriers,
        )
        persist()
        return true
    }

    @Synchronized fun setStatus(id: String, status: ChapterIntentStatus) {
        state = state.copy(operations = state.operations.map { if (it.id == id) it.copy(status = status) else it })
        persist()
    }

    @Synchronized fun acknowledge(id: String) {
        state = state.copy(operations = state.operations.filterNot { it.id == id })
        persist()
    }

    @Synchronized fun retry() {
        state = state.copy(operations = state.operations.map { it.copy(status = ChapterIntentStatus.PENDING) })
        persist()
    }

    private fun persist() = save(Json.encodeToString(state))
}
// KMK <--

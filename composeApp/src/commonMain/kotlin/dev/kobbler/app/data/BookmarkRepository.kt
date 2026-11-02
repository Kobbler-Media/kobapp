package dev.kobbler.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Bookmark(
    val id: String,
    val bookId: String,
    val label: String,
    val createdAt: Long,
    // Audiobook fields
    val fileId: String? = null,
    val positionSeconds: Double? = null,
    // Reader fields
    val chapterIndex: Int? = null,
    val blockIndex: Int? = null,
    val blockOffset: Int? = null,
)

object BookmarkRepository {
    private val json = Json { ignoreUnknownKeys = true }

    fun getBookmarks(bookId: String): List<Bookmark> {
        val raw = UserSettings.getString("bookmarks_$bookId") ?: return emptyList()
        return try {
            json.decodeFromString<List<Bookmark>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addBookmark(bookmark: Bookmark) {
        val existing = getBookmarks(bookmark.bookId).toMutableList()
        existing.add(0, bookmark)
        UserSettings.putString("bookmarks_${bookmark.bookId}", json.encodeToString(existing))
    }

    fun removeBookmark(bookId: String, bookmarkId: String) {
        val existing = getBookmarks(bookId).toMutableList()
        existing.removeAll { it.id == bookmarkId }
        UserSettings.putString("bookmarks_$bookId", json.encodeToString(existing))
    }

    fun generateId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..12).map { chars.random() }.joinToString("")
    }
}

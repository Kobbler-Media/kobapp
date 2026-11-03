package dev.kobbler.app.tv

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.media.tv.TvContract

class FakeTvProvider : ContentProvider() {
    val rows = linkedMapOf<Long, ContentValues>()
    private var nextId = 1L

    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val columns = projection ?: arrayOf(TvContract.WatchNextPrograms._ID)
        return MatrixCursor(columns).apply {
            rows.forEach { (id, values) ->
                addRow(columns.map { column ->
                    when (column) {
                        TvContract.WatchNextPrograms._ID -> id
                        // Apps cannot write this column; the real provider starts every row browsable.
                        TvContract.WatchNextPrograms.COLUMN_BROWSABLE -> values.getAsInteger(column) ?: 1
                        else -> values.get(column)
                    }
                })
            }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val id = nextId++
        rows[id] = ContentValues(values)
        return ContentUris.withAppendedId(TvContract.WatchNextPrograms.CONTENT_URI, id)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
        val row = rows[ContentUris.parseId(uri)] ?: return 0
        row.putAll(values)
        return 1
    }

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int =
        if (rows.remove(ContentUris.parseId(uri)) != null) 1 else 0
}

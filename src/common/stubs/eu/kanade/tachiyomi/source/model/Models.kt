package eu.kanade.tachiyomi.source.model

import android.net.Uri

// Stubs de compilación (extensions-lib). No se empaquetan en el APK.

interface SManga {
    var url: String
    var title: String
    var artist: String?
    var author: String?
    var description: String?
    var genre: String?
    var status: Int
    var thumbnail_url: String?
    var initialized: Boolean

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        fun create(): SManga = throw UnsupportedOperationException("stub")
    }
}

interface SChapter {
    var url: String
    var name: String
    var date_upload: Long
    var chapter_number: Float
    var scanlator: String?

    companion object {
        fun create(): SChapter = throw UnsupportedOperationException("stub")
    }
}

data class MangasPage(val mangas: List<SManga>, val hasNextPage: Boolean)

open class Page(
    val index: Int,
    val url: String = "",
    var imageUrl: String? = null,
    var uri: Uri? = null,
)

class FilterList

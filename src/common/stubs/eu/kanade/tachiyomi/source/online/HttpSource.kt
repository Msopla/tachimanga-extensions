package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable

/**
 * Stub de compilación (extensions-lib). No se empaqueta en el APK: la clase
 * real la aporta la app. Solo declara lo que usan las extensiones de este
 * repositorio, con las mismas firmas que la API real.
 */
abstract class HttpSource {
    val network: NetworkHelper get() = throw UnsupportedOperationException("stub")
    abstract val name: String
    abstract val lang: String
    abstract val supportsLatest: Boolean
    abstract val baseUrl: String
    open val id: Long get() = throw UnsupportedOperationException("stub")
    open val client: OkHttpClient get() = throw UnsupportedOperationException("stub")
    val headers: Headers get() = throw UnsupportedOperationException("stub")

    protected open fun headersBuilder(): Headers.Builder = throw UnsupportedOperationException("stub")

    open fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        throw UnsupportedOperationException("stub")

    open fun mangaDetailsRequest(manga: SManga): Request = throw UnsupportedOperationException("stub")
    protected open fun chapterListRequest(manga: SManga): Request = throw UnsupportedOperationException("stub")
    protected open fun pageListRequest(chapter: SChapter): Request = throw UnsupportedOperationException("stub")
    open fun getMangaUrl(manga: SManga): String = throw UnsupportedOperationException("stub")
    open fun getChapterUrl(chapter: SChapter): String = throw UnsupportedOperationException("stub")

    protected abstract fun popularMangaRequest(page: Int): Request
    protected abstract fun popularMangaParse(response: Response): MangasPage
    protected abstract fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request
    protected abstract fun searchMangaParse(response: Response): MangasPage
    protected abstract fun latestUpdatesRequest(page: Int): Request
    protected abstract fun latestUpdatesParse(response: Response): MangasPage
    protected abstract fun mangaDetailsParse(response: Response): SManga
    protected abstract fun chapterListParse(response: Response): List<SChapter>
    protected abstract fun pageListParse(response: Response): List<Page>
    protected abstract fun imageUrlParse(response: Response): String
}

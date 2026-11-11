package eu.kanade.tachiyomi.extension.es.manhwashot

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

class ManhwaShot : HttpSource() {

    override val name = "ManhwaShot"

    override val lang = "es"

    override val baseUrl = "https://manhwashot.lat"

    override val supportsLatest = false

    // Mismo cliente que la 1.4.1 (comparte cookies con el WebView de la app),
    // más el interceptor de páginas protegidas.
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor(ProtectedPages.Interceptor(baseUrl) { headers })
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ===== Catálogo =====

    override fun popularMangaRequest(page: Int): Request = get(catalogueUrl(page))

    override fun popularMangaParse(response: Response): MangasPage = parseCatalogue(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        get(catalogueUrl(page, query))

    override fun searchMangaParse(response: Response): MangasPage = parseCatalogue(response)

    override fun latestUpdatesRequest(page: Int): Request =
        throw UnsupportedOperationException("No hay un listado independiente de novedades.")

    override fun latestUpdatesParse(response: Response): MangasPage =
        throw UnsupportedOperationException("No utilizado.")

    // ===== Serie y capítulos =====

    override fun mangaDetailsParse(response: Response): SManga =
        toManga(ManhwaShotParser.details(document(response))).apply { initialized = true }

    override fun chapterListParse(response: Response): List<SChapter> {
        val html = response.body?.string() ?: throw IllegalStateException("Respuesta vacía.")
        val doc = Jsoup.parse(html, response.request.url.toString())
        // Fecha exacta de subida (mapa "dates" de la ficha); si falta, la aproximada de "hace X días".
        val dates = runCatching { ReaderData.chapterDates(html) }.getOrDefault(emptyMap())
        return ManhwaShotParser.chapters(doc).map { chapter ->
            SChapter.create().apply {
                url = chapter.url
                name = chapter.name
                chapter_number = chapter.number
                date_upload = dates[chapter.url.trimEnd('/').substringAfterLast("capitulo-")] ?: chapter.date
            }
        }
    }

    // ===== Páginas =====

    override fun pageListParse(response: Response): List<Page> {
        val location = response.request.url.toString()
        val html = response.body?.string() ?: throw IllegalStateException("Respuesta vacía.")
        val doc = Jsoup.parse(html, location)

        // 1) Capítulos libres: <img> normales, igual que en la 1.4.1.
        val images = ManhwaShotParser.pages(doc)
        if (images.isNotEmpty()) return images.mapIndexed { i, url -> Page(i, "", url) }

        val data = ReaderData.extract(html)
            ?: throw IllegalArgumentException(NO_IMAGES)

        // 2) Lista de imágenes dentro de los datos del lector.
        val listed = data.images.filter { it.isNotBlank() }.map { ProtectedPages.absolute(baseUrl, it) }
        if (listed.isNotEmpty()) return listed.mapIndexed { i, url -> Page(i, "", url) }

        // 3) Páginas protegidas (usuario con sesión).
        if ((data.pgUrls.isNotEmpty() || data.pgCount > 0) && data.postId > 0 && data.num.isNotEmpty()) {
            val pages = if (data.pgUrls.isNotEmpty()) {
                ProtectedPages.Urls(data.pgUrls.map { ProtectedPages.absolute(baseUrl, it) }, data.pgKey)
            } else {
                ProtectedPages.parseUrls(
                    client.newCall(ProtectedPages.urlsRequest(baseUrl, headers, data.postId, data.num)).execute(),
                    baseUrl,
                )
            }
            return pages.urls.mapIndexed { i, url ->
                Page(i, "", ProtectedPages.encode(url, ProtectedPages.Ref(data.postId, data.num, i, pages.key)))
            }
        }

        // 4) Bloqueado y sin sesión.
        if (data.locked) throw IllegalArgumentException(ProtectedPages.LOGIN_REQUIRED)

        throw IllegalArgumentException(NO_IMAGES)
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Las páginas contienen la URL de imagen.")

    // ===== Utilidades =====

    private fun catalogueUrl(page: Int, query: String = ""): String =
        "$baseUrl/explorar/" +
            (if (page > 1) "page/$page/" else "") +
            (if (query.isNotBlank()) "?q=" + URLEncoder.encode(query, "UTF-8") else "")

    private fun get(url: String): Request = Request.Builder().url(url).headers(headers).get().build()

    private fun document(response: Response): Document {
        val body = response.body?.string() ?: throw IllegalStateException("Respuesta vacía.")
        return Jsoup.parse(body, response.request.url.toString())
    }

    private fun parseCatalogue(response: Response): MangasPage {
        val catalogue = ManhwaShotParser.catalogue(document(response))
        return MangasPage(catalogue.mangas.map { toManga(it) }, catalogue.hasNext)
    }

    private fun toManga(item: ManhwaShotParser.Manga): SManga = SManga.create().apply {
        url = item.url
        title = item.title
        thumbnail_url = item.cover
        description = item.description
        genre = item.genre
        status = item.status
    }

    private companion object {
        const val NO_IMAGES = "No se encontraron imágenes; revisa este capítulo en el navegador integrado."
    }
}

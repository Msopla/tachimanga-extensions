package eu.kanade.tachiyomi.extension.es.lectorxd

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
import java.util.ArrayDeque

/**
 * LectorXD (lectorxd.com). Reconstruido a partir del APK 1.4.3 con el mismo
 * comportamiento: catálogo HTML (/catalogo), ficha y capítulos desde la página
 * de la serie (datos de Astro o, si faltan, enlaces del HTML con paginación).
 */
class LectorXD : HttpSource() {

    override val name = "LectorXD"

    override val lang = "es"

    override val baseUrl = "https://lectorxd.com"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ===== Catálogo =====

    override fun popularMangaRequest(page: Int): Request = get("$baseUrl/catalogo?orderBy=views&page=$page")

    override fun popularMangaParse(response: Response): MangasPage = parseCatalogue(response)

    override fun latestUpdatesRequest(page: Int): Request = get("$baseUrl/catalogo?orderBy=recent&page=$page")

    override fun latestUpdatesParse(response: Response): MangasPage = parseCatalogue(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        get("$baseUrl/catalogo?search=" + URLEncoder.encode(query, "UTF-8") + "&page=$page")

    override fun searchMangaParse(response: Response): MangasPage = parseCatalogue(response)

    // ===== Serie =====

    override fun mangaDetailsParse(response: Response): SManga =
        toManga(LectorXDParser.details(document(response))).apply { initialized = true }

    /**
     * Lista de capítulos. Si los datos de Astro no traen todos los capítulos
     * anunciados ("N capítulos"), se recorren las páginas de la lista y se exige
     * llegar al total para no dejar la biblioteca incompleta.
     */
    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = document(response)
        val found = LinkedHashMap<String, LectorXDParser.Chapter>()
        LectorXDParser.chapters(doc).associateByTo(found) { it.url }

        val expected = LectorXDParser.expectedChapterCount(doc)
        val records = ChapterData.records(doc)
        val complete = records != null && (expected == null || records.size >= expected)

        if (!complete) {
            val seen = mutableSetOf(doc.location())
            val queue = ArrayDeque(LectorXDParser.chapterPagination(doc))
            while (queue.isNotEmpty()) {
                val url = queue.removeFirst()
                if (!seen.add(url)) continue

                val page = client.newCall(get(url)).execute().use { res ->
                    require(res.isSuccessful) { "No se pudo cargar toda la lista de capítulos (HTTP ${res.code})." }
                    document(res)
                }
                val before = found.size
                LectorXDParser.chapters(page).forEach { found[it.url] = it }
                if (expected != null && found.size >= expected) break
                require(found.size > before) {
                    "LectorXD repitió una página; no se pudo completar la lista de capítulos."
                }
                LectorXDParser.chapterPagination(page).filterNot { it in seen }.forEach { queue.addLast(it) }
            }
            require(expected == null || found.size >= expected) {
                "LectorXD anuncia $expected capítulos pero solo se pudieron obtener ${found.size}. " +
                    "La lista está incompleta; actualiza la extensión."
            }
        }

        return found.values.sortedByDescending { it.number }.map { chapter ->
            SChapter.create().apply {
                url = chapter.url
                name = chapter.name
                chapter_number = chapter.number
                date_upload = chapter.date
            }
        }
    }

    // ===== Páginas =====

    override fun pageListParse(response: Response): List<Page> =
        LectorXDParser.pages(document(response)).mapIndexed { i, url -> Page(i, "", url) }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Las páginas contienen sus URL de imagen.")

    // ===== Utilidades =====

    private fun get(url: String): Request = Request.Builder().url(url).headers(headers).get().build()

    private fun document(response: Response): Document {
        val body = response.body?.string() ?: throw IllegalStateException("LectorXD devolvió una respuesta vacía.")
        return Jsoup.parse(body, response.request.url.toString())
    }

    private fun parseCatalogue(response: Response): MangasPage {
        val catalogue = LectorXDParser.catalogue(document(response))
        return MangasPage(catalogue.mangas.map { toManga(it) }, catalogue.hasNext)
    }

    private fun toManga(item: LectorXDParser.Manga): SManga = SManga.create().apply {
        url = item.url
        title = item.title
        thumbnail_url = item.cover
        description = item.description
        genre = item.genre
        status = item.status
    }
}

package eu.kanade.tachiyomi.extension.es.lectorxd

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/** Lectura del HTML de LectorXD (reconstruido del APK 1.4.3; mismos selectores y mensajes). */
internal object LectorXDParser {

    data class Manga(
        val url: String,
        val title: String,
        val cover: String,
        val description: String = "",
        val genre: String = "",
        val status: Int = 0,
    )

    data class Chapter(val url: String, val name: String, val number: Float, val date: Long)

    data class Catalogue(val mangas: List<Manga>, val hasNext: Boolean)

    private val mangaPath = Regex("^/(?:manga|manhwa|manhua|one_shot|doujinshi)/[^/]+/?$")
    private val chapterPath = Regex("^/(?:manga|manhwa|manhua|one_shot|doujinshi)/[^/]+/leer/[^/]+/?$")
    private val NUMBER = Regex("""\d+(?:\.\d+)?""")

    // ===== Catálogo =====

    fun catalogue(doc: Document): Catalogue {
        checkProtection(doc)
        val mangas = doc.select("a[href]:has(img)").mapNotNull { link ->
            val path = path(link.absUrl("href")) ?: return@mapNotNull null
            if (!mangaPath.matches(path)) return@mapNotNull null
            val img = link.selectFirst("img") ?: return@mapNotNull null
            val title = link.selectFirst("h2, h3, h4, h5")?.text()?.takeIf { it.isNotBlank() }
                ?: img.attr("alt").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            Manga(path.trimEnd('/'), title, imageUrl(img))
        }.distinctBy { it.url }

        val current = pageNumber(doc.location())
        val hasNext = doc.select("a[href]").any { link ->
            val url = link.absUrl("href")
            path(url) == "/catalogo" && pageNumber(url) > current
        }
        return Catalogue(mangas, hasNext)
    }

    // ===== Ficha =====

    fun details(doc: Document): Manga {
        checkProtection(doc)
        val url = (doc.selectFirst("link[rel=canonical]")?.absUrl("href") ?: doc.location())
            .let { path(it) }
            ?.trimEnd('/')
            ?: throw IllegalStateException("La ficha no contiene una dirección válida.")
        val title = doc.selectFirst("h1")?.text()?.trim() ?: ""
        require(title.isNotBlank()) { "LectorXD cambió la ficha o requiere abrir la fuente en el navegador integrado." }

        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.select("img").firstOrNull { it.attr("alt") == title }?.let { imageUrl(it) }
            ?: ""
        val description = doc.select("h2, h3").firstOrNull { it.text().contains("Sinopsis", ignoreCase = true) }
            ?.parent()
            ?.select("p")
            ?.joinToString("\n\n") { it.text() }
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
            ?: ""
        val genre = doc.select("a[href*=/catalogo?]").map { it.text() }.filter { it.isNotBlank() }.distinct()
            .joinToString(", ")
        val body = doc.body().text().lowercase(Locale.ROOT)
        val status = when {
            body.contains("en emisión") || body.contains("en emision") -> 1
            body.contains("completado") || body.contains("finalizado") -> 2
            else -> 0
        }
        return Manga(url, title, cover, description, genre, status)
    }

    // ===== Capítulos =====

    /** Total anunciado en la ficha ("123 capítulos"), si aparece. */
    fun expectedChapterCount(doc: Document): Int? =
        doc.select("h2, h3").mapNotNull { heading ->
            Regex("(?i)^(\\d+)\\s+capítulos?$").find(heading.text().trim())?.groupValues?.get(1)?.toIntOrNull()
        }.firstOrNull()

    /** Enlaces a otras páginas de la lista de capítulos de la misma serie. */
    fun chapterPagination(doc: Document): List<String> {
        val current = path(doc.location())
        return doc.select("a[href]").map { it.absUrl("href") }.filter { url ->
            path(url) == current && Regex("[?&](?:page|chapterPage|chaptersPage)=\\d+").containsMatchIn(url)
        }.distinct()
    }

    fun chapters(doc: Document): List<Chapter> {
        checkProtection(doc)
        val base = (doc.selectFirst("link[rel=canonical]")?.absUrl("href") ?: doc.location())
            .let { path(it) }
            ?.trimEnd('/')
            ?: throw IllegalStateException("Dirección de manga inválida.")
        val found = LinkedHashMap<String, Chapter>()

        // 1) Datos de Astro: lista completa con fechas.
        val records = ChapterData.records(doc)
        if (records != null) {
            for (record in records) {
                val number = ChapterData.number(record) ?: continue
                val group = record["groupId"]?.toString()?.let { "?g=" + URLEncoder.encode(it, "UTF-8") } ?: ""
                val url = "$base/leer/$number$group"
                val date = listOf("publishAt", "publishedAt", "createdAt", "updatedAt")
                    .mapNotNull { record[it]?.toString() }
                    .map { parseDate(it) }
                    .firstOrNull { it > 0L } ?: 0L
                found[url] = Chapter(url, "Capítulo $number", number.toFloat(), date)
            }
            val expected = expectedChapterCount(doc)
            if (expected == null || records.size >= expected) {
                return found.values.sortedByDescending { it.number }
            }
            found.clear()
        }

        // 2) Enlaces /leer/ en el HTML (href, data-*, onclick).
        for (el in doc.select("[href], [data-href], [data-url], [data-chapter-url], [onclick]")) {
            val raw = listOf("href", "data-href", "data-url", "data-chapter-url").map { el.attr(it) }
                .firstOrNull { it.isNotBlank() }
                ?: Regex("[\"']([^\"']*/leer/[^\"']+)[\"']").find(el.attr("onclick"))?.groupValues?.get(1)
                ?: continue
            val path = path(resolve(doc.location(), raw))
            if (path != null && chapterPath.matches(path) && path.startsWith("$base/leer/")) {
                addChapter(found, path, el)
            }
        }

        // 3) Elementos con número de capítulo.
        val numbered = "[data-chapter-number], [data-chapter-num], [data-number], [data-chapter], " +
            "[data-chapter-id], .chapter-row, .chapter-item"
        for (el in doc.select(numbered)) {
            val number = listOf("data-chapter-number", "data-chapter-num", "data-number", "data-chapter")
                .map { el.attr(it) }
                .firstOrNull { NUMBER.matches(it) }
                ?: Regex("(?i)^Cap(?:ítulo|itulo|\\.)?\\s*(\\d+(?:\\.\\d+)?)\\b").find(el.text())?.groupValues?.get(1)
                ?: continue
            addChapter(found, "$base/leer/$number", el)
        }

        // 4) Textos sueltos "Cap. 12".
        for (el in doc.getElementsMatchingOwnText("(?i)^Cap\\.\\s*\\d+(?:\\.\\d+)?$")) {
            val number = NUMBER.find(el.ownText())?.value ?: continue
            addChapter(found, "$base/leer/$number", el)
        }

        require(found.isNotEmpty()) {
            "No se pudo obtener la lista de capítulos. Abre LectorXD en el navegador integrado y vuelve a intentar. " +
                "Si persiste, esta versión necesita ajustar el lector de capítulos."
        }
        return found.values.sortedByDescending { it.number }
    }

    private fun addChapter(found: MutableMap<String, Chapter>, url: String, el: Element) {
        val clean = url.trimEnd('/')
        val numberText = clean.substringAfterLast('/')
        val number = numberText.toFloatOrNull() ?: -1f
        val container = el.closest("[data-chapter-id]") ?: el
        val dateText = container.selectFirst("time[datetime]")?.attr("datetime") ?: container.attr("data-date")
        val date = parseDate(dateText)
        val existing = found[clean]
        if (existing == null || (existing.date == 0L && date != 0L)) {
            found[clean] = Chapter(clean, "Capítulo $numberText", number, date)
        }
    }

    // ===== Páginas =====

    fun pages(doc: Document): List<String> {
        checkProtection(doc)
        val pages = doc.select("img[alt^=Página], img[alt^=Pagina], img[alt^=Page], #reader-container img, img[data-page]")
            .map { imageUrl(it) }
            .filter { it.startsWith("https://") || it.startsWith("http://") }
            .distinct()
        require(pages.isNotEmpty()) {
            "No se encontraron imágenes del capítulo. Abre la fuente en el navegador integrado; el lector puede haber cambiado."
        }
        return pages
    }

    // ===== Utilidades =====

    private fun checkProtection(doc: Document) {
        val blocked = doc.title().contains("Just a moment", ignoreCase = true) ||
            doc.selectFirst("#challenge-form") != null
        require(!blocked) {
            "Cloudflare requiere abrir LectorXD en el navegador integrado de Tachimanga y completar su verificación."
        }
    }

    private fun imageUrl(el: Element): String {
        for (attr in listOf("data-src", "data-original", "data-lazy-src", "src")) {
            val value = el.attr(attr)
            if (value.isNotBlank() && !value.startsWith("data:")) return el.absUrl(attr)
        }
        return ""
    }

    private fun pageNumber(url: String): Int =
        Regex("[?&]page=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun parseDate(value: String): Long {
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")) {
            val time = runCatching {
                SimpleDateFormat(pattern, Locale.ROOT).apply { isLenient = false }.parse(value)?.time
            }.getOrNull()
            if (time != null) return time
        }
        return 0L
    }

    private fun path(url: String): String? = runCatching { URI(url).path }.getOrNull()

    private fun resolve(base: String, value: String): String =
        runCatching { URI(base).resolve(value).toString() }.getOrDefault("")
}

package eu.kanade.tachiyomi.extension.es.manhwashot

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Lectura del HTML de ManhwaShot. Reconstruido a partir de la versión 1.4.1
 * (mismos selectores y mensajes); la única diferencia es que [pages] ya no
 * lanza error cuando no hay <img>, porque ahora existe un segundo camino
 * (páginas protegidas, ver [ReaderData]).
 */
object ManhwaShotParser {

    data class Manga(
        val url: String,
        val title: String,
        val cover: String,
        val description: String? = null,
        val genre: String? = null,
        val status: Int = 0,
    )

    /** [date]: milisegundos Unix (0 si no se conoce). */
    data class Chapter(val url: String, val name: String, val number: Float, val date: Long = 0L)

    data class Catalogue(val mangas: List<Manga>, val hasNext: Boolean)

    fun checkProtection(doc: Document) {
        val blocked = doc.title().contains("Just a moment", ignoreCase = true) ||
            doc.selectFirst("#challenge-form") != null
        require(!blocked) {
            "Abre ManhwaShot en el navegador integrado de Tachimanga y completa la verificación de Cloudflare."
        }
    }

    fun catalogue(doc: Document): Catalogue {
        checkProtection(doc)
        val mangas = doc.select(".s-card").mapNotNull { card ->
            val link = card.selectFirst("a.s-card-imglink[href]") ?: return@mapNotNull null
            val path = path(link.absUrl("href")) ?: return@mapNotNull null
            val title = card.selectFirst(".s-card-title")?.text()?.trim() ?: ""
            val img = card.selectFirst("img") ?: return@mapNotNull null
            if (!Regex("/manga/[^/]+/?").matches(path) || title.isBlank()) return@mapNotNull null
            Manga(path.trimEnd('/') + '/', title, imageUrl(img))
        }.distinctBy { it.url }
        val hasNext = doc.select(".pager a.pager-btn").any { it.text().contains("Siguiente", ignoreCase = true) }
        return Catalogue(mangas, hasNext)
    }

    fun details(doc: Document): Manga {
        checkProtection(doc)
        val title = doc.selectFirst("h1.series-title")?.text()?.trim() ?: ""
        require(title.isNotBlank()) { "No se pudo obtener la ficha de ManhwaShot." }
        val url = (doc.selectFirst("link[rel=canonical]")?.absUrl("href") ?: doc.location())
            .let { path(it) }
            ?.trimEnd('/')
            ?.let { "$it/" }
            ?: throw IllegalStateException("Dirección de manga inválida.")
        val cover = doc.selectFirst(".series-cover img")?.let { imageUrl(it) } ?: ""
        val descriptionNodes = doc.select(".series-desc:not([style]):not(.series-intro-auto)")
            .ifEmpty { doc.select(".series-desc:not(.series-intro-auto)") }
        val description = descriptionNodes.joinToString("\n\n") { it.text() }
        val genre = doc.select(".series-genres .genre-tag").map { it.text() }.distinct().joinToString(", ")
        val status = when {
            doc.selectFirst(".badge-ongoing") != null -> 1
            doc.selectFirst(".badge-completed, .badge-complete") != null -> 2
            else -> 0
        }
        return Manga(url, title, cover, description, genre, status)
    }

    fun chapters(doc: Document): List<Chapter> {
        checkProtection(doc)
        val base = (doc.selectFirst("link[rel=canonical]")?.absUrl("href") ?: doc.location())
            .let { path(it) }
            ?.trimEnd('/')
            ?: throw IllegalStateException("Dirección de manga inválida.")
        val chapters = doc.select(".chapters-grid a.ch-row[href]").mapNotNull { row ->
            val path = path(row.absUrl("href")) ?: return@mapNotNull null
            if (!path.startsWith("$base/capitulo-")) return@mapNotNull null
            val num = path.trimEnd('/').substringAfterLast("capitulo-")
            val number = num.toFloatOrNull() ?: return@mapNotNull null
            val date = row.select("span").lastOrNull()?.let { relativeDate(it.text()) } ?: 0L
            Chapter(path, "Capítulo $num", number, date)
        }.distinctBy { it.url }.sortedByDescending { it.number }

        val announced = doc.selectFirst(".ch-filter-count")?.text()?.toIntOrNull()
        require(chapters.isNotEmpty()) {
            "No se encontraron capítulos; abre la fuente en el navegador integrado."
        }
        require(announced == null || chapters.size == announced) {
            "ManhwaShot anuncia $announced capítulos pero solo se obtuvieron ${chapters.size}; " +
                "la estructura del sitio puede haber cambiado."
        }
        return chapters
    }

    /** Imágenes normales (<img>) del lector. Lista vacía si no hay. */
    fun pages(doc: Document): List<String> {
        checkProtection(doc)
        return doc.select(".reader-main img[alt*=Página]")
            .map { imageUrl(it) }
            .filter { it.startsWith("https://") || it.startsWith("http://") }
            .distinct()
    }

    private val RELATIVE = Regex("""hace\s+(un|una|\d+)\s+(segundo|minuto|hora|d[ií]a|semana|mes|año|ano)""", RegexOption.IGNORE_CASE)

    /** "hace 9 días" → fecha aproximada (respaldo si la página no trae las fechas exactas). */
    fun relativeDate(text: String, now: Long = System.currentTimeMillis()): Long {
        val match = RELATIVE.find(text) ?: return 0L
        val amount = match.groupValues[1].toLongOrNull() ?: 1L
        val unit = when (match.groupValues[2].lowercase().replace('í', 'i')) {
            "segundo" -> 1_000L
            "minuto" -> 60_000L
            "hora" -> 3_600_000L
            "dia" -> 86_400_000L
            "semana" -> 7 * 86_400_000L
            "mes" -> 30 * 86_400_000L
            else -> 365 * 86_400_000L
        }
        return now - amount * unit
    }

    private fun imageUrl(img: Element): String {
        for (attr in listOf("data-src", "data-original", "src")) {
            val value = img.attr(attr)
            if (value.isNotBlank() && !value.startsWith("data:")) return img.absUrl(attr)
        }
        return ""
    }

    private fun path(url: String): String? = runCatching { URI(url).path }.getOrNull()
}

package eu.kanade.tachiyomi.extension.es.lectorxd

import com.eclipsesource.json.Json
import com.eclipsesource.json.JsonValue
import org.jsoup.nodes.Document

/**
 * Lista de capítulos incrustada por Astro en `<astro-island props="…">`.
 *
 * Astro serializa cada valor como `[tipo, valor]`: 0 = valor simple,
 * 1 = arreglo (de valores serializados), 3 = fecha (texto).
 */
internal object ChapterData {

    private val keys = listOf("chapters", "initialChapters", "allChapters", "chapterList")
    private val NUMBER = Regex("""\d+(?:\.\d+)?""")

    /** Registros de capítulos, o null si no hay una lista completa y válida. */
    fun records(doc: Document): List<Map<String, Any?>>? {
        for (island in doc.select("astro-island[props]")) {
            val props = runCatching { unpackObject(Json.parse(island.attr("props"))) }.getOrNull() ?: continue
            for (key in keys) {
                val list = props[key] as? List<*> ?: continue
                val valid = list.mapNotNull { item ->
                    @Suppress("UNCHECKED_CAST")
                    (item as? Map<String, Any?>)?.takeIf { number(it) != null }
                }
                if (valid.isNotEmpty() && valid.size == list.size) return valid
            }
        }
        return null
    }

    /** Número del capítulo ("12" o "12.5") según los nombres de campo habituales. */
    fun number(record: Map<String, Any?>): String? =
        listOf("chapter", "number", "chapterNumber", "chapter_number")
            .mapNotNull { record[it]?.toString() }
            .firstOrNull { NUMBER.matches(it) }

    private fun decode(value: JsonValue): Any? {
        if (value.isNull) return null
        if (value.isObject) return unpackObject(value)
        if (!value.isArray) return scalar(value)
        val array = value.asArray()
        if (array.size() == 2 && array.get(0).isNumber) {
            return when (array.get(0).asInt()) {
                0 -> scalar(array.get(1))
                1 -> array.get(1).asArray().map { decode(it) }
                3 -> array.get(1).asString()
                else -> null
            }
        }
        return array.map { decode(it) }
    }

    private fun scalar(value: JsonValue): Any? = when {
        value.isNull -> null
        value.isString -> value.asString()
        value.isNumber -> value.toString()
        value.isBoolean -> value.asBoolean()
        value.isObject -> unpackObject(value)
        value.isArray -> value.asArray().map { decode(it) }
        else -> null
    }

    private fun unpackObject(value: JsonValue): Map<String, Any?> {
        val obj = value.asObject()
        return obj.names().associateWith { decode(obj.get(it)) }
    }
}

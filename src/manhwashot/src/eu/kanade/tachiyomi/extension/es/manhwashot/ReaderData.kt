package eu.kanade.tachiyomi.extension.es.manhwashot

/**
 * Datos del lector que ManhwaShot incrusta en la página del capítulo
 * (carga de Next.js: `self.__next_f.push([1,"..."])`).
 *
 * Ejemplo del objeto que se busca:
 * {"title":"…","slug":"…","num":933,"prev":932,"next":null,"images":[],
 *  "locked":true,"postId":2564,"windowN":0,"pgCount":0,"pgUrls":[],"pgKey":"","pdf":false}
 *
 * - `images`: páginas normales (capítulos libres).
 * - `pgCount`/`pgUrls`/`pgKey`: páginas protegidas para usuarios con sesión.
 *   Si `pgUrls` viene vacío, se piden a `POST /api/pg/urls/`.
 * - `locked` sin `pgCount`: el capítulo pide iniciar sesión.
 */
data class ReaderData(
    val postId: Long,
    val num: String,
    val locked: Boolean,
    val images: List<String>,
    val pgCount: Int,
    val pgUrls: List<String>,
    val pgKey: String,
) {
    companion object {
        // Sin expresiones regulares para las cadenas largas: en Java, un patrón
        // como ((?:[^"\\]|\\.)*) desborda la pila con las páginas reales.
        private const val PUSH = "self.__next_f.push([1,\""
        private val NUMBER = Regex("""^-?\d+(\.\d+)?$""")

        fun extract(html: String): ReaderData? {
            val payload = buildString {
                var at = html.indexOf(PUSH)
                while (at >= 0) {
                    val from = at + PUSH.length
                    val end = closingQuote(html, from) ?: break
                    append(unescape(html.substring(from, end)))
                    at = html.indexOf(PUSH, end)
                }
            }
            val obj = readerObject(payload) ?: readerObject(html.replace("\\\"", "\"")) ?: return null

            val postId = Regex(""""postId":(\d+)""").find(obj)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val num = Regex(""""num":([0-9.]+)""").find(obj)?.groupValues?.get(1)?.takeIf { NUMBER.matches(it) } ?: ""
            val locked = Regex(""""locked":(true|false)""").find(obj)?.groupValues?.get(1) == "true"
            val pgCount = Regex(""""pgCount":(\d+)""").find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val pgKey = Regex(""""pgKey":"([0-9a-fA-F]*)"""").find(obj)?.groupValues?.get(1) ?: ""
            return ReaderData(
                postId = postId,
                num = num,
                locked = locked,
                images = stringArray(obj, "images"),
                pgCount = pgCount,
                pgUrls = stringArray(obj, "pgUrls"),
                pgKey = pgKey,
            )
        }

        /** Localiza el objeto JSON del lector (el que contiene "pgKey"). */
        private fun readerObject(text: String): String? {
            val anchor = text.indexOf("\"pgKey\":")
            if (anchor < 0) return null
            val start = text.lastIndexOf("{\"title\":", anchor).takeIf { it >= 0 } ?: return null
            var depth = 0
            var inString = false
            var i = start
            while (i < text.length) {
                val c = text[i]
                if (inString) {
                    if (c == '\\') {
                        i++
                    } else if (c == '"') {
                        inString = false
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) return text.substring(start, i + 1)
                        }
                    }
                }
                i++
            }
            return null
        }

        fun stringArray(obj: String, key: String): List<String> {
            val match = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\\[").find(obj) ?: return emptyList()
            val from = match.range.last + 1
            val end = closingBracket(obj, from) ?: return emptyList()
            val items = mutableListOf<String>()
            var i = obj.indexOf('"', from)
            while (i in from until end) {
                val close = closingQuote(obj, i + 1) ?: break
                items += unescape(obj.substring(i + 1, close))
                i = obj.indexOf('"', close + 1)
            }
            return items
        }

        /** Índice de la comilla que cierra una cadena que empieza en [from]. */
        private fun closingQuote(text: String, from: Int): Int? {
            var i = from
            while (i < text.length) {
                when (text[i]) {
                    '\\' -> i++
                    '"' -> return i
                }
                i++
            }
            return null
        }

        private fun closingBracket(text: String, from: Int): Int? {
            var inString = false
            var i = from
            while (i < text.length) {
                val c = text[i]
                if (inString) {
                    if (c == '\\') i++ else if (c == '"') inString = false
                } else if (c == '"') {
                    inString = true
                } else if (c == ']') {
                    return i
                }
                i++
            }
            return null
        }

        /** Decodifica una cadena con escapes JSON/JS (\" \\ \n \uXXXX …). */
        fun unescape(s: String): String {
            if (s.indexOf('\\') < 0) return s
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c != '\\' || i + 1 >= s.length) {
                    out.append(c)
                    i++
                    continue
                }
                when (val n = s[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'u' -> {
                        val hex = s.substring(i + 2, minOf(i + 6, s.length))
                        val code = hex.toIntOrNull(16)
                        if (hex.length == 4 && code != null) {
                            out.append(code.toChar())
                            i += 4
                        } else {
                            out.append('u')
                        }
                    }
                    else -> out.append(n)
                }
                i += 2
            }
            return out.toString()
        }
    }
}

package eu.kanade.tachiyomi.extension.es.manhwashot

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/**
 * Páginas protegidas de ManhwaShot (capítulos "gratis solo para usuarios").
 *
 * Reproduce lo que hace el lector de la web cuando el usuario tiene sesión:
 *  1. `POST /api/pg/urls/` con {postId, chapter} → {urls:[…], key:"hex"}.
 *     Necesita la cookie de sesión, que la app comparte con su WebView.
 *  2. Cada URL es temporal (parámetro `e` = caducidad en segundos). Si caduca
 *     o devuelve 403, se piden URL nuevas.
 *  3. Si la respuesta trae `x-pg-enc: 1`, el contenido viene con XOR usando la
 *     clave: se deshace igual que en la web.
 *  4. Si el sitio limita la velocidad (429 o 403 con `x-pg-window`), se avisa
 *     de la página leída con `POST /api/pg/seen/` y se espera antes de reintentar.
 *
 * Los datos necesarios viajan en el fragmento (#…) de la URL de la imagen, que
 * OkHttp no envía al servidor.
 */
object ProtectedPages {

    private const val MARK = "msk="
    private val JSON: MediaType = "application/json".toMediaType()

    const val LOGIN_REQUIRED =
        "Capítulo bloqueado: es gratis, pero ManhwaShot pide iniciar sesión. " +
            "Pulsa WebView (icono del globo), inicia sesión en la web y vuelve a cargar el capítulo."

    const val SESSION_EXPIRED =
        "ManhwaShot no reconoce tu sesión. Abre WebView, vuelve a iniciar sesión y recarga el capítulo."

    data class Ref(val postId: Long, val chapter: String, val index: Int, val key: String)

    class Urls(val urls: List<String>, val key: String)

    fun encode(url: String, ref: Ref): String =
        url.substringBefore('#') + "#" + MARK + ref.key +
            "&pid=" + ref.postId + "&ch=" + ref.chapter + "&i=" + ref.index

    fun decode(fragment: String?): Ref? {
        if (fragment == null || !fragment.startsWith(MARK)) return null
        val parts = fragment.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        return Ref(
            postId = parts["pid"]?.toLongOrNull() ?: return null,
            chapter = parts["ch"] ?: return null,
            index = parts["i"]?.toIntOrNull() ?: return null,
            key = parts["msk"] ?: "",
        )
    }

    fun urlsRequest(baseUrl: String, headers: Headers, postId: Long, chapter: String): Request =
        Request.Builder()
            .url("$baseUrl/api/pg/urls/")
            .headers(headers)
            .header("Accept", "application/json")
            .post("{\"postId\":$postId,\"chapter\":$chapter}".toRequestBody(JSON))
            .build()

    private fun seenRequest(baseUrl: String, headers: Headers, ref: Ref, idx: Int): Request =
        Request.Builder()
            .url("$baseUrl/api/pg/seen/")
            .headers(headers)
            .post("{\"postId\":${ref.postId},\"chapter\":${ref.chapter},\"idx\":$idx}".toRequestBody(JSON))
            .build()

    /** Lee la respuesta de /api/pg/urls/. Lanza un mensaje claro si no hay sesión. */
    fun parseUrls(response: Response, baseUrl: String): Urls {
        response.use {
            if (it.code == 401 || it.code == 403) throw IOException(SESSION_EXPIRED)
            if (!it.isSuccessful) throw IOException("ManhwaShot respondió ${it.code} al pedir las páginas.")
            val body = it.body?.string().orEmpty()
            val urls = ReaderData.stringArray(body, "urls").map { u -> absolute(baseUrl, u) }
            val key = Regex(""""key"\s*:\s*"([0-9a-fA-F]*)"""").find(body)?.groupValues?.get(1) ?: ""
            if (urls.isEmpty()) throw IOException(SESSION_EXPIRED)
            return Urls(urls, key)
        }
    }

    fun absolute(baseUrl: String, url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> baseUrl + url
        else -> "$baseUrl/$url"
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    private fun expiresSoon(url: HttpUrl): Boolean {
        val e = url.queryParameter("e")?.toLongOrNull() ?: return false
        return e > 0 && e * 1000 < System.currentTimeMillis() + 30_000
    }

    class Interceptor(
        private val baseUrl: String,
        private val headers: () -> Headers,
    ) : okhttp3.Interceptor {

        private val lock = Any()
        private var lastRequest = 0L

        /** Espaciado mínimo entre páginas protegidas, para no saturar el sitio. */
        private fun throttle() {
            synchronized(lock) {
                val wait = lastRequest + MIN_GAP_MS - System.currentTimeMillis()
                if (wait > 0) Thread.sleep(wait)
                lastRequest = System.currentTimeMillis()
            }
        }

        override fun intercept(chain: okhttp3.Interceptor.Chain): Response {
            val original = chain.request()
            val ref = decode(original.url.fragment) ?: return chain.proceed(original)

            var key = ref.key
            var url = original.url.newBuilder().fragment(null).build()

            fun refresh(): Boolean {
                val fresh = runCatching {
                    parseUrls(chain.proceed(urlsRequest(baseUrl, headers(), ref.postId, ref.chapter)), baseUrl)
                }.getOrElse { error ->
                    if (error.message == SESSION_EXPIRED) throw IOException(SESSION_EXPIRED)
                    return false
                }
                val next = fresh.urls.getOrNull(ref.index) ?: return false
                url = next.toHttpUrlOrNull() ?: return false
                key = fresh.key
                return true
            }

            if (expiresSoon(url)) refresh()

            var refreshed = false
            var attempt = 0
            while (true) {
                throttle()
                val response = chain.proceed(original.newBuilder().url(url).build())
                val code = response.code
                val windowLimited = code == 429 || (code == 403 && response.header("x-pg-window", null) != null)

                if (windowLimited && attempt < MAX_WAITS) {
                    response.close()
                    attempt++
                    runCatching {
                        chain.proceed(seenRequest(baseUrl, headers(), ref, maxOf(0, ref.index - 1))).close()
                    }
                    Thread.sleep(1_500L * attempt)
                    continue
                }
                if (windowLimited) {
                    response.close()
                    throw IOException(
                        "Demasiadas páginas seguidas: espera unos segundos y vuelve a cargar (página ${ref.index + 1}).",
                    )
                }
                if ((code == 403 || code == 401 || code == 410) && !refreshed) {
                    response.close()
                    refreshed = true
                    if (refresh()) continue
                    throw IOException(SESSION_EXPIRED)
                }
                if (!response.isSuccessful) return response

                if (response.header("x-pg-enc", null) != "1") return response

                if (key.length < 32) {
                    response.close()
                    throw IOException("ManhwaShot no envió la clave de la página ${ref.index + 1}; recarga el capítulo.")
                }
                val body = response.body ?: return response
                val type = body.contentType() ?: "image/jpeg".toMediaType()
                val bytes = body.bytes()
                val k = hexToBytes(key)
                for (i in bytes.indices) {
                    bytes[i] = (bytes[i].toInt() xor k[i % k.size].toInt()).toByte()
                }
                return response.newBuilder()
                    .removeHeader("x-pg-enc")
                    .removeHeader("Content-Length")
                    .body(bytes.toResponseBody(type))
                    .build()
            }
        }

        private companion object {
            const val MIN_GAP_MS = 350L
            const val MAX_WAITS = 4
        }
    }
}

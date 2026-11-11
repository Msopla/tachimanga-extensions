package eu.kanade.tachiyomi.network

import okhttp3.OkHttpClient

/** Stub de compilación (extensions-lib). No se empaqueta en el APK. */
class NetworkHelper {
    val client: OkHttpClient get() = throw UnsupportedOperationException("stub")
    val cloudflareClient: OkHttpClient get() = throw UnsupportedOperationException("stub")
}

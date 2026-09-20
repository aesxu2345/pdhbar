package com.pdyy.pdhbar.runtime

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Inherited through withContext(Dispatchers.IO); unrelated scopes must explicitly inject it. */
internal enum class HttpTransportLane : CoroutineContext.Element {
    Interactive, Background;

    override val key: CoroutineContext.Key<HttpTransportLane> get() = Key
    companion object Key : CoroutineContext.Key<HttpTransportLane>
}

// 32 configured refill workers PLUS the concurrent hourly range query. This is a transport
// ceiling, not a change to the engine's default minWorkers=4 or explicit minimum in 1..32.
internal const val BACKGROUND_HTTP_WORKERS = 33
private val backgroundHttpRequests by lazy {
    BlockingHttpRequests(
        workerCount = BACKGROUND_HTTP_WORKERS,
        capacity = BACKGROUND_HTTP_WORKERS,
        name = "http-background"
    )
}
private val interactiveHttpRequests by lazy {
    BlockingHttpRequests(workerCount = 2, capacity = 4, name = "http-interactive")
}

// Separate admission, I/O, cleanup AND timers: stuck background disconnect/DNS cannot consume
// interactive slots. Neither pool grows/replaces a stuck worker beyond its fixed ceiling.
internal suspend fun httpRequestsForCurrentLane(): BlockingHttpRequests =
    when (coroutineContext[HttpTransportLane] ?: HttpTransportLane.Interactive) {
        HttpTransportLane.Interactive -> interactiveHttpRequests
        HttpTransportLane.Background -> backgroundHttpRequests
    }

private fun Long.socketTimeout(): Int = coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

private val urlHttpClient by lazy {
    OkHttpClient.Builder().retryOnConnectionFailure(false).build()
}

/** Abort budget includes queueing/DNS/connect/write/reads; uninterruptible cleanup may outlast it. */
internal fun httpRequestTimeoutMs(connectTimeoutMs: Long, readTimeoutMs: Long): Long =
    connectTimeoutMs.socketTimeout().toLong() + readTimeoutMs.socketTimeout()

internal suspend fun executeBoundedUrlRequest(
    method: String,
    requestUrl: String,
    headers: Map<String, String>,
    body: String?,
    connectTimeoutMs: Long,
    readTimeoutMs: Long,
    requestTimeoutMs: Long = httpRequestTimeoutMs(connectTimeoutMs, readTimeoutMs)
): HttpWireResponse = httpRequestsForCurrentLane().execute(requestTimeoutMs) { resources ->
    resources.checkActive()
    val client = urlHttpClient.newBuilder()
        .connectTimeout(connectTimeoutMs.socketTimeout().toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs.socketTimeout().toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(readTimeoutMs.socketTimeout().toLong(), TimeUnit.MILLISECONDS)
        .build()
    val request = Request.Builder().url(requestUrl).apply {
        headers.forEach { (name, value) -> header(name, value) }
        method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType()))
    }.build()
    val call = client.newCall(request)
    // Call.cancel closes the underlying socket without waiting for a body-read monitor.
    // HttpURLConnection.disconnect can block behind that monitor on slow streaming bodies.
    resources.own(call::cancel)
    call.execute().use { response ->
        resources.checkActive()
        val text = response.body?.byteStream()?.let(::readBoundedHttpBody).orEmpty()
        HttpWireResponse(response.code, text)
    }
}

internal suspend fun executeBoundedRawHttp11Post(
    host: String,
    port: Int,
    path: String,
    headers: Map<String, String>,
    body: String,
    connectTimeoutMs: Long,
    readTimeoutMs: Long,
    requestTimeoutMs: Long = httpRequestTimeoutMs(connectTimeoutMs, readTimeoutMs)
): HttpWireResponse = httpRequestsForCurrentLane().execute(requestTimeoutMs) { resources ->
    val socket = Socket()
    resources.own { socket.close() }
    val cleanPath = if (path.startsWith("/")) path else "/$path"
    val bodyBytes = body.toByteArray(Charsets.UTF_8)
    resources.checkActive()
    socket.connect(InetSocketAddress(host, port), connectTimeoutMs.socketTimeout())
    socket.soTimeout = readTimeoutMs.socketTimeout()
    resources.checkActive()
    val output = socket.getOutputStream()
    val writer = output.bufferedWriter(Charsets.ISO_8859_1)
    writer.append("POST $cleanPath HTTP/1.1\r\n")
    writer.append("Host: $host:$port\r\n")
    writer.append("Content-Type: application/json; charset=utf-8\r\n")
    writer.append("Accept: application/json\r\n")
    writer.append("Connection: close\r\n")
    headers.forEach { (key, value) -> writer.append("$key: $value\r\n") }
    writer.append("Content-Length: ${bodyBytes.size}\r\n")
    writer.append("\r\n")
    writer.flush()
    resources.checkActive()
    output.write(bodyBytes)
    output.flush()
    readBoundedHttpResponse(socket.getInputStream())
}

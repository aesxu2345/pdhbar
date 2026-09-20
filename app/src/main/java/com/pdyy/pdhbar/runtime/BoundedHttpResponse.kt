package com.pdyy.pdhbar.runtime

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

internal const val MAX_HTTP_BODY_BYTES = 4 * 1024 * 1024
internal const val MAX_HTTP_HEADER_BYTES = 64 * 1024

internal data class HttpWireResponse(val statusCode: Int, val body: String)

/** Count actual bytes, not characters or the (possibly absent/incorrect) Content-Length. */
internal fun readBoundedHttpBody(
    source: InputStream,
    maxBodyBytes: Int = MAX_HTTP_BODY_BYTES
): String {
    require(maxBodyBytes > 0)
    val body = ByteArrayOutputStream()
    val scratch = ByteArray(8192)
    while (true) {
        // At the limit, only probe one extra byte; never allocate from a remote length.
        val remaining = maxBodyBytes - body.size()
        val count = source.read(scratch, 0, minOf(scratch.size.toLong(), remaining.toLong() + 1).toInt())
        if (count < 0) break
        if (count > remaining) throw IOException("HTTP body limit exceeded")
        body.write(scratch, 0, count)
    }
    return body.toString(Charsets.UTF_8.name())
}

/** Parse framing in bytes, before UTF-8 decoding. Limits apply to every input path. */
internal fun readBoundedHttpResponse(
    source: InputStream,
    maxBodyBytes: Int = MAX_HTTP_BODY_BYTES,
    maxHeaderBytes: Int = MAX_HTTP_HEADER_BYTES
): HttpWireResponse {
    require(maxBodyBytes > 0 && maxHeaderBytes > 0)
    val input = source.buffered()
    var metadataBytes = 0
    fun line(): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) throw EOFException("Truncated HTTP metadata")
            if (++metadataBytes > maxHeaderBytes) throw IOException("HTTP metadata limit exceeded")
            if (value == 10) {
                val data = bytes.toByteArray()
                if (data.isEmpty() || data.last() != 13.toByte()) throw IOException("Invalid HTTP line ending")
                return String(data, 0, data.size - 1, Charsets.ISO_8859_1)
            }
            bytes.write(value)
        }
    }
    val status = line().split(' ').getOrNull(1)?.toIntOrNull()
        ?: throw IOException("Invalid HTTP status")
    val headers = mutableMapOf<String, String>()
    while (true) {
        val line = line()
        if (line.isEmpty()) break
        val colon = line.indexOf(':')
        if (colon <= 0) throw IOException("Invalid HTTP header")
        val name = line.substring(0, colon).trim().lowercase(java.util.Locale.ROOT)
        val value = line.substring(colon + 1).trim()
        if (headers.put(name, value) != null && name in setOf("content-length", "transfer-encoding")) {
            throw IOException("Ambiguous HTTP framing")
        }
    }
    val body = ByteArrayOutputStream()
    val scratch = ByteArray(8192)
    fun copyExactly(size: Long) {
        if (size < 0 || size > maxBodyBytes - body.size()) throw IOException("HTTP body limit exceeded")
        var remaining = size.toInt()
        while (remaining > 0) {
            val read = input.read(scratch, 0, minOf(scratch.size, remaining))
            if (read < 0) throw EOFException("Truncated HTTP body")
            body.write(scratch, 0, read)
            remaining -= read
        }
    }
    val encoding = headers["transfer-encoding"]
    when {
        encoding != null -> {
            if (!encoding.equals("chunked", ignoreCase = true)) throw IOException("Unsupported transfer encoding")
            while (true) {
                val size = line().substringBefore(';').trim().toLongOrNull(16)
                    ?: throw IOException("Invalid chunk size")
                if (size == 0L) {
                    while (line().isNotEmpty()) { /* bounded trailers */ }
                    break
                }
                copyExactly(size)
                if (line().isNotEmpty()) throw IOException("Invalid chunk terminator")
            }
        }
        headers.containsKey("content-length") -> {
            val size = headers.getValue("content-length").toLongOrNull()
                ?: throw IOException("Invalid content length")
            copyExactly(size)
        }
        else -> {
            return HttpWireResponse(status, readBoundedHttpBody(input, maxBodyBytes))
        }
    }
    return HttpWireResponse(status, body.toString(Charsets.UTF_8.name()))
}

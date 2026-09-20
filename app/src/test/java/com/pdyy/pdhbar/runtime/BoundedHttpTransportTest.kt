package com.pdyy.pdhbar.runtime

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Loopback only; no Android framework, emulator, device or external service required. */
class BoundedHttpTransportTest {
    private companion object {
        val peers = ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4),
            ThreadFactory { task -> Thread(task, "http-test-peer").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy()
        )
    }

    private class Peer(handler: (Socket) -> Unit) : Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 5_000
        }
        val port: Int = listener.localPort
        @Volatile private var accepted: Socket? = null
        val finished = peers.submit {
            listener.accept().use { socket ->
                accepted = socket
                socket.soTimeout = 5_000
                handler(socket)
            }
        }

        override fun close() {
            listener.close()
            accepted?.close()
            finished.get(6, TimeUnit.SECONDS)
        }
    }

    private fun readRequest(socket: Socket): Pair<String, ByteArray> {
        val input = socket.getInputStream()
        val header = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf(13, 10, 13, 10)
        while (matched < end.size) {
            val next = input.read()
            check(next >= 0 && header.size() < MAX_HTTP_HEADER_BYTES)
            header.write(next)
            matched = if (next == end[matched].toInt()) matched + 1 else if (next == 13) 1 else 0
        }
        val text = header.toString(Charsets.ISO_8859_1.name())
        val length = Regex("(?im)^Content-Length: *(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            check(count > 0)
            offset += count
        }
        return text to body
    }

    private fun Socket.send(text: String) {
        getOutputStream().write(text.toByteArray(Charsets.UTF_8))
        getOutputStream().flush()
    }

    private suspend fun request(peer: Peer, raw: Boolean, timeout: Long = 3_000): HttpWireResponse =
        if (raw) executeBoundedRawHttp11Post(
            "127.0.0.1", peer.port, "/test", emptyMap(), "{}", 2_000, 4_000, timeout
        ) else executeBoundedUrlRequest(
            "GET", "http://127.0.0.1:${peer.port}/test", emptyMap(), null, 2_000, 4_000, timeout
        )

    private suspend fun CountDownLatch.awaitSuspending() {
        withTimeout(5_000) { while (count > 0) delay(5) }
    }

    @Test fun rawPostRetainsHttp11HeadersUtf8LengthAndNoUserAgent() = runBlocking {
        Peer { socket ->
            val (headers, body) = readRequest(socket)
            assertEquals(
                "POST /test HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:${socket.localPort}\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Accept: application/json\r\nConnection: close\r\n" +
                    "X-Test: kept\r\nContent-Length: 6\r\n\r\n",
                headers
            )
            assertEquals("早餐", String(body, Charsets.UTF_8))
            assertFalse(headers.contains("User-Agent", ignoreCase = true))
            socket.send("HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\n早餐")
        }.use { peer ->
            val result = executeBoundedRawHttp11Post(
                "127.0.0.1", peer.port, "test", mapOf("X-Test" to "kept"), "早餐", 2_000, 4_000
            )
            assertEquals(HttpWireResponse(200, "早餐"), result)
        }
    }

    @Test fun getSuccessAndErrorStreamsRejectCumulativeChunkedOversize() = runBlocking {
        for (status in listOf(200, 400, 500)) {
            Peer { socket ->
                readRequest(socket)
                try {
                    socket.send("HTTP/1.1 $status Test\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
                    val chunk = "x".repeat(8192)
                    repeat(MAX_HTTP_BODY_BYTES / 8192 + 1) {
                        socket.send("2000\r\n$chunk\r\n")
                    }
                    socket.send("0\r\n\r\n")
                } catch (_: IOException) {
                    // Client is expected to close without draining an oversized response.
                }
            }.use { peer ->
                val error = runCatching { request(peer, raw = false, timeout = 10_000) }.exceptionOrNull()
                assertTrue("status=$status: $error", error is IOException)
                assertEquals("HTTP body limit exceeded", error?.message)
            }
        }
    }

    @Test fun getSuccessAndErrorStreamsAcceptExactUtf8ByteLimitWithoutLength() = runBlocking {
        for (status in listOf(200, 400)) {
            // 4 MiB is not divisible by three: finish the multi-byte body with ASCII.
            val body = "早".repeat(MAX_HTTP_BODY_BYTES / 3) + "x"
            Peer { socket ->
                readRequest(socket)
                socket.send("HTTP/1.1 $status Test\r\nConnection: close\r\n\r\n$body")
            }.use { peer ->
                assertEquals(HttpWireResponse(status, body), request(peer, raw = false, timeout = 10_000))
            }
        }
    }

    @Test fun rawAndUrlUnframedOversizeAreRejectedOnTheWire() = runBlocking {
        for (raw in listOf(true, false)) for (status in listOf(200, 500)) {
            Peer { socket ->
                readRequest(socket)
                try {
                    socket.send("HTTP/1.1 $status Test\r\nConnection: close\r\n\r\n")
                    val chunk = "x".repeat(8192)
                    repeat(MAX_HTTP_BODY_BYTES / 8192 + 1) { socket.send(chunk) }
                } catch (_: IOException) {
                    // Expected early close on the first excess byte.
                }
            }.use { peer ->
                val error = runCatching { request(peer, raw, 10_000) }.exceptionOrNull()
                assertTrue(error is IOException)
                assertEquals("HTTP body limit exceeded", error?.message)
            }
        }
    }

    @Test fun slowDripCannotExtendTotalDeadlineForRawOrUrlSuccessAndError() = runBlocking {
        // Warm class/TLS-provider initialization separately. A cold 500ms budget may validly
        // expire before connect; that does not exercise a streaming-body cancellation.
        Peer { socket ->
            readRequest(socket)
            socket.send("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}")
        }.use { peer -> request(peer, raw = false, timeout = 10_000) }
        for (raw in listOf(true, false)) for (status in listOf(200, 500)) {
            val clientClosed = CountDownLatch(1)
            Peer { socket ->
                readRequest(socket)
                socket.send("HTTP/1.1 $status Test\r\nContent-Length: 10000\r\nConnection: close\r\n\r\n")
                socket.soTimeout = 25
                try {
                    // Keep every read below readTimeout; the full-request budget must win.
                    while (true) {
                        socket.send("x")
                        try {
                            if (socket.getInputStream().read() == -1) break
                        } catch (_: SocketTimeoutException) {
                            // The read timeout also paces the peer without a timer thread.
                        }
                    }
                } catch (_: IOException) {
                    // EOF or reset are both valid evidence of actual transport closure.
                } finally {
                    clientClosed.countDown()
                }
            }.use { peer ->
                val started = System.nanoTime()
                val error = runCatching { withTimeout(3_000) { request(peer, raw, 500) } }.exceptionOrNull()
                assertTrue("raw=$raw status=$status: $error", error is SocketTimeoutException)
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_500)
                clientClosed.awaitSuspending()
            }
        }
    }

    @Test fun cancellationClosesPeerAndPropagatesCancellationWithoutRetry() = runBlocking {
        for (raw in listOf(true, false)) for (sendPartialBody in listOf(false, true)) {
            val received = CountDownLatch(1)
            val closed = CountDownLatch(1)
            Peer { socket ->
                readRequest(socket)
                if (sendPartialBody) {
                    socket.send("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\nx")
                }
                received.countDown()
                try {
                    assertEquals(-1, socket.getInputStream().read())
                } catch (error: IOException) {
                    if (error is SocketTimeoutException) throw error
                }
                closed.countDown()
            }.use { peer ->
                var attempts = 0
                val call = async {
                    repeat(5) {
                        attempts++
                        try {
                            request(peer, raw, 20_000)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: IOException) {
                            // Mirrors production retry exception ordering.
                        }
                    }
                }
                received.awaitSuspending()
                withTimeout(1_000) { call.cancelAndJoin() }
                closed.awaitSuspending()
                assertEquals(1, attempts)
                assertTrue(call.isCancelled)
            }
        }
    }

    @Test fun rawWriteToNonReadingPeerIsCoveredByDeadlineAndCancellation() = runBlocking {
        for (cancel in listOf(false, true)) {
            val accepted = CountDownLatch(1)
            val release = CountDownLatch(1)
            Peer { socket ->
                socket.receiveBufferSize = 1024
                accepted.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }.use { peer ->
                try {
                    val call = async {
                        runCatching {
                            executeBoundedRawHttp11Post(
                                "127.0.0.1", peer.port, "/test", emptyMap(), "x".repeat(16 * 1024 * 1024),
                                2_000, 4_000, if (cancel) 20_000 else 500
                            )
                        }.exceptionOrNull()
                    }
                    accepted.awaitSuspending()
                    if (cancel) {
                        withTimeout(1_000) { call.cancelAndJoin() }
                        assertTrue(call.isCancelled)
                    } else {
                        assertTrue(withTimeout(3_000) { call.await() } is SocketTimeoutException)
                    }
                } finally {
                    release.countDown()
                }
            }
        }
    }

    @Test fun urlPostStillWritesBodyAndCustomHeaders() = runBlocking {
        Peer { socket ->
            val (headers, body) = readRequest(socket)
            assertTrue(headers.startsWith("POST /test HTTP/1.1\r\n"))
            assertTrue(headers.contains("X-Test: kept\r\n"))
            assertEquals("早餐", String(body, Charsets.UTF_8))
            socket.send("HTTP/1.1 400 Bad Request\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}")
        }.use { peer ->
            assertEquals(HttpWireResponse(400, "{}"), executeBoundedUrlRequest(
                "POST", "http://127.0.0.1:${peer.port}/test", mapOf("X-Test" to "kept"), "早餐", 2_000, 4_000
            ))
        }
    }

    @Test fun invalidLocalTlsIsNotAccepted() = runBlocking {
        Peer { socket ->
            // A plaintext peer must not be accepted as HTTPS; no trust-all verifier/factory.
            socket.send("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}")
        }.use { peer ->
            val error = runCatching {
                executeBoundedUrlRequest(
                    "GET", "https://127.0.0.1:${peer.port}/test", emptyMap(), null, 2_000, 4_000
                )
            }.exceptionOrNull()
            assertTrue(error is IOException)
            assertFalse(error is SocketTimeoutException)
        }
    }
}

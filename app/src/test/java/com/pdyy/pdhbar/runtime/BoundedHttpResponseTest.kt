package com.pdyy.pdhbar.runtime

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedHttpResponseTest {
    private fun parse(raw: String, limit: Int = 32) =
        readBoundedHttpResponse(raw.byteInputStream(), maxBodyBytes = limit)

    @Test fun chunkSizesAreBytesNotCharacters() {
        val result = parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n6\r\n早餐\r\n0\r\n\r\n")
        assertEquals("早餐", result.body)
        assertEquals(200, result.statusCode)
    }

    @Test fun contentLengthAtLimitAccepted() {
        assertEquals("早餐", parse("HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\n早餐", 6).body)
    }

    @Test fun declaredOversizeRejectedBeforeReadingBody() {
        assertThrows(IOException::class.java) { parse("HTTP/1.1 200 OK\r\nContent-Length: 999999999999\r\n\r\n") }
    }

    @Test fun unframedBodyIsBounded() {
        assertThrows(IOException::class.java) { parse("HTTP/1.1 200 OK\r\n\r\n12345", 4) }
    }

    @Test fun chunkedAggregateIsBounded() {
        assertThrows(IOException::class.java) {
            parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n3\r\ndef\r\n0\r\n\r\n", 5)
        }
    }

    @Test fun truncatedBodyRejected() {
        assertThrows(IOException::class.java) { parse("HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nabc") }
    }

    @Test fun unterminatedHeaderIsBounded() {
        assertThrows(IOException::class.java) {
            readBoundedHttpResponse("HTTP/1.1 200 OK\r\nX-Test: ${"a".repeat(100)}".byteInputStream(), maxHeaderBytes = 32)
        }
    }
}

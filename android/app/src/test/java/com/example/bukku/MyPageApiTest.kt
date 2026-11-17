package com.example.bukku

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MyPageApiTest {
    @Test fun getProfileUsesExactPathBearerAndNoUserIdOrBody() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var method = ""; var token = ""; var body = ""; var query: String? = null
        server.createContext("/api/v1/me") { request ->
            method = request.requestMethod; token = request.requestHeaders.getFirst("Authorization")
            query = request.requestURI.query; body = request.requestBody.bufferedReader().readText()
            val raw = """{"name":"행운님","email":"em@naver.com","birthDate":"2000-01-01","calendarType":"LUNAR","isLeapMonth":true,"birthTime":null,"gender":"FEMALE"}""".toByteArray()
            request.responseHeaders.set("Content-Type", "application/json")
            request.sendResponseHeaders(200, raw.size.toLong()); request.responseBody.use { it.write(raw) }
        }
        server.start()
        try {
            val profile = RemoteProfileApi(ApiClient("http://127.0.0.1:${server.address.port}")).profile("access-A")
            assertEquals("GET", method); assertEquals("Bearer access-A", token); assertEquals("", body); assertNull(query)
            assertEquals("행운님", profile.name); assertEquals("em@naver.com", profile.email)
            assertEquals("음력 · 윤달", profile.calendarLabel); assertEquals("모름", profile.timeLabel)
        } finally { server.stop(0) }
    }

    @Test fun logoutPostsWithoutBodyAndAcceptsEmpty204() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var method = ""; var token = ""; var body = ""; var query: String? = null
        server.createContext("/api/v1/auth/logout") { request ->
            method = request.requestMethod; token = request.requestHeaders.getFirst("Authorization")
            body = request.requestBody.bufferedReader().readText(); query = request.requestURI.query
            request.sendResponseHeaders(204, -1); request.close()
        }
        server.start()
        try {
            RemoteProfileApi(ApiClient("http://127.0.0.1:${server.address.port}")).logout("access-A")
            assertEquals("POST", method); assertEquals("Bearer access-A", token); assertEquals("", body); assertNull(query)
        } finally { server.stop(0) }
    }

    @Test fun logoutRejectsAuthenticationServerErrorsAndUnexpectedSuccessStatus() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var status = 401
        server.createContext("/api/v1/auth/logout") { request ->
            val raw = """{"detail":"실패"}""".toByteArray()
            request.sendResponseHeaders(status, raw.size.toLong()); request.responseBody.use { it.write(raw) }
        }
        server.start()
        try {
            val api = RemoteProfileApi(ApiClient("http://127.0.0.1:${server.address.port}"))
            listOf(401 to 401, 500 to 500, 200 to 502, 202 to 502).forEach { (response, expected) ->
                status = response
                try { api.logout("access-A"); fail("Must not treat HTTP $status as completed logout") }
                catch (error: ApiFailure) { assertEquals(expected, error.status) }
            }
        } finally { server.stop(0) }
    }
}

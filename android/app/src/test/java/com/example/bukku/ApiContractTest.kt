package com.example.bukku

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApiContractTest {
    @Test fun signupPayloadMatchesBackendIncludingOptionalNulls() {
        val data = SignupForm(" USER@example.com ", "password1", "password1", " 한길 ", "2000-01-02", gender = "MALE").json()
        assertEquals("user@example.com", data.getString("email"))
        assertEquals("한길", data.getString("name"))
        assertEquals("SOLAR", data.getString("calendar_type"))
        assertTrue(data.isNull("birth_time")); assertTrue(data.isNull("is_leap_month"))
        val lunar = SignupForm(lunar = true, leap = false, time = "13:30").json()
        assertEquals("LUNAR", lunar.getString("calendar_type"))
        assertFalse(lunar.getBoolean("is_leap_month")); assertEquals("13:30", lunar.getString("birth_time"))
    }
    @Test fun refreshRequestAndBearerHeaderFollowContract() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var seenMethod = ""; var seenToken = ""; var seenBody = ""
        server.createContext("/auth/refresh") { request ->
            seenMethod = request.requestMethod
            seenToken = request.requestHeaders.getFirst("Authorization")
            seenBody = request.requestBody.bufferedReader().readText()
            val response = "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\"}".toByteArray()
            request.sendResponseHeaders(200, response.size.toLong()); request.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val result = ApiClient("http://127.0.0.1:${server.address.port}").request("/auth/refresh", JSONObject().put("refresh_token", "old-refresh"), "old-access")
            assertEquals("POST", seenMethod); assertEquals("Bearer old-access", seenToken)
            assertEquals("old-refresh", JSONObject(seenBody).getString("refresh_token"))
            assertEquals("new-access", result.getString("access_token")); assertEquals("new-refresh", result.getString("refresh_token"))
        } finally { server.stop(0) }
    }
    @Test fun duplicateEmailRemainsAnErrorInsteadOfFakeSuccess() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/auth/signup") { request ->
            val response = "{\"detail\":\"이미 가입된 이메일입니다.\"}".toByteArray(Charsets.UTF_8)
            request.sendResponseHeaders(409, response.size.toLong()); request.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            try { ApiClient("http://127.0.0.1:${server.address.port}").request("/auth/signup", JSONObject()); fail("Must reject duplicate email") }
            catch (error: ApiFailure) { assertEquals(409, error.status); assertEquals("이미 가입된 이메일입니다.", error.message) }
        } finally { server.stop(0) }
    }
}

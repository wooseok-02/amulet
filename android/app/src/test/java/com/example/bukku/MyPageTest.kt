package com.example.bukku

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MyPageTest {
    private val a = UserProfile("사용자A", "a@example.com", "2000-01-02", "SOLAR", false, null, "FEMALE")
    private val b = a.copy(name = "사용자B", email = "b@example.com", calendarType = "LUNAR", isLeapMonth = true, birthTime = "09:05", gender = "MALE")
    private fun json() = JSONObject("""{"name":"사용자A","email":"a@example.com","birthDate":"2000-01-02","calendarType":"SOLAR","isLeapMonth":false,"birthTime":null,"gender":"FEMALE"}""")
    private class FakeApi : ProfileApi {
        var get: suspend (String) -> UserProfile = { error("Unexpected profile request") }
        var post: suspend (String) -> Unit = { error("Unexpected logout request") }
        override suspend fun profile(token: String) = get(token)
        override suspend fun logout(token: String) = post(token)
    }

    @Test fun calendarTimeAndGenderMatchContractWithoutFallbackValues() {
        val solar = UserProfile.parse(json())
        assertEquals(a, solar)
        assertEquals("양력", solar.calendarLabel); assertEquals("모름", solar.timeLabel); assertEquals("여성", solar.genderLabel)
        val lunar = UserProfile.parse(json().put("calendarType", "LUNAR").put("gender", "MALE").put("birthTime", "09:05"))
        assertEquals("음력 · 평달", lunar.calendarLabel); assertEquals("남성", lunar.genderLabel); assertEquals("09:05", lunar.timeLabel)
        assertEquals("음력 · 윤달", UserProfile.parse(json().put("calendarType", "LUNAR").put("isLeapMonth", true)).calendarLabel)
    }

    @Test fun malformedRequiredFieldsAreErrorsInsteadOfMadeUpProfileData() {
        val invalid = listOf(
            json().put("birthDate", JSONObject.NULL), json().put("gender", JSONObject.NULL),
            json().put("birthDate", "2000-02-31"), json().put("birthTime", "25:00"),
            json().put("isLeapMonth", "false"), json().put("calendarType", "UNKNOWN"),
            json().put("isLeapMonth", true), json().put("gender", "OTHER"),
            json().put("name", 123), json().put("email", ""), json().apply { remove("birthTime") },
        )
        invalid.forEach { value ->
            try { UserProfile.parse(value); fail("Invalid profile must be rejected") }
            catch (error: ApiFailure) { assertEquals(502, error.status) }
        }
    }

    @Test fun refetchHidesOldProfileAndServerFailureCanBeRetried() = runBlocking {
        val api = FakeApi().apply { get = { a } }
        val controller = MyPageController(CoroutineScope(coroutineContext + Dispatchers.Unconfined), api, { "A" }, {}, {})
        controller.load(); assertEquals(a, controller.state.profile)
        val response = CompletableDeferred<UserProfile>()
        api.get = { response.await() }
        controller.load()
        assertTrue(controller.state.loading); assertNull(controller.state.profile)
        response.completeExceptionally(ApiFailure(503, "잠시 후 다시 시도해 주세요."))
        assertFalse(controller.state.loading); assertNull(controller.state.profile)
        assertTrue(controller.state.error.isNotBlank())
        api.get = { b }; controller.load()
        assertEquals(b, controller.state.profile); assertEquals("", controller.state.error)
        controller.clear()
    }

    @Test fun rejectedProfileExpiresSessionAndNeverShowsPreviousUser() = runBlocking {
        val api = FakeApi().apply { get = { a } }
        var token: String? = "A"
        var expirations = 0
        val controller = MyPageController(CoroutineScope(coroutineContext + Dispatchers.Unconfined), api, { token }, { token = null; expirations++ }, {})
        controller.load()
        api.get = { throw ApiFailure(401, "expired") }; controller.load()
        assertNull(controller.state.profile); assertNull(token); assertEquals(1, expirations)
    }

    @Test fun latePreviousAccountResponseCannotOverwriteNextAccount() = runBlocking {
        val old = CompletableDeferred<UserProfile>()
        var token = "A"
        val api = FakeApi().apply { get = { value -> if (value == "A") withContext(NonCancellable) { old.await() } else b } }
        val controller = MyPageController(CoroutineScope(coroutineContext + Dispatchers.Unconfined), api, { token }, {}, {})
        controller.load(); controller.clear(); token = "B"; controller.load()
        assertEquals(b, controller.state.profile)
        old.complete(a)
        assertEquals(b, controller.state.profile)
        controller.clear()
    }

    @Test fun logoutIsSingleFlightAndOnlySuccessClearsSessionAndProfile() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var token: String? = "A"
        var calls = 0; var completed = 0
        val api = FakeApi().apply { get = { a }; post = { value -> assertEquals("A", value); calls++; gate.await() } }
        val controller = MyPageController(CoroutineScope(coroutineContext + Dispatchers.Unconfined), api, { token }, {}, { token = null; completed++ })
        controller.load(); controller.logout(); controller.logout(); controller.load()
        assertEquals(1, calls); assertTrue(controller.state.loggingOut); assertEquals("A", token)
        gate.complete(Unit)
        assertEquals(1, completed); assertNull(token); assertNull(controller.state.profile); assertFalse(controller.state.loggingOut)
    }

    @Test fun logoutFailurePreservesSessionAndAllowsExplicitRetry() = runBlocking {
        var token: String? = "A"
        var completed = 0
        val api = FakeApi().apply { get = { a }; post = { throw ApiFailure(500, "로그아웃 실패") } }
        val controller = MyPageController(CoroutineScope(coroutineContext + Dispatchers.Unconfined), api, { token }, {}, { token = null; completed++ })
        controller.load(); controller.logout()
        assertEquals("A", token); assertEquals(a, controller.state.profile); assertEquals(0, completed)
        assertFalse(controller.state.loggingOut); assertEquals("로그아웃 실패", controller.state.error)
        api.post = {}; controller.logout()
        assertNull(token); assertEquals(1, completed)
    }

    @Test fun logout401IsExpirationRatherThanSuccessfulLogout() = runBlocking {
        var expired = 0; var completed = 0
        val api = FakeApi().apply { post = { throw ApiFailure(401, "expired") } }
        val controller = MyPageController(CoroutineScope(coroutineContext + Dispatchers.Unconfined), api, { "A" }, { expired++ }, { completed++ })
        controller.logout()
        assertEquals(1, expired); assertEquals(0, completed); assertNull(controller.state.profile)
    }

    @Test fun logoutInvalidatesAnAlreadyRunningProfileResponse() = runBlocking {
        val old = CompletableDeferred<UserProfile>()
        var completed = 0
        val api = FakeApi().apply { get = { withContext(NonCancellable) { old.await() } }; post = {} }
        val controller = MyPageController(CoroutineScope(coroutineContext + Dispatchers.Unconfined), api, { "A" }, {}, { completed++ })
        controller.load(); controller.logout(); old.complete(a)
        assertEquals(1, completed); assertNull(controller.state.profile); assertFalse(controller.state.loading)
    }
}

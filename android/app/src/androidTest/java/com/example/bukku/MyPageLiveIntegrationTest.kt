package com.example.bukku

import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in: creates three test accounts on the deployed server; never logs their credentials. */
@RunWith(AndroidJUnit4::class)
class MyPageLiveIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val api = ApiClient("https://amulet-api.onrender.com")
    private val rows = JSONArray()
    private val directory by lazy { File(context.getExternalFilesDir(null), "mypage-live-check").apply { mkdirs() } }
    private data class Account(val form: SignupForm, val session: JSONObject, val expected: UserProfile)

    private fun report(test: String, result: String = "PASS") {
        rows.put(JSONObject().put("test", test).put("result", result))
        File(directory, "results.json").writeText(JSONObject().put("results", rows).toString(2))
    }

    @Test fun deployedProfilesAndRealAppLoginLogout() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("bukkuLiveMyPage") == "true")
        try {
            expect401("missing token profile") { api.request("/api/v1/me") }
            expect401("malformed token profile") { api.request("/api/v1/me", token = "invalid-token") }
            expect401("malformed token logout") { api.logout("invalid-token") }
            val suffix = UUID.randomUUID().toString().take(8)
            suspend fun account(label: String, name: String, lunar: Boolean, leap: Boolean?, time: String, gender: String): Account {
                val password = "Bukku9!${UUID.randomUUID()}"
                val form = SignupForm("bukku-me-$label-$suffix@example.com", password, password, name,
                    "1990-05-01", lunar, leap, time, gender)
                val session = api.request("/auth/signup", form.json())
                val expected = UserProfile(name, form.email, form.birth, if (lunar) "LUNAR" else "SOLAR", leap == true, time.ifBlank { null }, gender)
                report("create test account $label")
                return Account(form, session, expected)
            }
            val accounts = listOf(
                account("solar", "양력검증", false, null, "", "FEMALE"),
                account("lunar", "평달검증", true, false, "08:10", "MALE"),
                account("leap", "윤달검증", true, true, "13:30", "FEMALE"),
            )
            val remote = RemoteProfileApi(api)
            accounts.forEachIndexed { index, account ->
                val token = account.session.getString("access_token")
                assertEquals(account.expected, serverProfile(token))
                report("account ${index + 1} exact server fields and calendar mapping")
            }
            val aToken = accounts.first().session.getString("access_token")
            val parts = aToken.split('.').toMutableList()
            assertEquals(3, parts.size)
            parts[2] = (if (parts[2].first() == 'A') "B" else "A") + parts[2].drop(1)
            val tampered = parts.joinToString(".")
            expect401("tampered signed token profile") { remote.profile(tampered) }
            expect401("tampered signed token logout") { remote.logout(tampered) }
            assertNotEquals(remote.profile(aToken).email, remote.profile(accounts[1].session.getString("access_token")).email)
            report("A and B profiles remain isolated")
            exerciseApp(accounts)
            report("genuinely expired issuer-signed token", "NOT RUN: requires a real expired-token fixture")
            report("live server 5xx fault injection", "NOT RUN: no production fault was introduced; covered by local tests")
        } catch (error: Throwable) {
            report("integration run", "FAIL: ${error.javaClass.simpleName}; see instrumentation output")
            throw error
        }
    }

    private suspend fun exerciseApp(accounts: List<Account>) {
        val store = SessionStore(context)
        val previous = store.read()
        val pending = SessionStore(context, "pending-gift", "bukku-pending-gift")
        val previousPending = pending.read()
        store.clear(); pending.clear()
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                lateinit var model: AppModel
                scenario.onActivity { model = ViewModelProvider(it)[AppModel::class.java] }
                waitModel { !model.restoring && !model.busy }
                accounts.forEachIndexed { index, account ->
                    scenario.onActivity {
                        assertFalse(model.loggedIn)
                        model.form = SignupForm(email = account.form.email, password = account.form.password)
                        model.authenticate()
                    }
                    waitModel { !model.busy }
                    scenario.onActivity {
                        assertTrue("Real app login must succeed", model.loggedIn)
                        assertFalse(model.preview)
                        model.navigate("account")
                    }
                    waitModel { !model.profileState.loading && model.profileState.profile != null || !model.loggedIn || model.profileState.error.isNotBlank() }
                    scenario.onActivity {
                        assertTrue("Session must stay authenticated", model.loggedIn)
                        assertEquals(account.expected, model.profileState.profile)
                        assertEquals("", model.profileState.error)
                    }
                    waitText(account.expected.name, account.expected.email, "오늘도 좋은 흐름과 함께해요")
                    capture("${index + 1}-profile-top")
                    val fields = arrayOf(account.expected.birthDate, account.expected.calendarLabel, account.expected.timeLabel, account.expected.genderLabel)
                    if (!fields.all { text().contains(it) }) scrollForward()
                    waitText(*fields)
                    capture("${index + 1}-profile-fields")
                    report("account ${index + 1} real login, AppModel, server profile and rendered UI")
                    val currentToken = store.read()!!.getString("access_token")
                    repeat(3) { if (!text().contains("로그아웃")) scrollForward() }
                    waitText("로그아웃")
                    clickText("로그아웃")
                    waitModel { !model.busy && !model.loggedIn }
                    scenario.onActivity {
                        assertEquals("account", model.page)
                        assertEquals("Logout must complete successfully, not expire the session", "", model.message)
                        assertNull(model.profileState.profile)
                        assertNull(model.fortune)
                        assertTrue(model.archived.isEmpty())
                        assertNull(store.read())
                    }
                    val loginText = waitText("다시 만나 반가워요")
                    assertFalse(loginText.contains(account.expected.email))
                    capture("${index + 1}-logged-out")
                    expect401("account ${index + 1} token rejected after app logout") { api.request("/api/v1/me", token = currentToken) }
                    if (index < accounts.lastIndex) {
                        val other = accounts[index + 1]
                        assertEquals(other.expected, RemoteProfileApi(api).profile(other.session.getString("access_token")))
                        report("account ${index + 1} logout leaves next account token valid")
                    }
                    report("account ${index + 1} logout 204, local cleanup and login-screen transition")
                }
            }
        } finally {
            if (previous != null) store.save(previous) else store.clear()
            if (previousPending != null) pending.save(previousPending) else pending.clear()
        }
    }

    private suspend fun expect401(name: String, action: suspend () -> Unit) {
        try { action(); fail("Expected HTTP 401: $name") }
        catch (error: ApiFailure) { assertEquals(name, 401, error.status); report(name, "HTTP 401") }
    }
    private fun serverProfile(token: String): UserProfile {
        val connection = URI("https://amulet-api.onrender.com/api/v1/me").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000; connection.readTimeout = 90000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            assertEquals("Profile HTTP status", 200, connection.responseCode)
            assertTrue("Profile Content-Type", connection.contentType.orEmpty().startsWith("application/json"))
            return UserProfile.parse(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
        } finally { connection.disconnect() }
    }
    private fun waitModel(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 150000
        while (System.currentTimeMillis() < deadline) {
            var done = false
            instrumentation.runOnMainSync { done = check() }
            if (done) return
            Thread.sleep(150)
        }
        fail("Timed out waiting for live app state")
    }
    private fun text(node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): String =
        if (node == null) "" else node.text?.toString().orEmpty() + "\n" + (0 until node.childCount).joinToString("\n") { text(node.getChild(it)) }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        repeat(node.childCount) { find(predicate, node.getChild(it))?.let { return it } }
        return null
    }
    private fun clickText(label: String) {
        var node = find({ it.text?.toString() == label })
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull("Expected clickable $label", node)
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun scrollForward() {
        find({ it.isScrollable })?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        instrumentation.waitForIdleSync(); Thread.sleep(300)
    }
    private fun waitText(vararg expected: String): String {
        val deadline = System.currentTimeMillis() + 35000
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val actual = text()
            if (actual.contains("System UI isn't responding") || actual.contains("Pixel Launcher isn't responding")) {
                clickText("Close app")
            } else if (expected.all { actual.contains(it) }) return actual
            Thread.sleep(150)
        }
        fail("Expected rendered text: ${expected.joinToString()}")
        error("Unreachable")
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync(); Thread.sleep(1000)
        instrumentation.uiAutomation.takeScreenshot()!!.let { image ->
            File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
}

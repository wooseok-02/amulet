package com.example.bukku

import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MyPageUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val solar = UserProfile("테스트가", "profile-a@example.com", "2000-01-02", "SOLAR", false, null, "FEMALE")
    private class FakeApi : ProfileApi {
        lateinit var value: UserProfile
        var error: Exception? = null
        var getGate: CompletableDeferred<Unit>? = null
        var logoutGate: CompletableDeferred<Unit>? = null
        var logoutCalls = 0
        override suspend fun profile(token: String): UserProfile {
            getGate?.await(); error?.let { throw it }; return value
        }
        override suspend fun logout(token: String) {
            logoutCalls++; logoutGate?.await(); error?.let { throw it }
        }
    }

    @Test fun profileFieldsCalendarVariantsLoadingRetryAndExpirationRenderCorrectly() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            lateinit var controller: MyPageController
            val api = FakeApi().apply { value = solar }
            var authenticated by mutableStateOf(true)
            scenario.onActivity { activity ->
                controller = MyPageController(activity.lifecycleScope, api, { "test-token" }, { authenticated = false }, { authenticated = false })
                activity.setContent {
                    MaterialTheme {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            if (authenticated) ProfileContent(controller.state, false, controller.state.loggingOut, controller::load, controller::logout)
                            else Text("로그인이 필요합니다.")
                        }
                    }
                }
                controller.load()
            }
            waitText("profile-a@example.com")
            assertText("테스트가", "2000-01-02", "양력", "모름", "여성", "오늘도 좋은 흐름과 함께해요")
            capture("01-solar-unknown-time")

            scenario.onActivity {
                api.value = solar.copy(name = "테스트나", email = "profile-b@example.com", calendarType = "LUNAR", birthTime = "08:10", gender = "MALE")
                api.getGate = CompletableDeferred(); controller.load()
            }
            val loadingText = waitText("회원정보를 불러오고 있어요.")
            assertFalse(loadingText.contains("profile-a@example.com")); assertFalse(loadingText.contains("테스트가"))
            capture("02-loading-no-old-profile")
            scenario.onActivity { api.getGate!!.complete(Unit); api.getGate = null }
            waitText("profile-b@example.com"); assertText("음력 · 평달", "08:10", "남성")
            capture("03-lunar-regular")

            scenario.onActivity { api.value = api.value.copy(isLeapMonth = true); controller.load() }
            waitText("음력 · 윤달"); capture("04-lunar-leap")
            scenario.onActivity { api.error = ApiFailure(503, "회원정보 서버에 연결하지 못했어요."); controller.load() }
            val errorText = waitText("다시 불러오기")
            assertFalse(errorText.contains("profile-b@example.com")); assertFalse(errorText.contains("테스트나"))
            capture("05-server-error-no-old-profile")
            scenario.onActivity { api.error = null; controller.load() }
            waitText("profile-b@example.com")
            scenario.onActivity { api.error = ApiFailure(401, "expired"); controller.load() }
            val expiredText = waitText("로그인이 필요합니다.")
            assertFalse(expiredText.contains("profile-b@example.com")); capture("06-expired")
            scenario.onActivity { assertNull(controller.state.profile) }
        }
    }

    @Test fun logoutDuplicateFailureRetryAndSessionRemovalAreVisible() {
        val store = SessionStore(context, "mypage-ui-test", "bukku-mypage-ui-test")
        store.save(JSONObject().put("access_token", "test-access").put("refresh_token", "test-refresh"))
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                lateinit var controller: MyPageController
                val api = FakeApi().apply { value = solar; logoutGate = CompletableDeferred() }
                var authenticated by mutableStateOf(true)
                scenario.onActivity { activity ->
                    controller = MyPageController(activity.lifecycleScope, api, { store.read()?.getString("access_token") },
                        { store.clear(); authenticated = false }, { store.clear(); authenticated = false })
                    activity.setContent {
                        MaterialTheme {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                if (authenticated) ProfileContent(controller.state, false, controller.state.loggingOut, controller::load, controller::logout)
                                else Text("로그인이 필요합니다.")
                            }
                        }
                    }
                    controller.load()
                }
                waitText("profile-a@example.com")
                scenario.onActivity { controller.logout(); controller.logout() }
                waitText("처리 중…")
                scenario.onActivity { assertEquals(1, api.logoutCalls); assertNotNull(store.read()) }
                capture("07-logout-in-progress")
                scenario.onActivity { api.error = ApiFailure(500, "로그아웃하지 못했어요. 다시 시도해주세요."); api.logoutGate!!.complete(Unit) }
                waitText("로그아웃하지 못했어요.")
                scenario.onActivity { assertTrue(authenticated); assertNotNull(store.read()); assertFalse(controller.state.loggingOut) }
                capture("08-logout-failed")
                scenario.onActivity { api.error = null; api.logoutGate = null; controller.logout() }
                waitText("로그인이 필요합니다.")
                scenario.onActivity { assertNull(store.read()); assertNull(controller.state.profile); assertEquals(2, api.logoutCalls) }
                capture("09-logged-out")
            }
        } finally { store.clear() }
    }

    private fun text(node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): String =
        if (node == null) "" else node.text?.toString().orEmpty() + "\n" + (0 until node.childCount).joinToString("\n") { text(node.getChild(it)) }
    private fun waitText(vararg expected: String): String {
        val deadline = System.currentTimeMillis() + 25000
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val observed = text()
            // A cold AVD can put an OS ANR dialog over a healthy test activity.
            // Dismiss only these named emulator system processes, never a Bukku ANR.
            if (observed.contains("System UI isn't responding") || observed.contains("Pixel Launcher isn't responding")) {
                fun closeButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (node == null) return null
                    if (node.text?.toString() == "Close app") return node
                    repeat(node.childCount) { closeButton(node.getChild(it))?.let { found -> return found } }
                    return null
                }
                var button = closeButton(instrumentation.uiAutomation.rootInActiveWindow)
                while (button != null && !button.isClickable) button = button.parent
                button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else if (expected.all { observed.contains(it) }) return observed
            Thread.sleep(150)
        }
        throw AssertionError("Expected rendered text: ${expected.joinToString()}; observed: ${text()}")
    }
    private fun assertText(vararg expected: String) { waitText(*expected) }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val directory = File(context.getExternalFilesDir(null), "mypage-check").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()!!.let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}

package com.example.bukku

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import java.time.LocalDate
import java.time.ZoneId
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal val seoul: ZoneId = ZoneId.of("Asia/Seoul")
internal class ApiFailure(val status: Int, message: String) : Exception(message)

/** Tokens are encrypted with a device-bound Android Keystore key; passwords are never stored. */
internal class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("bukku-session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("bukku-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(session: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(session.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    fun read(): JSONObject? {
        val data = prefs.getString("data", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
            }
            JSONObject(String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8))
        } catch (_: Exception) { clear(); null }
    }
    fun clear() { check(prefs.edit().clear().commit()) }
}

internal class ApiClient(private val base: String) {
    suspend fun request(path: String, body: JSONObject? = null, token: String? = null): JSONObject = withContext(Dispatchers.IO) {
        if (base.isBlank()) throw ApiFailure(0, "내 정보의 서버 설정에서 백엔드 주소를 입력해 주세요.")
        val connection = URI(base.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 90000
            connection.instanceFollowRedirects = false
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            body?.let {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { output -> output.write(it.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrNull()
            if (status !in 200..299) throw ApiFailure(status, json?.optString("detail")?.takeIf { !it.startsWith("[") && it.isNotBlank() } ?: "요청을 처리하지 못했습니다. ($status)")
            json ?: throw ApiFailure(502, "서버 응답 형식이 올바르지 않습니다.")
        } finally { connection.disconnect() }
    }
}

internal fun sampleFortune() = JSONObject().apply {
    put("fortune_date", LocalDate.now(seoul).toString())
    put("headline", "흐름이 천천히 열리는 날")
    put("total_comment", "서두르기보다 준비한 것을 차분히 보여주세요. 한 번 더 묻는 용기가 예상보다 좋은 흐름을 만들 수 있어요.")
    put("total_score", 78); put("love_score", 72); put("money_score", 68)
    put("career_score", 81); put("study_score", 86); put("health_score", 74)
    put("lucky_number", 7); put("lucky_color", "초록"); put("today_action", "질문 한 번 더")
}

internal data class SignupForm(
    val email: String = "", val password: String = "", val confirm: String = "", val name: String = "",
    val birth: String = "", val lunar: Boolean = false, val leap: Boolean? = null,
    val time: String = "", val gender: String = "",
) {
    fun validate(): Map<String, String> = buildMap {
        if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())) put("email", "올바른 이메일 형식이 아닙니다.")
        if (password.length < 8 || !password.any { it in 'a'..'z' || it in 'A'..'Z' } || !password.any { it in '0'..'9' }) put("password", "비밀번호는 영문과 숫자를 포함해 8자 이상이어야 합니다.")
        if (confirm != password) put("confirm", "비밀번호와 비밀번호 확인이 일치하지 않습니다.")
        if (name.trim().codePointCount(0, name.trim().length) !in 2..4) put("name", "사용자 이름은 2자 이상 4자 이하여야 합니다.")
        val date = runCatching { LocalDate.parse(birth) }.getOrNull()
        if (date == null || date.year !in 1900..LocalDate.now(seoul).year) put("birth", "생년월일은 1900년부터 현재 연도까지 입력해 주세요.")
        if (lunar && leap == null) put("leap", "윤달 여부를 선택해 주세요.")
        if (gender !in listOf("MALE", "FEMALE")) put("gender", "성별을 선택해 주세요.")
    }
    fun json() = JSONObject().apply {
        put("email", email.trim().lowercase()); put("password", password); put("password_confirm", confirm)
        put("name", name.trim()); put("birth_date", birth); put("calendar_type", if (lunar) "LUNAR" else "SOLAR")
        put("is_leap_month", if (lunar) leap else JSONObject.NULL)
        put("birth_time", time.ifBlank { null } ?: JSONObject.NULL); put("gender", gender)
    }
}

internal class AppModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("config", Context.MODE_PRIVATE)
    private val store = SessionStore(application)
    private val refreshLock = Mutex()
    private var session: JSONObject? = null
    var server by mutableStateOf(prefs.getString("server", "").orEmpty()); private set
    var page by mutableStateOf("account")
    var signup by mutableStateOf(false)
    var form by mutableStateOf(SignupForm())
    var errors by mutableStateOf(emptyMap<String, String>()); private set
    var busy by mutableStateOf(false); private set
    var restoring by mutableStateOf(true); private set
    var restoreFailed by mutableStateOf(false); private set
    var loggedIn by mutableStateOf(false); private set
    var preview by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var fortune by mutableStateOf<JSONObject?>(null); private set
    var category by mutableStateOf("전체")
    var concern by mutableStateOf("")
    var mood by mutableStateOf("불안해요")
    var interpretation by mutableStateOf<JSONObject?>(null); private set
    val email: String get() = session?.optString("email").orEmpty()
    init { restore() }
    private fun api() = ApiClient(server)
    private fun accept(value: JSONObject) { store.save(value); session = value; loggedIn = true }
    private suspend fun refresh(): Unit = refreshLock.withLock {
        val value = session ?: throw ApiFailure(401, "로그인이 필요합니다.")
        accept(api().request("/auth/refresh", JSONObject().put("refresh_token", value.getString("refresh_token"))))
    }
    private fun reset() {
        store.clear(); session = null; loggedIn = false; preview = false
        fortune = null; interpretation = null; concern = ""; form = SignupForm(); errors = emptyMap()
        category = "전체"; mood = "불안해요"; signup = false; page = "account"; restoreFailed = false
    }
    fun logout() { if (!busy) { reset(); message = "" } }
    fun restore() = viewModelScope.launch {
        restoring = true; restoreFailed = false; message = ""
        try {
            session = store.read()
            if (session != null) { refresh(); page = "home" }
        } catch (error: Exception) {
            if (error is ApiFailure && error.status == 401) reset() else restoreFailed = true
            message = error.message ?: "인터넷 연결을 확인해 주세요."
        } finally { restoring = false }
        if (loggedIn) loadFortune()
    }
    fun saveServer(value: String): Boolean {
        if (busy || restoring) return false
        val uri = runCatching { URI(value.trim().trimEnd('/')) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) {
            message = "https://로 시작하는 서버 주소를 입력해 주세요."; return false
        }
        val normalized = uri.toString().trimEnd('/')
        if (normalized != server) { reset(); server = normalized; prefs.edit().putString("server", server).apply() }
        message = "서버 주소를 저장했습니다."; return true
    }
    fun showPreview() { if (!busy && !restoreFailed) { preview = true; page = "home"; fortune = sampleFortune(); message = "" } }
    fun switchAuth() { if (!busy) { signup = !signup; form = SignupForm(); errors = emptyMap(); message = "" } }
    fun authenticate() {
        if (busy) return
        errors = if (signup) form.validate() else buildMap {
            if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(form.email.trim())) put("email", "올바른 이메일 형식이 아닙니다.")
            if (form.password.isEmpty()) put("password", "비밀번호를 입력해 주세요.")
        }
        if (errors.isNotEmpty()) return
        val isSignup = signup
        val body = if (isSignup) form.json() else JSONObject().put("email", form.email.trim().lowercase()).put("password", form.password)
        runAction {
            val response = api().request(if (isSignup) "/auth/signup" else "/auth/login", body)
            accept(response); form = SignupForm(); preview = false; page = "home"
            fortune = null; interpretation = null
            try { fortune = authorized("/fortune/today") } catch (error: Exception) {
                handle(error)
            }
        }
    }
    private suspend fun authorized(path: String, body: JSONObject? = null): JSONObject {
        val current = session ?: throw ApiFailure(401, "로그인이 필요합니다.")
        val expires = current.optLong("expires_at", 0)
        if (expires > 0 && expires <= System.currentTimeMillis() / 1000 + 30) refresh()
        return try { api().request(path, body, session!!.getString("access_token")) }
        catch (error: ApiFailure) {
            if (error.status != 401) throw error
            refresh(); api().request(path, body, session!!.getString("access_token"))
        }
    }
    fun loadFortune() {
        if (preview) { fortune = sampleFortune(); return }
        if (loggedIn) runAction { fortune = authorized("/fortune/today") }
    }
    fun interpret() {
        if (concern.isBlank()) { message = "고민 내용을 입력해 주세요."; return }
        if (concern.length > 200) { message = "고민은 200자까지 입력할 수 있습니다."; return }
        if (preview) {
            interpretation = JSONObject().put("day_message", "준비한 만큼 자신감이 빛나는 날이에요.")
                .put("action_advice", "첫 문장을 소리 내어 한 번 연습해보세요.")
            return
        }
        runAction { interpretation = authorized("/fortune/interpret", JSONObject().put("category", category).put("concern", concern.trim()).put("mood", mood)) }
    }
    private fun handle(error: Exception) {
        if (error is ApiFailure && error.status == 401) {
            val wasLoggedIn = loggedIn
            if (wasLoggedIn) reset()
            message = if (wasLoggedIn) "로그인 정보가 만료되었습니다. 다시 로그인해 주세요." else "이메일 또는 비밀번호가 올바르지 않습니다."
        }
        else message = if (error is ApiFailure) error.message.orEmpty() else "연결에 실패했습니다. 인터넷 연결과 서버 주소를 확인해 주세요."
    }
    private fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true; message = ""
        viewModelScope.launch {
            try { action() } catch (error: Exception) { handle(error) } finally { busy = false }
        }
    }
}

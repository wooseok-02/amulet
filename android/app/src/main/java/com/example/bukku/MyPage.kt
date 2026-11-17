package com.example.bukku

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

internal data class UserProfile(
    val name: String, val email: String, val birthDate: String,
    val calendarType: String, val isLeapMonth: Boolean, val birthTime: String?, val gender: String,
) {
    val calendarLabel: String get() = when {
        calendarType == "SOLAR" -> "양력"
        isLeapMonth -> "음력 · 윤달"
        else -> "음력 · 평달"
    }
    val timeLabel: String get() = birthTime ?: "모름"
    val genderLabel: String get() = if (gender == "MALE") "남성" else "여성"

    companion object {
        fun parse(json: JSONObject): UserProfile = try {
            fun text(key: String): String = (json.get(key) as? String)?.takeIf { it.isNotBlank() }
                ?: error("Invalid $key")
            val birth = text("birthDate")
            require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(birth))
            LocalDate.parse(birth)
            val calendar = text("calendarType").also { require(it in setOf("SOLAR", "LUNAR")) }
            val leap = json.get("isLeapMonth") as? Boolean ?: error("Invalid isLeapMonth")
            require(calendar != "SOLAR" || !leap)
            require(json.has("birthTime"))
            val time = if (json.isNull("birthTime")) null else text("birthTime").also {
                require(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d").matches(it))
            }
            val gender = text("gender").also { require(it in setOf("MALE", "FEMALE")) }
            UserProfile(text("name"), text("email"), birth, calendar, leap, time, gender)
        } catch (_: Exception) {
            throw ApiFailure(502, "회원정보 응답을 확인하지 못했습니다. 다시 시도해 주세요.")
        }
        val preview = UserProfile("행운님", "preview@example.com", "2000-01-02", "SOLAR", false, "13:30", "FEMALE")
    }
}

internal interface ProfileApi {
    suspend fun profile(token: String): UserProfile
    suspend fun logout(token: String)
}

internal class RemoteProfileApi(private val client: ApiClient) : ProfileApi {
    override suspend fun profile(token: String) = UserProfile.parse(client.request("/api/v1/me", token = token))
    override suspend fun logout(token: String) = client.logout(token)
}

internal data class MyPageState(
    val profile: UserProfile? = null, val loading: Boolean = false,
    val loggingOut: Boolean = false, val error: String = "",
)

/** Never retain a previous profile while loading, or accept a result from an old session. */
internal class MyPageController(
    private val scope: CoroutineScope, private val api: ProfileApi,
    private val accessToken: () -> String?,
    private val onExpired: () -> Unit, private val onLoggedOut: () -> Unit,
) {
    var state by mutableStateOf(MyPageState()); private set
    private var generation = 0
    private var loadJob: Job? = null
    private var logoutJob: Job? = null

    fun clear() {
        generation++
        loadJob?.cancel(); logoutJob?.cancel()
        state = MyPageState()
    }

    fun load() {
        if (state.loggingOut) return
        val version = ++generation
        loadJob?.cancel()
        state = MyPageState(loading = true)
        val token = accessToken()
        if (token.isNullOrBlank()) { clear(); onExpired(); return }
        loadJob = scope.launch {
            try {
                val value = api.profile(token)
                if (generation == version) state = MyPageState(profile = value)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation == version) {
                    state = MyPageState(error = failureMessage(error))
                    if (error is ApiFailure && error.status == 401) { clear(); onExpired() }
                }
            }
        }
    }

    fun logout() {
        if (state.loggingOut) return
        val version = ++generation
        loadJob?.cancel()
        state = state.copy(loading = false, loggingOut = true, error = "")
        val token = accessToken()
        if (token.isNullOrBlank()) { clear(); onExpired(); return }
        logoutJob = scope.launch {
            try {
                api.logout(token)
                if (generation == version) { clear(); onLoggedOut() }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation == version) {
                    state = state.copy(loggingOut = false, error = failureMessage(error))
                    if (error is ApiFailure && error.status == 401) { clear(); onExpired() }
                }
            }
        }
    }

    private fun failureMessage(error: Exception): String =
        if (error is ApiFailure) error.message.orEmpty()
        else "연결에 실패했습니다. 인터넷 연결을 확인하고 다시 시도해 주세요."
}

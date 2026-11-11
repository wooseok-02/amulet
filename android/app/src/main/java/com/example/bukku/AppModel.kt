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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.time.YearMonth
import java.io.ByteArrayOutputStream
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal val seoul: ZoneId = ZoneId.of("Asia/Seoul")
internal class ApiFailure(val status: Int, message: String) : Exception(message)

/** Tokens are encrypted with a device-bound Android Keystore key; passwords are never stored. */
internal class SessionStore(context: Context, prefsName: String = "session", private val keyAlias: String = "bukku-session") {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
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
    private val store = SessionStore(application)
    private val refreshLock = Mutex()
    private var session: JSONObject? = null
    val server: String = "https://amulet-api.onrender.com"
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
    var interpretationVisible by mutableStateOf(false)
    var friendMode by mutableStateOf(false)
    var friendForm by mutableStateOf(FriendForm())
    var friendErrors by mutableStateOf(emptyMap<String, String>()); private set
    var friendFortune by mutableStateOf<JSONObject?>(null); private set
    var friendInterpretation by mutableStateOf<JSONObject?>(null); private set
    var friendInterpretationVisible by mutableStateOf(false)
    var friendCategory by mutableStateOf("전체")
    var friendConcern by mutableStateOf("")
    var editorStep by mutableStateOf(0)
    var draft by mutableStateOf(Charm()); private set
    var draftSaved by mutableStateOf(false); private set
    private val charmStore = CharmStore(application)
    var archived by mutableStateOf(emptyList<Charm>()); private set
    private val archiveOwner: String get() = "$server|${session?.optString("user_id") ?: "preview"}"
    val email: String get() = session?.optString("email").orEmpty()
    val profile: JSONObject get() {
        if (preview) return JSONObject().put("name", "행운님").put("birth_date", "2000-01-02").put("calendar_type", "SOLAR").put("birth_time", "13:30").put("gender", "FEMALE")
        return runCatching {
            val encoded = session?.getString("access_token")?.split('.')?.get(1) ?: return@runCatching JSONObject()
            JSONObject(String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)).optJSONObject("user_metadata") ?: JSONObject()
        }.getOrDefault(JSONObject())
    }
    init { restore() }
    private val refreshedImages=mutableSetOf<String>()
    fun refreshImage(id:String) { if(refreshedImages.add(id))openArchiveItem(id) }
    var archiveMonth by mutableStateOf(YearMonth.now(seoul)); private set
    var archiveLoading by mutableStateOf(false); private set
    var archiveError by mutableStateOf(""); private set
    var openedDate by mutableStateOf<LocalDate?>(null); private set
    var dateItems by mutableStateOf(emptyList<Charm>()); private set
    var dateLoading by mutableStateOf(false); private set
    var detail by mutableStateOf<Charm?>(null); private set
    var detailLoading by mutableStateOf(false); private set
    var detailError by mutableStateOf(""); private set
    var shareUrl by mutableStateOf(""); private set
    var uploadUncertain by mutableStateOf(false); private set
    private var archiveJob: Job? = null
    private var dateJob: Job? = null
    private var detailJob: Job? = null
    private var archiveVersion=0
    private var dateVersion=0
    private var detailVersion=0
    private val pendingStore = SessionStore(application, "pending-gift", "bukku-pending-gift")
    private var incomingGift=false
    var pendingGift by mutableStateOf(pendingStore.read()?.optString("token").orEmpty()); private set
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
        friendMode = false; friendForm = FriendForm(); friendErrors = emptyMap(); friendFortune = null
        friendInterpretation = null; friendConcern = ""; friendCategory = "전체"
        interpretationVisible = false; friendInterpretationVisible = false; editorStep = 0; draft = Charm(); draftSaved = false; archived = emptyList()
        archiveVersion++; dateVersion++; detailVersion++
        archiveJob?.cancel(); dateJob?.cancel(); detailJob?.cancel()
        archiveLoading=false; dateLoading=false; detailLoading=false; archiveError=""; detailError=""
        openedDate=null; dateItems=emptyList(); detail=null; shareUrl=""; uploadUncertain=false
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
        if(loggedIn && incomingGift && pendingGift.isNotBlank())claimGift() else if(loggedIn)loadFortune()
    }
    fun showPreview() { if (!busy && !restoreFailed) { preview = true; page = "home"; fortune = sampleFortune(); message = ""; reloadArchive() } }
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
    private suspend fun authorized(path: String, body: JSONObject? = null): JSONObject = withAuthorization { api().request(path, body, it) }
    private suspend fun <T> withAuthorization(request: suspend (String) -> T): T {
        val current = session ?: throw ApiFailure(401, "로그인이 필요합니다.")
        val expires = current.optLong("expires_at", 0)
        if (expires > 0 && expires <= System.currentTimeMillis() / 1000 + 30) refresh()
        return try { request(session!!.getString("access_token")) }
        catch (error: ApiFailure) {
            if (error.status != 401) throw error
            refresh(); request(session!!.getString("access_token"))
        }
    }
    fun loadFortune() {
        if (preview) { fortune = sampleFortune(); return }
        if (loggedIn) runAction { fortune = authorized("/fortune/today") }
    }
    fun interpret() {
        if (busy) return
        if (interpretationVisible) { interpretationVisible = false; return }
        if (concern.isBlank()) { message = "고민 내용을 입력해 주세요."; return }
        if (concern.length > 200) { message = "고민은 200자까지 입력할 수 있습니다."; return }
        if (preview) {
            interpretation = JSONObject().put("day_message", "준비한 만큼 자신감이 빛나는 날이에요.")
                .put("action_advice", "첫 문장을 소리 내어 한 번 연습해보세요.")
            interpretationVisible = true; message = ""
            return
        }
        runAction { interpretation = authorized("/fortune/interpret", JSONObject().put("category", category).put("concern", concern.trim()).put("mood", mood)); interpretationVisible = true }
    }
    fun friendToday() {
        if (busy) return
        friendErrors = friendForm.validate()
        if (friendErrors.isNotEmpty()) return
        if (!preview && !loggedIn) { page = "account"; message = "로그인하고 친구의 운세를 확인해 주세요."; return }
        friendInterpretation = null; friendInterpretationVisible = false
        if (preview) { friendFortune = sampleFriendFortune(); message = "" }
        else runAction { friendFortune = authorized("/fortune/friend/today", friendForm.json()) }
    }
    fun editFriend() { if (!busy) { friendFortune = null; friendInterpretation = null; friendInterpretationVisible = false; message = "" } }
    fun interpretFriend() {
        if (busy) return
        if (friendInterpretationVisible) { friendInterpretationVisible = false; return }
        if (friendConcern.isBlank() || friendConcern.length > 200) { message = "친구의 상황을 1~200자로 입력해 주세요."; return }
        if (friendFortune == null) return
        if (preview) {
            friendInterpretation = JSONObject().put("day_message", "지금까지 쌓아온 준비가 친구의 가장 단단한 행운이에요.")
                .put("action_advice", "친구가 스스로의 노력을 믿을 수 있도록, 짧고 확실한 응원을 전해보세요.")
            friendInterpretationVisible = true; message = ""
        } else runAction {
            friendInterpretation = authorized("/fortune/friend/interpret", JSONObject().put("profile", friendForm.json()).put("fortune", friendFortune)
                .put("concern", JSONObject().put("category", friendCategory).put("situation", friendConcern.trim())))
            friendInterpretationVisible = true
        }
    }
    fun openEditor() {
        val interpretation = if (friendMode) friendInterpretation else this.interpretation
        if (interpretation == null) return
        val source = if (friendMode) friendFortune else fortune
        val charmText = if (preview) {
            if (friendMode) "너의 모든 준비가\n빛을 발할 거야" else "천천히 말해도\n나의 진심은 닿는다"
        } else interpretation.optString("day_message").ifBlank { interpretation.optString("action_advice") }
        draft = Charm(text = charmText,
            recipient = if (friendMode) friendForm.name.trim() else "", theme = if (friendMode) "sky" else "cream",
            message = source?.optString("total_comment").orEmpty(),
            dayMessage = interpretation.getString("day_message"), actionAdvice = interpretation.getString("action_advice"),
            advice = interpretation.optString("day_message") + "\n" + interpretation.optString("action_advice"),
            concern = if (friendMode) friendConcern else concern, luckyColor = source?.optString("lucky_color", "초록") ?: "초록",
            score = source?.optInt("total_score", 0) ?: 0, demo = preview)
        draftSaved = false; shareUrl=""; uploadUncertain=false; editorStep = 1; message = ""
    }
    fun updateDraft(value: Charm) { if (!busy && !draftSaved && shareUrl.isBlank() && !uploadUncertain) { draft = value; draftSaved = false } }
    fun reloadArchive(month: YearMonth = archiveMonth) {
        val version=++archiveVersion
        archiveMonth=month; archiveJob?.cancel(); archiveError=""
        if(preview) { archived=charmStore.read(archiveOwner); archiveLoading=false; return }
        archived=emptyList()
        if(!loggedIn) { archiveLoading=false; return }
        val owner=archiveOwner; archiveLoading=true
        archiveJob=viewModelScope.launch {
            try {
                val items=authorized("/amulets?month=$month").getJSONArray("items")
                val values=(0 until items.length()).map { Charm.fromServer(items.getJSONObject(it)) }
                if(owner==archiveOwner && version==archiveVersion) archived=values
            } catch(error:CancellationException) { throw error }
            catch(error:Exception) { if(owner==archiveOwner && version==archiveVersion) { handle(error); archiveError=message } }
            finally { if(owner==archiveOwner && version==archiveVersion) archiveLoading=false }
        }
    }
    fun openArchiveDate(date: LocalDate) {
        val version=++dateVersion; detailVersion++
        dateJob?.cancel(); detailJob?.cancel(); refreshedImages.clear(); openedDate=date; dateItems=emptyList(); detail=null; detailError=""
        if(preview) {
            dateItems=(archived+exampleCharms(YearMonth.from(date)).filter { sample -> archived.none { it.date==sample.date } }).filter{it.date==date.toString()}
            dateLoading=false; return
        }
        if(!loggedIn)return
        val owner=archiveOwner; dateLoading=true
        dateJob=viewModelScope.launch {
            try {
                val items=authorized("/amulets?date=$date").getJSONArray("items")
                if(owner==archiveOwner && version==dateVersion) dateItems=(0 until items.length()).map { Charm.fromServer(items.getJSONObject(it)) }
            } catch(error:CancellationException) { throw error }
            catch(error:Exception) { if(owner==archiveOwner && version==dateVersion) { handle(error); detailError=message } }
            finally { if(owner==archiveOwner && version==dateVersion)dateLoading=false }
        }
    }
    fun openArchiveItem(id: String) {
        val version=++detailVersion
        detailJob?.cancel(); detail=null; detailError=""; detailLoading=true
        val owner=archiveOwner
        detailJob=viewModelScope.launch {
            try { val value=Charm.fromServer(authorized("/amulets/$id")); if(owner==archiveOwner && version==detailVersion)detail=value }
            catch(error:CancellationException){throw error}
            catch(error:Exception){if(owner==archiveOwner && version==detailVersion){handle(error);detailError=message}}
            finally{if(owner==archiveOwner && version==detailVersion)detailLoading=false}
        }
    }
    fun closeArchive() { dateVersion++; detailVersion++; dateJob?.cancel(); detailJob?.cancel(); openedDate=null; dateItems=emptyList(); detail=null; dateLoading=false; detailLoading=false; detailError="" }
    // Only the explicitly labelled preview uses a device-local archive.
    fun saveDraft(): Boolean {
        if(!preview || draft.recipient.isNotBlank())return false
        return try {
            val updated=listOf(draft)+archived.filterNot{it.id==draft.id}
            charmStore.save(archiveOwner,updated); archived=updated; draftSaved=true; message="미리보기 보관함에 저장했어요."; true
        } catch(_:Exception) { message="부적을 저장하지 못했어요."; false }
    }
    fun saveAmulet() {
        if(busy || draftSaved || uploadUncertain || draft.recipient.isNotBlank())return
        if(preview){saveDraft();return}
        uploadDraft(false)
    }
    fun createGift() {
        if(busy || shareUrl.isNotBlank() || uploadUncertain || draft.recipient.isBlank())return
        if(preview){message="친구에게 보낼 링크는 로그인 후 만들 수 있어요.";return}
        uploadDraft(true)
    }
    private fun uploadDraft(gift: Boolean) = runAction {
        val snapshot=draft.copy(date=LocalDate.now(seoul).toString())
        val upload=withContext(Dispatchers.IO) {
            val bytes=ByteArrayOutputStream().use { stream ->
                val bitmap=CharmArtwork.render(getApplication(),snapshot)
                try { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream)); stream.toByteArray() } finally {bitmap.recycle()}
            }
            snapshot.upload(bytes)
        }
        // The only retry is after an explicit authentication rejection, never a network failure.
        val response=try { withAuthorization { api().upload(if(gift)"/amulets/shares" else "/amulets",upload,it) } }
        catch(error:Exception) {
            if(error is CancellationException)throw error
            if(error !is ApiFailure || error.status>=500 && (error.status!=503 || error.message.orEmpty().contains("결과"))) {
                uploadUncertain=true
                message=if(gift)"공유 생성 결과를 확인하지 못했어요. 중복 생성을 막기 위해 자동으로 다시 보내지 않습니다." else "저장 결과를 확인하지 못했어요. 다시 저장하기 전에 보관함을 확인해 주세요."
                if(!gift)reloadArchive()
                return@runAction
            }
            throw error
        }
        val id=response.getString("amulet_id")
        if(gift) {
            shareUrl=response.getString("share_url")
            message="공유 링크가 준비됐어요. 아래 버튼으로 친구에게 전해주세요."
        } else {
            draft=snapshot.copy(id=id,serverStored=true); draftSaved=true
            message="보관함에 저장했어요."; reloadArchive(YearMonth.from(LocalDate.parse(snapshot.date)))
        }
    }
    fun receiveLink(url: String) {
        val token=ShareLink.token(url, BuildConfig.SHARE_HOST)
        if(token==null) { message="공유 링크를 확인해 주세요."; return }
        pendingGift=token; pendingStore.save(JSONObject().put("token",token)); incomingGift=true
        if(restoring)return
        if(loggedIn) { if(!busy)claimGift() } else {
            incomingGift=false
            preview=false; page="account"; editorStep=0
            message="선물을 받으려면 로그인 또는 회원가입 후 원래 링크를 다시 눌러주세요."
        }
    }
    fun claimGift() {
        if(busy || restoring || pendingGift.isBlank())return
        if(!loggedIn){preview=false;page="account";message="로그인 후 원래 선물 링크를 다시 눌러주세요.";return}
        val token=pendingGift
        incomingGift=false
        runAction {
            val id=authorized("/amulets/shares/claim",JSONObject().put("token",token)).getString("amulet_id")
            val value=Charm.fromServer(authorized("/amulets/$id"))
            detailJob?.cancel(); dateJob?.cancel(); editorStep=0; page="library"
            openedDate=LocalDate.parse(value.date); dateItems=listOf(value); detail=value
            detailLoading=false; dateLoading=false; detailError=""
            pendingGift=""; pendingStore.clear(); reloadArchive(YearMonth.from(LocalDate.parse(value.date)))
            message="선물 받은 부적을 보관함에 담았어요."
        }
    }
    fun dismissGift() { pendingGift=""; incomingGift=false; pendingStore.clear() }
    fun notifySharingError() { message="공유를 마치지 못했어요. 생성된 같은 링크로 다시 시도해 주세요." }
    fun navigate(destination: String) { if(!busy){editorStep=0;page=destination;message="";if(destination=="library")reloadArchive()} }
    private fun handle(error: Exception) {
        if (error is ApiFailure && error.status == 401) {
            val wasLoggedIn = loggedIn
            if (wasLoggedIn) reset()
            message = if (wasLoggedIn) "로그인 정보가 만료되었습니다. 다시 로그인해 주세요." else "이메일 또는 비밀번호가 올바르지 않습니다."
        }
        else message = if (error is ApiFailure) error.message.orEmpty() else "연결에 실패했습니다. 인터넷 연결을 확인하고 잠시 후 다시 시도해 주세요."
    }
    private fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true; message = ""
        viewModelScope.launch {
            try { action() } catch(error:CancellationException){throw error} catch (error: Exception) { handle(error) } finally {
                busy = false
                if(incomingGift && loggedIn && !restoring && pendingGift.isNotBlank())claimGift()
            }
        }
    }
}

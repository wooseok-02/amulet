package com.example.bukku

import android.content.Context
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class FriendForm(
    val name: String = "", val birth: String = "", val lunar: Boolean = false,
    val leap: Boolean? = null, val time: String = "", val relationship: String = "", val gender: String = "",
) {
    fun validate(): Map<String, String> = buildMap {
        if (name.isBlank()) put("name", "친구 이름 또는 별명을 입력해 주세요.")
        val date = runCatching { LocalDate.parse(birth) }.getOrNull()
        if (date == null || date.year !in 1900..LocalDate.now(seoul).year) put("birth", "생년월일은 1900년부터 현재 연도까지 입력해 주세요.")
        if (lunar && leap == null) put("leap", "윤달 여부를 선택해 주세요.")
        if (relationship !in listOf("친구", "연인", "가족", "동료")) put("relationship", "친구와의 관계를 선택해 주세요.")
    }
    // Name and relationship remain in the app and are never sent to the API.
    fun json() = JSONObject().apply {
        put("birth_date", birth); put("calendar_type", if (lunar) "LUNAR" else "SOLAR")
        put("is_leap_month", if (lunar) leap else JSONObject.NULL)
        put("birth_time", time.ifBlank { null } ?: JSONObject.NULL)
        put("gender", gender.ifBlank { null } ?: JSONObject.NULL)
    }
}

internal data class Charm(
    val id: String = UUID.randomUUID().toString(),
    val date: String = LocalDate.now(seoul).toString(),
    val text: String = "천천히 말해도\n나의 진심은 닿는다",
    val pattern: String = "해", val frame: String = "두 줄", val theme: String = "cream",
    val recipient: String = "", val message: String = "", val advice: String = "", val concern: String = "",
    val luckyColor: String = "초록", val score: Int = 0, val demo: Boolean = false,
    val dayMessage: String = "", val actionAdvice: String = "", val imageUrl: String = "", val serverStored: Boolean = false,
) {
    fun json() = JSONObject().apply {
        put("id", id); put("date", date); put("text", text); put("pattern", pattern); put("frame", frame)
        put("theme", theme); put("recipient", recipient); put("message", message); put("advice", advice)
        put("luckyColor", luckyColor); put("score", score); put("demo", demo); put("concern", concern)
        put("dayMessage", dayMessage); put("actionAdvice", actionAdvice)
        // SCR 4: persist a snapshot only when the user explicitly saves this charm.
    }
    companion object {
        fun from(json: JSONObject) = Charm(
            id = json.getString("id"), date = json.getString("date"), text = json.getString("text"),
            pattern = json.optString("pattern", "해"), frame = json.optString("frame", "두 줄"),
            theme = json.optString("theme", "cream"), recipient = json.optString("recipient"),
            message = json.optString("message"), advice = json.optString("advice"), concern = json.optString("concern"), luckyColor = json.optString("luckyColor", "초록"), score = json.optInt("score"),
            demo = json.optBoolean("demo"),
            dayMessage = json.optString("dayMessage"), actionAdvice = json.optString("actionAdvice"),
        )
        fun fromServer(json: JSONObject) = Charm(id=json.getString("amulet_id"), date=json.getString("created_date"),
            text="", message=json.getString("fortune_summary"), concern=json.getString("record"),
            dayMessage=json.getString("day_message"), actionAdvice=json.getString("action_advice"),
            advice=json.getString("day_message")+"\n"+json.getString("action_advice"),
            luckyColor=json.getString("lucky_color"), imageUrl=json.optString("image_url"), serverStored=true)
    }
}

internal class CharmStore(context: Context) {
    private val encrypted = SessionStore(context, "charm-archive", "bukku-charms")
    fun read(owner: String): List<Charm> = runCatching {
        val data = encrypted.read()?.optJSONArray(owner) ?: JSONArray()
        (0 until data.length()).map { Charm.from(data.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    fun save(owner: String, values: List<Charm>) {
        val data = JSONArray().apply { values.forEach { put(it.json()) } }
        val archive = encrypted.read() ?: JSONObject()
        archive.put(owner, data); encrypted.save(archive)
    }
}

internal fun sampleFriendFortune() = JSONObject().apply {
    put("fortune_date", LocalDate.now(seoul).toString()); put("headline", "준비한 만큼 자신감이 빛나는 날")
    put("total_comment", "오늘은 익숙한 사람의 응원이 큰 힘이 되는 날이에요. 결과보다 지금까지 해온 준비를 믿을 수 있도록 따뜻한 말을 건네주세요.")
    put("total_score", 82); put("lucky_number", 3); put("lucky_color", "하늘"); put("support_action", "먼저 연락하기")
}

internal fun exampleCharms(month: YearMonth): List<Charm> = listOf(3, 7, 12, 18, 24, 27).filter { it <= month.lengthOfMonth() }.mapIndexed { index, day ->
    Charm(id = "demo-$month-$day", date = month.atDay(day).toString(),
        text = listOf("천천히 가도\n좋은 흐름은 이어진다", "작은 용기가\n행운을 데려온다", "너의 모든 준비가\n빛을 발할 거야")[index % 3],
        theme = listOf("cream", "sky", "rose")[index % 3], pattern = listOf("해", "별", "물결")[index % 3],
        message = "준비한 것을 믿고 한 걸음 내디디면 뜻밖의 도움을 만날 수 있는 날이에요.",
        advice = "완벽함보다 차분한 진심이 더 오래 기억되는 흐름이에요.",
        concern = "면접을 앞두고 준비한 말을 잘 전할 수 있을지 걱정했어요.", demo = true)
}

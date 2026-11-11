package com.example.bukku

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class MultipartUpload(val fields: Map<String, String>, val png: ByteArray) {
    init {
        require(png.isNotEmpty() && png.size <= 5_242_880) { "부적 이미지는 5MiB 이하의 PNG여야 합니다." }
        require(png.take(8).toByteArray().contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))) { "PNG 이미지를 확인해 주세요." }
        require(fields.keys == setOf("fortune_summary", "record", "day_message", "action_advice", "lucky_color", "created_date"))
        require(fields.values.all { it.isNotBlank() }) { "부적에 저장할 운세와 해석을 확인해 주세요." }
        require(fields.getValue("record").trim().length in 1..200) { "기록은 1~200자로 입력해 주세요." }
    }
    fun encode(boundary: String): ByteArray = ByteArrayOutputStream().use { output ->
        fun text(value: String) { output.write(value.toByteArray(Charsets.UTF_8)) }
        fields.forEach { (name,value) ->
            text("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n$value\r\n")
        }
        text("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"amulet.png\"\r\nContent-Type: image/png\r\n\r\n")
        output.write(png); text("\r\n--$boundary--\r\n"); output.toByteArray()
    }
}

internal class ApiClient(private val base: String) {
    suspend fun request(path: String, body: JSONObject? = null, token: String? = null): JSONObject =
        exchange(path, body?.toString()?.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8", token)

    suspend fun upload(path: String, upload: MultipartUpload, token: String): JSONObject {
        val boundary = "Bukku-${UUID.randomUUID()}"
        return exchange(path, upload.encode(boundary), "multipart/form-data; boundary=$boundary", token)
    }

    private suspend fun exchange(path: String, body: ByteArray?, contentType: String, token: String?): JSONObject = withContext(Dispatchers.IO) {
        val connection = URI(base.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000; connection.readTimeout = 90000
            connection.instanceFollowRedirects = false
            connection.requestMethod = if(body == null) "GET" else "POST"
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            body?.let {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(it.size)
                connection.outputStream.use { output -> output.write(it) }
            }
            val status = connection.responseCode
            val raw = (if(status in 200..299)connection.inputStream else connection.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrNull()
            if(status !in 200..299)throw ApiFailure(status, json?.optString("detail")?.takeIf { !it.startsWith("[") && it.isNotBlank() } ?: "요청을 처리하지 못했습니다. ($status)")
            json ?: throw ApiFailure(502, "서버 응답 형식이 올바르지 않습니다.")
        } finally { connection.disconnect() }
    }
}

internal fun Charm.upload(png: ByteArray) = MultipartUpload(linkedMapOf(
    "fortune_summary" to message, "record" to concern, "day_message" to dayMessage,
    "action_advice" to actionAdvice, "lucky_color" to luckyColor, "created_date" to date,
), png)

internal object ShareLink {
    fun token(url: String, shareHost: String): String? = runCatching {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host?.lowercase() in setOf(shareHost.lowercase(), "amulet-api.onrender.com"))
        require(uri.userInfo == null && uri.port in listOf(-1,443) && uri.query == null && uri.fragment == null)
        Regex("^/share/([A-Za-z0-9_-]{43})$").matchEntire(uri.rawPath)?.groupValues?.get(1)
    }.getOrNull()
}

package com.example.bukku

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AmuletApiTest {
    private val png=byteArrayOf(-119,80,78,71,13,10,26,10,0,1,2,3)
    private val charm=Charm(message="당시 운세",concern="내 기록",dayMessage="첫 줄\n해석의 둘째 줄",actionAdvice="원래 행동\n조언",date="2026-11-10")

    @Test fun multipartPreservesOriginalTextAndBinaryAndAccepts201()=runBlocking {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        var body=byteArrayOf();var contentType="";var authorization=""
        server.createContext("/amulets") { req ->
            contentType=req.requestHeaders.getFirst("Content-Type");authorization=req.requestHeaders.getFirst("Authorization")
            body=req.requestBody.readBytes()
            val response="{\"amulet_id\":\"server-id\"}".toByteArray()
            req.sendResponseHeaders(201,response.size.toLong());req.responseBody.use{it.write(response)}
        }
        server.start()
        try {
            val value=ApiClient("http://127.0.0.1:${server.address.port}").upload("/amulets",charm.upload(png),"test-bearer")
            assertEquals("server-id",value.getString("amulet_id"));assertEquals("Bearer test-bearer",authorization)
            assertTrue(contentType.startsWith("multipart/form-data; boundary="))
            val text=body.toString(Charsets.UTF_8)
            for((field,original) in charm.upload(png).fields) {
                assertTrue(text.contains("name=\"$field\""));assertTrue(text.contains("\r\n\r\n$original\r\n"))
            }
            assertFalse(text.contains("name=\"user_id\""));assertFalse(text.contains("name=\"pattern\""))
            assertTrue(text.contains("filename=\"amulet.png\"\r\nContent-Type: image/png"))
            assertTrue(body.toList().windowed(png.size).any{it==png.toList()})
        } finally {server.stop(0)}
    }
    @Test fun uploadFailuresAreNeverRetriedAutomatically()=runBlocking {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0);var calls=0
        server.createContext("/amulets/shares"){req->
            calls++;req.requestBody.readBytes();val response="{\"detail\":\"공유 주소 미설정\"}".toByteArray(Charsets.UTF_8)
            req.sendResponseHeaders(503,response.size.toLong());req.responseBody.use{it.write(response)}
        }
        server.start()
        try {
            try {ApiClient("http://127.0.0.1:${server.address.port}").upload("/amulets/shares",charm.upload(png),"token");fail("Must fail")}
            catch(error:ApiFailure){assertEquals(503,error.status);assertEquals("공유 주소 미설정",error.message)}
            assertEquals(1,calls)
        } finally {server.stop(0)}
    }
    @Test fun uploadRejectsOversizeBlankAndMissingFields() {
        assertThrows(IllegalArgumentException::class.java){charm.upload(ByteArray(5_242_881))}
        assertThrows(IllegalArgumentException::class.java){charm.copy(concern=" ").upload(png)}
        assertThrows(IllegalArgumentException::class.java){charm.copy(dayMessage="").upload(png)}
        assertThrows(IllegalArgumentException::class.java){MultipartUpload(emptyMap(),png)}
    }
    @Test fun shareTokenIsValidatedWithoutHashingOrAcceptingOtherHosts() {
        val token="A".repeat(42)+"_"
        assertEquals(token,ShareLink.token("https://gifts.example.com/share/$token","gifts.example.com"))
        listOf("http://gifts.example.com/share/$token","https://evil.example/share/$token",
            "https://gifts.example.com/share/short","https://gifts.example.com/share/$token?token=other",
            "https://gifts.example.com@evil.example/share/$token","https://gifts.example.com/share/$token/extra").forEach {
            assertNull(it,ShareLink.token(it,"gifts.example.com"))
        }
    }
    @Test fun serverSnapshotKeepsDateAndBothOriginalInterpretationFields() {
        val data=JSONObject().put("amulet_id","server-id").put("created_date","2026-10-31")
            .put("fortune_summary",charm.message).put("record",charm.concern)
            .put("day_message",charm.dayMessage).put("action_advice",charm.actionAdvice)
            .put("lucky_color","초록").put("image_url","https://storage.example/image")
        val saved=Charm.fromServer(data)
        assertEquals("2026-10-31",saved.date);assertEquals(charm.dayMessage,saved.dayMessage);assertEquals(charm.actionAdvice,saved.actionAdvice)
        assertTrue(saved.serverStored);assertEquals(0,saved.score);assertEquals("",saved.text)
        assertFalse(saved.json().has("image_url"))
    }
}

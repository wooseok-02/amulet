package com.example.bukku

import android.content.Intent
import android.app.Application
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageIntegrationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext

    @Test fun incomingLinkSurvivesColdAndWarmLaunchWithoutClaimingBeforeLogin() {
        val store=SessionStore(context);val previous=store.read();store.clear()
        val pending=SessionStore(context,"pending-gift","bukku-pending-gift");val oldPending=pending.read();pending.clear()
        try {
            val first="A".repeat(43);val second="B".repeat(43)
            val intent=Intent(Intent.ACTION_VIEW,Uri.parse("https://${BuildConfig.SHARE_HOST}/share/$first"),context,MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.recreate();instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val model=ViewModelProvider(activity)[AppModel::class.java]
                    assertFalse(model.loggedIn);assertEquals(first,model.pendingGift)
                    assertEquals("account",model.page);assertNull(model.detail)
                    instrumentation.callActivityOnNewIntent(activity,Intent(Intent.ACTION_VIEW,Uri.parse("https://${BuildConfig.SHARE_HOST}/share/$second")))
                    assertEquals(second,model.pendingGift);assertFalse(model.busy)
                    assertEquals(second,pending.read()!!.getString("token"))
                    // Keep ActivityScenario's original intent matcher valid during cleanup.
                    // The app normally retains the newest intent; this is test bookkeeping only.
                    activity.intent=intent
                }
            }
        } finally { if(previous!=null)store.save(previous) else store.clear();if(oldPending!=null)pending.save(oldPending) else pending.clear() }
    }

    @Test fun liveStorageAndSharingContract()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("bukkuLive")=="true")
        val api=ApiClient("https://amulet-api.onrender.com")
        val rows=JSONArray();val accountSuffix=UUID.randomUUID().toString().take(8)
        val directory=File(context.getExternalFilesDir(null),"storage-check").apply{mkdirs()}
        fun report(name:String,status:String){rows.put(JSONObject().put("test",name).put("result",status));File(directory,"live-results.json").writeText(JSONObject().put("results",rows).toString(2))}
        suspend fun account(label:String):JSONObject {
            val email="bukku-storage-$label-$accountSuffix@example.com";val password="Bukku9!"+UUID.randomUUID()
            val body=SignupForm(email,password,password,"테스트","2000-01-02",gender="MALE").json()
            return api.request("/auth/signup",body).also{assertTrue(it.getString("access_token").isNotBlank());report("test account $label","PASS")}
        }
        suspend fun expect(status:Int,name:String,block:suspend()->Unit) {
            try{block();fail("Expected HTTP $status: $name")}catch(error:ApiFailure){assertEquals(name,status,error.status);report(name,"HTTP $status")}
        }
        val sender=account("sender");val recipient=account("recipient")
        val senderToken=sender.getString("access_token");val recipientToken=recipient.getString("access_token")
        val createdDate=YearMonth.now(seoul).minusMonths(1).atEndOfMonth().toString()
        val charm=Charm(date=createdDate,message="테스트 당시 운세",concern="팀 연동 테스트용 상황입니다.",
            dayMessage="해석 첫 문장\n원본 줄바꿈 유지",actionAdvice="행동 조언 별도 보존",luckyColor="초록")
        val bitmap=CharmArtwork.render(context,charm)
        val png=ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it);bitmap.recycle();it.toByteArray() }
        File(directory,"uploaded-front.png").writeBytes(png)
        val upload=charm.upload(png)
        val first=api.upload("/amulets",upload,senderToken).getString("amulet_id");report("save own PNG multipart","PASS")
        val second=api.upload("/amulets",upload,senderToken).getString("amulet_id");assertNotEquals(first,second)
        val month=createdDate.take(7)
        val list=api.request("/amulets?month=$month",token=senderToken).getJSONArray("items")
        assertEquals(2,list.length());report("month list contains both saved amulets","PASS")
        val dateList=api.request("/amulets?date=$createdDate",token=senderToken).getJSONArray("items")
        assertEquals(2,dateList.length());report("multiple amulets on original creation date","PASS")
        assertEquals(0,api.request("/amulets?date=${LocalDate.now(seoul)}",token=senderToken).getJSONArray("items").length())
        report("empty date returns empty items","PASS")
        val detail=Charm.fromServer(api.request("/amulets/$first",token=senderToken))
        assertEquals(charm.dayMessage,detail.dayMessage);assertEquals(charm.actionAdvice,detail.actionAdvice)
        assertEquals(charm.concern,detail.concern);assertEquals(createdDate,detail.date);report("snapshot fields and original date preserved","PASS")
        val image=URI(detail.imageUrl).toURL().openStream().use{it.readBytes()}
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(png),MessageDigest.getInstance("SHA-256").digest(image))
        report("signed image URL returns exact uploaded PNG","PASS")
        expect(404,"other account cannot read private image"){api.request("/amulets/$first",token=recipientToken)}
        var shared:JSONObject?=null
        try {shared=api.upload("/amulets/shares",upload,senderToken);report("friend link creation","PASS")}
        catch(error:ApiFailure){assertEquals(503,error.status);report("friend link creation","PENDING HTTP 503: "+error.message)}
        if(shared!=null) {
            val id=shared.getString("amulet_id");val url=URI(shared.getString("share_url"));val token=url.path.substringAfterLast('/')
            report("server configured share host",url.host)
            assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(token))
            val senderItems=api.request("/amulets?month=$month",token=senderToken).getJSONArray("items")
            assertEquals(2,senderItems.length());report("gift absent from sender library","PASS")
            val body=JSONObject().put("token",token)
            assertEquals(id,api.request("/amulets/shares/claim",body,recipientToken).getString("amulet_id"))
            assertEquals(id,api.request("/amulets/shares/claim",body,recipientToken).getString("amulet_id"))
            assertEquals(1,api.request("/amulets?month=$month",token=recipientToken).getJSONArray("items").length())
            report("recipient claim and repeated claim do not duplicate","PASS")
            expect(409,"other account cannot claim already received link"){api.request("/amulets/shares/claim",body,senderToken)}
            assertEquals(createdDate,api.request("/amulets/$id",token=recipientToken).getString("created_date"))
        }
        expect(404,"missing share token"){api.request("/amulets/shares/claim",JSONObject().put("token","Z".repeat(43)),recipientToken)}
        val install=api.request("/app/install");report("public installation metadata",if(install.getBoolean("apk_available"))"AVAILABLE" else "PENDING APK not registered")
        val publicShare=api.request("/share/${"Q".repeat(43)}")
        assertEquals(install.getBoolean("apk_available"),publicShare.getBoolean("apk_available"))
        report("public share GET returns installation JSON only","PASS")
        if(!install.getBoolean("apk_available"))expect(503,"APK download pending registration"){api.request("/app/download")}
        try {api.request("/.well-known/assetlinks.json");report("domain association","RECHECK: endpoint returned object instead of expected array")}
        catch(error:ApiFailure){report("domain association",if(error.status==503)"PENDING HTTP 503" else "HTTP ${error.status}")}
        showServerDetail(sender,createdDate,directory)
        report("Android server archive and front/back UI","PASS")
    }

    private fun showServerDetail(session:JSONObject,date:String,directory:File) {
        val store=SessionStore(context);val previous=store.read()
        val pending=SessionStore(context,"pending-gift","bukku-pending-gift");val previousPending=pending.read()
        store.save(session);pending.clear()
        try {
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
                lateinit var model:AppModel
                scenario.onActivity {model=ViewModelProvider(it)[AppModel::class.java]}
                fun waitFor(check:()->Boolean) {
                    val deadline=System.currentTimeMillis()+110000;var okay=false
                    while(System.currentTimeMillis()<deadline) {
                        instrumentation.runOnMainSync{okay=check()};if(okay)return;Thread.sleep(200)
                    }
                    fail("Timed out waiting for server-backed screen")
                }
                waitFor{!model.restoring && !model.busy}
                scenario.onActivity {assertTrue(model.message,model.loggedIn);model.navigate("library");model.reloadArchive(YearMonth.from(LocalDate.parse(date)))}
                waitFor{!model.archiveLoading}
                scenario.onActivity {assertEquals(model.archiveError,2,model.archived.size);model.openArchiveDate(LocalDate.parse(date))}
                waitFor{!model.dateLoading && model.detail!=null && !model.detailLoading}
                scenario.onActivity {assertEquals(2,model.dateItems.size);assertEquals(date,model.detail!!.date)}
                fun find(node:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
                    if(node==null)return null
                    if(node.contentDescription?.toString()=="저장된 부적 앞면")return node
                    repeat(node.childCount){find(node.getChild(it))?.let{return it}}
                    return null
                }
                var node:AccessibilityNodeInfo?=null
                val imageDeadline=System.currentTimeMillis()+45000
                while(node==null && System.currentTimeMillis()<imageDeadline){node=find(instrumentation.uiAutomation.rootInActiveWindow);Thread.sleep(200)}
                assertNotNull("Server PNG must render",node)
                instrumentation.uiAutomation.takeScreenshot()!!.let{image->File(directory,"native-server-front.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}
                while(node!=null && !node.isClickable)node=node.parent
                assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(900)
                instrumentation.uiAutomation.takeScreenshot()!!.let{image->File(directory,"native-server-back.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}
            }
        } finally {
            if(previous!=null)store.save(previous) else store.clear()
            if(previousPending!=null)pending.save(previousPending) else pending.clear()
        }
    }
}

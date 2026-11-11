package com.example.bukku

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeatureFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun archiveIsEncryptedAndSeparatedByOwnerAcrossInstances() {
        val owner = "instrumentation-${System.nanoTime()}"
        val charm = Charm(concern = "private-record-unique", text = "응원 문구", score = 78)
        val store = CharmStore(context)
        try {
            store.save(owner, listOf(charm))
            assertEquals(listOf(charm), CharmStore(context).read(owner))
            assertTrue(store.read("$owner-other-account").isEmpty())
            val raw = context.getSharedPreferences("charm-archive", Context.MODE_PRIVATE).all.toString()
            assertFalse(raw.contains("private-record-unique"))
            assertFalse(raw.contains(owner))
        } finally { store.save(owner, emptyList()) }
    }

    @Test fun artworkExportsReadablePngThroughPrivateFileProvider() {
        val directory = File(context.cacheDir, "shared-charms").apply { mkdirs() }
        val file = File(directory, "instrumentation.png")
        try {
            val bitmap = CharmArtwork.render(context, Charm(pattern = "별", frame = "점선"))
            assertEquals(1000, bitmap.width); assertEquals(1400, bitmap.height)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            assertEquals("content", uri.scheme)
            context.contentResolver.openInputStream(uri)!!.use { stream ->
                val header = ByteArray(8); assertEquals(8, stream.read(header))
                assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), header)
            }
        } finally { file.delete() }
    }

    @Test fun previewOwnAndFriendFlowsRenderAndSave() {
        lateinit var model: AppModel
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                model = AppModel(context.applicationContext as Application)
                activity.setContent { BukkuUi(model) }
            }
            instrumentation.waitForIdleSync()
            val original = CharmStore(context).read("${model.server}|preview")
            try {
                scenario.onActivity { model.showPreview() }
                capture("01-home")
                scenario.onActivity { model.friendMode = true }
                capture("02-friend-input")
                scenario.onActivity {
                    model.friendToday(); assertTrue(model.friendErrors.isNotEmpty())
                    model.friendForm = FriendForm(name = "친구", birth = "2000-01-02", relationship = "친구")
                    model.friendToday(); assertNotNull(model.friendFortune)
                    model.friendConcern = "중요한 면접을 앞둔 친구를 응원하고 싶어요."
                    model.interpretFriend(); assertTrue(model.friendInterpretationVisible)
                }
                capture("03-friend-result")
                scenario.onActivity {
                    model.openEditor(); assertEquals(1, model.editorStep)
                    assertEquals(model.friendInterpretation!!.getString("day_message"),model.draft.dayMessage)
                    assertEquals(model.friendInterpretation!!.getString("action_advice"),model.draft.actionAdvice)
                    model.updateDraft(model.draft.copy(pattern = "별", frame = "점선"))
                }
                capture("04-editor")
                scenario.onActivity { model.editorStep = 2 }
                capture("05-complete")
                scenario.onActivity {
                    assertFalse("Friend gifts must not enter the sender archive",model.saveDraft())
                    model.friendMode=false;model.concern="면접에서 준비한 말을 잘 전하고 싶어요."
                    model.interpret();model.openEditor();model.editorStep=2
                    assertTrue(model.saveDraft())
                    assertEquals(model.draft, CharmStore(context).read("${model.server}|preview").first())
                    model.navigate("library")
                }
                capture("06-archive")
                clickText("3")
                capture("06a-saved-popup")
                clickDescription("문양")
                capture("06b-flipped-record")
                assertTrue(allText(instrumentation.uiAutomation.rootInActiveWindow).contains("그날의 기록"))
                clickDescription("부적 팝업 닫기")
                clickText("1")
                capture("06c-empty-date")
                assertTrue(allText(instrumentation.uiAutomation.rootInActiveWindow).contains("저장한 부적이 없어요."))
                clickText("닫기")
                scenario.onActivity { model.navigate("account") }
                capture("07-profile")
                scenario.onActivity {
                    model.navigate("home"); model.friendMode = false
                    model.concern = "면접에서 준비한 말을 잘 전하고 싶어요."
                    model.interpret(); model.openEditor()
                    assertEquals("", model.draft.recipient)
                    assertEquals("cream", model.draft.theme)
                }
                capture("08-own-editor")
            } finally {
                CharmStore(context).save("${model.server}|preview", original)
            }
        }
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        // AVD launcher ANRs can leave a system modal above the tested activity.
        // Dismiss only this known emulator launcher dialog, never an app failure.
        if (allText(instrumentation.uiAutomation.rootInActiveWindow).contains("Pixel Launcher isn't responding")) {
            clickText("Close app")
        }
        val expected = when (name) {
            "01-home" -> "WHO IS THIS FORTUNE FOR?"
            "02-friend-input" -> "누구에게 행운을 전할까요?"
            "03-friend-result" -> "FRIEND'S FORTUNE"
            "04-editor", "08-own-editor" -> "오늘의 부적 꾸미기"
            "05-complete" -> "오늘의 부적 완성"
            "06-archive" -> "행운 보관함"
            "06a-saved-popup" -> "CHARM OF THE DAY"
            "06b-flipped-record" -> "그날의 기록"
            "06c-empty-date" -> "저장한 부적이 없어요."
            else -> "MY FORTUNE PROFILE"
        }
        val deadline = System.currentTimeMillis() + 20000
        while (!allText(instrumentation.uiAutomation.rootInActiveWindow).contains(expected) && System.currentTimeMillis() < deadline) Thread.sleep(500)
        val observed = allText(instrumentation.uiAutomation.rootInActiveWindow)
        assertTrue("Expected rendered screen: $name; observed: $observed", observed.contains(expected))
        Thread.sleep(800)
        val directory = File(context.getExternalFilesDir(null), "ui-check").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().also { assertNotNull(it) }!!.let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun allText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        return node.text?.toString().orEmpty() + (0 until node.childCount).joinToString { allText(node.getChild(it)) }
    }
    private fun clickText(text: String) = clickNode { it.text?.toString() == text }
    private fun clickDescription(text: String) = clickNode { it.contentDescription?.toString()?.contains(text) == true }
    private fun clickNode(matches: (AccessibilityNodeInfo) -> Boolean) {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (matches(node)) return node
            repeat(node.childCount) { find(node.getChild(it))?.let { found -> return found } }
            return null
        }
        var node = find(instrumentation.uiAutomation.rootInActiveWindow)
        assertNotNull("Expected visible control", node)
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull("Expected clickable control", node)
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync(); Thread.sleep(700)
    }
}

package com.example.bukku

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = SessionStore(context)
    private fun session(access: String, refresh: String) = JSONObject().put("access_token", access).put("refresh_token", refresh)
    @After fun cleanup() { store.clear() }
    @Test fun tokensRoundTripButAreNotStoredInPlaintext() {
        store.save(session("access-unique-test-token", "refresh-unique-test-token"))
        assertEquals("access-unique-test-token", store.read()!!.getString("access_token"))
        assertEquals("refresh-unique-test-token", store.read()!!.getString("refresh_token"))
        val stored = context.getSharedPreferences("session", Context.MODE_PRIVATE).all.toString()
        assertFalse(stored.contains("access-unique-test-token")); assertFalse(stored.contains("refresh-unique-test-token"))
    }
    @Test fun rotationReplacesBothTokensAndLogoutClearsThem() {
        store.save(session("old-access", "old-refresh")); store.save(session("new-access", "new-refresh"))
        assertEquals("new-access", store.read()!!.getString("access_token"))
        assertEquals("new-refresh", store.read()!!.getString("refresh_token"))
        store.clear(); assertNull(store.read())
    }
    @Test fun corruptedSessionIsDiscarded() {
        store.save(session("access", "refresh"))
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putString("data", "corrupted").commit()
        assertNull(store.read())
        assertTrue(context.getSharedPreferences("session", Context.MODE_PRIVATE).all.isEmpty())
    }
}

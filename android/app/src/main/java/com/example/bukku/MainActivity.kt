package com.example.bukku

import android.os.Bundle
import android.content.Intent
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(0xFFFFF8E7.toInt(), 0xFFFFF8E7.toInt()),
            navigationBarStyle = SystemBarStyle.light(0xFFFFF8E7.toInt(), 0xFFFFF8E7.toInt()),
        )
        setContent { BukkuUi(model) }
        handleLink(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleLink(intent) }
    private fun handleLink(intent: Intent?) {
        if(intent?.action!=Intent.ACTION_VIEW)return
        val uri=intent.data ?: return
        if(BuildConfig.KAKAO_NATIVE_KEY.isNotBlank() && uri.scheme=="kakao${BuildConfig.KAKAO_NATIVE_KEY}" && uri.host=="kakaolink") {
            uri.getQueryParameter("share_url")?.let { model.receiveLink(it) }
        } else model.receiveLink(uri.toString())
    }
}

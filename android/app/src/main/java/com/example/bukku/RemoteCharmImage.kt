package com.example.bukku

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

@Composable
internal fun RemoteCharmImage(charm: Charm, reload: () -> Unit, refreshExpired: () -> Unit) {
    var loading by remember(charm.imageUrl) { mutableStateOf(true) }
    val bitmap by produceState<android.graphics.Bitmap?>(null,charm.imageUrl) {
        value=withContext(Dispatchers.IO) {
            runCatching {
                val uri=URI(charm.imageUrl); require(uri.scheme=="https" && uri.host!=null)
                val connection=uri.toURL().openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout=15000; connection.readTimeout=30000
                    val bytes=connection.inputStream.use { input ->
                        val output=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
                        while(true) { val count=input.read(buffer); if(count<0)break; output.write(buffer,0,count); require(output.size()<=5_242_880) }
                        output.toByteArray()
                    }
                    require(bytes.size<=5_242_880)
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: error("Invalid image")
                } finally {connection.disconnect()}
            }.getOrNull()
        }
        loading=false
        if(value==null) refreshExpired()
    }
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(),"저장된 부적 앞면",Modifier.fillMaxSize()) } ?: Column(horizontalAlignment=Alignment.CenterHorizontally) {
            if(loading) { CircularProgressIndicator(); Text("부적 이미지를 불러오고 있어요.") }
            else {
            Text("부적 이미지를 불러오지 못했어요.")
            TextButton(onClick=reload){Text("다시 불러오기")}
            }
        }
    }
}

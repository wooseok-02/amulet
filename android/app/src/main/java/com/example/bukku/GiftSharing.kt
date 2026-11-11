package com.example.bukku

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.kakao.sdk.common.KakaoSdk
import com.kakao.sdk.share.ShareClient
import com.kakao.sdk.share.WebSharerClient
import com.kakao.sdk.template.model.Link
import com.kakao.sdk.template.model.TextTemplate

internal object GiftSharing {
    private var initialized=false
    fun share(context: Context, url: String, failed: () -> Unit) {
        if(BuildConfig.KAKAO_NATIVE_KEY.isBlank()) { failed(); return }
        try {
            if(!initialized) { KakaoSdk.init(context.applicationContext,BuildConfig.KAKAO_NATIVE_KEY); initialized=true }
            val uri=java.net.URI(url)
            require(uri.scheme=="https" && uri.host!=null && uri.userInfo==null)
            val template=TextTemplate(text="너의 오늘을 응원하는 부적이 도착했어요. 로그인하고 선물을 받아보세요.",
                link=Link(webUrl=url,mobileWebUrl=url,androidExecutionParams=mapOf("share_url" to url)),buttonTitle="선물 받기")
            if(ShareClient.instance.isKakaoTalkSharingAvailable(context)) {
                ShareClient.instance.shareDefault(context,template) { result,error ->
                    if(error!=null || result==null)failed()
                    else runCatching { context.startActivity(result.intent) }.onFailure {failed()}
                }
            } else context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(WebSharerClient.instance.makeDefaultUrl(template).toString())))
        } catch(_:Exception) { failed() }
    }
}

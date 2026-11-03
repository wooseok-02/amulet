package com.example.bukku

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import java.io.File

internal object CharmArtwork {
    fun render(context: Context, charm: Charm): Bitmap {
        val width = 1000; val height = 1400
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val ink = android.graphics.Color.rgb(80, 56, 43)
        val background = when (charm.theme) {
            "sky" -> android.graphics.Color.rgb(237, 247, 249)
            "rose" -> android.graphics.Color.rgb(252, 238, 229)
            else -> android.graphics.Color.rgb(255, 240, 208)
        }
        canvas.drawColor(background)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = 4f }
        if (charm.frame == "점선") paint.pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
        canvas.drawRect(5f, 5f, 995f, 1395f, paint)
        paint.pathEffect = null
        if (charm.frame == "두 줄") { paint.strokeWidth = 2f; canvas.drawRect(24f, 24f, 976f, 1376f, paint) }
        paint.strokeWidth = 3f
        for ((x, y) in listOf(55f to 55f,945f to 55f,55f to 1345f,945f to 1345f)) {
            canvas.drawLine(x, y, x + if (x < 500f) 60f else -60f, y, paint)
            canvas.drawLine(x, y, x, y + if (y < 700f) 60f else -60f, paint)
        }
        paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        paint.textSize = 25f
        canvas.drawText(charm.date.replace("-", " · "), 500f, 150f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3.5f
        when (charm.pattern) {
            "해" -> {
                canvas.drawCircle(500f, 470f, 175f, paint); canvas.drawCircle(500f, 470f, 142f, paint)
                paint.style = Paint.Style.FILL; canvas.drawCircle(500f, 395f, 57f, paint)
            }
            "별" -> {
                val star = Path().apply {
                    moveTo(500f, 300f); cubicTo(540f, 415f, 555f, 430f, 670f, 470f)
                    cubicTo(555f, 510f, 540f, 525f, 500f, 640f)
                    cubicTo(460f, 525f, 445f, 510f, 330f, 470f)
                    cubicTo(445f, 430f, 460f, 415f, 500f, 300f); close()
                }
                paint.style = Paint.Style.FILL; canvas.drawPath(star, paint)
            }
            else -> repeat(3) { index ->
                val wave = Path().apply { moveTo(320f, 430f+index*60); cubicTo(425f, 350f+index*60, 575f, 500f+index*60, 680f, 420f+index*60) }
                canvas.drawPath(wave, paint)
            }
        }
        paint.style = Paint.Style.FILL; paint.typeface = context.resources.getFont(R.font.noto_serif_kr)
        paint.textSize = 58f
        var lines = charm.text.lines().flatMap { wrap(it, paint, 800f) }
        while (lines.size * paint.textSize * 1.6f > 350f && paint.textSize > 12f) {
            paint.textSize -= 2f
            lines = charm.text.lines().flatMap { wrap(it, paint, 800f) }
        }
        val lineHeight = paint.textSize * 1.6f
        val firstBaseline = 900f - (lines.size-1)*lineHeight/2
        lines.forEachIndexed { index, line -> canvas.drawText(line, 500f, firstBaseline+index*lineHeight, paint) }
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL); paint.textSize = 22f
        canvas.drawText("CALM · COURAGE · GOOD FORTUNE", 500f, 1220f, paint)
        return bitmap
    }
    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>(); var current = ""
        text.codePoints().toArray().forEach { code ->
            val next = String(Character.toChars(code))
            if (current.isNotEmpty() && paint.measureText(current+next) > maxWidth) { lines += current; current = next }
            else current += next
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }
    fun share(context: Context, charm: Charm) {
        val folder = File(context.cacheDir, "shared-charms").apply { mkdirs() }
        val file = File(folder, "charm-${charm.id}.png")
        file.outputStream().use { render(context, charm).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("부적", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, if (charm.recipient.isBlank()) "행운 나누기" else "${charm.recipient}에게 행운 전하기"))
    }
}

@Composable
internal fun CharmPreview(charm: Charm, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap = remember(charm) { CharmArtwork.render(context, charm) }
    Image(bitmap.asImageBitmap(), "${charm.pattern} 문양 ${charm.frame} 부적: ${charm.text.replace('\n', ' ')}", modifier)
}

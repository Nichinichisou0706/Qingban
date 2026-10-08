package io.github.ming.qingban.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

data class ParsedDocument(val title: String, val text: String, val method: String)
class DocumentImporter(private val context: Context) {
    suspend fun read(uri: Uri): ParsedDocument = withContext(Dispatchers.IO) {
        val title = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if(it.moveToFirst()) it.getString(0) else null } ?: "导入资料"
        val mime = context.contentResolver.getType(uri).orEmpty()
        val file = File.createTempFile("document-", ".tmp", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0L
                while(true) { val count = input.read(buffer); if(count < 0) break; total += count; require(total <= 32_000_000) { "单份文件最多 32 MB" }; output.write(buffer, 0, count) }
            } } ?: error("无法读取文件")
            val method: String
            val text = when {
                mime == "application/pdf" || title.endsWith(".pdf", true) -> {
                    PDFBoxResourceLoader.init(context)
                    val pages = PDDocument.load(file).use { doc ->
                        require(doc.numberOfPages <= 100) { "PDF 最多 100 页" }; require(!doc.isEncrypted) { "请先解除 PDF 密码" }
                        val stripper=PDFTextStripper(); var total=0
                        (1..doc.numberOfPages).map { page -> stripper.startPage=page; stripper.endPage=page; stripper.getText(doc).also { total+=it.length; require(total<=250_000) { "PDF 正文过长" } } }
                    }
                    if(pages.all { it.trim().length>=20 }) { method = "PDF 文本"; pages.joinToString("\n") } else { method = "PDF 文本及 OCR"; pdfOcr(file,pages) }
                }
                mime.startsWith("image/") || title.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp") -> { method = "图片 OCR"; imageOcr(file) }
                title.endsWith(".docx", true) || mime.contains("wordprocessingml") -> { method = "Word 文本"; docx(file) }
                else -> { method = "UTF-8 文本"; val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT); file.inputStream().use { java.io.InputStreamReader(it,decoder).use { reader -> reader.readText() } }.removePrefix("\uFEFF") }
            }
            require(text.isNotBlank()) { "没有识别到文字，请换一份清晰资料" }
            require(text.length <= 250_000 && '\u0000' !in text) { "正文最多 25 万字；请选择文本、PDF、DOCX 或图片" }
            ParsedDocument(title, text, method)
        } finally { file.delete() }
    }
    private fun docx(file: File): String = ZipFile(file).use { zip ->
        val entry = zip.getEntry("word/document.xml") ?: error("不是有效的 DOCX 文档")
        require(entry.size in 1..8_000_000) { "Word 正文过大" }
        val builder = StringBuilder()
        zip.getInputStream(entry).use { input ->
            val parser = Xml.newPullParser(); parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true); parser.setInput(input, "UTF-8")
            var event = parser.eventType
            while(event != XmlPullParser.END_DOCUMENT) {
                if(event == XmlPullParser.START_TAG && parser.name == "t") builder.append(parser.nextText())
                else if(event == XmlPullParser.END_TAG && parser.name in setOf("p", "tr")) builder.append('\n')
                else if(event == XmlPullParser.START_TAG && parser.name == "tab") builder.append('\t')
                require(builder.length <= 250_000) { "Word 正文最多 25 万字" }; event = parser.next()
            }
        }; builder.toString()
    }
    private fun recognize(image: InputImage, bitmap: Bitmap): String {
        val recognizer = try { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) } catch(e: Exception) { bitmap.recycle(); throw e }
        val task=try { recognizer.process(image) } catch(e: Exception) { recognizer.close(); bitmap.recycle(); throw e }
        var releaseLater=false
        try { return Tasks.await(task,90,TimeUnit.SECONDS).text }
        catch(e: Exception) {
            if(!task.isComplete) { releaseLater=true; task.addOnCompleteListener(java.util.concurrent.Executor { it.run() }) { recognizer.close(); bitmap.recycle() } }
            throw e
        } finally { if(!releaseLater) { recognizer.close(); bitmap.recycle() } }
    }
    private fun imageOcr(file: File): String {
        val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }; android.graphics.BitmapFactory.decodeFile(file.absolutePath,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0) { "无法解码图片" }
        var sample=1; while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2048) sample*=2
        val bitmap=android.graphics.BitmapFactory.decodeFile(file.absolutePath,android.graphics.BitmapFactory.Options().apply { inSampleSize=sample }) ?: error("无法解码图片")
        return recognize(InputImage.fromBitmap(bitmap,0),bitmap)
    }
    private fun pdfOcr(file: File, texts: List<String>): String = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            require(texts.count { it.trim().length<20 } <= 30) { "单份 PDF 需要 OCR 的页面最多 30 页" }
            buildString {
                for(index in 0 until renderer.pageCount) {
                    if(texts[index].trim().length>=20) { append(texts[index]); append('\n'); continue }
                    renderer.openPage(index).use { page ->
                    val scale = minOf(2f, 1800f / maxOf(page.width, page.height))
                    val bitmap = Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1), (page.height*scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    try { bitmap.eraseColor(Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } catch(e: Exception) { bitmap.recycle(); throw e }
                    append("第 ${index+1} 页\n"); append(recognize(InputImage.fromBitmap(bitmap, 0),bitmap)); append('\n'); require(length <= 250_000) { "识别文字过多" }
                }
                }
            }
        }
    }
}

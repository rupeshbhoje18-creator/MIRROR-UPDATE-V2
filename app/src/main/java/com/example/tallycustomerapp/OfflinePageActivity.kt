package com.example.tallycustomerapp.offline

import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.tallycustomerapp.data.AppDatabase
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class OfflinePageActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        webView.settings.javaScriptEnabled = false
        webView.settings.domStorageEnabled = false
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        setContentView(webView)

        val pageId = intent.getLongExtra(EXTRA_PAGE_ID, -1L)
        if (pageId <= 0L) {
            Toast.makeText(this, "Offline page not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Thread {
            val page = runCatching { AppDatabase.getDatabase(this).companyDao().getPage(pageId) }.getOrNull()
            runOnUiThread {
                if (page == null) {
                    Toast.makeText(this, "Offline page not found", Toast.LENGTH_SHORT).show()
                    finish()
                    return@runOnUiThread
                }
                title = page.title
                val html = runCatching { ungzip(page.htmlGzip) }.getOrElse {
                    Toast.makeText(this, "Saved page is corrupt", Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }
                webView.loadDataWithBaseURL(
                    page.url,
                    html,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        }.start()
    }

    private fun ungzip(data: ByteArray): String {
        return GZIPInputStream(ByteArrayInputStream(data)).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    companion object {
        const val EXTRA_PAGE_ID = "page_id"
    }
}

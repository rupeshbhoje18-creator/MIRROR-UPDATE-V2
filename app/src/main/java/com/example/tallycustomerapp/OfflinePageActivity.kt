package com.example.tallycustomerapp.offline

import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.tallycustomerapp.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            Toast.makeText(
                this,
                "Offline page not found",
                Toast.LENGTH_SHORT
            ).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val page = runCatching {
                withContext(Dispatchers.IO) {
                    AppDatabase.getDatabase(this@OfflinePageActivity)
                        .companyDao()
                        .getPage(pageId)
                }
            }.getOrNull()

            if (page == null) {
                Toast.makeText(
                    this@OfflinePageActivity,
                    "Offline page not found",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                return@launch
            }

            title = page.title

            val html = runCatching {
                withContext(Dispatchers.IO) {
                    ungzip(page.htmlGzip)
                }
            }.getOrElse {
                Toast.makeText(
                    this@OfflinePageActivity,
                    "Saved page is corrupt",
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }

            webView.loadDataWithBaseURL(
                page.url,
                html,
                "text/html",
                "UTF-8",
                null
            )
        }
    }

    private fun ungzip(data: ByteArray): String {
        return GZIPInputStream(
            ByteArrayInputStream(data)
        ).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    companion object {
        const val EXTRA_PAGE_ID = "page_id"
    }
}

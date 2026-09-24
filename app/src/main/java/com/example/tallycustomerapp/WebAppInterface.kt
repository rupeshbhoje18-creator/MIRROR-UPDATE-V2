package com.example.tallycustomerapp.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.room.withTransaction
import com.example.tallycustomerapp.data.AppDatabase
import com.example.tallycustomerapp.data.CompanyEntity
import com.example.tallycustomerapp.data.LedgerEntity
import com.example.tallycustomerapp.data.StockItemEntity
import com.example.tallycustomerapp.data.VoucherEntity
import com.example.tallycustomerapp.data.VoucherEntryInput
import com.google.gson.Gson
import com.google.gson.JsonParseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.GZIPOutputStream
import java.lang.ref.WeakReference
import java.net.URI
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class WebAppInterface(
    private val context: Context,
    private val db: AppDatabase,
    onNavigate: (String) -> Unit
) {
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val onNavigateCallback = onNavigate
    private val pageBuffers = ConcurrentHashMap<String, StringBuilder>()
    private val queueLock = Any()
    private val pageQueue = ArrayDeque<String>()
    private val queuedOrVisited = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private var webViewRef: WeakReference<WebView>? = null
    private var navigating = false
    private var mirrorStopped = false
    private var activeCompanyName = ""
    private var activeSerialNumber = ""
    private var activeCompanyKey = ""
    private var pagesVisited = 0
    private var pagesSaved = 0
    private var maxPages = 2000

    fun attachWebView(webView: WebView) {
        webViewRef = WeakReference(webView)
    }

    @JavascriptInterface
    fun getActiveCompanyName(): String = activeCompanyName

    @JavascriptInterface
    fun getActiveSerialNumber(): String = activeSerialNumber

    @JavascriptInterface
    fun setActiveCompany(companyName: String, serialNumber: String) {
        val name = companyName.trim()
        val serial = serialNumber.trim()
        if (name.isBlank()) return
        val key = name.lowercase()
        synchronized(queueLock) {
            if (activeCompanyKey.isNotBlank() && activeCompanyKey != key) {
                pageQueue.clear()
                queuedOrVisited.clear()
                navigating = false
                pagesVisited = 0
                pagesSaved = 0
            }
            activeCompanyName = name
            activeSerialNumber = if (serial.isBlank()) "WEB-${name.hashCode()}" else serial
            activeCompanyKey = key
            mirrorStopped = false
        }
    }

    @JavascriptInterface
    fun setMaxMirrorPages(value: Int) {
        maxPages = value.coerceIn(100, 10000)
    }

    @JavascriptInterface
    fun beginPageSnapshot(snapshotId: String, companyName: String, serialNumber: String, url: String, title: String) {
        if (snapshotId.isBlank()) return
        if (companyName.isNotBlank()) setActiveCompany(companyName, serialNumber)
        pageBuffers[snapshotId] = StringBuilder()
    }

    @JavascriptInterface
    fun pushPageSnapshotChunk(snapshotId: String, chunk: String) {
        pageBuffers[snapshotId]?.append(chunk)
    }

    @JavascriptInterface
    fun commitPageSnapshot(snapshotId: String, companyName: String, serialNumber: String, url: String, title: String) {
        val raw = pageBuffers.remove(snapshotId)?.toString() ?: return
        val cleanUrl = normalizeUrl(url)
        if (cleanUrl.isBlank() || !isAllowedPageUrl(cleanUrl)) {
            completePageAndNavigate(cleanUrl)
            return
        }
        val company = CompanyEntity(
            companyName = companyName.ifBlank { activeCompanyName }.trim(),
            serialNumber = serialNumber.ifBlank { activeSerialNumber }.trim(),
            lastSynced = System.currentTimeMillis()
        )
        if (company.companyName.isBlank() || company.serialNumber.isBlank()) {
            completePageAndNavigate(cleanUrl)
            return
        }
        val compressed = gzip(raw)
        scope.launch {
            runCatching {
                db.withTransaction {
                    db.offlineDao().savePageSnapshot(
                        company = company,
                        url = cleanUrl,
                        title = title.ifBlank { cleanUrl },
                        htmlGzip = compressed
                    )
                }
            }.onSuccess {
                pagesSaved++
            }.onFailure { error ->
                postToast("Offline page save failed: ${error.message ?: "database error"}")
            }.also {
                pagesVisited++
                completePageAndNavigate(cleanUrl)
            }
        }
    }

    @JavascriptInterface
    fun enqueueDiscoveredUrls(companyName: String, serialNumber: String, urlsJson: String) {
        if (companyName.isNotBlank()) setActiveCompany(companyName, serialNumber)
        val urls = runCatching { gson.fromJson(urlsJson, Array<String>::class.java)?.toList().orEmpty() }
            .getOrDefault(emptyList())
        synchronized(queueLock) {
            if (mirrorStopped) return
            for (url in urls) {
                val normalized = normalizeUrl(url)
                if (!isAllowedPageUrl(normalized)) continue
                if (normalized == currentWebUrl()) continue
                if (queuedOrVisited.size >= maxPages) break
                if (queuedOrVisited.add(normalized)) pageQueue.addLast(normalized)
            }
        }
    }

    @JavascriptInterface
    fun requestNextMirrorPage() {
        mainHandler.post { navigateNextIfIdle() }
    }

    @JavascriptInterface
    fun stopWholeCompanyMirror() {
        synchronized(queueLock) {
            mirrorStopped = true
            pageQueue.clear()
            navigating = false
        }
        webViewRef?.get()?.stopLoading()
        postToast("Whole company offline save stopped")
    }

    @JavascriptInterface
    fun getMirrorProgress(): String = synchronized(queueLock) {
        gson.toJson(
            mapOf(
                "company" to activeCompanyName,
                "saved" to pagesSaved,
                "visited" to pagesVisited,
                "queued" to pageQueue.size,
                "stopped" to mirrorStopped
            )
        )
    }

    @JavascriptInterface
    fun beginSync(syncId: String) {
        // Backward-compatible structured-data sync entry point.
        if (syncId.isBlank()) return
    }

    @JavascriptInterface
    fun pushSyncChunk(syncId: String, chunk: String) {
        // Kept for compatibility with older builds; whole-page mirroring is primary.
    }

    @JavascriptInterface
    fun commitSync(syncId: String) {
        // Kept for compatibility with older builds.
    }

    @JavascriptInterface
    fun saveCompanyDataOffline(dataJson: String) {
        // Older structured payload compatibility. The current app saves complete HTML page snapshots.
        try {
            val payload = gson.fromJson(dataJson, CompanySyncPayload::class.java)
                ?: throw JsonParseException("Empty sync payload")
            require(payload.companyName.isNotBlank()) { "Company name is missing" }
            require(payload.serialNumber.isNotBlank()) { "Serial number is missing" }
            scope.launch {
                runCatching {
                    val company = CompanyEntity(
                        companyName = payload.companyName.trim(),
                        serialNumber = payload.serialNumber.trim(),
                        gstin = payload.gstin?.trim()?.takeIf { it.isNotEmpty() },
                        financialYearFrom = payload.financialYearFrom,
                        lastSynced = System.currentTimeMillis()
                    )
                    val ledgers = payload.ledgers.map {
                        LedgerEntity(0, 0, it.guid.ifBlank { "ledger:${it.name}" }, it.name.trim(), it.parent, it.openingBalance, it.closingBalance, it.alteredOn)
                    }
                    val vouchers = payload.vouchers.map {
                        VoucherEntity(0, 0, it.guid.ifBlank { "voucher:${it.date}:${it.voucherNumber.orEmpty()}:${it.voucherType}:${it.partyName.orEmpty()}" }, it.date, it.voucherType, it.voucherNumber, it.partyName, it.amount, it.narration, it.alteredOn)
                    }
                    val entries = payload.vouchers.associate { voucher ->
                        voucher.guid to voucher.entries.map { entry ->
                            VoucherEntryInput(entry.ledgerGuid, entry.ledgerName, entry.amount, entry.isDeemedPositive)
                        }
                    }
                    val stocks = payload.stockItems.map {
                        StockItemEntity(0, 0, it.guid.ifBlank { "stock:${it.name}" }, it.name.trim(), it.parent, it.unit, it.openingQty, it.closingQty, it.openingValue, it.closingValue, it.closingRate, it.alteredOn)
                    }
                    db.withTransaction {
                        db.offlineDao().saveCollectedData(company, ledgers, vouchers, entries, stocks, payload.replaceAll, payload.collectedSections.toSet())
                    }
                }.onSuccess { postToast("Tally structured data saved offline") }
                    .onFailure { postToast("Offline save failed: ${it.message ?: "database error"}") }
            }
        } catch (e: Exception) {
            postToast("Invalid sync data: ${e.message ?: "invalid JSON"}")
        }
    }

    private fun completePageAndNavigate(url: String) {
        mainHandler.postDelayed {
            synchronized(queueLock) { navigating = false }
            navigateNextIfIdle()
        }, 450L)
    }

    private fun navigateNextIfIdle() {
        val next = synchronized(queueLock) {
            if (mirrorStopped || navigating) return
            if (pageQueue.isEmpty()) return
            if (pagesVisited >= maxPages) {
                mirrorStopped = true
                return
            }
            navigating = true
            pageQueue.removeFirstOrNullCompat()
        } ?: return

        val webView = webViewRef?.get()
        if (webView == null) {
            synchronized(queueLock) { navigating = false; pageQueue.addFirst(next) }
            return
        }
        webView.post { onNavigateCallback(next) }
    }

    private fun currentWebUrl(): String = webViewRef?.get()?.url?.let(::normalizeUrl).orEmpty()

    private fun postToast(message: String) {
        mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    private fun normalizeUrl(raw: String): String {
        return runCatching {
            val uri = URI(raw.trim())
            if (uri.scheme == null || uri.host == null) return@runCatching ""
            uri.normalize().toString().removeSuffix("#")
        }.getOrDefault("")
    }

    private fun isAllowedPageUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return runCatching {
            val uri = URI(url)
            val host = uri.host?.lowercase() ?: return@runCatching false
            if (host != "customer.tallysolutions.com") return@runCatching false
            val path = uri.path.orEmpty().lowercase()
            if (!path.startsWith("/customerapp")) return@runCatching false
            val blocked = Regex("/(logout|signout|signin|login|delete|remove|create|new|edit|save|submit)(/|$)")
            !blocked.containsMatchIn(path)
        }.getOrDefault(false)
    }

    private fun <T> ArrayDeque<T>.removeFirstOrNullCompat(): T? = if (isEmpty()) null else removeFirst()

    data class CompanySyncPayload(
        val companyName: String,
        val serialNumber: String,
        val gstin: String? = null,
        val financialYearFrom: String? = null,
        val replaceAll: Boolean = false,
        val collectedSections: List<String> = emptyList(),
        val ledgers: List<LedgerPayload> = emptyList(),
        val vouchers: List<VoucherPayload> = emptyList(),
        val stockItems: List<StockPayload> = emptyList()
    )
    data class LedgerPayload(val guid: String = "", val name: String, val parent: String? = null, val openingBalance: Double = 0.0, val closingBalance: Double = 0.0, val alteredOn: Long? = null)
    data class VoucherPayload(val guid: String = "", val date: String, val voucherType: String, val voucherNumber: String? = null, val partyName: String? = null, val amount: Double = 0.0, val narration: String? = null, val alteredOn: Long? = null, val entries: List<VoucherEntryPayload> = emptyList())
    data class VoucherEntryPayload(val ledgerGuid: String? = null, val ledgerName: String, val amount: Double = 0.0, val isDeemedPositive: Boolean = false)
    data class StockPayload(val guid: String = "", val name: String, val parent: String? = null, val unit: String? = null, val openingQty: Double = 0.0, val closingQty: Double = 0.0, val openingValue: Double = 0.0, val closingValue: Double = 0.0, val closingRate: Double = 0.0, val alteredOn: Long? = null)
}

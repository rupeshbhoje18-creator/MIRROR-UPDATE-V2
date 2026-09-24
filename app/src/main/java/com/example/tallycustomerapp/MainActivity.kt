package com.example.tallycustomerapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.tallycustomerapp.data.AppDatabase
import com.example.tallycustomerapp.data.CompanySummary
import com.example.tallycustomerapp.data.PageSnapshotEntity
import com.example.tallycustomerapp.databinding.ActivityMainBinding
import com.example.tallycustomerapp.databinding.FragmentOfflineBinding
import com.example.tallycustomerapp.databinding.ItemCompanyBinding
import com.example.tallycustomerapp.offline.OfflinePageActivity
import com.example.tallycustomerapp.offline.OfflineViewModel
import com.example.tallycustomerapp.offline.OfflineViewModelFactory
import com.example.tallycustomerapp.web.SyncCollector
import com.example.tallycustomerapp.web.WebAppInterface
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val db by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupNavigation()
    }

    private fun setupNavigation() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, LivePortalFragment())
            .commit()
        binding.bottomNav.setOnItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.tab_live -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, LivePortalFragment())
                        .commit()
                    true
                }
                R.id.tab_offline -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, OfflineFragment())
                        .commit()
                    true
                }
                else -> false
            }
        }
    }

    class LivePortalFragment : Fragment() {
        private lateinit var db: AppDatabase
        private lateinit var webView: WebView
        private lateinit var bridge: WebAppInterface

        @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
        override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
        ): View {
            val view = inflater.inflate(R.layout.fragment_live, container, false)
            webView = view.findViewById(R.id.webView)
            db = AppDatabase.getDatabase(requireContext())

            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.setSupportMultipleWindows(false)
            webView.settings.userAgentString = webView.settings.userAgentString + " AndroidNativeWebView"

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)
            restoreCookies(requireContext(), cookieManager)

            bridge = WebAppInterface(requireContext(), db) { url ->
                webView.loadUrl(url)
            }
            bridge.attachWebView(webView)
            webView.addJavascriptInterface(bridge, "AndroidBridge")

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    saveCookies(requireContext(), cookieManager)
                    webView.evaluateJavascript(SyncCollector.script(), null)
                }
            }

            webView.loadUrl("https://customer.tallysolutions.com/customerapp/")
            return view
        }

        override fun onDestroyView() {
            runCatching { bridge.stopWholeCompanyMirror() }
            runCatching { webView.removeJavascriptInterface("AndroidBridge") }
            super.onDestroyView()
        }

        private fun saveCookies(context: Context, cookieManager: CookieManager) {
            val cookies = cookieManager.getCookie("https://customer.tallysolutions.com/customerapp/")
            context.getSharedPreferences("cookies", Context.MODE_PRIVATE).edit()
                .putString("tally_cookies", cookies)
                .apply()
        }

        private fun restoreCookies(context: Context, cookieManager: CookieManager) {
            val cookies = context.getSharedPreferences("cookies", Context.MODE_PRIVATE)
                .getString("tally_cookies", null)
            if (!cookies.isNullOrEmpty()) {
                cookieManager.setCookie("https://customer.tallysolutions.com/customerapp/", cookies)
                CookieManager.getInstance().flush()
            }
        }
    }

    class OfflineFragment : Fragment() {
        private var _binding: FragmentOfflineBinding? = null
        private val binding get() = _binding!!
        private lateinit var adapter: CompanyAdapter
        private lateinit var viewModel: OfflineViewModel
        private lateinit var db: AppDatabase

        override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
        ): View {
            _binding = FragmentOfflineBinding.inflate(inflater, container, false)
            db = AppDatabase.getDatabase(requireContext())
            viewModel = ViewModelProvider(this, OfflineViewModelFactory(db.companyDao()))
                .get(OfflineViewModel::class.java)
            setupRecyclerView()
            observeViewModel()
            return binding.root
        }

        override fun onResume() {
            super.onResume()
            viewModel.loadCompanies()
        }

        private fun setupRecyclerView() {
            adapter = CompanyAdapter { company ->
                showCompanyPages(company)
            }
            binding.recyclerOffline.layoutManager = LinearLayoutManager(requireContext())
            binding.recyclerOffline.adapter = adapter
        }

        private fun observeViewModel() {
            viewModel.companiesLiveData.observe(viewLifecycleOwner) { companies ->
                adapter.submitList(companies)
                binding.textEmpty.visibility = if (companies.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        private fun showCompanyPages(company: CompanySummary) {
            viewLifecycleOwner.lifecycleScope.launch {
                val pages = runCatching { db.companyDao().getPages(company.id) }.getOrDefault(emptyList())
                if (pages.isEmpty()) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(company.companyName)
                        .setMessage("No company pages have been saved yet.")
                        .setPositiveButton("Close", null)
                        .show()
                    return@launch
                }
                val labels = pages.mapIndexed { index, page ->
                    "${index + 1}. ${page.title.ifBlank { page.url }}\n${page.url}"
                }.toTypedArray()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("${company.companyName}\n${pages.size} pages saved")
                    .setItems(labels) { _, which ->
                        startActivity(Intent(requireContext(), OfflinePageActivity::class.java).apply {
                            putExtra(OfflinePageActivity.EXTRA_PAGE_ID, pages[which].id)
                        })
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }

        override fun onDestroyView() {
            super.onDestroyView()
            _binding = null
        }
    }

    class CompanyAdapter(
        private val onItemClick: (CompanySummary) -> Unit
    ) : ListAdapter<CompanySummary, CompanyAdapter.VH>(
        object : DiffUtil.ItemCallback<CompanySummary>() {
            override fun areItemsTheSame(old: CompanySummary, new: CompanySummary) = old.id == new.id
            override fun areContentsTheSame(old: CompanySummary, new: CompanySummary) = old == new
        }
    ) {
        inner class VH(val binding: ItemCompanyBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(ItemCompanyBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val company = getItem(position)
            holder.binding.textCompanyName.text = company.companyName
            holder.binding.textSerialNumber.text = "Serial No: ${company.serialNumber}"
            holder.binding.textLastSynced.text = "Pages saved: ${company.pageCount}   •   Synced: ${android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", company.lastSynced)}"
            holder.binding.root.setOnClickListener { onItemClick(company) }
        }
    }
}

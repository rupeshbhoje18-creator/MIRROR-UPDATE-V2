package com.example.tallycustomerapp.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.tallycustomerapp.data.CompanyDao

class OfflineViewModelFactory(private val companyDao: CompanyDao) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OfflineViewModel::class.java)) {
            return OfflineViewModel(companyDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

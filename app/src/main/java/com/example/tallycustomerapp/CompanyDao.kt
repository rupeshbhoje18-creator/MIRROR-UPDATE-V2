package com.example.tallycustomerapp.data

import androidx.room.Dao
import androidx.room.Query

@Dao
interface CompanyDao {
    @Query("SELECT c.*, COUNT(p.id) AS pageCount FROM companies c LEFT JOIN page_snapshots p ON p.companyId = c.id GROUP BY c.id ORDER BY c.lastSynced DESC")
    suspend fun getAllCompanies(): List<CompanySummary>

    @Query("SELECT * FROM page_snapshots WHERE companyId = :companyId ORDER BY lastCaptured DESC, id DESC")
    suspend fun getPages(companyId: Long): List<PageSnapshotEntity>

    @Query("SELECT * FROM page_snapshots WHERE id = :pageId LIMIT 1")
    suspend fun getPage(pageId: Long): PageSnapshotEntity?
}

data class CompanySummary(
    val id: Long,
    val companyName: String,
    val serialNumber: String,
    val gstin: String?,
    val financialYearFrom: String?,
    val lastSynced: Long,
    val pageCount: Long
)

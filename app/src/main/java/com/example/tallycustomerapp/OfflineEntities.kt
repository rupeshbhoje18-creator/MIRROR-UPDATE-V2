package com.example.tallycustomerapp.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "companies",
    indices = [Index(value = ["serialNumber"], unique = true)]
)
data class CompanyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companyName: String,
    val serialNumber: String,
    val gstin: String? = null,
    val financialYearFrom: String? = null,
    val lastSynced: Long = 0L
)

@Entity(
    tableName = "ledgers",
    foreignKeys = [
        ForeignKey(
            entity = CompanyEntity::class,
            parentColumns = ["id"],
            childColumns = ["companyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("companyId"),
        Index(value = ["companyId", "guid"], unique = true),
        Index(value = ["companyId", "name"])
    ]
)
data class LedgerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companyId: Long,
    val guid: String,
    val name: String,
    val parent: String? = null,
    val openingBalance: Double = 0.0,
    val closingBalance: Double = 0.0,
    val alteredOn: Long? = null
)

@Entity(
    tableName = "vouchers",
    foreignKeys = [
        ForeignKey(
            entity = CompanyEntity::class,
            parentColumns = ["id"],
            childColumns = ["companyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("companyId"),
        Index(value = ["companyId", "guid"], unique = true),
        Index(value = ["companyId", "date"])
    ]
)
data class VoucherEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companyId: Long,
    val guid: String,
    val date: String,
    val voucherType: String,
    val voucherNumber: String? = null,
    val partyName: String? = null,
    val amount: Double = 0.0,
    val narration: String? = null,
    val alteredOn: Long? = null
)

@Entity(
    tableName = "voucher_entries",
    foreignKeys = [
        ForeignKey(
            entity = VoucherEntity::class,
            parentColumns = ["id"],
            childColumns = ["voucherId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("voucherId"), Index("ledgerGuid")]
)
data class VoucherEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val voucherId: Long,
    val ledgerGuid: String? = null,
    val ledgerName: String,
    val amount: Double = 0.0,
    val isDeemedPositive: Boolean = false
)

@Entity(
    tableName = "stock_items",
    foreignKeys = [
        ForeignKey(
            entity = CompanyEntity::class,
            parentColumns = ["id"],
            childColumns = ["companyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("companyId"),
        Index(value = ["companyId", "guid"], unique = true),
        Index(value = ["companyId", "name"])
    ]
)
data class StockItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companyId: Long,
    val guid: String,
    val name: String,
    val parent: String? = null,
    val unit: String? = null,
    val openingQty: Double = 0.0,
    val closingQty: Double = 0.0,
    val openingValue: Double = 0.0,
    val closingValue: Double = 0.0,
    val closingRate: Double = 0.0,
    val alteredOn: Long? = null
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val companyId: Long,
    val lastAttempt: Long = 0L,
    val lastSuccess: Long? = null,
    val status: String = "NEVER_SYNCED",
    val message: String? = null
)

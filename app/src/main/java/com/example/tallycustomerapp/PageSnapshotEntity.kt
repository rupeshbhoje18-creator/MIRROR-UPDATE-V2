package com.example.tallycustomerapp.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "page_snapshots",
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
        Index(value = ["companyId", "url"], unique = true),
        Index(value = ["companyId", "lastCaptured"])
    ]
)
data class PageSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companyId: Long,
    val url: String,
    val title: String,
    val htmlGzip: ByteArray,
    val lastCaptured: Long = System.currentTimeMillis()
)

package com.example.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room database entity mirroring the Supabase 'attendance_records' table.
 * Designed for local-first storage of attendance logs and offline synchronization
 * via Android WorkManager once network connectivity is restored.
 */
@Entity(
    tableName = "attendance_records",
    indices = [
        Index(value = ["employee_id", "sync_status"]),
        Index(value = ["client_event_id"], unique = true),
        Index(value = ["created_at_epoch_ms"])
    ]
)
data class AttendanceRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "client_event_id")
    val clientEventId: String,

    @ColumnInfo(name = "employee_id")
    val employeeId: String,

    @ColumnInfo(name = "employee_name")
    val employeeName: String = "",

    @ColumnInfo(name = "project_id")
    val projectId: String = "",

    @ColumnInfo(name = "project_name")
    val projectName: String = "",

    @ColumnInfo(name = "shift_date")
    val shiftDate: String,

    @ColumnInfo(name = "action")
    val action: String, // "CLOCK_IN" | "CLOCK_OUT"

    @ColumnInfo(name = "device_timestamp")
    val deviceTimestamp: String,

    @ColumnInfo(name = "clock_in_time")
    val clockInTime: String? = null,

    @ColumnInfo(name = "clock_out_time")
    val clockOutTime: String? = null,

    @ColumnInfo(name = "total_worked_minutes")
    val totalWorkedMinutes: Int? = null,

    @ColumnInfo(name = "latitude")
    val latitude: Double? = null,

    @ColumnInfo(name = "longitude")
    val longitude: Double? = null,

    @ColumnInfo(name = "gps_accuracy_meters")
    val gpsAccuracyMeters: Float? = null,

    @ColumnInfo(name = "is_mock_location")
    val isMockLocation: Boolean = false,

    @ColumnInfo(name = "geofence_status")
    val geofenceStatus: String? = null,

    @ColumnInfo(name = "distance_from_project_meters")
    val distanceFromProjectMeters: Double? = null,

    @ColumnInfo(name = "selfie_local_path")
    val selfieLocalPath: String? = null,

    @ColumnInfo(name = "selfie_url")
    val selfieUrl: String? = null,

    @ColumnInfo(name = "facial_metadata_json")
    val facialMetadataJson: String? = null,

    @ColumnInfo(name = "verification_status")
    val verificationStatus: String = "VERIFIED",

    @ColumnInfo(name = "compliance_flag")
    val complianceFlag: String = "COMPLIANT",

    @ColumnInfo(name = "status")
    val status: String = "PENDING_APPROVAL", // "ACTIVE", "PENDING_APPROVAL", "APPROVED", "REJECTED", "COMPLETED"

    @ColumnInfo(name = "reviewed_by")
    val reviewedBy: String? = null,

    @ColumnInfo(name = "reviewed_at")
    val reviewedAt: String? = null,

    @ColumnInfo(name = "review_comment")
    val reviewComment: String? = null,

    @ColumnInfo(name = "sync_status")
    val syncStatus: String = "PENDING_SYNC", // "PENDING_SYNC", "SYNCING", "SYNCED", "FAILED"

    @ColumnInfo(name = "sync_attempts")
    val syncAttempts: Int = 0,

    @ColumnInfo(name = "last_sync_error")
    val lastSyncError: String? = null,

    @ColumnInfo(name = "next_retry_at_epoch_ms")
    val nextRetryAtEpochMs: Long = 0L,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "synced_at_epoch_ms")
    val syncedAtEpochMs: Long? = null
)

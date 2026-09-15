package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.entity.AttendanceRecordEntity
import com.example.data.sync.OfflineAttendanceRecordSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * Repository providing a clean API for queueing and managing offline attendance logs
 * stored in the Room 'attendance_records' table (mirroring Supabase) and synchronized
 * via Android WorkManager.
 */
class OfflineAttendanceRepository(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.getInstance(context)
) {
    private val dao = database.attendanceRecordDao()

    companion object {
        private const val TAG = "OfflineAttendanceRepo"
    }

    /**
     * Records an attendance punch (clock-in or clock-out) into the local Room database
     * and immediately schedules a WorkManager task to synchronize with Supabase once network
     * connectivity is restored.
     */
    suspend fun queueAttendanceLog(
        clientEventId: String = UUID.randomUUID().toString(),
        employeeId: String,
        employeeName: String = "",
        projectId: String = "",
        projectName: String = "",
        shiftDate: String,
        action: String, // "CLOCK_IN" or "CLOCK_OUT"
        deviceTimestamp: String,
        latitude: Double? = null,
        longitude: Double? = null,
        gpsAccuracyMeters: Float? = null,
        isMockLocation: Boolean = false,
        geofenceStatus: String? = null,
        distanceFromProjectMeters: Double? = null,
        selfieTempPath: String? = null,
        facialMetadataJson: String? = null
    ): AttendanceRecordEntity = withContext(Dispatchers.IO) {
        // Step 1: Persist selfie to durable internal app storage if provided
        val durableSelfiePath = selfieTempPath?.let { copySelfieToDurableStorage(it, clientEventId) }

        val record = AttendanceRecordEntity(
            id = UUID.randomUUID().toString(),
            clientEventId = clientEventId,
            employeeId = employeeId,
            employeeName = employeeName,
            projectId = projectId,
            projectName = projectName,
            shiftDate = shiftDate,
            action = action.uppercase(),
            deviceTimestamp = deviceTimestamp,
            latitude = latitude,
            longitude = longitude,
            gpsAccuracyMeters = gpsAccuracyMeters,
            isMockLocation = isMockLocation,
            geofenceStatus = geofenceStatus,
            distanceFromProjectMeters = distanceFromProjectMeters,
            selfieLocalPath = durableSelfiePath,
            facialMetadataJson = facialMetadataJson,
            verificationStatus = "VERIFIED",
            complianceFlag = if (isMockLocation) "FLAGGED_MOCK_LOCATION" else "COMPLIANT",
            status = "PENDING_APPROVAL",
            syncStatus = "PENDING_SYNC",
            syncAttempts = 0,
            createdAtEpochMs = System.currentTimeMillis()
        )

        // Step 2: Insert into local Room database
        dao.insertRecord(record)
        Log.i(TAG, "Queued offline attendance record ${record.id} ($action) in 'attendance_records'.")

        // Step 3: Trigger WorkManager task with NetworkType.CONNECTED constraint
        OfflineAttendanceRecordSyncWorker.enqueueWork(context)

        record
    }

    /**
     * Observes unsynced attendance record count reactively for UI status badges/banners.
     */
    fun observeUnsyncedCount(): Flow<Int> = dao.observeUnsyncedCount()

    /**
     * Observes unsynced attendance record count for a specific employee.
     */
    fun observeUnsyncedCountForEmployee(employeeId: String): Flow<Int> =
        dao.observeUnsyncedCountForEmployee(employeeId)

    /**
     * Observes all attendance records for an employee ordered chronologically.
     */
    fun observeAttendanceRecordsForEmployee(employeeId: String): Flow<List<AttendanceRecordEntity>> =
        dao.observeRecordsForEmployee(employeeId)

    /**
     * Observes all pending/unsynced attendance records.
     */
    fun observeUnsyncedRecords(): Flow<List<AttendanceRecordEntity>> =
        dao.observeUnsyncedRecords()

    /**
     * Manually triggers immediate synchronization via WorkManager.
     */
    fun triggerSyncNow() {
        OfflineAttendanceRecordSyncWorker.enqueueWork(context)
    }

    /**
     * Copies captured biometric selfie to internal app storage so it survives process restarts
     * until Supabase Storage upload succeeds.
     */
    private fun copySelfieToDurableStorage(sourcePath: String, clientEventId: String): String? {
        return try {
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists() || sourceFile.length() == 0L) return null

            val targetDir = File(context.filesDir, "pending_attendance_records").apply { mkdirs() }
            val targetFile = File(targetDir, "selfie_${clientEventId}.jpg")

            FileInputStream(sourceFile).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            targetFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed copying selfie to durable storage: ${e.message}", e)
            sourcePath
        }
    }
}

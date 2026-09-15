package com.example.data.dao

import androidx.room.*
import com.example.data.entity.AttendanceRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the 'attendance_records' table mirroring Supabase.
 */
@Dao
interface AttendanceRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: AttendanceRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecords(records: List<AttendanceRecordEntity>)

    @Update
    suspend fun updateRecord(record: AttendanceRecordEntity)

    @Query("SELECT * FROM attendance_records WHERE id = :id LIMIT 1")
    suspend fun getRecordById(id: String): AttendanceRecordEntity?

    @Query("SELECT * FROM attendance_records WHERE client_event_id = :clientEventId LIMIT 1")
    suspend fun getRecordByClientEventId(clientEventId: String): AttendanceRecordEntity?

    @Query("SELECT * FROM attendance_records WHERE sync_status != 'SYNCED' ORDER BY created_at_epoch_ms ASC")
    suspend fun getAllUnsyncedRecords(): List<AttendanceRecordEntity>

    @Query("SELECT * FROM attendance_records WHERE employee_id = :employeeId AND sync_status != 'SYNCED' ORDER BY created_at_epoch_ms ASC")
    suspend fun getUnsyncedRecordsForEmployee(employeeId: String): List<AttendanceRecordEntity>

    @Query("SELECT * FROM attendance_records WHERE employee_id = :employeeId ORDER BY created_at_epoch_ms DESC")
    fun observeRecordsForEmployee(employeeId: String): Flow<List<AttendanceRecordEntity>>

    @Query("SELECT * FROM attendance_records ORDER BY created_at_epoch_ms DESC")
    fun observeAllRecords(): Flow<List<AttendanceRecordEntity>>

    @Query("SELECT * FROM attendance_records WHERE sync_status != 'SYNCED' ORDER BY created_at_epoch_ms ASC")
    fun observeUnsyncedRecords(): Flow<List<AttendanceRecordEntity>>

    @Query("SELECT COUNT(*) FROM attendance_records WHERE sync_status != 'SYNCED'")
    fun observeUnsyncedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM attendance_records WHERE employee_id = :employeeId AND sync_status != 'SYNCED'")
    fun observeUnsyncedCountForEmployee(employeeId: String): Flow<Int>

    @Query("UPDATE attendance_records SET sync_status = 'SYNCED', synced_at_epoch_ms = :syncedAt, selfie_url = COALESCE(:selfieUrl, selfie_url), last_sync_error = NULL WHERE id = :id")
    suspend fun markAsSynced(id: String, syncedAt: Long, selfieUrl: String? = null)

    @Query("UPDATE attendance_records SET sync_status = :status, sync_attempts = :attempts, last_sync_error = :error, next_retry_at_epoch_ms = :nextRetry WHERE id = :id")
    suspend fun updateSyncFailure(id: String, status: String, attempts: Int, error: String?, nextRetry: Long)

    @Query("DELETE FROM attendance_records WHERE id = :id")
    suspend fun deleteRecordById(id: String)

    @Query("DELETE FROM attendance_records WHERE sync_status = 'SYNCED' AND synced_at_epoch_ms < :beforeEpochMs")
    suspend fun pruneSyncedRecords(beforeEpochMs: Long)
}

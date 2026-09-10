package com.example

import com.example.network.AttendanceVerificationEntry
import com.example.network.FacialMetadataDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttendanceVerificationEntryTest {

    @Test
    fun testStorageUrlSerializationPreservesUrlAndOmitBase64() {
        val entry = AttendanceVerificationEntry(
            id = "test-entry-1",
            clientEventId = "VERIF_12345",
            employeeId = "EMP-001",
            employeeName = "Ahmed Al-Balushi",
            projectId = "PRJ-01",
            projectName = "Muscat Site",
            verificationType = "ATTENDANCE_VERIFICATION",
            deviceTimestamp = "2026-09-10T08:00:00Z",
            selfieUrl = "https://jpsiafvbyupofnbqonkq.supabase.co/storage/v1/object/public/attendance-selfies/selfie_123.jpg",
            selfieBase64 = null,
            facialMetadata = FacialMetadataDto(
                faceDetected = true,
                confidence = 0.98f,
                complianceStatus = "VERIFIED",
                complianceReason = "Optimal pose and lighting"
            )
        )

        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(AttendanceVerificationEntry::class.java)
        val json = adapter.toJson(entry)

        assertTrue(json.contains("\"selfie_url\":\"https://jpsiafvbyupofnbqonkq.supabase.co/storage/v1/object/public/attendance-selfies/selfie_123.jpg\""))
        
        val parsed = adapter.fromJson(json)
        assertEquals("test-entry-1", parsed?.id)
        assertEquals("https://jpsiafvbyupofnbqonkq.supabase.co/storage/v1/object/public/attendance-selfies/selfie_123.jpg", parsed?.selfieUrl)
        assertNull(parsed?.selfieBase64)
    }
}

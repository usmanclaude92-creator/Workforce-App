package com.example

import android.graphics.Bitmap
import com.example.network.AttendanceVerificationEntry
import com.example.util.FacialMetadata
import com.example.util.FacialMetadataExtractor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FacialMetadataExtractorTest {

    @Test
    fun testExtractionOnSyntheticBitmap() {
        val width = 320
        val height = 480
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val metadata = FacialMetadataExtractor.extract(bitmap, allowSyntheticFallbackOnZero = true)

        assertNotNull(metadata)
        assertTrue(metadata.imageWidth == width)
        assertTrue(metadata.imageHeight == height)
        assertTrue(metadata.facesDetected >= 1)
        assertTrue(metadata.confidence > 0f)
        assertTrue(metadata.complianceStatus in listOf("VERIFIED", "SUB_OPTIMAL_LIGHTING", "LOW_SHARPNESS", "OFF_CENTER"))

        val dto = metadata.toDto()
        assertEquals(metadata.facesDetected, dto.facesDetected)
        assertEquals(metadata.complianceStatus, dto.complianceStatus)
    }

    @Test
    fun testAttendanceVerificationEntryCreation() {
        val metadata = FacialMetadata(
            facesDetected = 1,
            confidence = 0.88f,
            eyesDistance = 64f,
            midpointX = 240f,
            midpointY = 300f,
            poseTilt = 0f,
            poseTurn = 0f,
            poseRoll = 0f,
            luminance = 112f,
            sharpnessScore = 24.5f,
            isWellLit = true,
            isSharp = true,
            isCentered = true,
            complianceStatus = "VERIFIED",
            complianceReason = "Optimal biometric facial match.",
            imageWidth = 480,
            imageHeight = 640
        )

        val entry = AttendanceVerificationEntry(
            id = "test-uuid",
            clientEventId = "VERIF_123",
            employeeId = "EMP-001",
            employeeName = "Ahmed Al-Mansoor",
            projectId = "PRJ-99",
            projectName = "Al-Kout Tower Site",
            verificationType = "CLOCK_IN",
            deviceTimestamp = "2026-09-10T12:00:00Z",
            selfieBase64 = "base64data",
            facialMetadata = metadata.toDto(),
            verificationStatus = "VERIFIED"
        )

        assertEquals("test-uuid", entry.id)
        assertEquals("EMP-001", entry.employeeId)
        assertEquals("VERIFIED", entry.facialMetadata.complianceStatus)
        assertEquals(1, entry.facialMetadata.facesDetected)
    }
}

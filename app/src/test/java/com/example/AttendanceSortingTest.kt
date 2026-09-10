package com.example

import com.example.network.AttendanceEventSummary
import com.example.network.AttendanceShiftDto
import org.junit.Assert.assertEquals
import org.junit.Test

class AttendanceSortingTest {

    private fun parseShiftInstantMs(shift: AttendanceShiftDto): Long {
        val raw = shift.clockIn?.serverTimestamp ?: shift.clockOut?.serverTimestamp
        if (!raw.isNullOrBlank()) {
            try {
                return java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
            } catch (_: Exception) {}
            try {
                return java.time.Instant.parse(raw).toEpochMilli()
            } catch (_: Exception) {}
        }
        return try {
            java.time.LocalDate.parse(shift.shiftDate)
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    @Test
    fun testLatestAttendanceSortedFirst() {
        val olderShift = AttendanceShiftDto(
            id = "shift-1",
            employeeId = "EMP-1",
            projectId = "PRJ-1",
            shiftDate = "2026-09-10",
            clockInEventId = "evt-1",
            status = "COMPLETED",
            complianceFlag = "OK",
            clockIn = AttendanceEventSummary(serverTimestamp = "2026-09-10T05:30:18Z"),
            clockOut = AttendanceEventSummary(serverTimestamp = "2026-09-10T05:47:06Z")
        )

        val latestActiveShift = AttendanceShiftDto(
            id = "shift-2",
            employeeId = "EMP-1",
            projectId = "PRJ-1",
            shiftDate = "2026-09-10",
            clockInEventId = "evt-2",
            status = "OPEN",
            complianceFlag = "OK",
            clockIn = AttendanceEventSummary(serverTimestamp = "2026-09-10T08:32:53Z")
        )

        val list = listOf(olderShift, latestActiveShift)

        val sorted = list.sortedWith(
            compareByDescending<AttendanceShiftDto> {
                if (it.status == "OPEN") 1 else 0
            }.thenByDescending {
                parseShiftInstantMs(it)
            }.thenByDescending {
                it.shiftDate
            }
        )

        assertEquals("shift-2", sorted[0].id)
        assertEquals("shift-1", sorted[1].id)
    }

    @Test
    fun testCompletedShiftsSameDayOrderedByTime() {
        val morningShift = AttendanceShiftDto(
            id = "morning",
            employeeId = "EMP-1",
            projectId = "PRJ-1",
            shiftDate = "2026-09-10",
            clockInEventId = "evt-1",
            status = "COMPLETED",
            complianceFlag = "OK",
            clockIn = AttendanceEventSummary(serverTimestamp = "2026-09-10T06:00:00Z")
        )

        val afternoonShift = AttendanceShiftDto(
            id = "afternoon",
            employeeId = "EMP-1",
            projectId = "PRJ-1",
            shiftDate = "2026-09-10",
            clockInEventId = "evt-2",
            status = "COMPLETED",
            complianceFlag = "OK",
            clockIn = AttendanceEventSummary(serverTimestamp = "2026-09-10T14:30:00Z")
        )

        val list = listOf(morningShift, afternoonShift)
        val sorted = list.sortedWith(
            compareByDescending<AttendanceShiftDto> {
                if (it.status == "OPEN") 1 else 0
            }.thenByDescending {
                parseShiftInstantMs(it)
            }.thenByDescending {
                it.shiftDate
            }
        )

        assertEquals("afternoon", sorted[0].id)
        assertEquals("morning", sorted[1].id)
    }
}

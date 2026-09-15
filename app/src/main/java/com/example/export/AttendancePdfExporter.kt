package com.example.export

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.data.entity.AttendanceEntity
import com.example.data.entity.UserEntity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Native PDF Exporter for Workforce Attendance Records.
 * Uses Android's built-in PdfDocument to generate vector-crisp, printable, multi-page PDF documents.
 */
object AttendancePdfExporter {

    private const val TAG = "AttendancePdfExporter"

    // Standard A4 dimensions in PostScript points (72 points per inch)
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842

    data class ExportResult(
        val file: File,
        val fileName: String,
        val fileSizeFormatted: String,
        val totalRecords: Int,
        val totalHoursFormatted: String,
        val approvedCount: Int,
        val pendingCount: Int,
        val rejectedCount: Int,
        val monthLabel: String
    )

    /**
     * Generates a monthly attendance PDF report.
     *
     * @param context Application context
     * @param selectedMonth Month string in "YYYY-MM" format (e.g. "2026-09") or "ALL"
     * @param attendanceList List of attendance records to export
     * @param supervisor Current supervisor generating the report
     * @param projectFilter Optional filter for specific project/site
     * @param statusFilter Optional filter for status (ALL, APPROVED, PENDING, REJECTED)
     */
    fun generateMonthlyAttendancePdf(
        context: Context,
        selectedMonth: String,
        attendanceList: List<AttendanceEntity>,
        supervisor: UserEntity?,
        projectFilter: String = "ALL",
        statusFilter: String = "ALL"
    ): Result<ExportResult> {
        return try {
            // 1. Filter attendance records
            val filteredRecords = attendanceList.filter { record ->
                val monthMatch = if (selectedMonth == "ALL") true else record.shiftDate.startsWith(selectedMonth)
                val projectMatch = if (projectFilter == "ALL") true else (record.projectName.equals(projectFilter, ignoreCase = true) || record.projectId == projectFilter)
                val statusMatch = when (statusFilter) {
                    "ALL" -> true
                    "APPROVED" -> record.state == "APPROVED"
                    "PENDING" -> record.state == "PENDING_APPROVAL"
                    "REJECTED" -> record.state == "REJECTED"
                    else -> record.state.equals(statusFilter, ignoreCase = true)
                }
                monthMatch && projectMatch && statusMatch
            }.sortedByDescending { it.shiftDate }

            val totalRecords = filteredRecords.size
            val totalMinutes = filteredRecords.sumOf { it.totalWorkedMinutes }
            val totalHours = totalMinutes / 60.0
            val totalHoursFormatted = String.format(Locale.US, "%.1f hrs", totalHours)
            val approvedCount = filteredRecords.count { it.state == "APPROVED" }
            val pendingCount = filteredRecords.count { it.state == "PENDING_APPROVAL" }
            val rejectedCount = filteredRecords.count { it.state == "REJECTED" }

            val monthLabel = if (selectedMonth == "ALL") {
                "All Recorded Months"
            } else {
                try {
                    val inputFormat = SimpleDateFormat("yyyy-MM", Locale.US)
                    val outputFormat = SimpleDateFormat("MMMM yyyy", Locale.US)
                    val date = inputFormat.parse(selectedMonth)
                    if (date != null) outputFormat.format(date) else selectedMonth
                } catch (e: Exception) {
                    selectedMonth
                }
            }

            // 2. Prepare Paints & Fonts
            val primaryColor = Color.rgb(24, 62, 120) // Sophisticated Deep Royal Blue
            val secondaryColor = Color.rgb(44, 98, 178)
            val accentGold = Color.rgb(212, 143, 24)
            val textDark = Color.rgb(30, 36, 48)
            val textMuted = Color.rgb(105, 115, 134)
            val bgLight = Color.rgb(246, 248, 252)
            val borderLight = Color.rgb(220, 226, 238)
            val successColor = Color.rgb(22, 140, 78)
            val warningColor = Color.rgb(217, 119, 6)
            val errorColor = Color.rgb(220, 38, 38)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            val pdfDocument = PdfDocument()

            // Calculate pagination
            val marginHorizontal = 36f
            val contentWidth = PAGE_WIDTH - (marginHorizontal * 2)
            val headerHeight = 180f
            val tableHeaderHeight = 24f
            val rowHeight = 36f
            val footerHeight = 40f

            val usableHeightFirstPage = PAGE_HEIGHT - headerHeight - tableHeaderHeight - footerHeight
            val rowsPerFirstPage = (usableHeightFirstPage / rowHeight).toInt().coerceAtLeast(1)
            val usableHeightOtherPages = PAGE_HEIGHT - 60f - tableHeaderHeight - footerHeight
            val rowsPerOtherPage = (usableHeightOtherPages / rowHeight).toInt().coerceAtLeast(1)

            val totalPages = if (totalRecords <= rowsPerFirstPage) {
                1
            } else {
                1 + Math.ceil((totalRecords - rowsPerFirstPage).toDouble() / rowsPerOtherPage).toInt()
            }

            var currentRecordIndex = 0
            val generatedDateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

            for (pageNumber in 1..totalPages) {
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                // Draw Page Background
                paint.color = Color.WHITE
                paint.style = Paint.Style.FILL
                canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT.toFloat(), paint)

                var currentY = 0f

                if (pageNumber == 1) {
                    // ---- PAGE 1 HEADER & BRANDING ----
                    // Top Accent Banner
                    paint.color = primaryColor
                    canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 55f, paint)

                    // Top Bar Logo & Brand Name
                    paint.color = Color.WHITE
                    paint.textSize = 15f
                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    canvas.drawText("ARTIFY WORKFORCE MANAGEMENT", marginHorizontal, 33f, paint)

                    paint.textSize = 9f
                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    paint.color = Color.rgb(200, 220, 255)
                    canvas.drawText("OFFICIAL COMPLIANCE & ATTENDANCE RECORD", PAGE_WIDTH - marginHorizontal - 210f, 33f, paint)

                    currentY = 72f

                    // Document Title
                    paint.color = textDark
                    paint.textSize = 16f
                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    canvas.drawText("Monthly Attendance Report: $monthLabel", marginHorizontal, currentY, paint)

                    currentY += 16f
                    paint.color = textMuted
                    paint.textSize = 8.5f
                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    val supervisorInfo = supervisor?.let { "${it.fullName} (${it.employeeId})" } ?: "Site Supervisor"
                    canvas.drawText("Generated by: $supervisorInfo  •  Date: $generatedDateStr UTC  •  Filter: Status: $statusFilter, Site: $projectFilter", marginHorizontal, currentY, paint)

                    currentY += 16f

                    // ---- KPI METRIC BOXES ----
                    val cardWidth = (contentWidth - 24f) / 4f
                    val cardHeight = 44f

                    val metrics = listOf(
                        Triple("TOTAL SHIFTS", "$totalRecords Records", primaryColor),
                        Triple("HOURS WORKED", totalHoursFormatted, accentGold),
                        Triple("APPROVED", "$approvedCount Shifts", successColor),
                        Triple("PENDING REVIEW", "$pendingCount Shifts", if (pendingCount > 0) warningColor else textMuted)
                    )

                    for (i in metrics.indices) {
                        val cardX = marginHorizontal + (i * (cardWidth + 8f))
                        val cardRect = RectF(cardX, currentY, cardX + cardWidth, currentY + cardHeight)

                        // Box background
                        paint.color = bgLight
                        paint.style = Paint.Style.FILL
                        canvas.drawRoundRect(cardRect, 6f, 6f, paint)

                        // Box border
                        paint.color = borderLight
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = 1f
                        canvas.drawRoundRect(cardRect, 6f, 6f, paint)

                        // Metric Label
                        paint.style = Paint.Style.FILL
                        paint.color = textMuted
                        paint.textSize = 7.5f
                        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        canvas.drawText(metrics[i].first, cardX + 8f, currentY + 14f, paint)

                        // Metric Value
                        paint.color = metrics[i].third
                        paint.textSize = 11f
                        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        canvas.drawText(metrics[i].second, cardX + 8f, currentY + 32f, paint)
                    }

                    currentY += cardHeight + 18f

                } else {
                    // ---- SUBSEQUENT PAGES COMPACT HEADER ----
                    paint.color = primaryColor
                    canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 28f, paint)

                    paint.color = Color.WHITE
                    paint.textSize = 10f
                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    canvas.drawText("ARTIFY WORKFORCE REPORT  •  $monthLabel (Cont.)", marginHorizontal, 18f, paint)

                    currentY = 48f
                }

                // ---- TABLE HEADERS ----
                val tableTop = currentY
                paint.color = primaryColor
                paint.style = Paint.Style.FILL
                val headerRect = RectF(marginHorizontal, tableTop, marginHorizontal + contentWidth, tableTop + tableHeaderHeight)
                canvas.drawRoundRect(headerRect, 4f, 4f, paint)

                paint.color = Color.WHITE
                paint.textSize = 8f
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

                val colDateX = marginHorizontal + 8f
                val colEmpX = marginHorizontal + 65f
                val colSiteX = marginHorizontal + 185f
                val colTimeX = marginHorizontal + 285f
                val colDurationX = marginHorizontal + 360f
                val colVerifyX = marginHorizontal + 410f
                val colStatusX = marginHorizontal + 470f

                val headerTextY = tableTop + 15f
                canvas.drawText("DATE", colDateX, headerTextY, paint)
                canvas.drawText("EMPLOYEE", colEmpX, headerTextY, paint)
                canvas.drawText("SITE / PROJECT", colSiteX, headerTextY, paint)
                canvas.drawText("CLOCK IN - OUT", colTimeX, headerTextY, paint)
                canvas.drawText("DURATION", colDurationX, headerTextY, paint)
                canvas.drawText("VERIFIED", colVerifyX, headerTextY, paint)
                canvas.drawText("STATUS", colStatusX, headerTextY, paint)

                currentY += tableHeaderHeight

                // ---- TABLE ROWS ----
                val maxRowsOnThisPage = if (pageNumber == 1) rowsPerFirstPage else rowsPerOtherPage
                var rowsRendered = 0

                if (filteredRecords.isEmpty()) {
                    paint.color = textMuted
                    paint.textSize = 11f
                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    canvas.drawText("No attendance records found matching the selected month and filters.", marginHorizontal + 10f, currentY + 30f, paint)
                }

                while (currentRecordIndex < filteredRecords.size && rowsRendered < maxRowsOnThisPage) {
                    val record = filteredRecords[currentRecordIndex]
                    val rowTop = currentY
                    val rowBottom = currentY + rowHeight

                    // Zebra stripe row background
                    if (rowsRendered % 2 == 1) {
                        paint.color = bgLight
                        paint.style = Paint.Style.FILL
                        canvas.drawRect(marginHorizontal, rowTop, marginHorizontal + contentWidth, rowBottom, paint)
                    }

                    // Row bottom divider
                    paint.color = borderLight
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 0.5f
                    canvas.drawLine(marginHorizontal, rowBottom, marginHorizontal + contentWidth, rowBottom, paint)

                    paint.style = Paint.Style.FILL

                    // 1. Date
                    paint.color = textDark
                    paint.textSize = 8f
                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    canvas.drawText(record.shiftDate, colDateX, rowTop + 16f, paint)

                    // 2. Employee Name & ID
                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    paint.textSize = 8.5f
                    val empNameTrunc = if (record.employeeName.length > 20) record.employeeName.take(18) + "..." else record.employeeName
                    canvas.drawText(empNameTrunc, colEmpX, rowTop + 14f, paint)

                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    paint.color = textMuted
                    paint.textSize = 7.5f
                    canvas.drawText("ID: ${record.employeeId}", colEmpX, rowTop + 26f, paint)

                    // 3. Site / Project Name
                    paint.color = textDark
                    paint.textSize = 8f
                    val projectNameTrunc = if (record.projectName.length > 18) record.projectName.take(16) + "..." else record.projectName
                    canvas.drawText(projectNameTrunc, colSiteX, rowTop + 16f, paint)

                    // 4. Clock In - Out
                    paint.color = textDark
                    paint.textSize = 7.5f
                    val inTime = record.startTimeFormatted ?: "--:--"
                    val outTime = record.endTimeFormatted ?: "Working"
                    canvas.drawText("$inTime - $outTime", colTimeX, rowTop + 16f, paint)

                    // 5. Duration
                    val hours = record.totalWorkedMinutes / 60
                    val mins = record.totalWorkedMinutes % 60
                    val durStr = if (record.totalWorkedMinutes > 0) "${hours}h ${mins}m" else "Active"
                    paint.color = textDark
                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    canvas.drawText(durStr, colDurationX, rowTop + 16f, paint)

                    // 6. Verification Badges (GPS & Selfie)
                    val hasGps = record.startGeofenceStatus == "INSIDE_GEOFENCE" || record.startLatitude != null
                    val hasSelfie = !record.startSelfieData.isNullOrBlank()
                    paint.textSize = 7f
                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    paint.color = if (hasGps && hasSelfie) successColor else accentGold
                    val verifyText = if (hasGps && hasSelfie) "GPS+Photo" else if (hasGps) "GPS only" else "Standard"
                    canvas.drawText(verifyText, colVerifyX, rowTop + 16f, paint)

                    // 7. Status Pill / Badge
                    val statusColor = when (record.state) {
                        "APPROVED" -> successColor
                        "PENDING_APPROVAL" -> warningColor
                        "REJECTED" -> errorColor
                        else -> primaryColor
                    }
                    val statusBg = when (record.state) {
                        "APPROVED" -> Color.rgb(232, 248, 238)
                        "PENDING_APPROVAL" -> Color.rgb(254, 243, 199)
                        "REJECTED" -> Color.rgb(254, 226, 226)
                        else -> bgLight
                    }

                    val pillRect = RectF(colStatusX, rowTop + 7f, colStatusX + 48f, rowTop + 23f)
                    paint.color = statusBg
                    paint.style = Paint.Style.FILL
                    canvas.drawRoundRect(pillRect, 4f, 4f, paint)

                    paint.color = statusColor
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 0.5f
                    canvas.drawRoundRect(pillRect, 4f, 4f, paint)

                    paint.style = Paint.Style.FILL
                    paint.textSize = 6.5f
                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    val displayStatus = when (record.state) {
                        "APPROVED" -> "APPROVED"
                        "PENDING_APPROVAL" -> "PENDING"
                        "REJECTED" -> "REJECTED"
                        else -> record.state.take(7)
                    }
                    canvas.drawText(displayStatus, colStatusX + 5f, rowTop + 18f, paint)

                    currentY += rowHeight
                    currentRecordIndex++
                    rowsRendered++
                }

                // ---- FOOTER ----
                val footerY = PAGE_HEIGHT - 24f
                paint.color = borderLight
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 0.5f
                canvas.drawLine(marginHorizontal, footerY - 12f, marginHorizontal + contentWidth, footerY - 12f, paint)

                paint.style = Paint.Style.FILL
                paint.color = textMuted
                paint.textSize = 7.5f
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                canvas.drawText("Artify Workforce Management  •  Confidential Payroll & Attendance Document", marginHorizontal, footerY, paint)

                val pageStr = "Page $pageNumber of $totalPages"
                val pageStrWidth = paint.measureText(pageStr)
                canvas.drawText(pageStr, marginHorizontal + contentWidth - pageStrWidth, footerY, paint)

                pdfDocument.finishPage(page)
            }

            // 3. Save PDF File to App Cache/Documents Directory
            val exportsDir = File(context.cacheDir, "reports").apply {
                if (!exists()) mkdirs()
            }

            val sanitizedMonth = selectedMonth.replace("-", "_")
            val fileName = "Artify_Attendance_Report_${sanitizedMonth}.pdf"
            val targetFile = File(exportsDir, fileName)

            val outputStream = FileOutputStream(targetFile)
            pdfDocument.writeTo(outputStream)
            outputStream.flush()
            outputStream.close()
            pdfDocument.close()

            val fileSizeKb = targetFile.length() / 1024
            val fileSizeFormatted = if (fileSizeKb > 1024) "${String.format(Locale.US, "%.1f", fileSizeKb / 1024.0)} MB" else "$fileSizeKb KB"

            Log.i(TAG, "Successfully generated PDF attendance report: ${targetFile.absolutePath} ($fileSizeFormatted)")

            Result.success(
                ExportResult(
                    file = targetFile,
                    fileName = fileName,
                    fileSizeFormatted = fileSizeFormatted,
                    totalRecords = totalRecords,
                    totalHoursFormatted = totalHoursFormatted,
                    approvedCount = approvedCount,
                    pendingCount = pendingCount,
                    rejectedCount = rejectedCount,
                    monthLabel = monthLabel
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate attendance PDF: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Opens the generated PDF in the system default PDF Viewer / Reader.
     */
    fun openPdfFile(context: Context, file: File) {
        try {
            val uri = getUriForFile(context, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                clipData = android.content.ClipData.newRawUri("Attendance Report", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Open Attendance Report").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening PDF file: ${e.message}")
            Toast.makeText(context, "No PDF viewer app found on device.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Shares the generated PDF file via Android system share sheet (Email, WhatsApp, Drive, etc.).
     */
    fun sharePdfFile(context: Context, file: File, subject: String = "Artify Monthly Attendance Report") {
        try {
            val uri = getUriForFile(context, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, "Attached is the monthly workforce attendance and compliance report generated from Artify Workforce Management.")
                clipData = android.content.ClipData.newRawUri("Attendance Report", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Share Attendance PDF").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing PDF file: ${e.message}")
            Toast.makeText(context, "Unable to share PDF: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getUriForFile(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }
}

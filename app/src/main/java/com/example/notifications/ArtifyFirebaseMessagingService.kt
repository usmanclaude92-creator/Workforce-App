package com.example.notifications

import android.util.Log
import com.example.data.AppDatabase
import com.example.data.entity.LeaveRequestEntity
import com.example.data.entity.NotificationEntity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Service that receives Firebase Cloud Messaging push events and payloads.
 * Handles background & foreground notifications, especially routing leave requests to Supervisors.
 */
class ArtifyFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "Incoming FCM message from: ${remoteMessage.from}")

        // 1. Parse notification and data payload
        var title = remoteMessage.notification?.title
        var body = remoteMessage.notification?.body
        val data = remoteMessage.data

        if (data.isNotEmpty()) {
            Log.d(TAG, "FCM data payload: $data")
            if (title.isNullOrBlank()) {
                title = data["title"] ?: "Artify Workforce Alert"
            }
            if (body.isNullOrBlank()) {
                body = data["body"] ?: data["message"] ?: "New workforce notification received."
            }
        }

        val type = data["type"] ?: "SYSTEM"
        val isSupervisorLeaveAlert = type == "LEAVE_REQUEST" ||
            data["recipientId"] == "ALL_SUPERVISORS" ||
            remoteMessage.from?.contains("all_supervisors") == true ||
            data["fcmTopic"] == "all_supervisors" ||
            title?.contains("Leave Request", ignoreCase = true) == true

        val isShiftOrScheduleAlert = type == "SHIFT_ASSIGNMENT" ||
            type == "SCHEDULE_CHANGE" ||
            type == "SHIFT" ||
            remoteMessage.from?.contains("shifts_schedules") == true ||
            data["fcmTopic"]?.contains("shifts_schedules") == true ||
            title?.contains("Shift", ignoreCase = true) == true ||
            title?.contains("Schedule", ignoreCase = true) == true

        if (title.isNullOrBlank()) {
            title = when {
                isSupervisorLeaveAlert -> "🔔 New Leave Request"
                type == "SCHEDULE_CHANGE" -> "⚠️ Schedule Change Alert"
                isShiftOrScheduleAlert -> "📅 New Shift Assignment"
                else -> "Workforce Update"
            }
        }
        if (body.isNullOrBlank()) {
            body = "You have an updated workforce notification."
        }

        val rawNotifId = data["notificationId"] ?: ("NOTIF-FCM-" + UUID.randomUUID().toString().take(8))
        val notificationIntId = rawNotifId.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }

        // 2. Persist to Room local database so it displays in notification drawer / badge counters
        val recipientId = data["recipientId"] ?: (if (isSupervisorLeaveAlert) "ALL_SUPERVISORS" else data["employeeId"] ?: "ALL")
        val localType = when {
            isSupervisorLeaveAlert -> "LEAVE"
            isShiftOrScheduleAlert -> "SHIFT"
            else -> type
        }

        serviceScope.launch {
            try {
                val db = AppDatabase.getInstance(applicationContext)
                
                // Store Notification Record
                db.notificationDao().insertNotification(
                    NotificationEntity(
                        notificationId = rawNotifId,
                        recipientId = recipientId,
                        title = title ?: "Notification",
                        message = body ?: "",
                        type = localType,
                        timestampUtc = System.currentTimeMillis(),
                        isRead = false
                    )
                )
                Log.d(TAG, "Saved FCM notification into local Room DB (Recipient: $recipientId, Type: $localType)")

                // If this is a new leave request payload containing complete leave fields, warm the local Leave cache
                if (isSupervisorLeaveAlert && !data["requestId"].isNullOrBlank() && !data["employeeId"].isNullOrBlank()) {
                    val requestId = data["requestId"]!!
                    val existing = db.leaveDao().getLeaveRequestById(requestId)
                    if (existing == null) {
                        db.leaveDao().insertLeaveRequest(
                            LeaveRequestEntity(
                                requestId = requestId,
                                employeeId = data["employeeId"] ?: "EMP-001",
                                employeeName = data["employeeName"] ?: "Employee",
                                employeeRole = data["employeeRole"] ?: "WORKER",
                                type = data["leaveType"] ?: "ANNUAL",
                                startDate = data["startDate"] ?: "",
                                endDate = data["endDate"] ?: "",
                                totalDays = data["totalDays"]?.toIntOrNull() ?: (data["totalDays"]?.toDoubleOrNull()?.toInt() ?: 1),
                                reason = data["reason"] ?: "",
                                status = data["status"] ?: "PENDING",
                                submittedAtUtc = data["timestampUtc"]?.toLongOrNull() ?: System.currentTimeMillis()
                            )
                        )
                        Log.d(TAG, "Cached incoming LeaveRequest $requestId in Room for offline supervisor review")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error processing FCM notification in Room DB: ${e.message}")
            }
        }

        // 3. Select appropriate notification channel (Supervisor alerts, Shift/Schedule alerts, or standard workforce)
        val channelId = when {
            isSupervisorLeaveAlert -> FcmNotificationManager.CHANNEL_ID_SUPERVISOR_ALERTS
            isShiftOrScheduleAlert -> FcmNotificationManager.CHANNEL_ID_SHIFT_SCHEDULE
            else -> FcmNotificationManager.CHANNEL_ID_WORKFORCE
        }

        val enrichedData = HashMap(data).apply {
            if (isSupervisorLeaveAlert && !containsKey("target_screen")) {
                put("target_screen", "SUPERVISOR_LEAVE")
            } else if (isShiftOrScheduleAlert && !containsKey("target_screen")) {
                put("target_screen", "WORKER_SHIFT")
            }
        }

        // 4. Trigger Rich Heads-up Android System Notification
        FcmNotificationManager.showSystemNotification(
            context = applicationContext,
            title = title,
            body = body,
            notificationId = notificationIntId,
            channelId = channelId,
            extraData = enrichedData
        )
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Refreshed FCM Token: $token")
    }

    companion object {
        private const val TAG = "ArtifyFcmService"
    }
}

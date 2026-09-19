import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function json(obj: any, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: { ...corsHeaders, "Content-Type": "application/json" } });
}

function selfiePublicUrl(path: string | null): string | null {
  if (!path) return null;
  if (path.startsWith("http://") || path.startsWith("https://")) return path;
  const clean = path.replace(/^attendance-selfies\//, "");
  return `${Deno.env.get("SUPABASE_URL")}/storage/v1/object/public/attendance-selfies/${clean}`;
}

// Uploads a raw base64 selfie captured on the supervisor's own device for a proxy
// clock-in/out and returns its public URL -- mirrors the `attendance` function's
// resolveSelfieUrl so a proxy-recorded punch carries the same evidence photo a
// self-service mobile punch does.
async function resolveSelfieUrl(supabase: any, reqBody: any, fileNameSeed: string): Promise<string | null> {
  if (reqBody.selfie_url || reqBody.storage_url) {
    return reqBody.selfie_url ?? reqBody.storage_url;
  }
  const rawBase64 = reqBody.selfie_base64 ?? reqBody.selfieBase64 ?? reqBody.selfie;
  if (rawBase64 && typeof rawBase64 === "string" && rawBase64.length > 50) {
    try {
      const cleanBase64 = rawBase64.replace(/^data:image\/\w+;base64,/, "").replace(/[\r\n\s]/g, "");
      const binaryString = atob(cleanBase64);
      const bytes = Uint8Array.from(binaryString, (c) => c.charCodeAt(0));
      const blob = new Blob([bytes], { type: "image/jpeg" });

      const fileName = `selfie_${fileNameSeed}_${Date.now()}.jpg`;
      const { error: uploadErr } = await supabase
        .storage
        .from("attendance-selfies")
        .upload(fileName, blob, { contentType: "image/jpeg", upsert: true });

      if (!uploadErr) {
        const { data: urlData } = supabase
          .storage
          .from("attendance-selfies")
          .getPublicUrl(fileName);
        return urlData.publicUrl;
      }
    } catch (_) {}
  }
  return null;
}

function mapApprovalStatus(shift: any): string {
  if (shift.approval_status === "APPROVED") return "APPROVED";
  if (shift.approval_status === "REJECTED") return "REJECTED";
  return "PENDING_REVIEW";
}

function mapComplianceFlag(shift: any): string {
  return shift.geofence_status === "INSIDE" ? "COMPLIANT" : (shift.geofence_status === "OUTSIDE" ? "OUTSIDE_GEOFENCE" : "NEEDS_REVIEW");
}

function shiftToDto(shift: any, emp: any, proj: any) {
  return {
    id: shift.id,
    employee_id: shift.employee_id,
    project_id: shift.project_id,
    shift_date: shift.shift_date,
    clock_in_event_id: shift.clock_in_event_id ?? crypto.randomUUID(),
    clock_out_event_id: shift.clock_out_event_id ?? null,
    total_worked_minutes: shift.total_worked_minutes ?? null,
    status: mapApprovalStatus(shift),
    compliance_flag: mapComplianceFlag(shift),
    reviewed_by: shift.reviewed_by ?? null,
    reviewed_at: shift.reviewed_at ?? null,
    review_comment: shift.review_comment ?? null,
    // Schedule that applied on this shift's own date, snapshotted at clock-in by the
    // `attendance` function (or, for a proxy entry, by this function's own proxy_clock_in
    // below) -- surfaced here unchanged so the supervisor's Approvals tab can show
    // Scheduled vs Actual side by side. Never re-derived from the employee's CURRENT
    // shift assignment; null when nothing was configured/applicable that day.
    scheduled_shift_id: shift.scheduled_shift_id ?? null,
    scheduled_start: shift.scheduled_start ?? null,
    scheduled_end: shift.scheduled_end ?? null,
    scheduled_break_minutes: shift.scheduled_break_minutes ?? null,
    scheduled_standard_hours: shift.scheduled_standard_hours ?? null,
    late_minutes: shift.late_minutes ?? null,
    early_departure_minutes: shift.early_departure_minutes ?? null,
    // Null for an ordinary self-service mobile punch; the recording supervisor's own
    // employee id for one entered on behalf of a worker with no mobile device.
    recorded_by: shift.recorded_by ?? null,
    clock_in: {
      server_timestamp: shift.clock_in_time,
      geofence_status: shift.geofence_status ?? null,
      distance_from_project_meters: shift.distance_from_project_meters ?? null,
      selfie_storage_path: selfiePublicUrl(shift.selfie_url),
      is_mock_location: false,
      device_id: "mobile-device",
    },
    clock_out: shift.clock_out_time ? {
      server_timestamp: shift.clock_out_time,
      geofence_status: shift.geofence_status ?? null,
      distance_from_project_meters: shift.distance_from_project_meters ?? null,
      selfie_storage_path: selfiePublicUrl(shift.end_selfie_url),
      is_mock_location: false,
      device_id: "mobile-device",
    } : null,
    employee: emp ? { full_name: emp.employee_name, employee_code: emp.employee_id, role: (emp.employee_type ?? "STAFF").toUpperCase() } : null,
    project: proj ? { name: proj.project_name } : null,
  };
}

function leaveToDto(l: any, emp: any) {
  const statusMap: Record<string, string> = { Submitted: "PENDING", Approved: "APPROVED", Rejected: "REJECTED", Draft: "PENDING", Cancelled: "REJECTED" };
  return {
    id: l.id,
    employee_id: l.employee_id,
    leave_type: l.leave_types?.name ?? "Leave",
    start_date: l.start_date,
    end_date: l.end_date,
    total_days: Number(l.days ?? 0),
    reason: l.reason ?? "",
    status: statusMap[l.status] ?? "PENDING",
    decision_reason: l.decision_reason ?? null,
    employee: emp ? { full_name: emp.employee_name, employee_code: emp.employee_id, role: (emp.employee_type ?? "STAFF").toUpperCase() } : null,
  };
}

function haversineMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371000;
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

// Approximates the local UTC offset for a longitude using the standard "15 degrees of
// longitude per hour" solar-time rule, matching the identical helper in `attendance`, so
// both agree on what "today" and a shift's scheduled wall-clock times mean for a given
// project. Falls back to Oman's fixed UTC+4 offset (Asia/Muscat has no DST) only when a
// project has no coordinates configured yet.
const FALLBACK_UTC_OFFSET_HOURS = 4;
function utcOffsetHoursFromLongitude(lon: number): number {
  return Math.round(lon / 15);
}
function localDateFromGps(iso: string, lon: number | null | undefined): string {
  const offsetHours = typeof lon === "number" && !isNaN(lon) ? utcOffsetHoursFromLongitude(lon) : FALLBACK_UTC_OFFSET_HOURS;
  return new Date(new Date(iso).getTime() + offsetHours * 3600000).toISOString().slice(0, 10);
}
function localTimeToUtcDate(dateStr: string, timeStr: string, lon: number | null | undefined): Date {
  const offsetHours = typeof lon === "number" && !isNaN(lon) ? utcOffsetHoursFromLongitude(lon) : FALLBACK_UTC_OFFSET_HOURS;
  const [h, m] = timeStr.split(":").map(Number);
  const d = new Date(`${dateStr}T00:00:00.000Z`);
  d.setUTCHours(h - offsetHours, m, 0, 0);
  return d;
}
function scheduledEndUtc(shiftDateStr: string, startTime: string, endTime: string, lon: number | null | undefined): Date {
  const end = localTimeToUtcDate(shiftDateStr, endTime, lon);
  if (endTime <= startTime) {
    end.setUTCDate(end.getUTCDate() + 1);
  }
  return end;
}

// Shift resolution priority for an employee + attendance date -- mirrors the identical
// helper in `attendance` and server/db.ts's resolveEmployeeShift exactly, so a
// supervisor-recorded proxy shift snapshots the same schedule a self-service mobile
// clock-in would have:
//   1. Individual Employee Shift Assignment (project-scoped one wins over an
//      employee-wide one when both cover the date).
//   2. Project Shift Assignment's default shift -- Project here also covers Head Office
//      (it's project HO0001, not a separate concept).
// Returns null if nothing applicable is configured -- never fabricated.
async function resolveApplicableShift(supabase: any, employeeId: string, dateStr: string, projectId: string | null) {
  const { data: individualRows } = await supabase
    .from("employee_shift_assignments")
    .select("project_id, shifts(*)")
    .eq("employee_id", employeeId)
    .eq("is_active", true)
    .lte("effective_from", dateStr)
    .or(`effective_to.is.null,effective_to.gte.${dateStr}`);

  if (individualRows && individualRows.length > 0) {
    const scoped = projectId ? individualRows.find((r: any) => r.project_id === projectId) : null;
    const chosen = scoped ?? individualRows.find((r: any) => r.project_id === null);
    if (chosen?.shifts) return chosen.shifts;
  }

  if (projectId) {
    const { data: projectRow } = await supabase
      .from("project_shift_assignments")
      .select("shifts(*)")
      .eq("project_id", projectId)
      .eq("is_default", true)
      .eq("is_active", true)
      .lte("effective_from", dateStr)
      .or(`effective_to.is.null,effective_to.gte.${dateStr}`)
      .limit(1)
      .maybeSingle();
    if (projectRow?.shifts) return projectRow.shifts;
  }

  return null;
}

// Real geofence evaluation against a project's own coordinates/radius, identical in
// spirit to `attendance`'s evaluateGeofence -- UNKNOWN (not a guessed pass/fail) whenever
// the project has no coordinates configured, or the recording device supplied none.
function evaluateGeofence(
  lat: number | null | undefined,
  lon: number | null | undefined,
  projectLat: number | null | undefined,
  projectLon: number | null | undefined,
  radiusMeters: number
): { status: "INSIDE" | "OUTSIDE" | "UNKNOWN"; distance: number | null } {
  if (lat == null || lon == null || projectLat == null || projectLon == null) {
    return { status: "UNKNOWN", distance: null };
  }
  const distance = haversineMeters(lat, lon, projectLat, projectLon);
  return { status: distance <= radiusMeters ? "INSIDE" : "OUTSIDE", distance };
}

serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    const supabase = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
    );

    const authHeader = req.headers.get("Authorization") ?? "";
    const token = authHeader.replace(/^Bearer\s+/i, "").trim();
    const body = await req.json().catch(() => ({}));
    const action = (body.action ?? "").toString().toLowerCase();

    if (!token) {
      return json({ error: "Missing session token." }, 401);
    }

    // Same real-session resolution as the `attendance` function: the Civil-ID-then-PIN
    // login the mobile app already uses, never a body-supplied identity.
    const { data: sess } = await supabase
      .from("device_sessions")
      .select("employee_id")
      .eq("session_token", token)
      .eq("is_active", true)
      .order("created_at", { ascending: false })
      .limit(1)
      .maybeSingle();

    const supEmpId = sess?.employee_id ?? null;
    if (!supEmpId) {
      return json({ error: "Invalid or expired session." }, 401);
    }

    const { data: supervisor } = await supabase
      .from("employees")
      .select("id, employee_name, employee_id, employee_type, is_workforce_supervisor, assigned_project_id, is_active")
      .eq("id", supEmpId)
      .maybeSingle();

    // Supervisor access is gated STRICTLY by the real is_workforce_supervisor flag HCMS
    // sets on the employee record -- never inferred, never a body-supplied role. Anyone
    // without it hitting this function (even with a valid session) is refused outright,
    // the same way `attendance` refuses an invalid token outright.
    if (!supervisor || !supervisor.is_active || !supervisor.is_workforce_supervisor) {
      return json({ error: "This account is not authorized as a Workforce Supervisor." }, 403);
    }

    const nowIso = new Date().toISOString();
    const today = nowIso.slice(0, 10);

    // The supervisor's real scope: workers/staff assigned to the SAME real project the
    // supervisor is assigned to (employees.assigned_project_id -- the one field every
    // other part of this integration already keys off: shift-status, sync-eligibility,
    // civil-id-register). Recomputed on every call, so a project reassignment on either
    // side takes effect immediately with no separate mapping table to go stale.
    async function scopedEmployeeIds(): Promise<string[]> {
      if (!supervisor.assigned_project_id) return [];
      const { data } = await supabase
        .from("employees")
        .select("id")
        .eq("assigned_project_id", supervisor.assigned_project_id)
        .eq("is_active", true);
      return (data ?? []).map((e: any) => e.id);
    }

    async function employeeMap(ids: string[]): Promise<Record<string, any>> {
      if (ids.length === 0) return {};
      const { data } = await supabase.from("employees").select("id, employee_name, employee_id, employee_type").in("id", ids);
      const map: Record<string, any> = {};
      for (const e of data ?? []) map[e.id] = e;
      return map;
    }

    async function projectMap(rows: any[]): Promise<Record<string, any>> {
      const codes = Array.from(new Set(rows.map((r) => r.project_id).filter(Boolean)));
      if (codes.length === 0) return {};
      const { data } = await supabase.from("projects").select("project_code, project_name").in("project_code", codes);
      const map: Record<string, any> = {};
      for (const p of data ?? []) map[p.project_code] = p;
      return map;
    }

    async function logAction(actionName: string, entityType: string, entityId: string, reason: string | null) {
      await supabase.from("workforce_action_log").insert({
        actor_employee_id: supervisor.id,
        actor_role: "SUPERVISOR",
        action: actionName,
        entity_type: entityType,
        entity_id: entityId,
        reason: reason ?? null,
      });
    }

    // -------------------- pending_attendance --------------------
    if (action === "pending_attendance") {
      const ids = await scopedEmployeeIds();
      if (ids.length === 0) return json({ shifts: [], error: null });
      const { data: shifts } = await supabase
        .from("attendance_shifts")
        .select("*")
        .in("employee_id", ids)
        .eq("approval_status", "PENDING")
        .order("clock_out_time", { ascending: false });
      const empMap = await employeeMap(ids);
      const projMap = await projectMap(shifts ?? []);
      return json({ shifts: (shifts ?? []).map((s: any) => shiftToDto(s, empMap[s.employee_id], projMap[s.project_id])), error: null });
    }

    // -------------------- review_attendance --------------------
    if (action === "review_attendance") {
      const shiftId = body.shift_id?.toString();
      const decision = body.decision?.toString().toUpperCase();
      const comment = body.comment ? body.comment.toString() : null;
      if (!shiftId || (decision !== "APPROVED" && decision !== "REJECTED")) {
        return json({ shift: null, error: "shift_id and a valid decision (APPROVED/REJECTED) are required." }, 400);
      }
      const ids = await scopedEmployeeIds();
      const { data: shift } = await supabase.from("attendance_shifts").select("*").eq("id", shiftId).maybeSingle();
      if (!shift || !ids.includes(shift.employee_id)) {
        return json({ shift: null, error: "Attendance record not found in your team." }, 404);
      }
      const { data: updated, error: updErr } = await supabase
        .from("attendance_shifts")
        .update({ approval_status: decision, reviewed_by: supervisor.id, reviewed_at: nowIso, review_comment: comment })
        .eq("id", shiftId)
        .select()
        .maybeSingle();
      if (updErr || !updated) {
        return json({ shift: null, error: updErr?.message ?? "Failed to update attendance." }, 500);
      }
      await logAction(decision === "APPROVED" ? "ATTENDANCE_APPROVED" : "ATTENDANCE_REJECTED", "ATTENDANCE", shiftId, comment);
      await supabase.from("notifications").insert({
        employee_id: shift.employee_id,
        title: decision === "APPROVED" ? "Attendance approved" : "Attendance rejected",
        body: decision === "APPROVED"
          ? `Your shift on ${shift.shift_date} was approved by your supervisor.`
          : `Your shift on ${shift.shift_date} was rejected by your supervisor.${comment ? " Reason: " + comment : ""}`,
      });
      const empMap = await employeeMap([shift.employee_id]);
      const projMap = await projectMap([updated]);
      return json({ shift: shiftToDto(updated, empMap[shift.employee_id], projMap[updated.project_id]), error: null });
    }

    // -------------------- pending_leave --------------------
    if (action === "pending_leave") {
      const ids = await scopedEmployeeIds();
      if (ids.length === 0) return json({ leave_requests: [], error: null });
      const { data: leaves } = await supabase
        .from("leave_requests")
        .select("*, leave_types(name)")
        .in("employee_id", ids)
        .eq("status", "Submitted")
        .order("submitted_at", { ascending: false });
      const empMap = await employeeMap(ids);
      return json({ leave_requests: (leaves ?? []).map((l: any) => leaveToDto(l, empMap[l.employee_id])), error: null });
    }

    // -------------------- review_leave --------------------
    if (action === "review_leave") {
      const leaveId = body.leave_id?.toString();
      const decision = body.decision?.toString().toUpperCase();
      const comment = body.comment ? body.comment.toString() : null;
      if (!leaveId || (decision !== "APPROVED" && decision !== "REJECTED")) {
        return json({ leave_request: null, error: "leave_id and a valid decision (APPROVED/REJECTED) are required." }, 400);
      }
      const ids = await scopedEmployeeIds();
      const { data: leave } = await supabase.from("leave_requests").select("*, leave_types(name)").eq("id", leaveId).maybeSingle();
      if (!leave || !ids.includes(leave.employee_id)) {
        return json({ leave_request: null, error: "Leave request not found in your team." }, 404);
      }
      const dbStatus = decision === "APPROVED" ? "Approved" : "Rejected";
      const { data: updated, error: updErr } = await supabase
        .from("leave_requests")
        .update({ status: dbStatus, decided_by: supervisor.employee_id, decided_at: nowIso, decision_reason: comment })
        .eq("id", leaveId)
        .select("*, leave_types(name)")
        .maybeSingle();
      if (updErr || !updated) {
        return json({ leave_request: null, error: updErr?.message ?? "Failed to update leave request." }, 500);
      }
      await logAction(decision === "APPROVED" ? "LEAVE_APPROVED" : "LEAVE_REJECTED", "LEAVE", leaveId, comment);
      await supabase.from("notifications").insert({
        employee_id: leave.employee_id,
        title: decision === "APPROVED" ? "Leave approved" : "Leave rejected",
        body: decision === "APPROVED"
          ? `Your leave request (${leave.start_date} to ${leave.end_date}) was approved.`
          : `Your leave request (${leave.start_date} to ${leave.end_date}) was rejected.${comment ? " Reason: " + comment : ""}`,
      });
      const empMap = await employeeMap([leave.employee_id]);
      return json({ leave_request: leaveToDto(updated, empMap[leave.employee_id]), error: null });
    }

    // -------------------- attendance_roster --------------------
    if (action === "attendance_roster") {
      const ids = await scopedEmployeeIds();
      if (ids.length === 0) return json({ shifts: [], error: null });
      const { data: shifts } = await supabase
        .from("attendance_shifts")
        .select("*")
        .in("employee_id", ids)
        .order("created_at", { ascending: false })
        .limit(200);
      const empMap = await employeeMap(ids);
      const projMap = await projectMap(shifts ?? []);
      return json({ shifts: (shifts ?? []).map((s: any) => shiftToDto(s, empMap[s.employee_id], projMap[s.project_id])), error: null });
    }

    // -------------------- sites --------------------
    if (action === "sites") {
      const { data: projects } = await supabase.from("projects").select("*").eq("status", "Active");
      const { data: allEmployees } = await supabase.from("employees").select("id, employee_name, assigned_project_id").eq("is_active", true);
      const sites = (projects ?? []).map((p: any) => ({
        id: p.id,
        project_code: p.project_code,
        name: p.project_name,
        address: p.geofence_name ?? null,
        geofence_radius_meters: p.geofence_radius_meters ?? 200,
        is_active: p.status === "Active",
        employees: (allEmployees ?? []).filter((e: any) => e.assigned_project_id === p.id).map((e: any) => e.employee_name),
      }));
      return json({ sites, error: null });
    }

    // -------------------- audit_log --------------------
    if (action === "audit_log") {
      const { data: logs } = await supabase
        .from("workforce_action_log")
        .select("*")
        .eq("actor_employee_id", supervisor.id)
        .order("created_at", { ascending: false })
        .limit(100);
      const auditLogs = (logs ?? []).map((l: any) => ({
        id: l.id,
        actor_employee_id: l.actor_employee_id,
        actor_role: l.actor_role,
        action: l.action,
        entity_type: l.entity_type,
        entity_id: l.entity_id,
        reason: l.reason,
        created_at: l.created_at,
      }));
      return json({ audit_logs: auditLogs, error: null });
    }

    // -------------------- erp_outbox --------------------
    // No real ERP system is connected to this deployment. Returning a genuinely empty
    // list rather than fabricating events -- the tab already renders an honest empty
    // state ("No ERP outbox events yet.") for this.
    if (action === "erp_outbox") {
      return json({ erp_events: [], error: null });
    }

    // -------------------- metrics --------------------
    if (action === "metrics") {
      const ids = await scopedEmployeeIds();
      if (ids.length === 0) {
        return json({ present: 0, working: 0, attendance_pending: 0, leave_pending: 0, on_leave: 0, error: null });
      }
      const { data: todayShifts } = await supabase
        .from("attendance_shifts")
        .select("employee_id, status")
        .in("employee_id", ids)
        .eq("shift_date", today);
      const presentSet = new Set((todayShifts ?? []).map((s: any) => s.employee_id));
      const working = (todayShifts ?? []).filter((s: any) => s.status === "OPEN").length;
      const { count: attendancePending } = await supabase
        .from("attendance_shifts")
        .select("id", { count: "exact", head: true })
        .in("employee_id", ids)
        .eq("approval_status", "PENDING");
      const { count: leavePending } = await supabase
        .from("leave_requests")
        .select("id", { count: "exact", head: true })
        .in("employee_id", ids)
        .eq("status", "Submitted");
      const { count: onLeave } = await supabase
        .from("leave_requests")
        .select("id", { count: "exact", head: true })
        .in("employee_id", ids)
        .eq("status", "Approved")
        .lte("start_date", today)
        .gte("end_date", today);
      return json({
        present: presentSet.size,
        working,
        attendance_pending: attendancePending ?? 0,
        leave_pending: leavePending ?? 0,
        on_leave: onLeave ?? 0,
        error: null,
      });
    }

    // -------------------- get_team_selfie_url --------------------
    if (action === "get_selfie_url") {
      const storagePath = body.storage_path?.toString();
      if (!storagePath) return json({ url: null, error: "storage_path is required." }, 400);
      return json({ url: selfiePublicUrl(storagePath), error: null });
    }

    // -------------------- team_without_mobile --------------------
    // Workers/staff in the supervisor's own scope who have never completed device
    // registration (civil-id-register) -- i.e. have no workforce_auth row at all. That is
    // the real, automatically-derived signal for "has no mobile of their own"; nothing
    // here is a manually-set flag that could go stale.
    if (action === "team_without_mobile") {
      const ids = await scopedEmployeeIds();
      if (ids.length === 0) return json({ workers: [], error: null });
      const { data: registered } = await supabase
        .from("workforce_auth")
        .select("employee_id")
        .in("employee_id", ids)
        .eq("is_active", true);
      const registeredIds = new Set((registered ?? []).map((r: any) => r.employee_id));
      const withoutMobileIds = ids.filter((id) => id !== supervisor.id && !registeredIds.has(id));
      if (withoutMobileIds.length === 0) return json({ workers: [], error: null });
      const { data: emps } = await supabase
        .from("employees")
        .select("id, employee_name, employee_id, employee_type")
        .in("id", withoutMobileIds);
      const { data: openShifts } = await supabase
        .from("attendance_shifts")
        .select("id, employee_id, clock_in_time")
        .in("employee_id", withoutMobileIds)
        .eq("status", "OPEN");
      const openByEmp: Record<string, any> = {};
      for (const s of openShifts ?? []) openByEmp[s.employee_id] = s;
      const workers = (emps ?? []).map((e: any) => ({
        id: e.id,
        employee_code: e.employee_id,
        full_name: e.employee_name,
        role: (e.employee_type ?? "STAFF").toUpperCase(),
        open_shift_id: openByEmp[e.id]?.id ?? null,
        clock_in_time: openByEmp[e.id]?.clock_in_time ?? null,
      }));
      return json({ workers, error: null });
    }

    // -------------------- team_roster --------------------
    // ALL active employees in the supervisor's own project (excluding the supervisor
    // themself), regardless of whether they've registered a mobile device -- the full
    // picklist for "whose attendance is this?" after a supervisor captures a selfie on
    // the Home tab. Unlike team_without_mobile, this is never filtered to unregistered
    // employees only.
    if (action === "team_roster") {
      const ids = (await scopedEmployeeIds()).filter((id) => id !== supervisor.id);
      if (ids.length === 0) return json({ workers: [], error: null });
      const { data: emps } = await supabase
        .from("employees")
        .select("id, employee_name, employee_id, employee_type")
        .in("id", ids);
      const { data: openShifts } = await supabase
        .from("attendance_shifts")
        .select("id, employee_id, clock_in_time")
        .in("employee_id", ids)
        .eq("status", "OPEN");
      const openByEmp: Record<string, any> = {};
      for (const s of openShifts ?? []) openByEmp[s.employee_id] = s;
      const workers = (emps ?? []).map((e: any) => ({
        id: e.id,
        employee_code: e.employee_id,
        full_name: e.employee_name,
        role: (e.employee_type ?? "STAFF").toUpperCase(),
        open_shift_id: openByEmp[e.id]?.id ?? null,
        clock_in_time: openByEmp[e.id]?.clock_in_time ?? null,
      }));
      return json({ workers, error: null });
    }

    // -------------------- proxy_clock_in --------------------
    // Records a clock-in for a worker in the supervisor's own team who has no mobile
    // device of their own -- entered from the supervisor's phone on the worker's behalf.
    // Schedule snapshot and shift resolution follow the exact same priority order as a
    // real self-service mobile clock-in (see resolveApplicableShift above); geofence is
    // evaluated against the SUPERVISOR's own device location (if supplied) versus the
    // WORKER's assigned project, since the worker has no device of their own to report
    // from.
    if (action === "proxy_clock_in") {
      const targetId = body.employee_id?.toString();
      if (!targetId) return json({ shift: null, error: "employee_id is required." }, 400);
      const ids = await scopedEmployeeIds();
      if (!ids.includes(targetId)) {
        return json({ shift: null, error: "That employee is not on your team." }, 404);
      }
      const { data: target } = await supabase
        .from("employees")
        .select("id, employee_name, employee_id, employee_type, assigned_project_id, is_active")
        .eq("id", targetId)
        .maybeSingle();
      if (!target || !target.is_active) {
        return json({ shift: null, error: "Employee record not found or inactive." }, 404);
      }
      const { data: existingOpen } = await supabase
        .from("attendance_shifts")
        .select("id")
        .eq("employee_id", targetId)
        .eq("status", "OPEN")
        .limit(1)
        .maybeSingle();
      if (existingOpen) {
        return json({ shift: null, error: "This employee already has an open shift." }, 409);
      }

      let projectCode = "PRJ-001";
      let projectName = "Head Office";
      let projectLat: number | null = null;
      let projectLon: number | null = null;
      let geofenceRadius = 200;
      if (target.assigned_project_id) {
        const { data: prj } = await supabase
          .from("projects")
          .select("project_code, project_name, latitude, longitude, geofence_radius_meters")
          .eq("id", target.assigned_project_id)
          .maybeSingle();
        if (prj) {
          projectCode = prj.project_code ?? projectCode;
          projectName = prj.project_name ?? projectName;
          projectLat = prj.latitude ?? null;
          projectLon = prj.longitude ?? null;
          geofenceRadius = prj.geofence_radius_meters ?? geofenceRadius;
        }
      }

      const deviceLat = typeof body.latitude === "number" ? body.latitude : null;
      const deviceLon = typeof body.longitude === "number" ? body.longitude : null;
      const geofence = evaluateGeofence(deviceLat, deviceLon, projectLat, projectLon, geofenceRadius);
      const today = localDateFromGps(nowIso, projectLon);

      const scheduledShift = await resolveApplicableShift(supabase, targetId, today, target.assigned_project_id ?? null);
      let scheduledFields: Record<string, unknown> = {
        scheduled_shift_id: null,
        scheduled_start: null,
        scheduled_end: null,
        scheduled_break_minutes: null,
        scheduled_standard_hours: null,
        scheduled_grace_in_minutes: null,
        scheduled_grace_out_minutes: null,
        late_minutes: null,
      };
      if (scheduledShift) {
        const schedStartUtc = localTimeToUtcDate(today, scheduledShift.start_time, projectLon);
        const rawLate = Math.round((new Date(nowIso).getTime() - schedStartUtc.getTime()) / 60000);
        const lateMinutes = Math.max(0, rawLate - (scheduledShift.grace_in_minutes ?? 0));
        scheduledFields = {
          scheduled_shift_id: scheduledShift.id,
          scheduled_start: scheduledShift.start_time,
          scheduled_end: scheduledShift.end_time,
          scheduled_break_minutes: scheduledShift.break_minutes,
          scheduled_standard_hours: scheduledShift.standard_working_hours,
          scheduled_grace_in_minutes: scheduledShift.grace_in_minutes,
          scheduled_grace_out_minutes: scheduledShift.grace_out_minutes,
          late_minutes: lateMinutes,
        };
      }

      const proxyInSelfieUrl = await resolveSelfieUrl(supabase, body, `${targetId}_in`);

      const shiftId = crypto.randomUUID();
      const clockInId = crypto.randomUUID();
      const { data: newShift, error: insertErr } = await supabase
        .from("attendance_shifts")
        .insert({
          id: shiftId,
          employee_id: targetId,
          project_id: projectCode,
          shift_date: today,
          clock_in_event_id: clockInId,
          status: "OPEN",
          compliance_flag: geofence.status === "INSIDE" ? "VERIFIED" : "NEEDS_REVIEW",
          clock_in_time: nowIso,
          geofence_status: geofence.status,
          distance_from_project_meters: geofence.distance,
          latitude: deviceLat,
          longitude: deviceLon,
          recorded_by: supervisor.id,
          selfie_url: proxyInSelfieUrl,
          ...scheduledFields,
        })
        .select()
        .single();

      if (insertErr || !newShift) {
        console.error("[supervisor] proxy_clock_in insert failed:", JSON.stringify(insertErr));
        return json({ shift: null, error: `Could not record clock-in: ${insertErr?.message ?? "unknown database error"}` }, 500);
      }

      await logAction("PROXY_CLOCK_IN", "ATTENDANCE", shiftId, `Recorded by supervisor for ${target.employee_name} (no personal mobile device).`);

      return json({ shift: shiftToDto(newShift, { employee_name: target.employee_name, employee_id: target.employee_id, employee_type: target.employee_type }, { project_name: projectName }), error: null });
    }

    // -------------------- proxy_clock_out --------------------
    // Closes a proxy-recorded (or any open) shift for a team member on the supervisor's
    // own behalf. Since the supervisor is both the recorder and the one who would
    // otherwise separately approve it, the shift is marked APPROVED immediately rather
    // than left PENDING for a redundant second review step.
    if (action === "proxy_clock_out") {
      const targetId = body.employee_id?.toString();
      if (!targetId) return json({ shift: null, error: "employee_id is required." }, 400);
      const ids = await scopedEmployeeIds();
      if (!ids.includes(targetId)) {
        return json({ shift: null, error: "That employee is not on your team." }, 404);
      }

      const { data: openShift, error: findErr } = await supabase
        .from("attendance_shifts")
        .select("id, employee_id, project_id, clock_in_time, shift_date, scheduled_end, scheduled_grace_out_minutes")
        .eq("employee_id", targetId)
        .eq("status", "OPEN")
        .order("created_at", { ascending: false })
        .limit(1)
        .maybeSingle();

      if (findErr) {
        console.error("[supervisor] proxy_clock_out lookup failed:", JSON.stringify(findErr));
        return json({ shift: null, error: `Could not record clock-out: ${findErr.message}` }, 500);
      }
      if (!openShift) {
        return json({ shift: null, error: "No open shift to close for this employee." }, 409);
      }

      const deviceLat = typeof body.latitude === "number" ? body.latitude : null;
      const deviceLon = typeof body.longitude === "number" ? body.longitude : null;

      let projectLat: number | null = null;
      let projectLon: number | null = null;
      let geofenceRadius = 200;
      const { data: prj } = await supabase
        .from("projects")
        .select("latitude, longitude, geofence_radius_meters")
        .eq("project_code", openShift.project_id)
        .maybeSingle();
      if (prj) {
        projectLat = prj.latitude ?? null;
        projectLon = prj.longitude ?? null;
        geofenceRadius = prj.geofence_radius_meters ?? geofenceRadius;
      }
      const geofence = evaluateGeofence(deviceLat, deviceLon, projectLat, projectLon, geofenceRadius);

      const totalWorkedMinutes = openShift.clock_in_time
        ? Math.max(0, Math.round((new Date(nowIso).getTime() - new Date(openShift.clock_in_time).getTime()) / 60000))
        : null;

      let earlyDepartureMinutes: number | null = null;
      if (openShift.scheduled_end && openShift.shift_date) {
        const schedEndUtc = scheduledEndUtc(openShift.shift_date, "00:00", openShift.scheduled_end, deviceLon ?? projectLon);
        const rawEarly = Math.round((schedEndUtc.getTime() - new Date(nowIso).getTime()) / 60000);
        earlyDepartureMinutes = Math.max(0, rawEarly - (openShift.scheduled_grace_out_minutes ?? 0));
      }

      const proxyOutSelfieUrl = await resolveSelfieUrl(supabase, body, `${targetId}_out`);

      const { data: updated, error: updateErr } = await supabase
        .from("attendance_shifts")
        .update({
          status: "COMPLETED",
          clock_out_time: nowIso,
          total_worked_minutes: totalWorkedMinutes,
          compliance_flag: geofence.status === "INSIDE" ? "VERIFIED" : "NEEDS_REVIEW",
          geofence_status: geofence.status,
          distance_from_project_meters: geofence.distance,
          early_departure_minutes: earlyDepartureMinutes,
          end_selfie_url: proxyOutSelfieUrl,
          approval_status: "APPROVED",
          reviewed_by: supervisor.id,
          reviewed_at: nowIso,
          review_comment: "Recorded and approved by supervisor (no personal mobile device).",
        })
        .eq("id", openShift.id)
        .select()
        .maybeSingle();

      if (updateErr || !updated) {
        console.error("[supervisor] proxy_clock_out update failed:", JSON.stringify(updateErr));
        return json({ shift: null, error: updateErr?.message ?? "Could not record clock-out." }, 500);
      }

      await logAction("PROXY_CLOCK_OUT", "ATTENDANCE", openShift.id, "Recorded by supervisor (no personal mobile device).");

      const empMap = await employeeMap([targetId]);
      const projMap = await projectMap([updated]);
      return json({ shift: shiftToDto(updated, empMap[targetId], projMap[updated.project_id]), error: null });
    }

    return json({ error: "Unknown action." }, 400);
  } catch (err: any) {
    return json({ error: err.message ?? "Error" }, 500);
  }
});

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

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
// longitude per hour" solar-time rule, so the business date follows wherever a project's
// own GPS coordinates actually are instead of a single hardcoded timezone string. Falls
// back to Oman's fixed UTC+4 offset (Asia/Muscat has no DST) only when a project has no
// coordinates configured yet. Kept identical to the same helper in `supervisor` so both
// agree on what "today" and a shift's scheduled wall-clock times mean.
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

// Shift resolution priority for an employee + attendance date -- kept identical to the
// same helper in `supervisor` (and server/db.ts's resolveEmployeeShift) so a self-service
// mobile clock-in and a supervisor-recorded proxy clock-in snapshot the same schedule:
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

serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const supabase = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
    );

    const authHeader = req.headers.get("Authorization") ?? "";
    const token = authHeader.replace(/^Bearer\s+/i, "").trim();
    const body = await req.json().catch(() => ({}));
    const action = body.action?.toString().toLowerCase() ?? "my_profile";

    if (!token) {
      return new Response(
        JSON.stringify({ error: "Missing session token." }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Resolve the employee STRICTLY from a live, active session row for this exact
    // token. Never a body-supplied employee_id/civil_id (letting any caller act as
    // anyone), and never "the most recently active session on the whole system" as a
    // fallback (letting a request with no valid token at all authenticate as an
    // arbitrary employee). An invalid or unrecognized token is rejected outright.
    const { data: sess } = await supabase
      .from("device_sessions")
      .select("employee_id")
      .eq("session_token", token)
      .eq("is_active", true)
      .order("created_at", { ascending: false })
      .limit(1)
      .maybeSingle();

    const empId = sess?.employee_id ?? null;
    if (!empId) {
      return new Response(
        JSON.stringify({ error: "Invalid or expired session." }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 2. Fetch full employee details from HCMS master database
    const { data: emp } = await supabase
      .from("employees")
      .select("id, employee_name, employee_id, civil_id, employee_company, designation, employee_type, assigned_project_id, is_active")
      .eq("id", empId)
      .maybeSingle();

    if (!emp || !emp.is_active) {
      return new Response(
        JSON.stringify({ error: "Employee record not found or inactive." }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 3. Fetch linked Project details for geofencing
    let projectName = "Head Office";
    let projectCode = "PRJ-001";
    let projectAddress = "Muscat, Oman";
    let projectLat: number | null = null;
    let projectLon: number | null = null;
    let geofenceRadius = 200;

    if (emp.assigned_project_id) {
      const { data: prj } = await supabase
        .from("projects")
        .select("project_code, project_name, latitude, longitude, geofence_radius_meters")
        .eq("id", emp.assigned_project_id)
        .maybeSingle();
      if (prj) {
        projectName = prj.project_name ?? projectName;
        projectCode = prj.project_code ?? projectCode;
        projectLat = prj.latitude ?? null;
        projectLon = prj.longitude ?? null;
        geofenceRadius = prj.geofence_radius_meters ?? geofenceRadius;
      }
    }

    // 4. Fetch linked Company Name
    let companyName = emp.employee_company ?? "DGO";
    const { data: comp } = await supabase
      .from("companies")
      .select("name")
      .eq("code", emp.employee_company)
      .maybeSingle();
    if (comp?.name) {
      companyName = comp.name;
    }

    const nowIso = new Date().toISOString();
    // Local business date is derived from the assigned project's own GPS coordinates
    // (falling back to Oman's UTC+4 offset when a project has no coordinates configured),
    // rather than the raw UTC calendar date -- so "today" always matches the wall-clock
    // date where the work is actually happening.
    const today = localDateFromGps(nowIso, projectLon);

    // Real geofence evaluation. If the project has no coordinates configured yet, the
    // status is UNKNOWN (and flagged for manual review) rather than assumed compliant.
    function evaluateGeofence(lat: number | null | undefined, lon: number | null | undefined, isMock: boolean | null | undefined) {
      if (isMock) {
        return { status: "OUTSIDE" as const, distance: null as number | null, compliant: false, reason: "MOCK_LOCATION" };
      }
      if (lat == null || lon == null) {
        return { status: "UNKNOWN" as const, distance: null as number | null, compliant: false, reason: "NO_DEVICE_LOCATION" };
      }
      if (projectLat == null || projectLon == null) {
        return { status: "UNKNOWN" as const, distance: null as number | null, compliant: false, reason: "PROJECT_NOT_CONFIGURED" };
      }
      const distance = haversineMeters(lat, lon, projectLat, projectLon);
      return { status: distance <= geofenceRadius ? ("INSIDE" as const) : ("OUTSIDE" as const), distance, compliant: distance <= geofenceRadius, reason: null };
    }

    // Resolves an uploaded selfie's public URL from whichever shape the client sent it
    // in (a ready-made URL, a storage path, or a raw base64 payload uploaded here).
    // Shared by both clock-in and clock-out so a shift-end selfie is captured the same
    // reliable way a shift-start selfie already is.
    async function resolveSelfieUrl(reqBody: any, fileNameSeed: string): Promise<string | null> {
      if (reqBody.selfie_url || reqBody.storage_url) {
        return reqBody.selfie_url ?? reqBody.storage_url;
      }
      if (reqBody.selfie_storage_path) {
        const cleanPath = reqBody.selfie_storage_path.replace(/^attendance-selfies\//, '');
        return `${Deno.env.get("SUPABASE_URL")}/storage/v1/object/public/attendance-selfies/${cleanPath}`;
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

    function formatShiftDto(dbShift: any, selfieUrl: string | null = null, geofence?: ReturnType<typeof evaluateGeofence>) {
      const isCompleted = dbShift.status === "COMPLETED";
      const g = geofence ?? { status: dbShift.geofence_status ?? "UNKNOWN", distance: dbShift.distance_from_project_meters ?? null, compliant: dbShift.compliance_flag === "VERIFIED", reason: null };
      return {
        id: dbShift.id,
        employee_id: emp.id,
        project_id: projectCode,
        shift_date: dbShift.shift_date ?? today,
        clock_in_event_id: dbShift.clock_in_event_id ?? crypto.randomUUID(),
        clock_out_event_id: isCompleted ? (dbShift.clock_out_event_id ?? crypto.randomUUID()) : null,
        total_worked_minutes: dbShift.total_worked_minutes ?? null,
        status: dbShift.status ?? "OPEN",
        compliance_flag: g.status === "INSIDE" ? "VERIFIED" : "NEEDS_REVIEW",
        reviewed_by: dbShift.reviewed_by ?? null,
        reviewed_at: dbShift.reviewed_at ?? null,
        review_comment: dbShift.review_comment ?? null,
        // Schedule that applied on this shift's own date, snapshotted at clock-in --
        // surfaced unchanged so the supervisor's Approvals tab can show Scheduled vs
        // Actual. Never re-derived from the employee's CURRENT shift assignment; null
        // when nothing was configured/applicable that day.
        scheduled_shift_id: dbShift.scheduled_shift_id ?? null,
        scheduled_start: dbShift.scheduled_start ?? null,
        scheduled_end: dbShift.scheduled_end ?? null,
        scheduled_break_minutes: dbShift.scheduled_break_minutes ?? null,
        scheduled_standard_hours: dbShift.scheduled_standard_hours ?? null,
        late_minutes: dbShift.late_minutes ?? null,
        early_departure_minutes: dbShift.early_departure_minutes ?? null,
        recorded_by: dbShift.recorded_by ?? null,
        clock_in: {
          server_timestamp: dbShift.clock_in_time ?? nowIso,
          geofence_status: g.status,
          distance_from_project_meters: g.distance,
          selfie_storage_path: selfieUrl ?? dbShift.selfie_url ?? "attendance-selfies/in.jpg",
          is_mock_location: dbShift.is_mock_location ?? false,
          device_id: "mobile-device"
        },
        clock_out: isCompleted ? {
          server_timestamp: dbShift.clock_out_time ?? nowIso,
          geofence_status: g.status,
          distance_from_project_meters: g.distance,
          selfie_storage_path: dbShift.end_selfie_url ?? "attendance-selfies/out.jpg",
          is_mock_location: dbShift.is_mock_location ?? false,
          device_id: "mobile-device"
        } : null,
        employee: {
          full_name: emp.employee_name,
          employee_code: emp.employee_id ?? emp.civil_id,
          role: (emp.employee_type ?? "STAFF").toUpperCase()
        },
        project: {
          name: projectName
        }
      };
    }

    // Handle "my_profile"
    if (action === "my_profile") {
      return new Response(
        JSON.stringify({
          profile: {
            full_name: emp.employee_name,
            employee_code: emp.employee_id ?? emp.civil_id,
            role: (emp.employee_type ?? "STAFF").toUpperCase(),
            department: emp.designation ?? "General",
            email: `${(emp.employee_id ?? "emp").toLowerCase()}@company.com`,
            phone: "+96800000000",
            company_name: companyName,
            is_demo: false,
            project_name: projectName,
            project_code: projectCode,
            project_address: projectAddress,
            project_latitude: projectLat,
            project_longitude: projectLon,
            geofence_radius_meters: geofenceRadius
          },
          error: null
        }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Handle "clock_in"
    if (action.includes("in") || action.includes("start") || action === "clock_in") {
      const deviceLat = typeof body.latitude === "number" ? body.latitude : null;
      const deviceLon = typeof body.longitude === "number" ? body.longitude : null;
      const isMock = Boolean(body.is_mock_location);
      const geofence = evaluateGeofence(deviceLat, deviceLon, isMock);

      if (isMock) {
        return new Response(
          JSON.stringify({ error: "Mock/spoofed location detected. Clock-in blocked.", geofence_status: "OUTSIDE" }),
          { status: 422, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      const clockInId = crypto.randomUUID();
      const shiftId = crypto.randomUUID();
      const publicSelfieUrl = await resolveSelfieUrl(body, emp.id);

      // Snapshot the schedule that applies TODAY, at the moment of clock-in -- never
      // re-derived later from the employee's current assignment, which could have since
      // changed. Absent entirely (all null) when nothing is configured for this employee/
      // project/date, matching the real, honest "no schedule" state rather than a guess.
      const scheduledShift = await resolveApplicableShift(supabase, emp.id, today, emp.assigned_project_id ?? null);
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

      const { data: newShift, error: insertErr } = await supabase
        .from("attendance_shifts")
        .insert({
          id: shiftId,
          employee_id: emp.id,
          project_id: emp.assigned_project_id ?? projectCode,
          shift_date: today,
          clock_in_event_id: clockInId,
          status: "OPEN",
          compliance_flag: geofence.status === "INSIDE" ? "VERIFIED" : "NEEDS_REVIEW",
          clock_in_time: nowIso,
          selfie_url: publicSelfieUrl,
          geofence_status: geofence.status,
          distance_from_project_meters: geofence.distance,
          latitude: deviceLat,
          longitude: deviceLon,
          is_mock_location: false,
          ...scheduledFields,
        })
        .select()
        .single();

      // Trigger automatic supervisor notification for the assigned project
      try {
        const projectIdForSup = emp.assigned_project_id ?? projectCode;
        const supervisorIds: string[] = [];

        const { data: projRecord } = await supabase
          .from("projects")
          .select("id, supervisor_id, manager_id")
          .or(`id.eq.${projectIdForSup},project_code.eq.${projectCode}`)
          .maybeSingle();
        if (projRecord?.supervisor_id) supervisorIds.push(String(projRecord.supervisor_id));
        if (projRecord?.manager_id) supervisorIds.push(String(projRecord.manager_id));

        const { data: psRecords } = await supabase
          .from("project_supervisors")
          .select("supervisor_id")
          .eq("is_active", true)
          .or(`project_id.eq.${projectIdForSup},project_code.eq.${projectCode}`);
        if (Array.isArray(psRecords)) {
          for (const r of psRecords) {
            if (r.supervisor_id) supervisorIds.push(String(r.supervisor_id));
          }
        }

        const { data: supEmployees } = await supabase
          .from("employees")
          .select("id")
          .eq("assigned_project_id", projectIdForSup)
          .in("employee_type", ["SUPERVISOR", "MANAGER", "supervisor", "manager"]);
        if (Array.isArray(supEmployees)) {
          for (const s of supEmployees) {
            if (s.id) supervisorIds.push(String(s.id));
          }
        }

        const uniqueSupervisors = Array.from(new Set(supervisorIds.filter(Boolean)));
        for (const sId of uniqueSupervisors) {
          await supabase.from("notifications").insert({
            recipient_id: sId,
            type: "ATTENDANCE_PENDING",
            title: `🔔 ${emp.employee_name} clocked in`,
            message: `${emp.employee_name} clocked in for ${projectName} on ${today}.`,
            created_at: nowIso
          });
        }
      } catch (_notifErr) {
        // Notification dispatch optional; do not fail clock-in
      }

      // A failed insert must never look like a successful clock-in. Surface the real
      // database error instead, and log it server-side so a future failure is
      // diagnosable without guessing.
      if (insertErr || !newShift) {
        console.error("[attendance] clock-in insert failed:", JSON.stringify(insertErr));
        return new Response(
          JSON.stringify({ error: `Could not save clock-in: ${insertErr?.message ?? "unknown database error"}` }),
          { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      const shiftDto = formatShiftDto(newShift, publicSelfieUrl, geofence);

      return new Response(
        JSON.stringify({
          shift: shiftDto,
          server_timestamp: nowIso,
          geofence_status: geofence.status,
          distance_meters: geofence.distance,
          error: null
        }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Handle "clock_out"
    if (action.includes("out") || action.includes("end") || action === "clock_out") {
      const deviceLat = typeof body.latitude === "number" ? body.latitude : null;
      const deviceLon = typeof body.longitude === "number" ? body.longitude : null;
      const isMock = Boolean(body.is_mock_location);
      const geofence = evaluateGeofence(deviceLat, deviceLon, isMock);

      if (isMock) {
        return new Response(
          JSON.stringify({ error: "Mock/spoofed location detected. Clock-out blocked.", geofence_status: "OUTSIDE" }),
          { status: 422, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      // Find the open shift FIRST (not update-in-one-shot) so total_worked_minutes and
      // early_departure_minutes can be computed for real from its own clock_in_time and
      // scheduled_end snapshot, instead of a fabricated constant.
      const { data: openShift, error: findErr } = await supabase
        .from("attendance_shifts")
        .select("id, clock_in_time, shift_date, scheduled_end, scheduled_grace_out_minutes, selfie_url")
        .eq("employee_id", emp.id)
        .eq("status", "OPEN")
        .order("created_at", { ascending: false })
        .limit(1)
        .maybeSingle();

      if (findErr) {
        console.error("[attendance] clock-out lookup failed:", JSON.stringify(findErr));
        return new Response(
          JSON.stringify({ error: `Could not save clock-out: ${findErr.message}` }),
          { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }
      if (!openShift) {
        return new Response(
          JSON.stringify({ error: "No open shift to close." }),
          { status: 409, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      const clockOutId = crypto.randomUUID();
      const publicEndSelfieUrl = await resolveSelfieUrl(body, `${emp.id}_end`);

      const totalWorkedMinutes = openShift.clock_in_time
        ? Math.max(0, Math.round((new Date(nowIso).getTime() - new Date(openShift.clock_in_time).getTime()) / 60000))
        : null;

      let earlyDepartureMinutes: number | null = null;
      if (openShift.scheduled_end && openShift.shift_date) {
        const schedEndUtc = scheduledEndUtc(openShift.shift_date, "00:00", openShift.scheduled_end, deviceLon ?? projectLon);
        const rawEarly = Math.round((schedEndUtc.getTime() - new Date(nowIso).getTime()) / 60000);
        earlyDepartureMinutes = Math.max(0, rawEarly - (openShift.scheduled_grace_out_minutes ?? 0));
      }

      // approval_status is set to PENDING only now, on a completed shift -- not at
      // clock-in -- so the supervisor's approval queue shows finished shifts ready for
      // review, not shifts still in progress.
      const { data: updatedShift, error: updateErr } = await supabase
        .from("attendance_shifts")
        .update({
          status: "COMPLETED",
          clock_out_event_id: clockOutId,
          clock_out_time: nowIso,
          total_worked_minutes: totalWorkedMinutes,
          compliance_flag: geofence.status === "INSIDE" ? "VERIFIED" : "NEEDS_REVIEW",
          geofence_status: geofence.status,
          distance_from_project_meters: geofence.distance,
          early_departure_minutes: earlyDepartureMinutes,
          end_selfie_url: publicEndSelfieUrl,
          approval_status: "PENDING",
        })
        .eq("id", openShift.id)
        .select()
        .maybeSingle();

      if (updateErr || !updatedShift) {
        console.error("[attendance] clock-out update failed:", JSON.stringify(updateErr));
        return new Response(
          JSON.stringify({ error: `Could not save clock-out: ${updateErr?.message ?? "unknown database error"}` }),
          { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      const shiftDto = formatShiftDto(updatedShift, updatedShift.selfie_url ?? openShift.selfie_url ?? null, geofence);

      return new Response(
        JSON.stringify({
          shift: shiftDto,
          server_timestamp: nowIso,
          geofence_status: geofence.status,
          distance_meters: geofence.distance,
          error: null
        }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Handle "my_shifts"
    if (action === "my_shifts") {
      const { data: recentShifts } = await supabase
        .from("attendance_shifts")
        .select("*")
        .eq("employee_id", emp.id)
        .order("created_at", { ascending: false })
        .limit(30);

      const rawShifts = Array.isArray(recentShifts) ? recentShifts : [];
      const shiftsList = rawShifts.map((s: any) => formatShiftDto(s, s.selfie_url));

      return new Response(
        JSON.stringify({ shifts: shiftsList, error: null }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Handle "my_notifications"
    if (action === "my_notifications") {
      const notifsList: any[] = [];

      try {
        const { data: dbNotifs } = await supabase
          .from("notifications")
          .select("id, type, title, message, created_at")
          .or(`recipient_id.eq.${emp.id},recipient_id.eq.${emp.employee_id},recipient_id.eq.ALL`)
          .order("created_at", { ascending: false })
          .limit(25);

        if (Array.isArray(dbNotifs)) {
          for (const n of dbNotifs) {
            notifsList.push({
              id: n.id || crypto.randomUUID(),
              type: n.type || "SYSTEM",
              title: n.title || "Notification",
              message: n.message || "",
              timestamp: n.created_at || new Date().toISOString()
            });
          }
        }
      } catch (_e) {
        // Ignore table errors
      }

      try {
        const { data: reviewedShifts } = await supabase
          .from("attendance_shifts")
          .select("id, shift_date, approval_status, review_comment, reviewed_by, reviewed_at")
          .eq("employee_id", emp.id)
          .not("reviewed_at", "is", null)
          .order("reviewed_at", { ascending: false })
          .limit(10);

        if (Array.isArray(reviewedShifts)) {
          for (const s of reviewedShifts) {
            const isApproved = s.approval_status === "APPROVED";
            const shiftNotifId = `notif-shift-${s.id}`;
            if (!notifsList.some((n: any) => n.id === shiftNotifId)) {
              notifsList.push({
                id: shiftNotifId,
                type: "ATTENDANCE",
                title: isApproved ? "✅ Shift Attendance Approved" : "❌ Shift Attendance Rejected",
                message: isApproved
                  ? `Your attendance on ${s.shift_date} was approved.${s.review_comment ? ` Note: ${s.review_comment}` : ""}`
                  : `Your attendance on ${s.shift_date} was rejected. Reason: ${s.review_comment || "Not specified"}`,
                timestamp: s.reviewed_at || new Date().toISOString()
              });
            }
          }
        }
      } catch (_e) {
        // Ignore
      }

      return new Response(
        JSON.stringify({ notifications: notifsList, error: null }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Fallback
    return new Response(
      JSON.stringify({ success: true, error: null }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (err: any) {
    // An unhandled exception here (malformed body, a thrown error anywhere above) must
    // never look identical to a real success at the HTTP layer. Logged server-side (not
    // just returned to the client) so a future failure is diagnosable from function logs
    // without needing to guess.
    console.error("[attendance] unhandled error:", err?.message, err?.stack);
    return new Response(
      JSON.stringify({ error: err.message ?? "Error" }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});

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
// coordinates configured yet.
const FALLBACK_UTC_OFFSET_HOURS = 4;
function utcOffsetHoursFromLongitude(lon: number): number {
  return Math.round(lon / 15);
}
function localDateFromGps(iso: string, lon: number | null | undefined): string {
  const offsetHours = typeof lon === "number" && !isNaN(lon) ? utcOffsetHoursFromLongitude(lon) : FALLBACK_UTC_OFFSET_HOURS;
  return new Date(new Date(iso).getTime() + offsetHours * 3600000).toISOString().slice(0, 10);
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
    // token. Previous versions accepted a body-supplied employee_id/civil_id (letting
    // any caller act as anyone) and, failing that, fell back to "the most recently
    // active session on the whole system" (letting a request with no valid token at all
    // authenticate as an arbitrary employee). Neither fallback exists here: an invalid
    // or unrecognized token is rejected outright.
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
      // compliance_flag is the only geofence result actually stored on this table
      // (there is no is_geofence_exception column -- see the clock-in insert above).
      const g = geofence ?? { status: dbShift.geofence_status ?? "UNKNOWN", distance: dbShift.geofence_distance_meters ?? null, compliant: dbShift.compliance_flag === "VERIFIED", reason: null };
      return {
        id: dbShift.id,
        employee_id: emp.id,
        project_id: projectCode,
        shift_date: dbShift.shift_date ?? today,
        clock_in_event_id: dbShift.clock_in_event_id ?? crypto.randomUUID(),
        clock_out_event_id: isCompleted ? (dbShift.clock_out_event_id ?? crypto.randomUUID()) : null,
        total_worked_minutes: dbShift.total_worked_minutes ?? (isCompleted ? 480 : null),
        status: dbShift.status ?? "PENDING",
        compliance_flag: g.status === "INSIDE" ? "VERIFIED" : "NEEDS_REVIEW",
        reviewed_by: dbShift.reviewed_by ?? null,
        reviewed_at: dbShift.reviewed_at ?? null,
        review_comment: dbShift.review_comment ?? null,
        clock_in: {
          server_timestamp: dbShift.clock_in_time ?? nowIso,
          geofence_status: g.status,
          distance_from_project_meters: g.distance,
          selfie_storage_path: selfieUrl ?? "attendance-selfies/in.jpg",
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

      const { data: newShift, error: insertErr } = await supabase
        .from("attendance_shifts")
        .insert({
          id: shiftId,
          employee_id: emp.id,
          project_id: emp.assigned_project_id ?? projectCode,
          shift_date: today,
          clock_in_event_id: clockInId,
          status: "PENDING",
          compliance_flag: geofence.status === "INSIDE" ? "VERIFIED" : "NEEDS_REVIEW",
          clock_in_time: nowIso,
          selfie_url: publicSelfieUrl
        })
        .select()
        .single();

      // Trigger automatic supervisor notification for the assigned project
      try {
        const projectIdForSup = emp.assigned_project_id ?? projectCode;
        const supervisorIds: string[] = [];

        // Check project record
        const { data: projRecord } = await supabase
          .from("projects")
          .select("id, supervisor_id, manager_id")
          .or(`id.eq.${projectIdForSup},project_code.eq.${projectCode}`)
          .maybeSingle();
        if (projRecord?.supervisor_id) supervisorIds.push(String(projRecord.supervisor_id));
        if (projRecord?.manager_id) supervisorIds.push(String(projRecord.manager_id));

        // Check project_supervisors table
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

        // Check supervisor employees assigned to this project
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
            title: `🔔 Pending Attendance: ${emp.employee_name}`,
            message: `${emp.employee_name} submitted attendance for ${projectName} on ${today}. Tap to review and approve.`,
            created_at: nowIso
          });
        }
      } catch (_notifErr) {
        // Notification dispatch optional; do not fail clock-in
      }

      // A failed insert must never look like a successful clock-in. Previously this
      // fell back to a synthesized-in-memory shift object and still returned 200 --
      // the phone showed "Shift started" while nothing was actually saved, with no
      // error visible anywhere. Surface the real database error instead, and log it
      // server-side so a future failure is diagnosable without guessing.
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

      const clockOutId = crypto.randomUUID();

      // The mobile app sends an end-of-shift selfie (selfie_base64) on clock-out. Stored
      // in end_selfie_url, separate from selfie_url (the shift-start selfie), so the
      // start selfie is never overwritten/lost when the shift ends -- both remain in
      // history. compliance_flag is also updated here so the geofence badge reflects
      // this clock-out's own location check (the "latest today selfie") rather than
      // staying frozen at the clock-in's result.
      const publicEndSelfieUrl = await resolveSelfieUrl(body, `${emp.id}_end`);

      const { data: updatedShift, error: updateErr } = await supabase
        .from("attendance_shifts")
        .update({
          status: "COMPLETED",
          clock_out_event_id: clockOutId,
          clock_out_time: nowIso,
          total_worked_minutes: 480,
          compliance_flag: geofence.status === "INSIDE" ? "VERIFIED" : "NEEDS_REVIEW",
          end_selfie_url: publicEndSelfieUrl
        })
        .eq("employee_id", emp.id)
        .eq("status", "OPEN")
        .select()
        .maybeSingle();

      // Distinguish a real database error (500 -- something is actually broken) from
      // the legitimate business case of no open shift existing (409 -- nothing to fix,
      // the employee just hasn't clocked in). Previously both cases were indistinguishable
      // (both just "no row came back"), which is exactly how a silently-failed clock-in
      // upstream would surface downstream as a confusing "No open shift to close" here.
      if (updateErr) {
        console.error("[attendance] clock-out update failed:", JSON.stringify(updateErr));
        return new Response(
          JSON.stringify({ error: `Could not save clock-out: ${updateErr.message}` }),
          { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }
      if (!updatedShift) {
        return new Response(
          JSON.stringify({ error: "No open shift to close." }),
          { status: 409, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      const shiftDto = formatShiftDto(updatedShift, updatedShift.selfie_url ?? null, geofence);

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
      const shiftIds = rawShifts.map((s: any) => s.id);

      // Fetch approvals data from attendance_approvals table
      const approvalsMap: Record<string, any> = {};
      if (shiftIds.length > 0) {
        try {
          const { data: approvals } = await supabase
            .from("attendance_approvals")
            .select("*")
            .in("shift_id", shiftIds)
            .order("reviewed_at", { ascending: false });

          if (Array.isArray(approvals)) {
            for (const a of approvals) {
              if (!approvalsMap[a.shift_id]) {
                approvalsMap[a.shift_id] = a;
              }
            }
          }
        } catch (_e) {}
      }

      const shiftsList = rawShifts.map((s: any) => {
        const dto = formatShiftDto(s, s.selfie_url);
        const appr = approvalsMap[s.id];
        if (appr) {
          dto.status = appr.decision; // "APPROVED", "REJECTED", "PENDING"
          if (appr.comment) dto.review_comment = appr.comment;
          if (appr.reviewed_at) dto.reviewed_at = appr.reviewed_at;
        } else if (dto.status !== "OPEN" && !dto.reviewed_at) {
          dto.status = "PENDING";
        }
        return dto;
      });

      return new Response(
        JSON.stringify({ shifts: shiftsList, error: null }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Handle "my_attendance_approvals"
    if (action === "my_attendance_approvals") {
      try {
        const { data: approvals, error: apprErr } = await supabase
          .from("attendance_approvals")
          .select("*")
          .eq("employee_id", emp.id)
          .order("created_at", { ascending: false });

        return new Response(
          JSON.stringify({ approvals: approvals || [], error: apprErr?.message ?? null }),
          { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      } catch (err: any) {
        return new Response(
          JSON.stringify({ approvals: [], error: err?.message ?? "Error fetching attendance approvals" }),
          { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }
    }

    // Handle "my_notifications"
    if (action === "my_notifications") {
      const notifsList: any[] = [];

      // 1. Fetch from notifications table if available
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

      // 2. Fetch reviewed attendance shifts to ensure worker always sees their approvals / rejections
      try {
        const { data: reviewedShifts } = await supabase
          .from("attendance_shifts")
          .select("id, shift_date, status, review_comment, reviewed_by, reviewed_at")
          .eq("employee_id", emp.id)
          .not("reviewed_at", "is", null)
          .order("reviewed_at", { ascending: false })
          .limit(10);

        if (Array.isArray(reviewedShifts)) {
          for (const s of reviewedShifts) {
            const isApproved = s.status === "APPROVED";
            const shiftNotifId = `notif-shift-${s.id}`;
            if (!notifsList.some((n: any) => n.id === shiftNotifId)) {
              notifsList.push({
                id: shiftNotifId,
                type: "ATTENDANCE",
                title: isApproved ? "✅ Shift Attendance Approved" : "❌ Shift Attendance Rejected",
                message: isApproved
                  ? `Your attendance on ${s.shift_date} was approved${s.reviewed_by ? ` by ${s.reviewed_by}` : ""}.${s.review_comment ? ` Note: ${s.review_comment}` : ""}`
                  : `Your attendance on ${s.shift_date} was rejected${s.reviewed_by ? ` by ${s.reviewed_by}` : ""}. Reason: ${s.review_comment || "Not specified"}`,
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
    // Was status 200 -- an unhandled exception here (malformed body, a thrown error
    // anywhere above) looked identical to a real success at the HTTP layer. Logged
    // server-side (not just returned to the client) so a future failure is diagnosable
    // from function logs without needing to guess.
    console.error("[attendance] unhandled error:", err?.message, err?.stack);
    return new Response(
      JSON.stringify({ error: err.message ?? "Error" }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});

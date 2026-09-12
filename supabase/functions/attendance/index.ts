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
    const today = nowIso.split("T")[0];

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

    function formatShiftDto(dbShift: any, selfieUrl: string | null = null, geofence?: ReturnType<typeof evaluateGeofence>) {
      const isCompleted = dbShift.status === "COMPLETED";
      const g = geofence ?? { status: dbShift.geofence_status ?? "UNKNOWN", distance: dbShift.geofence_distance_meters ?? null, compliant: dbShift.is_geofence_exception === false, reason: null };
      return {
        id: dbShift.id,
        employee_id: emp.id,
        project_id: projectCode,
        shift_date: dbShift.shift_date ?? today,
        clock_in_event_id: dbShift.clock_in_event_id ?? crypto.randomUUID(),
        clock_out_event_id: isCompleted ? (dbShift.clock_out_event_id ?? crypto.randomUUID()) : null,
        total_worked_minutes: dbShift.total_worked_minutes ?? (isCompleted ? 480 : null),
        status: dbShift.status,
        compliance_flag: g.status === "INSIDE" ? "VERIFIED" : "NEEDS_REVIEW",
        reviewed_by: null,
        reviewed_at: null,
        review_comment: null,
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
          selfie_storage_path: "attendance-selfies/out.jpg",
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
      let publicSelfieUrl: string | null = null;

      if (body.selfie_url || body.storage_url) {
        publicSelfieUrl = body.selfie_url ?? body.storage_url;
      } else if (body.selfie_storage_path) {
        const cleanPath = body.selfie_storage_path.replace(/^attendance-selfies\//, '');
        publicSelfieUrl = `${Deno.env.get("SUPABASE_URL")}/storage/v1/object/public/attendance-selfies/${cleanPath}`;
      } else {
        const rawBase64 = body.selfie_base64 ?? body.selfieBase64 ?? body.selfie;
        if (rawBase64 && typeof rawBase64 === "string" && rawBase64.length > 50) {
          try {
            const cleanBase64 = rawBase64.replace(/^data:image\/\w+;base64,/, "").replace(/[\r\n\s]/g, "");
            const binaryString = atob(cleanBase64);
            const bytes = Uint8Array.from(binaryString, (c) => c.charCodeAt(0));
            const blob = new Blob([bytes], { type: "image/jpeg" });

            const fileName = `selfie_${emp.id}_${Date.now()}.jpg`;
            const { error: uploadErr } = await supabase
              .storage
              .from("attendance-selfies")
              .upload(fileName, blob, { contentType: "image/jpeg", upsert: true });

            if (!uploadErr) {
              const { data: urlData } = supabase
                .storage
                .from("attendance-selfies")
                .getPublicUrl(fileName);
              publicSelfieUrl = urlData.publicUrl;
            }
          } catch (_) {}
        }
      }

      const { data: newShift } = await supabase
        .from("attendance_shifts")
        .insert({
          id: shiftId,
          employee_id: emp.id,
          project_id: projectCode,
          shift_date: today,
          clock_in_event_id: clockInId,
          status: "OPEN",
          compliance_flag: geofence.status === "INSIDE" ? "VERIFIED" : "NEEDS_REVIEW",
          clock_in_time: nowIso,
          selfie_url: publicSelfieUrl,
          is_geofence_exception: geofence.status !== "INSIDE"
        })
        .select()
        .single();

      const shiftDto = formatShiftDto(newShift ?? { id: shiftId, status: "OPEN" }, publicSelfieUrl, geofence);

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

      const { data: updatedShift } = await supabase
        .from("attendance_shifts")
        .update({
          status: "COMPLETED",
          clock_out_event_id: clockOutId,
          clock_out_time: nowIso,
          total_worked_minutes: 480
        })
        .eq("employee_id", emp.id)
        .eq("status", "OPEN")
        .select()
        .maybeSingle();

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
      const { data: latestShift } = await supabase
        .from("attendance_shifts")
        .select("*")
        .eq("employee_id", emp.id)
        .order("created_at", { ascending: false })
        .limit(1)
        .maybeSingle();

      const shiftsList = latestShift ? [formatShiftDto(latestShift, latestShift.selfie_url)] : [];

      return new Response(
        JSON.stringify({ shifts: shiftsList, error: null }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Fallback
    return new Response(
      JSON.stringify({ success: true, error: null }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (err: any) {
    return new Response(
      JSON.stringify({ error: err.message ?? "Error" }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});

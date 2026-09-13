import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, x-integration-secret",
};

// Approximates the local UTC offset for a longitude using the standard "15 degrees of
// longitude per hour" solar-time rule, so the business date follows wherever a project's
// own GPS coordinates actually are instead of a single hardcoded timezone/UTC split.
// Falls back to Oman's fixed UTC+4 offset (Asia/Muscat has no DST) only when a project
// has no coordinates configured yet. Kept identical to the same helper in the
// `attendance` function so both agree on what "today" means for a given employee.
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

    const body = await req.json().catch(() => ({}));
    const civilIds = body.civil_ids ?? [];
    const statuses: Record<string, any> = {};

    const nowIso = new Date().toISOString();

    // Cache project GPS lookups within this one request so multiple employees on the
    // same project don't each trigger a separate query.
    const projectLonCache = new Map<string, number | null>();
    async function projectLongitude(projectId: string | null | undefined): Promise<number | null> {
      if (!projectId) return null;
      if (projectLonCache.has(projectId)) return projectLonCache.get(projectId) ?? null;
      const { data: prj } = await supabase
        .from("projects")
        .select("longitude")
        .eq("id", projectId)
        .maybeSingle();
      const lon = typeof prj?.longitude === "number" ? prj.longitude : null;
      projectLonCache.set(projectId, lon);
      return lon;
    }

    for (const cid of civilIds) {
      // Look up each employee by either civil_id OR employee_id
      const { data: emp } = await supabase
        .from("employees")
        .select("id, employee_name, employee_id, civil_id, assigned_project_id")
        .or(`civil_id.eq.${cid},employee_id.eq.${cid}`)
        .maybeSingle();

      if (!emp) {
        statuses[cid] = {
          shift_date: null,
          clock_in_at: null,
          clock_out_at: null,
          status: "NOT_LINKED",
          selfie_url: null,
          end_selfie_url: null,
          is_inside_geofence: null,
          total_today_minutes: 0
        };
        continue;
      }

      const empId = emp.id;

      // "Today" is derived from THIS employee's assigned project's own GPS coordinates
      // (see localDateFromGps above), not a single global UTC-based date -- so an
      // employee on a project in a different timezone still gets the correct local
      // business day.
      const lon = await projectLongitude(emp.assigned_project_id);
      const today = localDateFromGps(nowIso, lon);

      // Fetch all shifts strictly for THIS specific employee today
      const { data: todayShifts } = await supabase
        .from("attendance_shifts")
        .select("*")
        .eq("employee_id", empId)
        .eq("shift_date", today)
        .order("created_at", { ascending: true });

      const shifts = todayShifts ?? [];
      const openShift = shifts.find((s: any) => s.status === "OPEN" || s.status === "IN_PROGRESS");
      const completedShifts = shifts.filter((s: any) => s.status === "COMPLETED" || s.status === "CLOSED");

      let todayCompletedMinutes = 0;
      for (const cs of completedShifts) {
        if (cs.clock_in_time && cs.clock_out_time) {
          const start = new Date(cs.clock_in_time).getTime();
          const end = new Date(cs.clock_out_time).getTime();
          if (end > start) {
            todayCompletedMinutes += Math.floor((end - start) / 60000);
          }
        }
      }

      const latestShift = shifts.length > 0 ? shifts[shifts.length - 1] : null;

      if (openShift) {
        // Shift Started: only a start selfie exists so far. `end_selfie_url` stays null
        // (the column exists in this response shape for forward-compatibility once the
        // shift-end selfie capture -- see attendance/index.ts's clock-out handler -- has
        // a column to write into) until the shift is closed.
        statuses[cid] = {
          shift_date: openShift.shift_date,
          clock_in_at: openShift.clock_in_time,
          clock_out_at: null,
          status: "OPEN",
          selfie_url: openShift.selfie_url || latestShift?.selfie_url || null,
          end_selfie_url: openShift.end_selfie_url ?? null,
          // Reflects the start selfie's own geofence check (compliance_flag is set at
          // clock-in) -- not a blanket "true" whenever a shift merely exists.
          is_inside_geofence: openShift.compliance_flag ? openShift.compliance_flag === "VERIFIED" : null,
          total_today_minutes: todayCompletedMinutes
        };
      } else if (completedShifts.length > 0) {
        const lastCompleted = completedShifts[completedShifts.length - 1];
        statuses[cid] = {
          shift_date: today,
          clock_in_at: lastCompleted.clock_in_time,
          clock_out_at: lastCompleted.clock_out_time,
          status: "CLOSED",
          selfie_url: latestShift?.selfie_url || null,
          end_selfie_url: lastCompleted.end_selfie_url ?? null,
          // compliance_flag is re-evaluated and overwritten at clock-out, so once closed
          // this reflects the END selfie's location check -- the "latest today selfie",
          // per the dashboard's requirement -- rather than staying frozen at clock-in.
          is_inside_geofence: lastCompleted.compliance_flag ? lastCompleted.compliance_flag === "VERIFIED" : null,
          total_today_minutes: todayCompletedMinutes
        };
      } else {
        statuses[cid] = {
          shift_date: null,
          clock_in_at: null,
          clock_out_at: null,
          status: "NO_SHIFT_TODAY",
          selfie_url: null,
          end_selfie_url: null,
          is_inside_geofence: null,
          total_today_minutes: 0
        };
      }
    }

    return new Response(
      JSON.stringify({ statuses }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (err: any) {
    return new Response(
      JSON.stringify({ error: err.message }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});

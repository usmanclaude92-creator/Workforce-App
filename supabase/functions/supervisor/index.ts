import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

interface SupervisorActionBody {
  action: string;
  shift_id?: string;
  leave_id?: string;
  decision?: string;
  comment?: string;
  storage_path?: string;
}

serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const authHeader = req.headers.get("authorization") ?? req.headers.get("Authorization") ?? "";
    const token = authHeader.replace(/^Bearer\s+/i, "").trim();

    if (!token) {
      return new Response(JSON.stringify({ error: "Missing Bearer authorization header" }), {
        status: 401,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const supabase = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
    );

    // 1. Authenticate supervisor via device_sessions
    const { data: sess, error: sessErr } = await supabase
      .from("device_sessions")
      .select("employee_id, is_active")
      .eq("session_token", token)
      .eq("is_active", true)
      .order("created_at", { ascending: false })
      .limit(1)
      .maybeSingle();

    if (sessErr || !sess?.employee_id) {
      return new Response(JSON.stringify({ error: "Invalid or expired session. Please sign in again." }), {
        status: 401,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // 2. Resolve supervisor profile and role
    const { data: supervisor, error: supErr } = await supabase
      .from("employees")
      .select("id, employee_name, employee_id, civil_id, employee_type, designation, assigned_project_id, is_active")
      .eq("id", sess.employee_id)
      .maybeSingle();

    if (supErr || !supervisor) {
      return new Response(JSON.stringify({ error: "Supervisor profile not found." }), {
        status: 403,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // 3. Resolve assigned project(s) for this supervisor
    // Project assignment sources:
    // a. employees.assigned_project_id
    // b. project_supervisors table (project_id, supervisor_id)
    // c. projects table (supervisor_id or manager_id)
    const assignedProjectIds = new Set<string>();
    const assignedProjectCodes = new Set<string>();

    if (supervisor.assigned_project_id) {
      assignedProjectIds.add(String(supervisor.assigned_project_id));
    }

    // Check project_supervisors table
    try {
      const { data: projSups } = await supabase
        .from("project_supervisors")
        .select("project_id")
        .eq("is_active", true)
        .or(`supervisor_id.eq.${supervisor.id},supervisor_employee_id.eq.${supervisor.employee_id},supervisor_id.eq.${supervisor.employee_id}`);
      if (Array.isArray(projSups)) {
        for (const ps of projSups) {
          if (ps.project_id) assignedProjectIds.add(String(ps.project_id));
        }
      }
    } catch (_e) {
      // Table may not exist; ignore
    }

    // Check projects table by supervisor_id, manager_id, or matching IDs
    try {
      const { data: projs } = await supabase
        .from("projects")
        .select("id, project_code, project_name")
        .or(`supervisor_id.eq.${supervisor.id},manager_id.eq.${supervisor.id},supervisor_id.eq.${supervisor.employee_id}`);
      if (Array.isArray(projs)) {
        for (const p of projs) {
          if (p.id) assignedProjectIds.add(String(p.id));
          if (p.project_code) assignedProjectCodes.add(String(p.project_code));
        }
      }
    } catch (_e) {
      // Ignore
    }

    // If supervisor has assigned_project_id, also lookup its project_code
    if (supervisor.assigned_project_id) {
      try {
        const { data: p } = await supabase
          .from("projects")
          .select("id, project_code, project_name")
          .eq("id", supervisor.assigned_project_id)
          .maybeSingle();
        if (p) {
          if (p.id) assignedProjectIds.add(String(p.id));
          if (p.project_code) assignedProjectCodes.add(String(p.project_code));
        }
      } catch (_e) {
        // Ignore
      }
    }

    // Combine all project identifiers (UUIDs and codes)
    const allProjectIdentifiers = Array.from(new Set([...assignedProjectIds, ...assignedProjectCodes]));

    // Parse request body
    let body: SupervisorActionBody = { action: "pending_attendance" };
    if (req.method === "POST") {
      try {
        body = await req.json();
      } catch (_e) {
        body = { action: "pending_attendance" };
      }
    }

    const action = body.action || "pending_attendance";

    // Helper: format shift DTO with employee & project summaries
    async function enrichShifts(shifts: any[]) {
      if (!shifts || shifts.length === 0) return [];

      const empIds = Array.from(new Set(shifts.map((s: any) => s.employee_id).filter(Boolean)));
      const projIds = Array.from(new Set(shifts.map((s: any) => s.project_id).filter(Boolean)));

      const { data: emps } = await supabase
        .from("employees")
        .select("id, employee_name, employee_id, civil_id, employee_type, designation")
        .in("id", empIds.length > 0 ? empIds : ["00000000-0000-0000-0000-000000000000"]);

      const empMap = new Map((emps || []).map((e: any) => [e.id, e]));

      const { data: prjs } = await supabase
        .from("projects")
        .select("id, project_code, project_name")
        .in("id", projIds.length > 0 ? projIds : ["00000000-0000-0000-0000-000000000000"]);

      const projMap = new Map((prjs || []).map((p: any) => [p.id, p]));

      // Clock in/out events
      const eventIds = Array.from(new Set(
        shifts.flatMap((s: any) => [s.clock_in_event_id, s.clock_out_event_id]).filter(Boolean)
      ));

      let eventMap = new Map();
      if (eventIds.length > 0) {
        try {
          const { data: events } = await supabase
            .from("attendance_events")
            .select("id, server_timestamp, geofence_status, distance_from_project_meters, selfie_storage_path, is_mock_location, device_id")
            .in("id", eventIds);
          eventMap = new Map((events || []).map((ev: any) => [ev.id, ev]));
        } catch (_e) {
          // Ignore
        }
      }

      return shifts.map((s: any) => {
        const emp = empMap.get(s.employee_id);
        const prj = projMap.get(s.project_id);
        const inEv = eventMap.get(s.clock_in_event_id);
        const outEv = eventMap.get(s.clock_out_event_id);

        return {
          id: s.id,
          employee_id: s.employee_id,
          project_id: s.project_id,
          shift_date: s.shift_date,
          clock_in_event_id: s.clock_in_event_id,
          clock_out_event_id: s.clock_out_event_id ?? null,
          total_worked_minutes: s.total_worked_minutes ?? 0,
          status: s.status ?? "PENDING",
          compliance_flag: s.compliance_flag ?? "VERIFIED",
          reviewed_by: s.reviewed_by ?? null,
          reviewed_at: s.reviewed_at ?? null,
          review_comment: s.review_comment ?? null,
          clock_in: inEv ? {
            server_timestamp: inEv.server_timestamp,
            geofence_status: inEv.geofence_status,
            distance_from_project_meters: inEv.distance_from_project_meters,
            selfie_storage_path: inEv.selfie_storage_path ?? s.selfie_url ?? null,
            is_mock_location: inEv.is_mock_location ?? false,
            device_id: inEv.device_id ?? null
          } : {
            server_timestamp: s.clock_in_time ?? s.created_at ?? null,
            geofence_status: s.compliance_flag === "NEEDS_REVIEW" ? "OUTSIDE_GEOFENCE" : "INSIDE_GEOFENCE",
            distance_from_project_meters: null,
            selfie_storage_path: s.selfie_url ?? null,
            is_mock_location: false,
            device_id: null
          },
          clock_out: outEv ? {
            server_timestamp: outEv.server_timestamp,
            geofence_status: outEv.geofence_status,
            distance_from_project_meters: outEv.distance_from_project_meters,
            selfie_storage_path: outEv.selfie_storage_path ?? null,
            is_mock_location: outEv.is_mock_location ?? false,
            device_id: outEv.device_id ?? null
          } : null,
          employee: emp ? {
            full_name: emp.employee_name ?? "Worker",
            employee_code: emp.employee_id ?? emp.civil_id ?? "",
            role: emp.employee_type ?? emp.designation ?? "Worker"
          } : {
            full_name: "Worker",
            employee_code: "",
            role: "Worker"
          },
          project: prj ? {
            name: prj.project_name ?? prj.project_code ?? "Project Site"
          } : {
            name: "Project Site"
          }
        };
      });
    }

    // ACTION: pending_attendance
    if (action === "pending_attendance") {
      let query = supabase
        .from("attendance_shifts")
        .select("*")
        .or("status.eq.PENDING,status.eq.OPEN")
        .is("reviewed_at", null)
        .order("created_at", { ascending: false });

      // Strictly enforce project-based security:
      // If supervisor has assigned project(s), ONLY return shifts from their project(s).
      if (allProjectIdentifiers.length > 0) {
        query = query.in("project_id", allProjectIdentifiers);
      } else {
        // Supervisor with no assigned projects cannot see any pending attendances
        return new Response(JSON.stringify({ shifts: [], error: null }), {
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      const { data: shifts, error: shiftsErr } = await query;
      if (shiftsErr) {
        return new Response(JSON.stringify({ error: shiftsErr.message }), {
          status: 500,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      const dtos = await enrichShifts(shifts || []);
      return new Response(JSON.stringify({ shifts: dtos, error: null }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // ACTION: review_attendance (APPROVE or REJECT)
    if (action === "review_attendance") {
      const shiftId = body.shift_id;
      const decision = (body.decision || "").toUpperCase();
      const comment = (body.comment || "").trim();

      if (!shiftId) {
        return new Response(JSON.stringify({ error: "Missing shift_id parameter." }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      if (decision !== "APPROVED" && decision !== "REJECTED") {
        return new Response(JSON.stringify({ error: "Decision must be 'APPROVED' or 'REJECTED'." }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      // Mandatory rejection reason validation
      if (decision === "REJECTED" && !comment) {
        return new Response(JSON.stringify({ error: "A rejection reason is required to reject attendance." }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      // Fetch the shift to review
      const { data: targetShift, error: findErr } = await supabase
        .from("attendance_shifts")
        .select("*")
        .eq("id", shiftId)
        .maybeSingle();

      if (findErr || !targetShift) {
        return new Response(JSON.stringify({ error: "Attendance shift record not found." }), {
          status: 404,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      // Project-based security check:
      // Supervisor can ONLY approve/reject shifts for their assigned project(s).
      if (allProjectIdentifiers.length > 0 && !allProjectIdentifiers.includes(String(targetShift.project_id))) {
        return new Response(JSON.stringify({
          error: "Permission denied: You are only authorized to review attendance for your assigned project."
        }), {
          status: 403,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      const nowIso = new Date().toISOString();
      const reviewerIdentifier = supervisor.employee_name || supervisor.employee_id || supervisor.id;

      // Update attendance_shifts table
      const { data: updatedShift, error: updateErr } = await supabase
        .from("attendance_shifts")
        .update({
          status: decision,
          reviewed_by: reviewerIdentifier,
          reviewed_at: nowIso,
          review_comment: comment || null
        })
        .eq("id", shiftId)
        .select("*")
        .single();

      if (updateErr) {
        return new Response(JSON.stringify({ error: "Failed to update shift: " + updateErr.message }), {
          status: 500,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      // Record / update in attendance_approvals table
      let savedApprovalRecord = null;
      try {
        const { data: existingAppr } = await supabase
          .from("attendance_approvals")
          .select("id")
          .eq("shift_id", shiftId)
          .maybeSingle();

        if (existingAppr?.id) {
          const { data: updAppr } = await supabase
            .from("attendance_approvals")
            .update({
              decision: decision,
              comment: comment || null,
              supervisor_id: supervisor.id,
              reviewed_at: nowIso,
              updated_at: nowIso
            })
            .eq("id", existingAppr.id)
            .select("*")
            .single();
          savedApprovalRecord = updAppr;
        } else {
          const { data: insAppr } = await supabase
            .from("attendance_approvals")
            .insert({
              shift_id: shiftId,
              employee_id: targetShift.employee_id,
              project_id: targetShift.project_id,
              supervisor_id: supervisor.id,
              decision: decision,
              comment: comment || null,
              reviewed_at: nowIso,
              updated_at: nowIso
            })
            .select("*")
            .single();
          savedApprovalRecord = insAppr;
        }
      } catch (_e) {
        // Table optional
      }

      // Create Notification for the worker
      try {
        const notifTitle = decision === "APPROVED" ? "✅ Shift Attendance Approved" : "❌ Shift Attendance Rejected";
        const notifMsg = decision === "APPROVED"
          ? `Your attendance on ${targetShift.shift_date} was approved by ${supervisor.employee_name || "Supervisor"}.${comment ? ` Note: ${comment}` : ""}`
          : `Your attendance on ${targetShift.shift_date} was rejected by ${supervisor.employee_name || "Supervisor"}. Reason: ${comment}`;

        await supabase
          .from("notifications")
          .insert({
            recipient_id: targetShift.employee_id,
            type: "ATTENDANCE_APPROVAL",
            title: notifTitle,
            message: notifMsg,
            created_at: nowIso
          });
      } catch (_e) {
        // Notifications table optional
      }

      const enriched = await enrichShifts([updatedShift]);
      return new Response(JSON.stringify({
        shift: enriched[0] ?? null,
        approval: savedApprovalRecord,
        error: null
      }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // ACTION: update_attendance_approval
    // Explicit function that updates the status in attendance_approvals table based on supervisor's Approve or Reject action
    if (action === "update_attendance_approval") {
      const shiftId = body.shift_id;
      const decision = (body.decision || "").toUpperCase();
      const comment = (body.comment || "").trim();

      if (!shiftId) {
        return new Response(JSON.stringify({ error: "Missing shift_id parameter." }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      if (decision !== "APPROVED" && decision !== "REJECTED") {
        return new Response(JSON.stringify({ error: "Decision must be 'APPROVED' or 'REJECTED'." }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      if (decision === "REJECTED" && !comment) {
        return new Response(JSON.stringify({ error: "A rejection reason is required to reject attendance." }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      const { data: targetShift, error: findErr } = await supabase
        .from("attendance_shifts")
        .select("*")
        .eq("id", shiftId)
        .maybeSingle();

      if (findErr || !targetShift) {
        return new Response(JSON.stringify({ error: "Attendance shift record not found." }), {
          status: 404,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      if (allProjectIdentifiers.length > 0 && !allProjectIdentifiers.includes(String(targetShift.project_id))) {
        return new Response(JSON.stringify({
          error: "Permission denied: You are only authorized to review attendance for your assigned project."
        }), {
          status: 403,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      const nowIso = new Date().toISOString();
      const reviewerIdentifier = supervisor.employee_name || supervisor.employee_id || supervisor.id;

      // 1. Update attendance_shifts status
      const { data: updatedShift } = await supabase
        .from("attendance_shifts")
        .update({
          status: decision,
          reviewed_by: reviewerIdentifier,
          reviewed_at: nowIso,
          review_comment: comment || null
        })
        .eq("id", shiftId)
        .select("*")
        .single();

      // 2. Update status in attendance_approvals table
      let savedApproval = null;
      try {
        const { data: existingAppr } = await supabase
          .from("attendance_approvals")
          .select("id")
          .eq("shift_id", shiftId)
          .maybeSingle();

        if (existingAppr?.id) {
          const { data: updAppr } = await supabase
            .from("attendance_approvals")
            .update({
              decision: decision,
              comment: comment || null,
              supervisor_id: supervisor.id,
              reviewed_at: nowIso,
              updated_at: nowIso
            })
            .eq("id", existingAppr.id)
            .select("*")
            .single();
          savedApproval = updAppr;
        } else {
          const { data: insAppr } = await supabase
            .from("attendance_approvals")
            .insert({
              shift_id: shiftId,
              employee_id: targetShift.employee_id,
              project_id: targetShift.project_id,
              supervisor_id: supervisor.id,
              decision: decision,
              comment: comment || null,
              reviewed_at: nowIso,
              updated_at: nowIso
            })
            .select("*")
            .single();
          savedApproval = insAppr;
        }
      } catch (_e) {}

      // 3. Create Notification for the worker
      try {
        const notifTitle = decision === "APPROVED" ? "✅ Shift Attendance Approved" : "❌ Shift Attendance Rejected";
        const notifMsg = decision === "APPROVED"
          ? `Your attendance on ${targetShift.shift_date} was approved by ${supervisor.employee_name || "Supervisor"}.${comment ? ` Note: ${comment}` : ""}`
          : `Your attendance on ${targetShift.shift_date} was rejected by ${supervisor.employee_name || "Supervisor"}. Reason: ${comment}`;

        await supabase
          .from("notifications")
          .insert({
            recipient_id: targetShift.employee_id,
            type: "ATTENDANCE_APPROVAL",
            title: notifTitle,
            message: notifMsg,
            created_at: nowIso
          });
      } catch (_e) {}

      const enriched = updatedShift ? await enrichShifts([updatedShift]) : [];
      return new Response(JSON.stringify({
        approval: savedApproval ? {
          id: savedApproval.id,
          shift_id: savedApproval.shift_id,
          employee_id: savedApproval.employee_id,
          project_id: savedApproval.project_id,
          supervisor_id: savedApproval.supervisor_id,
          decision: savedApproval.decision,
          comment: savedApproval.comment,
          reviewed_at: savedApproval.reviewed_at,
          shift_date: targetShift.shift_date
        } : null,
        shift: enriched[0] ?? null,
        error: null
      }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // ACTION: pending_attendance_approvals
    // Queries the attendance_approvals table and returns pending attendance requests
    if (action === "pending_attendance_approvals") {
      // Sync any unreviewed shifts into attendance_approvals with 'PENDING'
      try {
        let openShiftsQuery = supabase
          .from("attendance_shifts")
          .select("id, employee_id, project_id, created_at")
          .or("status.eq.PENDING,status.eq.OPEN,status.eq.COMPLETED")
          .is("reviewed_at", null);

        if (allProjectIdentifiers.length > 0) {
          openShiftsQuery = openShiftsQuery.in("project_id", allProjectIdentifiers);
        }

        const { data: openShifts } = await openShiftsQuery;
        if (Array.isArray(openShifts) && openShifts.length > 0) {
          for (const s of openShifts) {
            const { data: existing } = await supabase
              .from("attendance_approvals")
              .select("id")
              .eq("shift_id", s.id)
              .maybeSingle();

            if (!existing) {
              await supabase.from("attendance_approvals").insert({
                shift_id: s.id,
                employee_id: s.employee_id,
                project_id: s.project_id,
                supervisor_id: supervisor.id,
                decision: "PENDING",
                reviewed_at: new Date().toISOString()
              });
            }
          }
        }
      } catch (_syncErr) {}

      let approvalsQuery = supabase
        .from("attendance_approvals")
        .select("*")
        .eq("decision", "PENDING")
        .order("created_at", { ascending: false });

      if (allProjectIdentifiers.length > 0) {
        approvalsQuery = approvalsQuery.in("project_id", allProjectIdentifiers);
      }

      const { data: approvalsData, error: apprErr } = await approvalsQuery;
      if (apprErr) {
        return new Response(JSON.stringify({ error: apprErr.message }), {
          status: 500,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      const enrichedApprovals = [];
      for (const a of (approvalsData || [])) {
        const { data: shift } = await supabase
          .from("attendance_shifts")
          .select("*")
          .eq("id", a.shift_id)
          .maybeSingle();

        const { data: emp } = await supabase
          .from("employees")
          .select("employee_name, employee_id, civil_id")
          .eq("id", a.employee_id)
          .maybeSingle();

        const { data: prj } = await supabase
          .from("projects")
          .select("project_name, project_code")
          .eq("id", a.project_id)
          .maybeSingle();

        enrichedApprovals.push({
          id: a.id,
          shift_id: a.shift_id,
          employee_id: a.employee_id,
          project_id: a.project_id,
          supervisor_id: a.supervisor_id,
          decision: a.decision,
          comment: a.comment,
          reviewed_at: a.reviewed_at,
          created_at: a.created_at,
          updated_at: a.updated_at,
          employee_name: emp?.employee_name ?? "Employee",
          employee_code: emp?.employee_id ?? emp?.civil_id ?? "",
          project_name: prj?.project_name ?? prj?.project_code ?? "Project Site",
          shift_date: shift?.shift_date ?? "",
          clock_in_time: shift?.clock_in_time ?? shift?.created_at,
          clock_out_time: shift?.clock_out_time,
          total_worked_minutes: shift?.total_worked_minutes,
          compliance_flag: shift?.compliance_flag ?? "VERIFIED",
          selfie_url: shift?.selfie_url ?? null
        });
      }

      return new Response(JSON.stringify({ approvals: enrichedApprovals, error: null }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // ACTION: attendance_roster
    if (action === "attendance_roster") {
      let query = supabase
        .from("attendance_shifts")
        .select("*")
        .order("created_at", { ascending: false })
        .limit(100);

      if (allProjectIdentifiers.length > 0) {
        query = query.in("project_id", allProjectIdentifiers);
      }

      const { data: shifts, error: shiftsErr } = await query;
      if (shiftsErr) {
        return new Response(JSON.stringify({ error: shiftsErr.message }), {
          status: 500,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      const dtos = await enrichShifts(shifts || []);
      return new Response(JSON.stringify({ shifts: dtos, error: null }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // ACTION: roster
    if (action === "roster") {
      let query = supabase
        .from("employees")
        .select("id, employee_name, employee_id, civil_id, employee_type, designation, is_active, assigned_project_id");

      if (allProjectIdentifiers.length > 0) {
        query = query.in("assigned_project_id", allProjectIdentifiers);
      }

      const { data: emps, error: empsErr } = await query;
      if (empsErr) {
        return new Response(JSON.stringify({ error: empsErr.message }), {
          status: 500,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      const roster = (emps || []).map((e: any) => ({
        id: e.id,
        employee_code: e.employee_id || e.civil_id || "",
        full_name: e.employee_name || "Employee",
        role: e.employee_type || e.designation || "Worker",
        employment_status: e.is_active ? "ACTIVE" : "INACTIVE",
        project: { name: "Assigned Project Site" }
      }));

      return new Response(JSON.stringify({ employees: roster, error: null }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // ACTION: sites
    if (action === "sites") {
      let query = supabase.from("projects").select("*");
      if (allProjectIdentifiers.length > 0) {
        query = query.in("id", allProjectIdentifiers);
      }

      const { data: prjs, error: prjsErr } = await query;
      if (prjsErr) {
        return new Response(JSON.stringify({ error: prjsErr.message }), {
          status: 500,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      const sites = (prjs || []).map((p: any) => ({
        id: p.id,
        project_code: p.project_code || "PRJ",
        name: p.project_name || p.project_code || "Project Site",
        address: p.location_address || null,
        geofence_radius_meters: p.geofence_radius_meters || 100,
        is_active: p.is_active ?? true,
        employees: []
      }));

      return new Response(JSON.stringify({ sites, error: null }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // ACTION: metrics
    if (action === "metrics") {
      let shiftQuery = supabase
        .from("attendance_shifts")
        .select("id, status, compliance_flag, reviewed_at");

      if (allProjectIdentifiers.length > 0) {
        shiftQuery = shiftQuery.in("project_id", allProjectIdentifiers);
      }

      const { data: shifts } = await shiftQuery;
      const allShifts = shifts || [];

      const pendingCount = allShifts.filter((s: any) =>
        (s.status === "PENDING" || s.status === "OPEN") && !s.reviewed_at
      ).length;
      const approvedCount = allShifts.filter((s: any) => s.status === "APPROVED").length;
      const rejectedCount = allShifts.filter((s: any) => s.status === "REJECTED").length;

      return new Response(JSON.stringify({
        total_workers_assigned: 0,
        present_count: approvedCount + pendingCount,
        working_count: pendingCount,
        pending_attendance_approvals: pendingCount,
        pending_leave_approvals: 0,
        on_leave_count: 0
      }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // ACTION: pending_leave
    if (action === "pending_leave") {
      return new Response(JSON.stringify({ leave_requests: [], error: null }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // ACTION: get_selfie_url or get_team_selfie_url
    if (action === "get_selfie_url" || action === "get_team_selfie_url") {
      const storagePath = body.storage_path;
      if (!storagePath) {
        return new Response(JSON.stringify({ error: "Missing storage_path." }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      // If already a full http/https URL, return directly
      if (storagePath.startsWith("http://") || storagePath.startsWith("https://")) {
        return new Response(JSON.stringify({ url: storagePath, error: null }), {
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      // Otherwise generate signed URL from Supabase storage
      try {
        const { data: signedData, error: signErr } = await supabase.storage
          .from("attendance-selfies")
          .createSignedUrl(storagePath, 3600);

        if (signErr || !signedData?.signedUrl) {
          const { data: publicUrl } = supabase.storage
            .from("attendance-selfies")
            .getPublicUrl(storagePath);
          return new Response(JSON.stringify({ url: publicUrl?.publicUrl ?? null, error: null }), {
            headers: { ...corsHeaders, "Content-Type": "application/json" },
          });
        }

        return new Response(JSON.stringify({ url: signedData.signedUrl, error: null }), {
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      } catch (e: any) {
        return new Response(JSON.stringify({ error: e.message }), {
          status: 500,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }
    }

    // Default fallback
    return new Response(JSON.stringify({ error: `Unknown action: ${action}` }), {
      status: 400,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });

  } catch (err: any) {
    return new Response(JSON.stringify({ error: err.message || "Internal server error" }), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});

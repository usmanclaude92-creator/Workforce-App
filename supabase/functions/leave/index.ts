import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

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
    const action = body.action?.toString().toLowerCase() ?? "my_requests";

    if (!token) {
      return new Response(
        JSON.stringify({ error: "Missing session token." }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Resolve the employee STRICTLY from a live, active session row for this exact
    // token -- matching `attendance`'s auth. Never a body-supplied employee_id, and
    // never "the oldest active employee on the whole system" as a fallback: that
    // previously let a request with no valid token at all read and submit leave
    // requests as an arbitrary employee. An invalid or unrecognized token is rejected
    // outright.
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

    const { data: emp } = await supabase
      .from("employees")
      .select("id, is_active")
      .eq("id", empId)
      .maybeSingle();

    if (!emp || !emp.is_active) {
      return new Response(
        JSON.stringify({ error: "Employee record not found or inactive." }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (action === "my_requests" || action.includes("request")) {
      const { data: requests } = await supabase
        .from("leave_requests")
        .select("*")
        .eq("employee_id", empId)
        .order("created_at", { ascending: false });

      const dtoList = (requests ?? []).map((r: any) => ({
        id: r.id,
        employee_id: r.employee_id,
        leave_type: r.leave_type ?? "ANNUAL",
        start_date: r.start_date,
        end_date: r.end_date,
        total_days: Number(r.total_days ?? 1),
        reason: r.reason ?? "",
        status: r.status ?? "APPROVED",
        decision_reason: r.decision_reason ?? null
      }));

      return new Response(
        JSON.stringify({
          leave_requests: dtoList,
          error: null
        }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (action === "submit") {
      const { data: newLeave, error: leaveErr } = await supabase
        .from("leave_requests")
        .insert({
          employee_id: empId,
          leave_type: body.leave_type ?? "ANNUAL",
          start_date: body.start_date,
          end_date: body.end_date,
          total_days: body.total_days ?? 1,
          reason: body.reason ?? "Leave request",
          status: "PENDING"
        })
        .select()
        .single();

      if (leaveErr) {
        console.error("[leave] submit insert failed:", JSON.stringify(leaveErr));
        return new Response(JSON.stringify({ error: leaveErr.message }), { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } });
      }

      return new Response(
        JSON.stringify({
          leave_request: {
            id: newLeave.id,
            employee_id: newLeave.employee_id,
            leave_type: newLeave.leave_type,
            start_date: newLeave.start_date,
            end_date: newLeave.end_date,
            total_days: Number(newLeave.total_days ?? 1),
            reason: newLeave.reason ?? "",
            status: newLeave.status ?? "PENDING"
          },
          error: null
        }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    return new Response(
      JSON.stringify({ leave_requests: [], error: null }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (err: any) {
    console.error("[leave] unhandled error:", err?.message, err?.stack);
    return new Response(
      JSON.stringify({ error: err.message ?? "Error" }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});

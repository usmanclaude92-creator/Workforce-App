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

    const body = await req.json().catch(() => ({}));
    const employeeId = (body.employee_id ?? body.employeeId)?.toString().trim();
    const deviceId = (body.device_id ?? body.deviceId)?.toString().trim();
    const refreshToken = (body.refresh_token ?? body.refreshToken)?.toString().trim();

    if (!employeeId || !deviceId || !refreshToken) {
      return new Response(
        JSON.stringify({ access_token: null, error: "employee_id, device_id and refresh_token are required.", needs_registration: true }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Previously this endpoint minted and returned a fresh token unconditionally, for
    // any request. Now the presented refresh token must correspond to a real, active
    // session bound to the same employee and device before a new one is issued.
    const { data: sess } = await supabase
      .from("device_sessions")
      .select("id, employee_id, device_id")
      .eq("session_token", refreshToken)
      .eq("employee_id", employeeId)
      .eq("device_id", deviceId)
      .eq("is_active", true)
      .maybeSingle();

    if (!sess) {
      return new Response(
        JSON.stringify({ access_token: null, error: "Session is no longer valid.", needs_registration: true }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const newToken = `session_${employeeId}_${crypto.randomUUID()}`;
    await supabase
      .from("device_sessions")
      .update({ session_token: newToken, last_active_at: new Date().toISOString() })
      .eq("id", sess.id);

    return new Response(
      JSON.stringify({ access_token: newToken, error: null, needs_registration: false }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (err: any) {
    return new Response(
      JSON.stringify({ access_token: null, error: err.message ?? "Error", needs_registration: false }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});

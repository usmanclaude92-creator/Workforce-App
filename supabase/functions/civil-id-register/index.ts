import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    const supabase = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
    );

    const body = await req.json();
    const inputId = (body.civil_id ?? body.civilId)?.toString().trim();
    const deviceId = (body.device_id ?? body.deviceId)?.toString().trim();
    // No default: a missing PIN used to silently become "1234", which meant registration
    // with no PIN at all still produced a working, guessable-PIN account.
    const pin = (body.pin ?? "").toString().trim();

    if (!inputId || !deviceId) {
      return new Response(
        JSON.stringify({ eligible: false, access_token: null, refresh_token: null, employee: null, error: "Civil ID and Device ID are required." }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }
    if (!/^[0-9]{4,6}$/.test(pin)) {
      return new Response(
        JSON.stringify({ eligible: false, access_token: null, refresh_token: null, employee: null, error: "A 4-6 digit PIN is required." }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 1. Hash PIN
    const encoder = new TextEncoder();
    const hashBuffer = await crypto.subtle.digest("SHA-256", encoder.encode(pin));
    const pinHash = Array.from(new Uint8Array(hashBuffer)).map((b) => b.toString(16).padStart(2, "0")).join("");

    // 2. Lookup in HCMS master database by Civil ID ONLY. Matching the internal
    // employee_id here (removed) let anyone who knew/guessed a person's employee code
    // register as them -- civil_id is the one identifier meant to prove identity.
    const { data: emp } = await supabase
      .from("employees")
      .select("id, employee_name, employee_id, civil_id, employee_type, assigned_project_id, is_active")
      .eq("civil_id", inputId)
      .eq("is_active", true)
      .maybeSingle();

    if (!emp) {
      return new Response(
        JSON.stringify({ eligible: false, access_token: null, refresh_token: null, employee: null, error: "Civil ID does not match any active employee record in HCMS." }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 3. Save auth
    await supabase
      .from("workforce_auth")
      .upsert({
        employee_id: emp.id,
        civil_id: inputId,
        pin_hash: pinHash,
        registered_device_id: deviceId,
        device_model: body.device_model ?? "Android Device",
        is_active: true,
        failed_pin_attempts: 0,
        locked_until: null,
        last_login_at: new Date().toISOString()
      }, { onConflict: "civil_id" });

    await supabase
      .from("civil_id_lookup")
      .upsert({
        civil_id: inputId,
        employee_code: emp.employee_id,
        full_name: emp.employee_name,
        used: true,
        used_by_employee_id: emp.id
      }, { onConflict: "civil_id" });

    const sessionToken = `session_${emp.id}_${crypto.randomUUID()}`;

    await supabase
      .from("device_sessions")
      .insert({
        employee_id: emp.id,
        device_id: deviceId,
        session_token: sessionToken,
        is_active: true
      });

    return new Response(
      JSON.stringify({
        eligible: true,
        access_token: sessionToken,
        refresh_token: sessionToken,
        employee: {
          id: emp.id,
          employee_code: emp.employee_id ?? inputId,
          full_name: emp.employee_name,
          role: (emp.employee_type ?? "STAFF").toUpperCase(),
          assigned_project_id: emp.assigned_project_id ?? null,
          is_demo: false
        },
        error: null
      }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (err: any) {
    return new Response(
      JSON.stringify({ eligible: false, access_token: null, refresh_token: null, employee: null, error: err.message }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});

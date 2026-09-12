import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

const MAX_ATTEMPTS = 5;
const LOCKOUT_SECONDS = 900;

async function sha256Hex(input: string): Promise<string> {
  const encoder = new TextEncoder();
  const hashBuffer = await crypto.subtle.digest("SHA-256", encoder.encode(input));
  return Array.from(new Uint8Array(hashBuffer)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    const supabase = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
    );

    const body = await req.json();
    const identifier = (body.employee_id ?? body.employeeId ?? body.civil_id ?? body.civilId)?.toString().trim();
    const deviceId = (body.device_id ?? body.deviceId)?.toString().trim();
    const pin = (body.pin ?? "").toString().trim();

    if (!identifier || !pin || !deviceId) {
      return new Response(
        JSON.stringify({ access_token: null, employee: null, error: "ID, device ID and PIN are required." }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Look up employee by Civil ID only. Matching on the internal employee_id as well
    // (as this endpoint previously did) let anyone who could guess a sequential employee
    // code log in as that person with any PIN once combined with the (now-fixed) missing
    // PIN check below -- civil_id is the only identifier meant to authenticate a person.
    const { data: emp } = await supabase
      .from("employees")
      .select("id, employee_name, employee_id, civil_id, employee_type, assigned_project_id, is_active")
      .eq("civil_id", identifier)
      .eq("is_active", true)
      .maybeSingle();

    if (!emp) {
      return new Response(
        JSON.stringify({ access_token: null, employee: null, error: "Employee record not found in HCMS database.", needs_registration: true }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const { data: auth } = await supabase
      .from("workforce_auth")
      .select("pin_hash, registered_device_id, is_active, failed_pin_attempts, locked_until")
      .eq("employee_id", emp.id)
      .maybeSingle();

    if (!auth || !auth.is_active || !auth.pin_hash) {
      return new Response(
        JSON.stringify({ access_token: null, employee: null, error: "Device not registered.", needs_registration: true }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Device binding: a PIN registered on one device must not authenticate a different one.
    if (auth.registered_device_id && auth.registered_device_id !== deviceId) {
      return new Response(
        JSON.stringify({ access_token: null, employee: null, error: "This account is registered to a different device.", needs_registration: true }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Server-side lockout (mirrors the client's own local lockout, but this is the copy
    // that actually matters since a direct API caller does not go through the app).
    const now = Date.now();
    if (auth.locked_until && new Date(auth.locked_until).getTime() > now) {
      const secondsRemaining = Math.ceil((new Date(auth.locked_until).getTime() - now) / 1000);
      return new Response(
        JSON.stringify({ access_token: null, employee: null, error: "Too many attempts. Try again later.", locked_for_seconds: secondsRemaining }),
        { status: 423, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const submittedHash = await sha256Hex(pin);
    if (submittedHash !== auth.pin_hash) {
      const attempts = (auth.failed_pin_attempts ?? 0) + 1;
      if (attempts >= MAX_ATTEMPTS) {
        const lockedUntil = new Date(now + LOCKOUT_SECONDS * 1000).toISOString();
        await supabase.from("workforce_auth").update({ failed_pin_attempts: attempts, locked_until: lockedUntil }).eq("employee_id", emp.id);
        return new Response(
          JSON.stringify({ access_token: null, employee: null, error: "Too many attempts. Try again later.", locked_for_seconds: LOCKOUT_SECONDS }),
          { status: 423, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }
      await supabase.from("workforce_auth").update({ failed_pin_attempts: attempts }).eq("employee_id", emp.id);
      return new Response(
        JSON.stringify({ access_token: null, employee: null, error: "Incorrect PIN.", attempts_remaining: MAX_ATTEMPTS - attempts }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Success: reset the counter and issue a session.
    await supabase.from("workforce_auth").update({ failed_pin_attempts: 0, locked_until: null, last_login_at: new Date().toISOString() }).eq("employee_id", emp.id);

    const sessionToken = `session_${emp.id}_${crypto.randomUUID()}`;
    await supabase.from("device_sessions").insert({
      employee_id: emp.id,
      device_id: deviceId,
      session_token: sessionToken,
      is_active: true,
    });

    return new Response(
      JSON.stringify({
        access_token: sessionToken,
        employee: {
          id: emp.id,
          employee_code: emp.employee_id ?? identifier,
          full_name: emp.employee_name,
          role: (emp.employee_type ?? "STAFF").toUpperCase(),
          assigned_project_id: emp.assigned_project_id ?? null,
          is_demo: false,
        },
        error: null,
        needs_registration: false,
      }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (err: any) {
    return new Response(
      JSON.stringify({ access_token: null, employee: null, error: err.message }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});

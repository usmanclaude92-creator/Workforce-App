import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, x-integration-secret",
};

serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    // This endpoint was previously reachable by anyone holding the (public) anon key:
    // the CORS headers advertised x-integration-secret but the function body never
    // actually checked it. It now fails closed both when the header is missing/wrong
    // AND when the server-side secret itself has not been configured.
    const expectedSecret = Deno.env.get("WORKFORCE_INTEGRATION_SECRET");
    const providedSecret = req.headers.get("x-integration-secret") ?? req.headers.get("X-Integration-Secret");
    if (!expectedSecret || !providedSecret || providedSecret !== expectedSecret) {
      return new Response(
        JSON.stringify({ error: "Unauthorized." }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const supabase = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
    );

    const body = await req.json().catch(() => ({}));
    const employees = body.employees ?? [];

    let upserted = 0;
    for (const emp of employees) {
      if (emp.civil_id) {
        const { error } = await supabase
          .from("civil_id_lookup")
          .upsert({
            civil_id: emp.civil_id,
            company_code: emp.company_code ?? "DGO",
            employee_code: emp.employee_code ?? emp.civil_id,
            full_name: emp.full_name,
            role: emp.role ?? "STAFF",
            department: emp.department ?? "General",
            phone: emp.phone ?? null,
            used: false
          }, { onConflict: "civil_id" });
        if (!error) upserted++;
      }
    }

    return new Response(
      JSON.stringify({
        companies: 1,
        projects_created: 0,
        lookup_upserted: upserted,
        employees_refreshed: 0,
        skipped: employees.length - upserted
      }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (err: any) {
    return new Response(
      JSON.stringify({ error: err.message }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});

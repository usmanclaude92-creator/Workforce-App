# Supabase Edge Functions — Artify Workforce

These were previously deployed to the `jpsiafvbyupofnbqonkq` project directly, with no copy
tracked in either this repo or `hcm` — this directory is that copy, so changes go through
review instead of being edited only in the Supabase dashboard.

## What changed here vs. what is currently deployed (2026-09-12)

All five were rewritten to close an authentication bypass discovered in audit and, for
`pin-login` and `civil-id-register`, deployed to production. `attendance`,
`refresh-session` and `sync-eligibility` are drafted here but **not yet deployed** (see
below for why, and how to finish it).

- **`pin-login`** — previously checked only that a PIN was *present*, never that it matched
  the employee's stored hash. Any employee/civil ID plus any non-empty PIN returned a
  working session. Now hashes the submitted PIN and compares it, enforces the device
  binding set at registration, and adds server-side lockout after 5 failed attempts
  (`workforce_auth.failed_pin_attempts` / `locked_until`, added in migration 008).
- **`civil-id-register`** — previously defaulted a missing PIN to `"1234"` and matched on
  either Civil ID or the internal (often sequential) `employee_id`. Now requires a real
  4-6 digit PIN and matches on Civil ID only.
- **`attendance`** — previously fell back to "the most recently active session on the
  entire system" when no valid token was found, and separately accepted a body-supplied
  `employee_id`/`civil_id` to resolve identity — either path let an unauthenticated or
  wrongly-authenticated caller clock in/out as an arbitrary employee. Now resolves the
  employee strictly from an active `device_sessions` row matching the presented token, or
  rejects with 401. It also replaces the hardcoded `geofence_status: "INSIDE"` /
  `is_mock_location: false` (the app already sends real `latitude`/`longitude`/
  `is_mock_location` — the server was simply discarding them) with a real haversine
  distance check against `projects.latitude/longitude/geofence_radius_meters` (added in
  migration 008; existing projects have NULL coordinates until someone fills them in --
  until then geofence status reports `UNKNOWN`/`NEEDS_REVIEW` rather than a fabricated
  `INSIDE`).
- **`refresh-session`** — previously issued a valid-looking token to any request,
  unconditionally. Now validates the presented refresh token against an active
  `device_sessions` row for that employee/device before issuing a new one.
- **`sync-eligibility`** — declared `x-integration-secret` in its CORS headers but never
  actually checked it, so anyone holding the (public) anon key could inject arbitrary rows
  into the `civil_id_lookup` eligibility whitelist. Now requires it to match the
  `WORKFORCE_INTEGRATION_SECRET` Edge Function secret, and fails closed if that secret
  isn't set.

## Deploying

```bash
supabase login
supabase link --project-ref jpsiafvbyupofnbqonkq
supabase functions deploy pin-login             # already deployed 2026-09-12, safe to redeploy
supabase functions deploy civil-id-register     # already deployed 2026-09-12, safe to redeploy
supabase functions deploy attendance            # NOT yet deployed -- see below
supabase functions deploy refresh-session       # NOT yet deployed
supabase functions deploy sync-eligibility      # NOT yet deployed -- set the secret first (below)
```

`sync-eligibility` will reject every call (including hcm's legitimate ones) until a
matching secret exists on both sides:

```bash
supabase secrets set WORKFORCE_INTEGRATION_SECRET=<a long random value> --project-ref jpsiafvbyupofnbqonkq
```

...and the same value goes into hcm's `WORKFORCE_INTEGRATION_SECRET` environment variable
(Vercel). Whatever value was previously implied by the old client-side default
(`'artify-secret'`) should be treated as already compromised (it sat in `hcm`'s public
source) — pick a fresh one.

## Still open (not fixed here)

- `register_workforce_staff` — a SECURITY DEFINER Postgres function directly callable by
  `anon`/`authenticated` over PostgREST RPC, bypassing all of the above entirely (caller
  supplies their own `pin_hash`). Migration `005_revoke_dangerous_anon_execute.sql`
  (`db/migrations` in `hcm`, mirrored in this repo's `supabase/migrations/`) revokes
  `EXECUTE` on it and on `rls_auto_enable()`. **Not yet applied to production** — the
  automated session that drafted this was blocked from applying it by a platform-level
  production-safety control; apply it manually (see that file).
- 18 functions have a mutable `search_path` (standard Postgres hardening item, migration
  `006_function_search_path_hardening.sql`) — same status, not yet applied.
- The HMAC request-signing scheme (`CryptoRequestSigner` in the Android app) is not
  verified by any of these functions. It was also, until this pass, never actually
  triggered (see `ApiClient.kt`'s fixed path-matching) — treat both the signing and its
  server-side verification as a follow-up, not a dependency of the fixes above.

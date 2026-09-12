# Supabase Edge Functions — Artify Workforce

These were previously deployed to the `jpsiafvbyupofnbqonkq` project directly, with no copy
tracked in either this repo or `hcm` — this directory is that copy, so changes go through
review instead of being edited only in the Supabase dashboard.

## What changed here vs. what is currently deployed (2026-09-12)

All five were rewritten to close an authentication bypass discovered in audit.
**Four of the five are now deployed to production**: `pin-login` (v4), `civil-id-register`
(v10), `attendance` (v17), `refresh-session` (v3). Only **`sync-eligibility` remains
undeployed**, held back deliberately — it needs `WORKFORCE_INTEGRATION_SECRET` set as a
Supabase Edge Function secret first (no tool access to set Edge Function secrets from this
session), and deploying it without that secret configured would also break hcm's own
legitimate calls.

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

`pin-login`, `civil-id-register`, `attendance` and `refresh-session` are already deployed
(versions 4, 10, 17, 3). Only `sync-eligibility` is still outstanding:

```bash
supabase login
supabase link --project-ref jpsiafvbyupofnbqonkq
supabase functions deploy sync-eligibility      # set the secret first (below), or this starts rejecting hcm's calls too
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
  supplies their own `pin_hash`). `hcm`'s `db/migrations/005_revoke_dangerous_anon_execute.sql`
  revokes `EXECUTE` on it and on `rls_auto_enable()`. **Applied to production 2026-09-12** —
  verified: only `postgres`/`service_role` remain as grantees.
- 18 functions had a mutable `search_path` (standard Postgres hardening item, migration
  `006_function_search_path_hardening.sql`). **Applied to production 2026-09-12** — verified
  via the security advisor, which no longer flags `function_search_path_mutable`.
- The HMAC request-signing scheme (`CryptoRequestSigner` in the Android app) is not
  verified by any of these functions. It was also, until this pass, never actually
  triggered (see `ApiClient.kt`'s fixed path-matching) — treat both the signing and its
  server-side verification as a follow-up, not a dependency of the fixes above.

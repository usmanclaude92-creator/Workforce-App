# Supabase Migrations — Attendance Approvals & Project Supervisors

This directory contains database schema migrations for the Artify Workforce management platform on Supabase (`jpsiafvbyupofnbqonkq`).

## Migrations Added

### `009_attendance_approvals_and_project_supervisors.sql` / `20260915103000_create_attendance_approvals_and_project_supervisors.sql`

This migration creates two core relational tables supporting the attendance review and approval workflow:

### 1. `public.project_supervisors`
Maps site supervisors, project managers, and lead engineers to specific projects.
- **`id`**: UUID primary key (`gen_random_uuid()`)
- **`project_id`**: UUID referencing `public.projects(id)`
- **`project_code`**: Optional short project code (e.g., `PRJ-001`)
- **`supervisor_id`**: UUID of supervisor in `public.employees` or `public.workforce_auth`
- **`supervisor_employee_id`**: Optional civil/employee ID for alphanumeric lookup
- **`role`**: `SUPERVISOR`, `PROJECT_MANAGER`, `SITE_ENGINEER`, `FOREMAN`
- **`is_active`**: Boolean flag indicating current active assignment
- **`assigned_at`**: Timestamptz when supervisor was assigned
- **`assigned_by`**: UUID of manager/admin who created the assignment
- **Constraints & Indexes**:
  - `UNIQUE(project_id, supervisor_id, role)`
  - Indexes on `project_id`, `project_code`, `supervisor_id`, `supervisor_employee_id`, and active status.
- **Row Level Security**: RLS enabled with permissive SELECT for active assignments and full access for `service_role`.

### 2. `public.attendance_approvals`
Audit ledger capturing every approval or rejection decision executed by supervisors on worker attendance shifts.
- **`id`**: UUID primary key (`gen_random_uuid()`)
- **`shift_id`**: UUID referencing `public.attendance_shifts(id)`
- **`employee_id`**: UUID of the worker whose shift was reviewed
- **`project_id`**: UUID of the associated project
- **`supervisor_id`**: UUID of the reviewing project manager or supervisor
- **`decision`**: `APPROVED` or `REJECTED` (with check constraint)
- **`comment`**: Review notes or mandatory rejection reason
- **`reviewed_at`**: Timestamptz of review decision
- **`metadata`**: Extensible JSONB payload for GPS/device audit details
- **Constraints & Indexes**:
  - Indexes on `shift_id`, `employee_id`, `project_id`, `supervisor_id`, `decision`, and `reviewed_at DESC`.
- **Row Level Security**: RLS enabled with SELECT, INSERT, and UPDATE policies for authenticated and service roles.

---

## How to Apply to Production Supabase Database

### Option A: Supabase Dashboard SQL Editor (Recommended & Instant)
1. Open the [Supabase Dashboard](https://supabase.com/dashboard/project/jpsiafvbyupofnbqonkq).
2. Navigate to **SQL Editor** from the left navigation.
3. Paste the contents of `supabase/migrations/009_attendance_approvals_and_project_supervisors.sql`.
4. Click **Run**. The tables, indexes, RLS policies, and triggers will be deployed immediately.

### Option B: Supabase CLI
```bash
supabase link --project-ref jpsiafvbyupofnbqonkq
supabase db push
```

-- ============================================================================
-- Migration: 20260915103000_create_attendance_approvals_and_project_supervisors.sql
-- Description: Create 'project_supervisors' and 'attendance_approvals' tables
-- for linking attendance records with assigned project managers / supervisors.
-- ============================================================================

-- Ensure required cryptographic extensions are available
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ============================================================================
-- 1. Table: project_supervisors
-- Links projects to assigned supervisors and project managers.
-- Allows project managers to be linked via project_id and project_code.
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.project_supervisors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NULL,
    project_code TEXT NULL,
    supervisor_id UUID NOT NULL,
    supervisor_employee_id TEXT NULL,
    role TEXT NOT NULL DEFAULT 'SUPERVISOR', -- 'PROJECT_MANAGER', 'SUPERVISOR', 'SITE_ENGINEER', 'FOREMAN'
    is_active BOOLEAN NOT NULL DEFAULT true,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_by UUID NULL,
    notes TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_project_supervisor_role UNIQUE (project_id, supervisor_id, role)
);

-- Comments for database documentation
COMMENT ON TABLE public.project_supervisors IS 'Associates project managers and site supervisors with projects for shift approvals and compliance monitoring';
COMMENT ON COLUMN public.project_supervisors.project_id IS 'UUID reference to public.projects(id)';
COMMENT ON COLUMN public.project_supervisors.project_code IS 'Optional project alphanumeric code (e.g. PRJ-001) for fast code-based mapping';
COMMENT ON COLUMN public.project_supervisors.supervisor_id IS 'UUID of the supervisor / manager in public.employees or public.workforce_auth';
COMMENT ON COLUMN public.project_supervisors.supervisor_employee_id IS 'Optional internal or civil ID for string-based employee lookup';
COMMENT ON COLUMN public.project_supervisors.role IS 'Assignment role: PROJECT_MANAGER, SUPERVISOR, SITE_ENGINEER, FOREMAN';

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_project_supervisors_project_id 
    ON public.project_supervisors(project_id);

CREATE INDEX IF NOT EXISTS idx_project_supervisors_project_code 
    ON public.project_supervisors(project_code);

CREATE INDEX IF NOT EXISTS idx_project_supervisors_supervisor_id 
    ON public.project_supervisors(supervisor_id);

CREATE INDEX IF NOT EXISTS idx_project_supervisors_sup_emp_id 
    ON public.project_supervisors(supervisor_employee_id);

CREATE INDEX IF NOT EXISTS idx_project_supervisors_active 
    ON public.project_supervisors(is_active) 
    WHERE is_active = true;

-- Graceful foreign key link to projects table if table exists with UUID id
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables 
        WHERE table_schema = 'public' AND table_name = 'projects'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.columns 
            WHERE table_schema = 'public' AND table_name = 'projects' 
            AND column_name = 'id' AND data_type = 'uuid'
        ) THEN
            IF NOT EXISTS (
                SELECT 1 FROM information_schema.table_constraints 
                WHERE constraint_name = 'fk_project_supervisors_project'
            ) THEN
                ALTER TABLE public.project_supervisors
                ADD CONSTRAINT fk_project_supervisors_project
                FOREIGN KEY (project_id) REFERENCES public.projects(id)
                ON DELETE CASCADE;
            END IF;
        END IF;
    END IF;
END $$;


-- ============================================================================
-- 2. Table: attendance_approvals
-- Audit trail linking shift attendance records with the reviewing project manager / supervisor.
-- Records approval/rejection decisions, rejection reasons, and review timestamps.
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.attendance_approvals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shift_id UUID NOT NULL,
    employee_id UUID NOT NULL,
    project_id UUID NULL,
    supervisor_id UUID NOT NULL,
    decision TEXT NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED', 'PENDING')),
    comment TEXT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata JSONB NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Comments for database documentation
COMMENT ON TABLE public.attendance_approvals IS 'Audit ledger of supervisor and project manager decisions on worker attendance shifts';
COMMENT ON COLUMN public.attendance_approvals.shift_id IS 'UUID reference to public.attendance_shifts(id)';
COMMENT ON COLUMN public.attendance_approvals.employee_id IS 'UUID of the worker whose shift was reviewed';
COMMENT ON COLUMN public.attendance_approvals.project_id IS 'UUID of the project where attendance occurred';
COMMENT ON COLUMN public.attendance_approvals.supervisor_id IS 'UUID of the reviewing project manager or supervisor';
COMMENT ON COLUMN public.attendance_approvals.decision IS 'Decision status: APPROVED or REJECTED';
COMMENT ON COLUMN public.attendance_approvals.comment IS 'Reviewer notes or mandatory rejection reason';
COMMENT ON COLUMN public.attendance_approvals.reviewed_at IS 'Timestamp when the approval/rejection was executed';

-- High-performance indexes for lookups & reporting
CREATE INDEX IF NOT EXISTS idx_attendance_approvals_shift_id 
    ON public.attendance_approvals(shift_id);

CREATE INDEX IF NOT EXISTS idx_attendance_approvals_employee_id 
    ON public.attendance_approvals(employee_id);

CREATE INDEX IF NOT EXISTS idx_attendance_approvals_project_id 
    ON public.attendance_approvals(project_id);

CREATE INDEX IF NOT EXISTS idx_attendance_approvals_supervisor_id 
    ON public.attendance_approvals(supervisor_id);

CREATE INDEX IF NOT EXISTS idx_attendance_approvals_decision 
    ON public.attendance_approvals(decision);

CREATE INDEX IF NOT EXISTS idx_attendance_approvals_reviewed_at 
    ON public.attendance_approvals(reviewed_at DESC);

-- Graceful foreign key link to attendance_shifts table if table exists with UUID id
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables 
        WHERE table_schema = 'public' AND table_name = 'attendance_shifts'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.columns 
            WHERE table_schema = 'public' AND table_name = 'attendance_shifts' 
            AND column_name = 'id' AND data_type = 'uuid'
        ) THEN
            IF NOT EXISTS (
                SELECT 1 FROM information_schema.table_constraints 
                WHERE constraint_name = 'fk_attendance_approvals_shift'
            ) THEN
                ALTER TABLE public.attendance_approvals
                ADD CONSTRAINT fk_attendance_approvals_shift
                FOREIGN KEY (shift_id) REFERENCES public.attendance_shifts(id)
                ON DELETE CASCADE;
            END IF;
        END IF;
    END IF;
END $$;


-- ============================================================================
-- 3. Row Level Security (RLS) & Access Control
-- ============================================================================
ALTER TABLE public.project_supervisors ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.attendance_approvals ENABLE ROW LEVEL SECURITY;

-- Grant permissions to standard PostgREST API roles
GRANT ALL ON TABLE public.project_supervisors TO anon, authenticated, service_role;
GRANT ALL ON TABLE public.attendance_approvals TO anon, authenticated, service_role;

-- RLS Policies for project_supervisors
DROP POLICY IF EXISTS "project_supervisors_select_policy" ON public.project_supervisors;
CREATE POLICY "project_supervisors_select_policy" ON public.project_supervisors
    FOR SELECT USING (true);

DROP POLICY IF EXISTS "project_supervisors_service_role_policy" ON public.project_supervisors;
CREATE POLICY "project_supervisors_service_role_policy" ON public.project_supervisors
    FOR ALL TO service_role USING (true) WITH CHECK (true);

-- RLS Policies for attendance_approvals
DROP POLICY IF EXISTS "attendance_approvals_select_policy" ON public.attendance_approvals;
CREATE POLICY "attendance_approvals_select_policy" ON public.attendance_approvals
    FOR SELECT USING (true);

DROP POLICY IF EXISTS "attendance_approvals_insert_policy" ON public.attendance_approvals;
CREATE POLICY "attendance_approvals_insert_policy" ON public.attendance_approvals
    FOR INSERT WITH CHECK (true);

DROP POLICY IF EXISTS "attendance_approvals_update_policy" ON public.attendance_approvals;
CREATE POLICY "attendance_approvals_update_policy" ON public.attendance_approvals
    FOR UPDATE USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS "attendance_approvals_service_role_policy" ON public.attendance_approvals;
CREATE POLICY "attendance_approvals_service_role_policy" ON public.attendance_approvals
    FOR ALL TO service_role USING (true) WITH CHECK (true);


-- ============================================================================
-- 4. Automatic Timestamp Update Triggers
-- ============================================================================
CREATE OR REPLACE FUNCTION public.set_updated_at_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_project_supervisors_updated_at ON public.project_supervisors;
CREATE TRIGGER trg_project_supervisors_updated_at
    BEFORE UPDATE ON public.project_supervisors
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at_timestamp();

DROP TRIGGER IF EXISTS trg_attendance_approvals_updated_at ON public.attendance_approvals;
CREATE TRIGGER trg_attendance_approvals_updated_at
    BEFORE UPDATE ON public.attendance_approvals
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at_timestamp();

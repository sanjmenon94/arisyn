-- Additive only: authoritative session lifecycle timestamps, replacing the fragile
-- "derive duration from set_log timestamps" approach (broke down to 0 min for single-set sessions).
alter table public.sessions add column if not exists started_at timestamptz;
alter table public.sessions add column if not exists completed_at timestamptz;

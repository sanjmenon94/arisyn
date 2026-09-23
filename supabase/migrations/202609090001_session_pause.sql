-- Additive only: pause/resume bookkeeping for the in-session workout timer.
-- Actual elapsed time = now - started_at - paused_duration_ms (plus the open gap while paused_at is set).
alter table public.sessions add column if not exists paused_at timestamptz;
alter table public.sessions add column if not exists paused_duration_ms bigint not null default 0;

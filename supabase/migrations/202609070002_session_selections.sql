-- Additive only: retain the actual duration/intensity choices with a completed session.
alter table public.sessions add column if not exists selected_duration_minutes int;
alter table public.sessions add column if not exists selected_intensity text;

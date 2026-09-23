-- Additive only: marks a set logged beyond the exercise's target_sets at the time it was
-- logged. Stored rather than derived, since target_sets can change as the program evolves
-- and a derived flag would drift for historical records.
alter table public.set_logs add column if not exists is_extra boolean not null default false;

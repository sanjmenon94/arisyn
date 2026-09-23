# Migration history

## 2026-09-07 — `202609070001_initial.sql`

Created the persistent Training schema: profiles, shared read-only exercise catalogue, user-owned program days/exercises, sessions, set logs, body metrics, and cardio sessions. All tables have RLS enabled in this migration. All mutable primary keys are client-generated UUIDs, which makes upserts safe to retry.

Added `exercises.is_time_based` as part of the initial, additive schema. It is `false` by default and allows Plank to be displayed and logged in seconds without overloading reps. Future database changes must be additive or provide a reversible, backfilled transition; never drop or rename stored data in place.

## 2026-09-07 — `202609070002_session_selections.sql`

Additive nullable `sessions.selected_duration_minutes` and `sessions.selected_intensity` fields. They preserve the deterministic workout choices used when a session was logged; existing sessions remain valid with null values.

## 2026-09-07 — `202609070003_exercise_images.sql`

Additive nullable `exercises.image_url`, pointing at the public `exercise-images` Storage bucket. Mirrored locally as Room migration `MIGRATION_1_2` (`ALTER TABLE exercises ADD COLUMN imageUrl TEXT`), bumping the local db version to 2 so existing installs keep their session/set-log history.

## 2026-09-07 — `202609070004_session_timing.sql`

Additive nullable `sessions.started_at`/`sessions.completed_at`. These are now the source of truth for "is a workout in progress" (`started_at` set, `completed_at` null) and for session duration, replacing the old derive-from-set-log-timestamps approach that showed "0 min" whenever a session had only one logged set. Mirrored locally as Room migration `MIGRATION_2_3`, bumping the local db version to 3.

## 2026-09-09 — `202609090001_session_pause.sql`

Additive `sessions.paused_at` (nullable) and `sessions.paused_duration_ms` (default 0) for the in-session timer's pause/resume control. Elapsed time is `now - started_at - paused_duration_ms`, adjusted for any currently-open pause gap. Mirrored locally as Room migration `MIGRATION_3_4`, bumping the local db version to 4. Note: "day completed" status (weekly strip, streak, Progress list) is derived only from `completed_at IS NOT NULL` — never from set_logs or from a session merely existing — fixed in `observeSessionSummaries()` after a bug where ticking then un-ticking a set could still leave the day marked done.

## 2026-09-09 — `202609090002_set_logs_extra.sql`

Additive `set_logs.is_extra` (default false), marking a set logged beyond the exercise's `target_sets` at the time it was logged. Stored rather than derived at query time, since `target_sets` can change as the program progresses and a derived flag would drift for historical records. Mirrored locally as Room migration `MIGRATION_4_5`, bumping the local db version to 5.

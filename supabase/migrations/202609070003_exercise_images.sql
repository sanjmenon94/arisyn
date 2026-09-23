-- Additive only: public exercise-image URLs, sourced from the exercise-images Storage bucket.
alter table public.exercises add column if not exists image_url text;

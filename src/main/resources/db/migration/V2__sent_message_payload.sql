-- The chat ref is a keyed hash and cannot be reversed, but editing or deleting a
-- message needs the real chat id. Keep it sealed like every other identity.
--
-- Safe as NOT NULL: nothing writes to sent_message before this task (ButtonService's
-- strip was a no-op seam through Task 10), so the table is empty in every deployment
-- that reaches this migration.
ALTER TABLE sent_message ADD COLUMN payload BYTEA NOT NULL;

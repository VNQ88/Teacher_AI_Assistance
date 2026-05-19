-- Drop classroom FK from documents and chat_sessions, then drop classroom tables
ALTER TABLE documents DROP COLUMN IF EXISTS classroom_id;
ALTER TABLE chat_sessions DROP COLUMN IF EXISTS classroom_id;
DROP TABLE IF EXISTS user_classroom;
DROP TABLE IF EXISTS classrooms;

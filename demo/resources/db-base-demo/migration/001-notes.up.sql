-- The host's schema, and the host's SQL: §3 says the engine is the host's business and
-- this one brought SQLite, whose INTEGER PRIMARY KEY is its rowid and fills itself.
-- db-base neither writes this nor reads it: it finds the file under the classpath prefix
-- the host named, applies it once, and records it in ragtime_migrations.
CREATE TABLE note (id INTEGER PRIMARY KEY, body VARCHAR(200) NOT NULL, written_at BIGINT NOT NULL)

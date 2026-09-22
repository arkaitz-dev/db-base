-- Who has an account. The subject is a UUID this host mints at registration and
-- never a rowid: the value is frozen into the session, read back on every request
-- and handed to `revoke!`, so letting a storage artefact be the identity would put
-- one engine's numbering into auth-base's domain. It also makes this the first
-- consumer to put a tagged value through db-base's session store, which carries
-- EDN and reads `#uuid` without being asked for a reader.
--
-- Unlike the first demo's schema, nothing here is SQLite-shaped: config.edn offers
-- PostgreSQL as a one-line change, and a `INTEGER PRIMARY KEY` rowid would not
-- survive it. The identifier is unique because two rows for one address is an
-- account somebody can never log into again.
CREATE TABLE account (subject VARCHAR(36) NOT NULL PRIMARY KEY,
                      identifier VARCHAR(320) NOT NULL UNIQUE,
                      created_at BIGINT NOT NULL)

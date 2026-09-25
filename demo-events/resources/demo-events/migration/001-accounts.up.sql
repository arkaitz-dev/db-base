-- Who has an account. The subject is a UUID this host mints at registration and
-- never a rowid: the value is frozen into the session, read back on every request
-- and handed to `revoke!`, so letting a storage artefact be the identity would put
-- one engine's numbering into auth-base's domain.
--
-- It is the UUID's **spelling** and not a `java.util.UUID`, measured in the running
-- host: the session row holds `:ab/subject "d4dccdae-…"` and not `#uuid "…"`. So this
-- host does NOT exercise db-base §8's tagged-value round trip, and its `:readers`
-- surface still has no consumer anywhere. Carrying the object instead would put a
-- conversion at every SQL boundary in three namespaces, which is a poor trade for
-- coverage of somebody else's line — but the gap is real and is recorded rather than
-- papered over.
--
-- Unlike the first demo's schema, nothing here is SQLite-shaped: config.edn offers
-- PostgreSQL as a one-line change, and a `INTEGER PRIMARY KEY` rowid would not
-- survive it. The identifier is unique because two rows for one address is an
-- account somebody can never log into again.
CREATE TABLE account (subject VARCHAR(36) NOT NULL PRIMARY KEY,
                      identifier VARCHAR(320) NOT NULL UNIQUE,
                      created_at BIGINT NOT NULL)

-- The revocation generation of auth-base §10, and a table of its own on purpose.
-- Its port says `bump-generation!` "must work for a subject with no account row",
-- because a bootstrap identity holds sessions while having no record anywhere — so
-- a generation column on `account` could never be moved for exactly the subjects
-- that most need revoking. There is deliberately no foreign key here for the same
-- reason.
--
-- BIGINT and never NUMERIC: a NUMERIC column comes back as a BigDecimal, and
-- `(= 0M 0)` is false in Clojure while `(== 0M 0)` is true. auth-base compares the
-- session's generation with the store's using `=`, so that column type would log
-- every user out on PostgreSQL while looking perfect on SQLite.
CREATE TABLE account_generation (subject VARCHAR(36) NOT NULL PRIMARY KEY,
                                 generation BIGINT NOT NULL)

-- Who belongs to which group. Every read and write of a group's data asks this table
-- first, in the same statement, so a request cannot reach a group it is not in.
--
-- ON DELETE CASCADE is declared and, on SQLite, only honoured when the connection
-- turned foreign keys on: `foreign_keys=true` in the JDBC URL. Without it SQLite
-- accepts the clause and ignores it, which is why a test pins it.
CREATE TABLE membership (group_id VARCHAR(36) NOT NULL REFERENCES ledger_group (id) ON DELETE CASCADE,
                         subject VARCHAR(36) NOT NULL REFERENCES account (subject),
                         joined_at BIGINT NOT NULL,
                         PRIMARY KEY (group_id, subject))

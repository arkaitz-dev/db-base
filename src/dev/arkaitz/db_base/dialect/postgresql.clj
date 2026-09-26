(ns dev.arkaitz.db-base.dialect.postgresql
  "What PostgreSQL does differently from the portable schema, for a host that names it:
  `:sessions {:lock-wait-ms … :dialect :postgresql}`. Chosen by the host and never
  detected, so an engine nobody named gets the portable schema rather than a guess
  (SPEC §8). Nothing here runs a statement; it is DDL as data, as the portable schema is.

  **One difference today: the session data is unbounded text**, where the portable table
  holds a bounded `VARCHAR`. It carries its own migration id, so the control table
  records which schema a database has: naming this dialect over a database the portable
  schema created, or the reverse, is refused by §7 as a history the source does not
  have — the session table is not silently the other shape. Moving a database from one
  to the other is the host's own migration; for sessions, which are ephemeral, dropping
  the table and its control row is enough, at the cost of one more sign-in for everyone."
  (:require [dev.arkaitz.db-base.session.schema :as schema]
            [ragtime.next-jdbc :as ragtime-jdbc]))

(def sessions
  "The session table's migrations under this dialect, and the most characters a stored
  session may take — nil, unbounded."
  {:migrations [(ragtime-jdbc/sql-migration
                 {:id "001-sessions-postgresql"
                  :up [(str "CREATE TABLE " schema/table " (id VARCHAR(36) NOT NULL PRIMARY KEY,"
                            " data TEXT NOT NULL, expires_at BIGINT NOT NULL)")]})]
   :data-max   nil})

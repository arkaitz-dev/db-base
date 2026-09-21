(ns dev.arkaitz.db-base.session.schema
  "The table SPEC §8's session store keeps, and the migration that makes it. Separate
  from the store itself because `start` has to create the table and must not load the
  store: the store implements Ring's port and so needs `ring-core`, which this library
  does not declare and a host that only wanted a pool never has. Nothing here requires
  anything but the migration runner.

  **The migration is data rather than a file**, which is a decision and not a
  convenience. A `.sql` under a resources root would put this library's own DDL outside
  §3's dialect scan — that scan reads Clojure sources, and a one-vendor spelling in SQL
  no test runs has no other way of being noticed — and it would need a classpath root a
  build tool can silently leave out of a jar.

  **The types are §8's, measured on five engines** rather than reasoned about: H2, H2 in
  PostgreSQL mode, HSQLDB, Derby and SQLite all take this. `TEXT` for the data does not —
  HSQLDB and Derby refuse it — and a bounded `VARCHAR` is worse than it looks, because an
  over-long session throws on three of the five and is silently truncated by SQLite, so
  the failure mode itself would depend on the engine.

  **No `:down`.** §7 never runs one, so shipping a `DROP TABLE` here would be a statement
  that exists only to be run by mistake."
  (:require [ragtime.next-jdbc :as ragtime-jdbc]))

(def table
  "This library's own, written here and never generated (SPEC §8). A host reads it when
  it wants to look at the rows itself; nothing else needs it."
  "db_base_sessions")

(def migrations
  "What `start` applies into this library's own control table, before the host's run."
  [(ragtime-jdbc/sql-migration
    {:id "001-sessions"
     :up [(str "CREATE TABLE " table " (id VARCHAR(36) NOT NULL PRIMARY KEY,"
               " data CLOB NOT NULL, expires_at BIGINT NOT NULL)")]})])

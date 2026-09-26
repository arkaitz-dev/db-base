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

  **The portable column is a bounded `VARCHAR`, and the bound is enforced here, in
  Clojure** (corrected 2026-09-26). The first choice was a large-object type, measured on
  H2, H2 in PostgreSQL mode, HSQLDB, Derby and SQLite — and refused by PostgreSQL itself,
  which none of the five was. Measured again on PostgreSQL 18.6, H2, HSQLDB, Derby and
  SQLite: a bounded `VARCHAR` is the one data type all five take, where the large object
  is refused by PostgreSQL and `TEXT` by HSQLDB and Derby. What made the bounded type look
  worse — an over-long value throws on some engines and is stored whole by SQLite — is
  answered by the store refusing it before any engine sees it, so the failure is this
  library's and the same everywhere. An engine that wants another type gets it from a
  dialect namespace the host names, never from a guess.

  **No `:down`.** §7 never runs one, so shipping a `DROP TABLE` here would be a statement
  that exists only to be run by mistake."
  (:require [ragtime.next-jdbc :as ragtime-jdbc]))

(def table
  "This library's own, written here and never generated (SPEC §8). A host reads it when
  it wants to look at the rows itself; nothing else needs it."
  "db_base_sessions")

(def data-max
  "The most characters a stored session may take in the portable table, counted as Java
  counts a String — which never undercounts what the five engines measured count, so a
  session this lets through fits them. An engine that bounds `VARCHAR` in bytes rather
  than characters is not one of them, and non-ASCII would fill it sooner. Twenty times the largest session the four hosts of this repository wrote
  (203, measured 2026-09-26), and within what the engines in reach take for a bounded
  `VARCHAR`."
  4000)

(def migrations
  "What `start` applies into this library's own control table, before the host's run,
  when the host names no dialect."
  [(ragtime-jdbc/sql-migration
    {:id "001-sessions"
     :up [(str "CREATE TABLE " table " (id VARCHAR(36) NOT NULL PRIMARY KEY,"
               " data VARCHAR(" data-max ") NOT NULL, expires_at BIGINT NOT NULL)")]})])

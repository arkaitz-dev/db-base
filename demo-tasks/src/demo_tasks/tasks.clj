(ns demo-tasks.tasks
  "The application's own storage, written with next.jdbc against the datasource
  db-base handed over. The library has no opinion here: it gives a
  `javax.sql.DataSource` and stops, which is why this namespace declares
  next.jdbc itself.

  **The owner is a predicate of every statement, never a check in a handler.**
  Both refuse the same request and both look right in a browser, so no test
  through HTTP can tell them apart — but the next caller of these functions, a
  second route or a batch job or an API, will not have the handler's guard, and
  a `WHERE id = ?` that trusted its caller would hand them somebody else's row.
  The predicate is here because here is the only place that cannot be bypassed.

  **The writes return how many rows they changed**, and the callers treat zero
  the same way whether the task belongs to somebody else or does not exist at
  all. Telling those apart would answer, to anyone who can guess an id, the
  question of whether it is real."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn- ds [db] (:datasource db))

(defn- changed
  "How many rows a statement touched, as a number rather than the driver's own
  shape: `execute-one!` answers a map whose single key is the engine's name for
  the count, and a caller comparing that map to 1 would be comparing spellings."
  [result]
  (or (some-> result vals first) 0))

(defn list-tasks
  "Everything `subject` owns, newest first. Nobody else's row can be in it,
  because the filter is the query rather than something applied afterwards."
  [db subject]
  (jdbc/execute! (ds db)
                 ["SELECT id, body, done, created_at FROM task
                   WHERE subject = ? ORDER BY created_at DESC, id DESC" subject]
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn add-task!
  "Writes one task for `subject` and returns its id. The id is minted here and
  not by the engine, so the same code runs on SQLite and PostgreSQL, and so a
  row's name never leaks the number of rows before it."
  [db subject body]
  (let [id (str (random-uuid))]
    (jdbc/execute-one! (ds db)
                       ["INSERT INTO task (id, subject, body, done, created_at)
                         VALUES (?, ?, ?, 0, ?)"
                        id subject body (System/currentTimeMillis)])
    id))

(defn rename-task!
  "Changes the body of a task `subject` owns. Returns the number of rows
  changed: 1, or 0 for a task that is somebody else's or is not there."
  [db subject id body]
  (changed (jdbc/execute-one! (ds db)
                              ["UPDATE task SET body = ? WHERE id = ? AND subject = ?"
                               body id subject])))

(defn toggle-task!
  "Flips `done` on a task `subject` owns, in one statement rather than a read
  and a write: two statements would let two of the owner's own tabs race and
  leave the flag where neither of them asked for it."
  [db subject id]
  (changed (jdbc/execute-one! (ds db)
                              ["UPDATE task SET done = 1 - done WHERE id = ? AND subject = ?"
                               id subject])))

(defn delete-task!
  "Removes a task `subject` owns."
  [db subject id]
  (changed (jdbc/execute-one! (ds db)
                              ["DELETE FROM task WHERE id = ? AND subject = ?" id subject])))

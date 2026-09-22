(ns demo-tasks.devices
  "Where somebody is signed in, and ending one of those places without ending
  the others.

  **The only feature in this host that a sealed cookie cannot serve.** Logging
  out ends the session in front of you; `revoke!` ends all of them, and does it
  over any store because auth-base moves a generation on the subject rather than
  enumerating sessions. Ending *one* of several needs the session to be a row
  somebody else can delete, which is db-base §8 and nothing else.

  The host learns its own session's id from `:session/key`, which Ring puts on
  every request, and keeps it here. It never reads `db_base_sessions`: that
  table is the library's, and the whole point of this namespace is that the
  feature did not need it."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.sql SQLException]))

(defn- ds [db] (:datasource db))

(defn seen!
  "Records that `subject` is signed in under `session-id`, if it is not recorded
  already. Called on the ordinary page rather than at login, because at the
  login request `:session/key` still names the session being replaced — Ring
  mints the new one inside `write-session` and hands it to the response, never
  to the handler."
  [db subject session-id user-agent]
  (when (and subject session-id)
    (try
      (jdbc/execute-one! (ds db)
                         ["INSERT INTO device (session_id, subject, user_agent, first_seen)
                           VALUES (?, ?, ?, ?)"
                          session-id subject (subs (str user-agent) 0 (min 200 (count (str user-agent))))
                          (System/currentTimeMillis)])
      ;; Already there, which is every request after the first: the primary key
      ;; is the arbiter rather than a read before the write, for the reason
      ;; `accounts/register!` gives at more length.
      ;;
      ;; **Recognised by re-reading the row, and rethrowing when it is not
      ;; there.** A bare `(catch SQLException _ nil)` reads as "the row already
      ;; existed" and means "the engine complained about something" — a column
      ;; refusing a null, a disk with nothing left, a table somebody dropped —
      ;; all of it turned into a silent no-op on the one path that is supposed
      ;; to make a person's sessions visible to them.
      (catch SQLException e
        (when-not (seq (jdbc/execute! (ds db) ["SELECT 1 FROM device WHERE session_id = ?"
                                               session-id]))
          (throw e))))))

(defn list-devices
  "Everywhere `subject` is signed in, oldest first."
  [db subject]
  (jdbc/execute! (ds db)
                 ["SELECT session_id, user_agent, first_seen FROM device
                   WHERE subject = ? ORDER BY first_seen, session_id" subject]
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn forget!
  "Removes the host's record of one of `subject`'s devices, and answers how many
  rows that was. The owner is in the WHERE clause for the reason every statement
  in this host has it there: ending somebody else's session by guessing an id is
  the failure this predicate exists to prevent."
  [db subject session-id]
  (or (some-> (jdbc/execute-one! (ds db)
                                 ["DELETE FROM device WHERE session_id = ? AND subject = ?"
                                  session-id subject])
              vals first)
      0))

(ns demo-tasks.accounts
  "Who has an account, and how one comes into being.

  **Registration happens here and nowhere else.** auth-base's port says
  `subject-for` must never create — an account is the host's act, not a side
  effect of somebody typing an address — so `register!` is a separate function
  and the ceremony reaches it only through `:on-unknown`, which is asked once a
  single-use token has already vouched for the address.

  **What `register!` returns is what `subject-for` answers afterwards**, and
  that is a contract rather than a coincidence. auth-base freezes the returned
  value into the session and re-reads the revocation generation keyed on *that*
  value on every request, while `revoke!` moves the generation of whatever
  `subject-for` answers. Two different values would mean a session no revocation
  could ever end. So both return the bare subject string and nothing decorated
  with it."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.sql SQLException]))

(defn- ds [db] (:datasource db))

(defn- one [db sql & params]
  (jdbc/execute-one! (ds db) (into [sql] params) {:builder-fn rs/as-unqualified-lower-maps}))

(defn subject-for
  "The subject behind an identifier, or nil. It creates nothing, ever."
  [db identifier]
  (:subject (one db "SELECT subject FROM account WHERE identifier = ?" identifier)))

(defn register!
  "The subject for `identifier`, creating the account if it is not there yet.
  Safe to call concurrently for the same address: the UNIQUE index is the
  arbiter, not the read before it.

  The read first, then an insert, then — if the insert collided — the read
  again. The collision is recognised by **re-reading the row rather than by its
  SQLSTATE**, which is not portable: SQLite leaves it null where PostgreSQL says
  23505. db-base's own migration lock is written the same way for the same
  measured reason, and this is the third place in this codebase that shape has
  been needed.

  **Which half is load-bearing, measured rather than assumed.** Delete the first
  read and this still behaves identically — every caller mints, collides, and is
  handed the row that was already there; a mutation battery could not tell the
  two apart. The read is an optimisation, so that the ordinary case — somebody
  logging in for the tenth time — does not raise and swallow an exception on the
  way. **The `catch` is the correctness**, and anybody simplifying this should
  remove the read rather than it."
  [db identifier]
  (or (subject-for db identifier)
      (let [minted (str (random-uuid))]
        (try
          (one db "INSERT INTO account (subject, identifier, created_at) VALUES (?, ?, ?)"
               minted identifier (System/currentTimeMillis))
          minted
          (catch SQLException e
            (or (subject-for db identifier)
                ;; The insert failed for something that was not this race, so
                ;; the caller gets the engine's own complaint rather than a
                ;; subject invented to make the failure go away.
                (throw e)))))))

(defn generation
  "The subject's revocation generation. A subject with no row is 0 — auth-base
  says so, because a bootstrap identity holds sessions while having no record
  anywhere.

  `long`, deliberately: auth-base compares this with the value in the session
  using `=`, and `(= 0M 0)` is false in Clojure. The column is BIGINT so a
  driver should hand back a Long, and this is the line that makes a schema
  change to NUMERIC a loud failure here instead of a silent logout everywhere."
  [db subject]
  (long (or (:generation (one db "SELECT generation FROM account_generation WHERE subject = ?"
                              subject))
            0)))

(defn bump-generation!
  "Moves the subject's generation on, ending every session of theirs at its next
  request, and returns the new value. It works for a subject with no row at all.

  **The mirror image of db-base's session store, and the asymmetry is the
  point.** There, an update that touches zero rows must do nothing, because the
  row is gone on purpose and putting it back resurrects a revoked session. Here,
  zero rows means this subject has never been revoked, and the revocation must
  create the row or it silently does nothing at all — which is the worst failure
  available, because the person is told it worked."
  [db subject]
  (let [bump! #(:generation (one db "UPDATE account_generation SET generation = generation + 1
                                     WHERE subject = ? RETURNING generation" subject))]
    ;; `long` for the reason `generation` gives: the port promises a non-negative
    ;; integer, and a NUMERIC column would hand back a BigDecimal that compares
    ;; unequal to every literal anyone writes against it.
    (long (or (bump!)
              (try
                (one db "INSERT INTO account_generation (subject, generation) VALUES (?, 1)" subject)
                1
                (catch SQLException e
                  ;; Another caller created the row between the update and the
                  ;; insert, so it is there now and the update will find it.
                  (or (bump!) (throw e))))))))

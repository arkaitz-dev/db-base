(ns demo-ledger.auth-store
  "auth-base's `Store` port, over the pool db-base handed this host.

  **This is the file db-base is not allowed to contain.** Its third rule says a
  library may implement a port defined by a stable third party — Ring's session
  store is three functions old enough to trust — but never one of ours, because
  an implementation there would lock two of our libraries to each other's
  releases in both directions. So it lives in the host, which is the only place
  that already depends on both, and this is how big it turns out to be.

  The account half is `demo-ledger.accounts`; what is written here is the
  challenge, which is the part with a security property to keep."
  (:require [demo-ledger.accounts :as accounts]
            [dev.arkaitz.auth-base.store :as store]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn- one [db sql & params]
  (jdbc/execute-one! (:datasource db) (into [sql] params)
                     {:builder-fn rs/as-unqualified-lower-maps}))

(defrecord JdbcStore [db]
  store/Store

  (put-challenge! [this token identifier expires-at]
    ;; Refused before anything is written, which the port asks for in so many
    ;; words. The alternative is a row whose expiry cannot be compared, found
    ;; by whoever reads it next rather than by whoever wrote it.
    (when-not (number? expires-at)
      (throw (ex-info "demo-ledger: a challenge's expiry must be a number of epoch milliseconds"
                      ;; Never the token: it is the secret, and an exception's
                      ;; data is the likeliest thing in this host to be logged.
                      {:expires-at expires-at :token-present? (some? token)})))
    (one db "INSERT INTO login_challenge (token, identifier, expires_at) VALUES (?, ?, ?)"
         token identifier expires-at)
    this)

  (take-challenge! [_ token]
    ;; **One statement, and the single use of a magic link rests on it.** The
    ;; delete is what decides: whoever's DELETE reports a row is the one caller
    ;; who redeemed it, and the engine serialises that. A SELECT followed by a
    ;; DELETE would let two callers both see the row and both believe they had
    ;; it — the trap db-base's own notes name as read-then-delete, and the whole
    ;; security of a link that travels by email.
    ;;
    ;; No `expires_at` predicate, deliberately. The port says an expired
    ;; challenge is consumed by the attempt that found it expired, so expiry is
    ;; the ceremony's judgement; a WHERE here would leave expired rows redeemable
    ;; again and again until something swept them.
    (when-let [row (one db "DELETE FROM login_challenge WHERE token = ?
                            RETURNING identifier, expires_at" token)]
      {:ab/identifier (:identifier row)
       :ab/expires-at (:expires_at row)}))

  (subject-for [_ identifier] (accounts/subject-for db identifier))
  (generation [_ subject] (accounts/generation db subject))
  (bump-generation! [_ subject] (accounts/bump-generation! db subject)))

(defn store
  "auth-base's `Store` over `db`, the handle `dev.arkaitz.db-base/start`
  returned. The ceremony takes it as `:store` and asks nothing else of it."
  [db]
  (->JdbcStore db))

(defn reclaim-expired!
  "Deletes challenges whose expiry has passed, and returns how many there were.
  The operator's, and only the operator's — the same shape db-base gives its own
  session rows, and for the same two reasons: an expired challenge is already
  unusable, because the ceremony refuses it and consumes it on sight, so this is
  about disk; and a sweeper on a timer is a lifecycle nobody asked this host for
  and a shutdown path that gets forgotten."
  [db now]
  (or (some-> (one db "DELETE FROM login_challenge WHERE expires_at <= ?" now) vals first) 0))

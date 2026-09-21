(ns dev.arkaitz.db-base.session
  "SPEC §8: a session store over Ring's port, on the table `dev.arkaitz.db-base.session.schema`
  creates. **The only namespace of this library that requires ring**, and the only one a
  host has to bring a dependency for: `ring-core` is not declared here, so requiring this
  without it on the classpath fails as a missing class rather than as a message of ours.
  Every host of web-base or auth-base already has it; a host that only wanted a pool
  never loads this and pays nothing.

  **It never upserts, and that is the whole point.** Ring never asks a store to. The key
  reaching `write-session` is non-nil only when `read-session` returned that row in the
  same request, because the middleware sets the request's key to nil on a miss. So a nil
  key is an insert under a key this store mints, a non-nil key is an update, and **an
  update that touches zero rows is the correct outcome and must do nothing**: the row is
  gone because someone logged out, revoked it, or it expired between the read and the
  write. Re-inserting it there undoes a revocation and adds a primary-key race to a path
  that could not previously fail. Update-then-insert-if-zero is the trap — engine-neutral,
  defensive-looking, and it resurrects the dead. Ring's own `MemoryStore` does exactly
  that with a `swap! assoc`, and so does the one JDBC store the ecosystem has, whose
  maintainer gives mirroring the memory store as the reason. Mirroring an in-memory assoc
  is safe in memory and is not safe in a row another request can delete.

  **The expiry lives in the read**, so an expired session is a miss rather than a row that
  is there and should not be. Reclaiming those rows is only about disk, and it happens
  when an operator calls `reclaim-expired!` — never on write, which would put a delete in
  the path of every request that touches a session, and never on a timer, which §9
  forbids.

  **Five statements, all ANSI**, measured on five engines rather than reasoned about. A
  dialect table here would be the mistake: it would make correctness a function of an
  enumerated list, and the first engine absent from that list breaks with no symptom until
  it is in production. The day ANSI stops being enough, a sibling namespace named after
  that engine appears beside this one and this goes on meaning what it says."
  (:require [clojure.edn :as edn]
            [dev.arkaitz.db-base.session.schema :as schema]
            [ring.middleware.session.store :as store])
  (:import [java.sql Connection PreparedStatement ResultSet]
           [javax.sql DataSource]))

(defn- fail!
  ([message config-key] (throw (ex-info (str "db-base: " message) {:config-key config-key})))
  ([message config-key value]
   (throw (ex-info (str "db-base: " message) {:config-key config-key :value value})))
  ([message config-key value cause]
   (throw (ex-info (str "db-base: " message) {:config-key config-key :value value} cause))))

(defn- prepared ^PreparedStatement [^Connection c sql params]
  (let [statement (.prepareStatement c ^String sql)]
    (doseq [[i p] (map-indexed vector params)] (.setObject statement (int (inc i)) p))
    statement))

(defn- update!
  "Runs one statement and returns how many rows it changed."
  [^DataSource ds sql & params]
  (with-open [c  (.getConnection ds)
              st (prepared c sql params)]
    (.executeUpdate st)))

(defn- first-string
  "The first column of the first row as a String, or nil when there is no row. Read with
  `getString` rather than `getObject`, which is not a detail: on one of the two engines
  the suite runs, the column type §8 chose comes back as a `java.sql.Clob` object and on
  the other as a String, so a reader written against either alone breaks on the other."
  [^DataSource ds sql & params]
  (with-open [c  (.getConnection ds)
              st (prepared c sql params)
              ^ResultSet rows (.executeQuery st)]
    (when (.next rows) (.getString rows 1))))

(def ^:private select-sql
  (str "SELECT data FROM " schema/table " WHERE id = ? AND expires_at > ?"))

(def ^:private insert-sql
  (str "INSERT INTO " schema/table " (id, data, expires_at) VALUES (?, ?, ?)"))

(def ^:private update-sql
  (str "UPDATE " schema/table " SET data = ?, expires_at = ? WHERE id = ?"))

(def ^:private delete-sql
  (str "DELETE FROM " schema/table " WHERE id = ?"))

(def ^:private reclaim-sql
  (str "DELETE FROM " schema/table " WHERE expires_at <= ?"))

(defn- deserialise
  "The EDN of a stored session, as this library's own refusal when it cannot be read.
  The reader throws a bare `RuntimeException` for a tag nobody gave it a function for,
  and §6's rule is that a failure of this library is an `ex-info` naming the key that
  would fix it. Never answered as a miss: a row that cannot be read is a host whose
  readers do not match what it wrote, and turning that into `nil` would log everybody
  out in silence."
  [text readers]
  (try (edn/read-string {:readers readers} text)
       (catch Exception e
         (fail! (str "a stored session could not be read back as EDN: give"
                     " [:session :readers] a reader for every tagged value it holds")
                [:session :readers] (vec (sort (map str (keys readers)))) e))))

(defn- serialise
  "The session as EDN, refused here if it cannot be read back. Ring's own cookie store
  asserts the same round trip on write, and for the same reason: a store that only calls
  `pr-str` writes a session nobody can read, and does it quietly. What the refusal carries
  is the tags the host DID give a reader for, never the session: a session is the host's
  data, and the useful thing to see beside `[:session :readers]` is what is in that map
  rather than what is in the value that would not go through it."
  [session readers]
  (let [text (pr-str session)
        back (try [(edn/read-string {:readers readers} text)]
                  ;; Held rather than propagated, so what reaches the host is this
                  ;; library's message with the reader's own as its cause.
                  (catch Exception e e))]
    ;; Two different failures, and they had been wearing one message. A reader that
    ;; complained is a host missing a `:readers` entry and can be told which it gave; a
    ;; value that came back as something else — `##NaN` is the plain case, and it is why
    ;; the comparison is here rather than a bare `try` — is a host whose session holds
    ;; something EDN cannot carry, and telling that one to add a reader names a fix it
    ;; cannot apply.
    (cond
      (instance? Throwable back)
      (fail! (str "a session was written that cannot be read back as EDN: give"
                  " [:session :readers] a reader for every tagged value it holds")
             [:session :readers] (vec (sort (map str (keys readers))))
             back)

      (not= session (first back))
      (fail! (str "a session was written that came back as something else when read as"
                  " EDN: it holds a value this round trip does not carry")
             [:session] (vec (sort (distinct (map #(.getName (class %)) (vals session)))))))
    text))

(defn- expires-at
  "When a session written now stops being read back. Saturating rather than summing: a
  lifetime with more room than the clock has left would overflow, and Clojure's `+`
  throws on that — measured, `ArithmeticException: long overflow` — so a value this
  library's own constructor accepts would have made every write fail. The ceiling is the
  column's own and not one invented here, and a session that reaches it is one whose host
  asked for a lifetime longer than the epoch can express, which is the same thing as
  never."
  [lifetime-ms]
  (let [now (System/currentTimeMillis)]
    (if (> lifetime-ms (- Long/MAX_VALUE now))
      Long/MAX_VALUE
      (+ now lifetime-ms))))

(defrecord JdbcStore [^DataSource datasource lifetime-ms readers]
  store/SessionStore
  (read-session [_ key]
    (when key
      (when-let [text (first-string datasource select-sql (str key) (System/currentTimeMillis))]
        (deserialise text readers))))

  (write-session [_ key session]
    (let [text    (serialise session readers)
          expires (expires-at (long lifetime-ms))]
      (if key
        ;; One statement, and zero rows changed is success: the row is gone because it was
        ;; revoked, logged out or expired since the read, and putting it back is the
        ;; defect this namespace exists to avoid.
        (do (update! datasource update-sql text expires (str key))
            (str key))
        ;; A key minted per session, inside this function: nothing of ours in a var root,
        ;; which is also what keeps it from being baked into a native image (SPEC §9, §11).
        (let [minted (str (random-uuid))]
          (update! datasource insert-sql minted text expires)
          minted))))

  (delete-session [_ key]
    ;; Ring passes nil on the rotation path, which web-base documents: a login that
    ;; rotates an anonymous session asks to delete a session that was never stored.
    (when key
      (update! datasource delete-sql (str key)))
    nil))

(defn store
  "A `ring.middleware.session.store/SessionStore` over the pool of `handle` — what
  `dev.arkaitz.db-base/start` returned — keeping its rows in the table that boot created.
  A host hands the result to whatever takes Ring's port.

    :lifetime-ms  integer 1..9223372036854775807 — how long a session written now is
                  read back; required and never defaulted, because a session that
                  outlives what its host intended is the incident with no symptom
    :readers      a map of tag to function, as `clojure.edn/read-string` takes it, for
                  sessions holding tagged values; `{}` when they hold none

  The lifetime is this constructor's and not `start`'s: Ring does not supply it, it is not
  a property of the pool, and two stores over one pool may legitimately differ."
  [handle {:keys [lifetime-ms readers] :as options}]
  (let [datasource (:datasource handle)]
    (when-not (instance? DataSource datasource)
      ;; Never echoed: what a mis-wired caller passed may be the configuration map.
      (fail! "session store takes the handle start returned, whose :datasource is a javax.sql.DataSource"
             [:datasource]))
    (when-not (map? options)
      (fail! "session store options must be a map of :lifetime-ms and :readers" [:session] options))
    (when-let [unknown (not-empty (sort-by pr-str (remove #{:lifetime-ms :readers} (keys options))))]
      (fail! (str "unknown key" (when (next unknown) "s") " " (pr-str (vec unknown))
                  " in [:session] — it takes [:lifetime-ms :readers]")
             (conj [:session] (first unknown))))
    (when-not (and (integer? lifetime-ms) (<= 1 lifetime-ms Long/MAX_VALUE))
      (fail! (str "[:session :lifetime-ms] must be an integer from 1 to " Long/MAX_VALUE
                  " milliseconds")
             [:session :lifetime-ms] lifetime-ms))
    (when-not (map? readers)
      (fail! "[:session :readers] must be a map of tag to function" [:session :readers] readers))
    (->JdbcStore datasource lifetime-ms readers)))

(defn reclaim-expired!
  "Deletes the rows whose expiry has passed, and returns how many there were. The
  operator's, and only the operator's: an expired session is already invisible, because
  the expiry is in the read, so this is about disk and nothing else. Never called on
  write, which would add a delete to the path of every request that touches a session,
  and never on a timer, which §9 forbids — a background thread is a lifecycle the host
  did not ask for and a shutdown path that gets forgotten."
  [handle]
  (let [datasource (:datasource handle)]
    (when-not (instance? DataSource datasource)
      (fail! "reclaim-expired! takes the handle start returned, whose :datasource is a javax.sql.DataSource"
             [:datasource]))
    (update! datasource reclaim-sql (System/currentTimeMillis))))

;; As Ring's own memory store does: the record is an implementation detail, and a host
;; that reached for its constructor would be writing this library's internals into its own.
(ns-unmap *ns* '->JdbcStore)
(ns-unmap *ns* 'map->JdbcStore)

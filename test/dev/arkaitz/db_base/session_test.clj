(ns dev.arkaitz.db-base.session-test
  "SPEC §8: the session store over Ring's port. The claim that matters most is negative —
  **it never upserts** — and §8 says why in the strongest terms this specification uses:
  the one JDBC store the ecosystem has does `update!` and, on zero rows affected,
  `insert!`, because it mirrors Ring's own `MemoryStore`, which upserts with `swap!
  assoc`. Mirroring an in-memory assoc is safe in memory and resurrects a revoked session
  when the row is one another request can delete.

  A resurrection has no symptom of its own: the row comes back with its old contents and
  everything downstream behaves. So the tests below never ask whether a call succeeded —
  they ask the table, through a connection of this test's own, what is in it afterwards.

  Two mechanisms are worth naming before they are read. **A `DataSource` of this test's
  own** wraps the pool's and counts every connection the store borrows, and can park the
  store inside `getConnection` — that is how `delete-session` running NO statement is
  observed rather than assumed, and how a delete is made to land while a write is
  provably in flight. And **expiry is never waited for**: a row is backdated by SQL, so
  nothing here sleeps.

  Every claim runs on H2 and on SQLite (§3)."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.db-base :as db]
            [dev.arkaitz.db-base.session :as session]
            [dev.arkaitz.db-base.test-support :as ts]
            [ring.middleware.session :as ring-session]
            [ring.middleware.session.store :as store])
  (:import [java.sql Connection DriverManager ResultSet]
           [javax.sql DataSource]))

(defrecord Tagged [what])
(defrecord Other [what])

(defn- far-future
  "Ahead of the clock by a day. Derived rather than written as a date: a constant year
  would turn this suite red when it arrived, and a red that names no defect is the other
  half of the rule against softening one."
  []
  (+ (System/currentTimeMillis) 86400000))

(def ^:private long-past
  "An epoch in milliseconds that is behind any clock this will run on, 2023-11-14."
  1700000000000)

(defn- booted
  "A handle with §8's table made, left open — the store needs the pool alive."
  [url]
  (db/start (assoc (ts/config url "three") :migrations :none :sessions {:lock-wait-ms 1000})))

(defn- rows
  "Every row of the session table, read through a connection of this test's own and with
  `getString`, which is the reader §8's column type needs on both engines."
  [url]
  (with-open [^Connection c (DriverManager/getConnection url ts/user-sentinel ts/password-sentinel)
              st (.createStatement c)
              ^ResultSet rs (.executeQuery st "SELECT id, data, expires_at FROM db_base_sessions ORDER BY id")]
    (loop [acc []]
      (if (.next rs)
        (recur (conj acc [(.getString rs 1) (.getString rs 2) (.getLong rs 3)]))
        acc))))

(defn- age! [url id at] (ts/execute! url (str "UPDATE db_base_sessions SET expires_at = " at
                                              " WHERE id = '" id "'")))

(defn- counting-datasource
  "The pool's `DataSource` with a turnstile on it. Every connection the store borrows is
  counted, and when `arrived` is given the store is parked inside `getConnection` until
  `release` is delivered. No cooperation from the code under test is needed and none is
  asked for: `store` requires only that the handle's `:datasource` is a `javax.sql.DataSource`,
  and the store's single door to the database is this method."
  ([real counter] (counting-datasource real counter nil nil nil))
  ([^DataSource real counter arrived release exit]
   (reify DataSource
     (getConnection [_]
       (swap! counter inc)
       (when arrived
         (deliver arrived true)
         ;; What ended the wait is recorded, never discarded: a guard that expired on its
         ;; own would let the writer past while the test still believed it was parked, and
         ;; the assertion that follows would then be about a sequence rather than about
         ;; the case it names. `migrations_test` pins its own gate the same way.
         (reset! exit (deref release 30000 ::the-guard-expired-not-the-test)))
       (.getConnection real)))))

(defn- counted
  "`[store counter]` over `handle`, with every borrow counted."
  [handle options]
  (let [counter (atom 0)]
    [(session/store {:datasource (counting-datasource (:datasource handle) counter)} options)
     counter]))

(deftest a-nil-key-inserts-under-a-minted-string-and-a-key-updates-that-row-in-place
  (doseq [[engine url] (ts/engines)]
    (let [handle (booted url)
          s      (session/store handle {:lifetime-ms 60000 :readers {}})]
      (try
        (let [before (System/currentTimeMillis)
              k      (store/write-session s nil {:visitor "ada"})
              after  (System/currentTimeMillis)]
          (is (= [String 36] [(class k) (count k)])
              (str engine ": the key is a UUID as a String, 36 characters, which is the"
                   " column §8 names. The class is asserted and not just the round trip:"
                   " Ring compares what this returns against the cookie's own String, so a"
                   " `java.util.UUID` would never be `=` to it and every request would"
                   " re-set the cookie"))
          (let [[[id data expires] :as all] (rows url)]
            (is (= [1 k "{:visitor \"ada\"}"] [(count all) id data])
                (str engine ": exactly one row, under that key, holding the session as EDN"))
            ;; The window is derived from the lifetime the store was given and the clock
            ;; either side of the call — not a number chosen to make this pass.
            (is (<= (+ before 60000) expires (+ after 60000))
                (str engine ": expiring one lifetime from when it was written")))
          (is (= {:visitor "ada"} (store/read-session s k))
              (str engine ": and it reads back"))
          (let [before-update (System/currentTimeMillis)
                same-key      (store/write-session s k {:visitor "ada" :seen 2})
                after-update  (System/currentTimeMillis)
                [[id data expires] :as all] (rows url)]
            (is (= k same-key)
                (str engine ": a write under that key returns the same key, so the browser's"
                     " cookie is left alone"))
            (is (= [1 k "{:visitor \"ada\", :seen 2}"] [(count all) id data])
                (str engine ": and it updated that row in place — still one row, new data."
                     " A store that inserted here would leave two"))
            ;; Bracketed by the clock either side of THIS call, never by the row read back
            ;; a second time: an expected value taken from the table is the table agreeing
            ;; with itself, and a renewal that ignored the lifetime would pass it. A
            ;; session outliving what its host asked for is the one thing a row buys over
            ;; a cookie, so the renewal is where it has to be pinned.
            (is (<= (+ before-update 60000) expires (+ after-update 60000))
                (str engine ": renewing the expiry by one lifetime from the moment of the"
                     " write, not by whatever it had before and not by a constant")))
          (is (nil? (store/read-session s "no-such-key-7f3a"))
              (str engine ": a key with no row reads as nil and never as {}, which is"
                   " truthy and would make the middleware ask to update a row that is not"
                   " there — the very case this store exists to get right"))
          (let [second-key (store/write-session s nil {:visitor "bea"})]
            (is (= 2 (count (rows url)))
                (str engine ": a second nil-key write is a second session"))
            (is (not= k second-key)
                (str engine ": under a key of its own")))
          ;; Ring's protocol docstring makes this contract and not taste: "Session keys
          ;; are exposed to end users via a cookie, and therefore must be unguessable."
          ;; What twelve samples CAN pin is stated exactly, because entropy is not
          ;; something a test observes: these kill a key derived from a clock or a
          ;; counter, which is distinct every time, 36 characters long, and guessable by
          ;; anyone holding a watch. The randomness itself is `random-uuid`'s, which the
          ;; JDK takes from a `SecureRandom` — and §11 records that the generator is not
          ;; the hazard, the load-time `def` is.
          (let [minted (vec (repeatedly 12 #(store/write-session s nil {:n 1})))]
            (is (= #{4} (set (map #(.version (java.util.UUID/fromString %)) minted)))
                (str engine ": every key parses as a version-4 UUID, which is the random"
                     " one — version 1 is the clock, and a key derived from the clock is a"
                     " key another visitor can reach"))
            (is (not= minted (vec (sort minted)))
                (str engine ": and twelve of them minted in a row do not come out in"
                     " ascending order, which anything counting or reading a clock would."
                     " Twelve random keys landing sorted by chance is one run in 4.8×10^8,"
                     " so this is a bound derived from the invariant and not a threshold"))))
        (finally (db/stop handle))))))

(deftest a-delete-that-lands-while-a-write-is-in-flight-does-not-bring-the-row-back
  ;; SPEC §3 calls this the single most valuable test in the library. Said precisely, so
  ;; the message does not claim more than the body does: the invariant holds in either
  ;; order, and at the level of SQL this is the same sequence as the test below it — the
  ;; writer has issued no statement while it is parked. What the turnstile buys is that
  ;; the revocation lands inside the `write-session` CALL, which is the case §8 names, and
  ;; that the store's only door to the database is the one being watched.
  (doseq [[engine url] (ts/engines)]
    (let [handle  (booted url)
          plain   (session/store handle {:lifetime-ms 60000 :readers {}})
          k       (store/write-session plain nil {:visitor "ada"})
          counter (atom 0)
          arrived (promise)
          release (promise)
          exit    (atom ::never-parked)
          gated   (session/store {:datasource (counting-datasource (:datasource handle) counter
                                                                   arrived release exit)}
                                 {:lifetime-ms 60000 :readers {}})]
      (try
        (is (= [[k "{:visitor \"ada\"}" (nth (first (rows url)) 2)]] (rows url))
            (str engine ": precondition — the session is there before anything races"))
        (let [[_ result] (ts/running #(store/write-session gated k {:visitor "ada" :seen 2}))]
          (is (true? (deref arrived 30000 ::never-arrived))
              (str engine ": witness — the writer really is inside its call. Without this a"
                   " green below would mean the delete ran before the write started, which"
                   " is a sequence and not the case this test names"))
          (is (false? (realized? result))
              (str engine ": and it has not finished, so what follows lands inside it"))
          ;; Another request revokes the session, on a connection of its own.
          (ts/execute! url (str "DELETE FROM db_base_sessions WHERE id = '" k "'"))
          (is (= [] (rows url))
              (str engine ": the revocation took effect while the writer was parked"))
          (deliver release true)
          (is (= [:ok k] (deref result 30000 ::the-writer-never-returned))
              (str engine ": the writer finished and handed its key back"))
          (is (= [true 1] [@exit @counter])
              (str engine ": and it was THIS TEST that let it past, not the guard expiring"
                   " on its own — which would have let the writer run its update while the"
                   " row was still there, and then the empty table below would be the"
                   " delete having landed afterwards. One borrow, so the store went"
                   " through that door exactly once"))
          (is (= [] (rows url))
              (str engine ": AND THE ROW IS STILL GONE. This is §8's whole argument: an"
                   " update that touches zero rows is the correct outcome and must do"
                   " nothing. A store that inserted on zero rows affected — which is what"
                   " Ring's MemoryStore does with swap! assoc, and what the one JDBC store"
                   " the ecosystem has copied from it — would have put the revoked session"
                   " back here, with its old contents and no symptom")))
        (finally (db/stop handle))))))

(deftest an-update-that-touches-no-row-changes-nothing-and-takes-no-bystander
  (doseq [[engine url] (ts/engines)]
    (let [handle (booted url)
          s      (session/store handle {:lifetime-ms 60000 :readers {}})]
      (try
        (let [gone      (store/write-session s nil {:visitor "ada"})
              bystander (store/write-session s nil {:visitor "bea"})
              _         (is (= 2 (count (rows url))) (str engine ": precondition — two sessions"))
              _         (is (nil? (store/delete-session s gone))
                            (str engine ": delete answers nil, so the middleware drops the cookie"))
              left      (rows url)]
          (is (= 1 (count left))
              (str engine ": one row went and one stayed. The bystander is what a `DELETE`"
                   " with its `WHERE` dropped would take with it, and a count alone would"
                   " not have told them apart"))
          (is (= bystander (ffirst left))
              (str engine ": and the one that stayed is the other session"))
          (is (= gone (store/write-session s gone {:visitor "resurrected"}))
              (str engine ": a write under the key of the row that is gone reports success"
                   " and returns the key"))
          (is (= left (rows url))
              (str engine ": and the table is byte-identical to before it — nothing"
                   " inserted, nothing touched")))
        (finally (db/stop handle))))))

(deftest an-expired-session-is-a-read-miss-and-its-row-is-left-alone
  (doseq [[engine url] (ts/engines)]
    (let [handle (booted url)
          s      (session/store handle {:lifetime-ms 60000 :readers {}})]
      (try
        (let [k        (store/write-session s nil {:visitor "ada"})
              boundary (dec (System/currentTimeMillis))]
          ;; One millisecond behind the clock, not a date in 2023: the predicate is
          ;; `expires_at > now`, and a window of years would stay green for a store that
          ;; served every expired session for an hour after it expired.
          (age! url k boundary)
          (is (nil? (store/read-session s k))
              (str engine ": a session whose expiry has passed is a miss. §8 puts the"
                   " expiry in the read for exactly this: an expired row that answered"
                   " would be served, and a read that deleted would make reclaiming rows"
                   " something every request paid for"))
          (is (= [[k "{:visitor \"ada\"}" boundary]] (rows url))
              (str engine ": and the row is still there with the expiry this test wrote —"
                   " asserted against that literal and never against the row read a second"
                   " time, which would be the table agreeing with itself and would let a"
                   " read that renewed what it found pass as a read"))
          (age! url k long-past)
          (is (nil? (store/read-session s k))
              (str engine ": and an expiry long past is a miss too, so the boundary above"
                   " was the predicate and not an off-by-one that happens to land"))
          (testing "control: the same key, one minute ahead of the clock"
            (let [ahead (+ (System/currentTimeMillis) 60000)]
              (age! url k ahead)
              (is (= {:visitor "ada"} (store/read-session s k))
                  (str engine ": it answers again, so the miss above was the expiry and not"
                       " a store that misses everything"))
              ;; The assertions above only ever read rows whose read MISSED, so a read that
              ;; renewed what it found would have been invisible to all of them. A session
              ;; that slides while it is used is a lifetime the host never asked for, and a
              ;; write in the path of every request that reads one.
              (is (= [[k "{:visitor \"ada\"}" ahead]] (rows url))
                  (str engine ": and a read that HITS leaves the expiry exactly as it"
                       " found it — the read is a read on this path too")))))
        (finally (db/stop handle))))))

(deftest neither-a-delete-nor-a-read-of-a-nil-key-runs-a-statement
  ;; Ring hands a nil key to both: to `delete-session` on the rotation path — a login that
  ;; rotates an anonymous session asks to delete one that was never stored — and to
  ;; `read-session` on every request from a visitor with no cookie. `(str nil)` is "", so
  ;; a store that dropped either guard would run a statement matching nothing: harmless to
  ;; the rows, invisible to any count of them, and a connection borrowed per visitor.
  (doseq [[engine url] (ts/engines)]
    (let [handle    (booted url)
          [s count*] (counted handle {:lifetime-ms 60000 :readers {}})]
      (try
        (is (nil? (store/delete-session s nil))
            (str engine ": it answers nil"))
        (is (= 0 @count*)
            (str engine ": having borrowed no connection. Asked of the store's only door to"
                 " the database rather than of the rows, which cannot tell a statement that"
                 " matched nothing from a statement that never ran"))
        (is (nil? (store/read-session s nil))
            (str engine ": and a read of a nil key answers nil"))
        (is (= 0 @count*)
            (str engine ": having borrowed nothing either. Ring hands a nil key to the read"
                 " on every anonymous request, so a store that dropped this guard would"
                 " open a connection for every visitor who has no session at all"))
        (testing "control: a real key does borrow one, on each path"
          (store/delete-session s "some-key-7f3a")
          (is (= 1 @count*)
              (str engine ": so the zeros above are the nil paths and not a counter that"
                   " never moves"))
          (store/read-session s "some-key-7f3a")
          (is (= 2 @count*)
              (str engine ": and the read borrows too, when there is a key to look for")))
        (finally (db/stop handle))))))

(deftest a-session-that-cannot-be-read-back-is-refused-before-anything-is-written
  (doseq [[engine url] (ts/engines)]
    (let [handle     (booted url)
          [s count*] (counted handle {:lifetime-ms 60000 :readers {}})
          unreadable {:visitor "ada" :pattern (->Tagged "no reader for this tag")}]
      (try
        (is (= [(str "db-base: a session was written that cannot be read back as EDN:"
                     " give [:session :readers] a reader for every tagged value it holds")
                {:config-key [:session :readers] :value []}]
               (ts/attempt #(store/write-session s nil unreadable)))
            (str engine ": a session holding a value EDN cannot read back is refused, naming"
                 " the key that would fix it. Ring's own cookie store asserts the same round"
                 " trip on write, and for the same reason: a store that only calls `pr-str`"
                 " writes a session nobody can read, and does it quietly"))
        (is (= [] (rows url))
            (str engine ": and NOTHING was written"))
        (is (= 0 @count*)
            (str engine ": no connection was even borrowed, so the check runs before the"
                 " statement and not after it — a row check alone would accept a store that"
                 " inserted first and complained afterwards"))
        (testing "a value the reader takes without complaint and hands back as another"
          ;; The only other fixture here fails by THROWING, so a check degraded to "did
          ;; the reader throw?" would pass it. `##NaN` is read back without a murmur and
          ;; is not `=` to itself, which is the shape the comparison exists for — and it
          ;; is the same refusal Ring's own cookie store makes, whose `:post` condition
          ;; compares the same way.
          (let [[message data] (ts/attempt #(store/write-session s nil {:visitor "ada" :n ##NaN}))]
            (is (= [(str "db-base: a session was written that came back as something else"
                         " when read as EDN: it holds a value this round trip does not carry")
                    {:config-key [:session] :value ["java.lang.Double" "java.lang.String"]}]
                   [message data])
                (str engine ": refused, because what came back was not what went in — and"
                     " told so in its own words. Naming `[:session :readers]` here would"
                     " name a fix this host cannot apply: there is no tag to write a reader"
                     " for. The classes it holds are listed, never the values"))))
        (testing "the refusal names the readers the host DID give, and carries the reader's own complaint"
          (let [ours   (session/store handle {:lifetime-ms 60000
                                              :readers {'dev.arkaitz.db_base.session_test.Tagged map->Tagged}})
                thrown (ts/thrown #(store/write-session ours nil {:other (->Other "no reader for me")}))]
            (is (= {:config-key [:session :readers] :value ["dev.arkaitz.db_base.session_test.Tagged"]}
                   (ex-data thrown))
                (str engine ": the refusal lists the tags this store was given a reader for,"
                     " which is what a host compares against the tag it forgot. Asserted"
                     " from a store that HAS readers: every other refusal here is raised"
                     " from one with none, where an empty list is reachable from anything"))
            (is (some? (re-find #"dev\.arkaitz\.db_base\.session_test\.Other"
                                (str (ex-message (ex-cause thrown)))))
                (str engine ": and the reader's own complaint is the cause, which is the"
                     " only line that names the tag that was missing. Dropped, the host"
                     " would be told which readers it has and never which one it needs"))))
        (testing "the same on the update path, over a row that already exists"
          (let [good (session/store handle {:lifetime-ms 60000 :readers {}})
                k    (store/write-session good nil {:visitor "ada"})]
            (is (= :dev.arkaitz.db-base.test-support/no-throw
                   (ts/attempt #(store/write-session good k {:visitor "bea"})))
                (str engine ": precondition — an ordinary update goes through"))
            ;; Read AFTER the good write, because a write renews the expiry too: comparing
            ;; against a row captured earlier would red on that renewal and name nothing.
            (let [was (rows url)]
              (ts/attempt #(store/write-session good k unreadable))
              (is (= was (rows url))
                  (str engine ": and the refusal left the row exactly as the last good"
                       " write had it, expiry included")))))
        (finally (db/stop handle))))))

(deftest the-readers-a-host-gives-are-used-on-the-way-in-and-on-the-way-out
  (doseq [[engine url] (ts/engines)]
    (let [handle  (booted url)
          ;; The tag is the class name `pr-str` writes, with the underscores a Clojure
          ;; namespace turns into when it becomes a package: a reader keyed by the
          ;; namespace's own spelling is a reader that never fires.
          readers {'dev.arkaitz.db_base.session_test.Tagged map->Tagged}
          s       (session/store handle {:lifetime-ms 60000 :readers readers})
          value   {:visitor "ada" :tagged (->Tagged "kept")}]
      (try
        (let [k (store/write-session s nil value)]
          (is (= 1 (count (rows url)))
              (str engine ": with a reader for its tag the session is accepted, where the"
                   " test above showed the same value refused without one"))
          (is (= value (store/read-session s k))
              (str engine ": and comes back as itself, record and all — so the readers"
                   " reached the read and not only the write"))
          (testing "control: another store over the same row, with no readers"
            (let [blind (session/store handle {:lifetime-ms 60000 :readers {}})]
              (is (= [(str "db-base: a stored session could not be read back as EDN: give"
                           " [:session :readers] a reader for every tagged value it holds")
                      {:config-key [:session :readers] :value []}]
                     (ts/attempt #(store/read-session blind k)))
                  (str engine ": it cannot read that row, and says so as this library's own"
                       " refusal naming the key that fixes it — never as a miss, which"
                       " would log everybody out in silence. That is also what says the"
                       " readers above were what made the read work, rather than the value"
                       " having been plain all along")))))
        (finally (db/stop handle))))))

(deftest reclaiming-takes-every-expired-row-and-no-other-and-says-how-many
  (doseq [[engine url] (ts/engines)]
    (let [handle (booted url)
          s      (session/store handle {:lifetime-ms 60000 :readers {}})]
      (try
        (let [a (store/write-session s nil {:n 1})
              b (store/write-session s nil {:n 2})
              c (store/write-session s nil {:n 3})]
          (age! url a long-past)
          (age! url b 0)
          (age! url c (far-future))
          (is (= 3 (count (rows url))) (str engine ": precondition — three rows, two stale"))
          (is (= 2 (session/reclaim-expired! handle))
              (str engine ": it says how many it took, which is what an operator schedules"
                   " on. A boolean could not tell an empty sweep from a full one"))
          (is (= [c] (mapv first (rows url)))
              (str engine ": and it took exactly the stale ones — the live session is the"
                   " bystander a predicate flipped, or a `WHERE` dropped, would have taken"))
          (is (= 0 (session/reclaim-expired! handle))
              (str engine ": a second sweep finds nothing, and says so")))
        (finally (db/stop handle))))))

(deftest a-write-never-sweeps-and-a-stored-session-is-never-evaluated
  (doseq [[engine url] (ts/engines)]
    (let [handle     (booted url)
          plain      (session/store handle {:lifetime-ms 60000 :readers {}})
          stale      (store/write-session plain nil {:visitor "gone"})
          [s count*] (counted handle {:lifetime-ms 60000 :readers {}})]
      (try
        (age! url stale long-past)
        (reset! count* 0)
        (let [fresh (store/write-session s nil {:visitor "ada"})]
          (is (= 1 @count*)
              (str engine ": a write borrows exactly one connection — a count of borrows,"
                   " which is not a count of statements, so the row assertion below is"
                   " what rules out a sweep issued on that same connection. §8"
                   " refuses to sweep on write by name — it would put a delete in the path"
                   " of every request that touches a session — and §11 records that as"
                   " settled, which until now nothing in the suite settled"))
          (is (= #{stale fresh} (set (map first (rows url))))
              (str engine ": and the expired row is still there afterwards. A sweep hidden"
                   " in the write would have taken it, and every other test in this file"
                   " would have stayed green, because none of them has an expired row"
                   " present while a write happens")))
        ;; SPEC §8 names `clojure.edn/read-string`. `clojure.core/read-string` would pass
        ;; every test in this suite and every scan in the repository, while admitting the
        ;; ambient `*data-readers*` and `#=`, which evaluates. Measured: the core reader
        ;; turns this row into {:x 3} — while `*read-eval*` is true, which is its default
        ;; and what a host will have — and the EDN reader refuses it outright.
        (let [planted "planted-7f3a"]
          (ts/execute! url (str "INSERT INTO db_base_sessions (id, data, expires_at) VALUES ('"
                                planted "', '{:x #=(+ 1 2)}', " (+ (System/currentTimeMillis) 60000) ")"))
          (is (= [(str "db-base: a stored session could not be read back as EDN: give"
                       " [:session :readers] a reader for every tagged value it holds")
                  {:config-key [:session :readers] :value []}]
                 (ts/attempt #(store/read-session s planted)))
              (str engine ": a stored row asking to be evaluated is refused, not evaluated."
                   " A session is a value that came back from a browser through a table"
                   " anything with the password can write, so the reader has to be the one"
                   " that cannot run code")))
        (finally (db/stop handle))))))

(deftest the-store-refuses-a-bad-handle-and-bad-options-naming-the-key
  ;; One engine, deliberately, and it is the only test here that uses one: every refusal
  ;; below is raised before any statement is built, so there is nothing for a second
  ;; engine to disagree about. The claim that IS about a column — the ceiling a lifetime
  ;; saturates at — is a test of its own, over both.
  (let [url    (ts/h2-memory-url ts/url-sentinel)
        handle (booted url)
        good   {:lifetime-ms 60000 :readers {}}
        ds     "session store takes the handle start returned, whose :datasource is a javax.sql.DataSource"]
    (try
      (is (satisfies? store/SessionStore (session/store handle good))
          "positive control: the accepted shape really is accepted, and is Ring's port")
      (doseq [[label thunk expected]
              [["a handle with no datasource" #(session/store {} good)
                [(str "db-base: " ds) {:config-key [:datasource]}]]
               ["the configuration map passed as the handle"
                #(session/store {:jdbc-url ts/url-sentinel :password ts/password-sentinel} good)
                [(str "db-base: " ds) {:config-key [:datasource]}]]
               ["reclaim-expired! on a handle with no datasource" #(session/reclaim-expired! {})
                ["db-base: reclaim-expired! takes the handle start returned, whose :datasource is a javax.sql.DataSource"
                 {:config-key [:datasource]}]]
               ["options that are not a map" #(session/store handle nil)
                ["db-base: session store options must be a map of :lifetime-ms and :readers"
                 {:config-key [:session] :value nil}]]
               ["an unknown option" #(session/store handle (assoc good :ttl 1))
                ["db-base: unknown key [:ttl] in [:session] — it takes [:lifetime-ms :readers]"
                 {:config-key [:session :ttl]}]]
               ["two unknown options, reported sorted" #(session/store handle (assoc good :b 1 :a 2))
                ["db-base: unknown keys [:a :b] in [:session] — it takes [:lifetime-ms :readers]"
                 {:config-key [:session :a]}]]
               [":lifetime-ms missing" #(session/store handle (dissoc good :lifetime-ms))
                ["db-base: [:session :lifetime-ms] must be an integer from 1 to 9223372036854775807 milliseconds"
                 {:config-key [:session :lifetime-ms] :value nil}]]
               [":lifetime-ms 0, which is a session already expired"
                #(session/store handle (assoc good :lifetime-ms 0))
                ["db-base: [:session :lifetime-ms] must be an integer from 1 to 9223372036854775807 milliseconds"
                 {:config-key [:session :lifetime-ms] :value 0}]]
               [":lifetime-ms not an integer" #(session/store handle (assoc good :lifetime-ms 1.5))
                ["db-base: [:session :lifetime-ms] must be an integer from 1 to 9223372036854775807 milliseconds"
                 {:config-key [:session :lifetime-ms] :value 1.5}]]
               [":readers not a map" #(session/store handle (assoc good :readers []))
                ["db-base: [:session :readers] must be a map of tag to function"
                 {:config-key [:session :readers] :value []}]]]]
        (is (= expected (ts/attempt thunk)) label)
        (is (= [] (ts/leaks-in label (ts/thrown thunk)))
            (str label ": and the refusal echoes no secret")))
      (finally (db/stop handle)))))

(deftest the-largest-lifetime-the-constructor-accepts-is-one-a-write-can-honour
  ;; A claim about what a `BIGINT` holds, so it is measured on both engines: §3 keeps a
  ;; strict engine beside a permissive one because a column that silently narrows on one
  ;; of them is exactly what a single-engine test cannot see.
  (doseq [[engine url] (ts/engines)]
    (let [handle (booted url)
          s      (session/store handle {:lifetime-ms Long/MAX_VALUE :readers {}})]
      (try
        (let [k (store/write-session s nil {:visitor "ada"})]
          (is (= [k "{:visitor \"ada\"}" Long/MAX_VALUE] (first (rows url)))
              (str engine ": the largest lifetime the constructor accepts saturates at the"
                   " column's own ceiling rather than summing past it — measured before"
                   " this was fixed: ArithmeticException, long overflow, on every write a"
                   " store built that way made"))
          (is (= {:visitor "ada"} (store/read-session s k))
              (str engine ": and such a session reads back, rather than being expired by a"
                   " number that wrapped")))
        (finally (db/stop handle))))))

(deftest ring-s-own-middleware-drives-this-store-end-to-end
  ;; Narrow on purpose: it pins the three facts about Ring 1.15.5 that the store's own
  ;; docstring assumes — a miss hands `write-session` nil, the key it returns is compared
  ;; with the cookie's String, and a nil `:session` asks for a delete. If Ring drifts on
  ;; any of them, this is what reds. The full seam, with web-base's wiring and a login
  ;; that rotates, is the demo's and runs on a host's own classpath.
  (doseq [[engine url] (ts/engines)]
    (let [handle  (booted url)
          s       (session/store handle {:lifetime-ms 60000 :readers {}})
          handler (ring-session/wrap-session
                   (fn [request]
                     (if (= "/end" (:uri request))
                       {:status 200 :body "" :session nil}
                       {:status 200 :body (pr-str (:session request)) :session {:visitor "ada"}}))
                   {:store s})]
      (try
        (let [first-visit (handler {:request-method :get :uri "/" :headers {}})
              cookie      (first (get-in first-visit [:headers "Set-Cookie"]))
              value       (second (re-find #"ring-session=([^;]+)" (str cookie)))]
          (is (= [1 value] [(count (rows url)) (ffirst (rows url))])
              (str engine ": the middleware stored one session and put THAT key in the"
                   " cookie — which is what a key returned as a UUID would break, since"
                   " Ring compares the two as strings"))
          (let [again (handler {:request-method :get :uri "/" :headers {"cookie" (str "ring-session=" value)}})]
            (is (= "{:visitor \"ada\"}" (:body again))
                (str engine ": a second request with that cookie is handed the session back")))
          (handler {:request-method :get :uri "/end" :headers {"cookie" (str "ring-session=" value)}})
          (is (= [] (rows url))
              (str engine ": and a response whose :session is nil asks the store to delete"
                   " the row, which it did")))
        (finally (db/stop handle))))))

(defn- session-of-length
  "A session whose EDN is exactly `n` characters: `{:v \"…\"}` spends 7 on itself."
  [n]
  {:v (apply str (repeat (- n 7) "x"))})

(deftest a-session-longer-than-its-table-holds-is-refused-before-the-engine-sees-it
  (doseq [[engine url] (ts/engines)]
    (let [handle (booted url)
          s      (session/store handle {:lifetime-ms 60000 :readers {}})]
      (try
        (is (= 4000 (:session-data-max handle)) (str engine ": precondition: the portable table's bound"))
        (is (= 4000 (count (pr-str (session-of-length 4000)))) "precondition: the helper spells what it says")
        (let [k (store/write-session s nil (session-of-length 4000))]
          (is (= (session-of-length 4000) (store/read-session s k))
              (str engine ": a session exactly at the bound is written and read back"))
          (is (= k (store/write-session s k (session-of-length 3999)))
              (str engine ": and updated"))
          (let [before (rows url)]
            (doseq [[path key] [["insert" nil] ["update" k]]]
              (let [e (try (store/write-session s key (session-of-length 4001)) nil
                           (catch clojure.lang.ExceptionInfo e e))]
                (is (= ["db-base: a session of 4001 characters is longer than the 4000 the session table holds"
                        {:length 4001 :data-max 4000}]
                       (ts/pair e))
                    (str engine ": one character over is refused on the " path " path, by this library,"
                         " naming the numbers and never the session"))))
            (is (= before (rows url))
                (str engine ": and nothing was written by either — SQLite would have stored it whole"))))
        (finally (db/stop handle))))))

(deftest the-bound-counts-what-java-counts-so-a-session-it-lets-through-fits
  ;; A character outside the Basic Multilingual Plane is two UTF-16 units to Java and one
  ;; character to PostgreSQL: counting units can only refuse early, never let through what
  ;; the column refuses.
  (doseq [[engine url] (ts/engines)]
    (let [handle (booted url)
          s      (session/store handle {:lifetime-ms 60000 :readers {}})
          emoji  "😀"
          over   {:v (apply str (repeat 1997 emoji))}]
      (try
        (is (= [4001 2004] [(count (pr-str over)) (.codePointCount ^String (pr-str over) 0 (count (pr-str over)))])
            "precondition: 4001 units, fewer characters than the bound")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a session of 4001 characters"
                              (store/write-session s nil over))
            (str engine ": refused by units, which is the conservative count"))
        (finally (db/stop handle))))))

(deftest the-store-takes-its-bound-from-the-handle-and-the-portable-one-without-it
  (doseq [[engine url] (ts/engines)]
    (let [handle (db/start (assoc (ts/config url "three") :migrations :none
                                  :sessions {:lock-wait-ms 1000 :dialect :postgresql}))]
      (try
        (let [s (session/store handle {:lifetime-ms 60000 :readers {}})
              k (store/write-session s nil (session-of-length 10000))]
          (is (= (session-of-length 10000) (store/read-session s k))
              (str engine ": a dialect's unbounded table takes a session the portable one refuses")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"longer than the 4000"
                              (store/write-session (session/store {:datasource (:datasource handle)}
                                                                  {:lifetime-ms 60000 :readers {}})
                                                   nil (session-of-length 4001)))
            (str engine ": a handle that does not say gets the portable bound, the conservative one"))
        (finally (db/stop handle))))))

(deftest a-hand-built-handle-with-a-bad-bound-is-refused-at-construction
  (let [[_ url] (first (ts/engines))
        handle  (booted url)]
    (try
      (doseq [bad ["4000" 0 -1 4000.0]]
        (is (= ["db-base: the handle's :session-data-max must be a positive integer, or nil for an unbounded table"
                {:config-key [:session-data-max] :value bad}]
               (try (session/store (assoc handle :session-data-max bad) {:lifetime-ms 60000 :readers {}}) nil
                    (catch clojure.lang.ExceptionInfo e (ts/pair e))))
            (str (pr-str bad) " is refused here rather than at the first write")))
      (is (some? (session/store (assoc handle :session-data-max nil) {:lifetime-ms 60000 :readers {}}))
          "control: nil, a dialect's unbounded table, is taken")
      (finally (db/stop handle)))))

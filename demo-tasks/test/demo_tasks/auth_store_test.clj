(ns demo-tasks.auth-store-test
  "auth-base's `Store` port, and the one property in it that a careless test
  cannot see.

  **Why the concurrency here is forced rather than raced.** `take-challenge!`
  must never hand the same row to two callers, and auth-base says in so many
  words that a test which does not run two callers concurrently has not tested
  it. But two threads and a barrier are not enough either: SQLite serialises
  writers at the file, so a broken implementation can pass that test every time
  on this machine. What this suite does instead is **suspend one caller between
  its statements, above the driver**, by handing the store db-base's
  `testing/parking` handle, which parks the first DELETE any of its connections
  is asked to prepare. It began as a harness written here, and moved into the
  library once it had proved itself; what it adds over that first version is
  `:exit`, which says whether the suspension was ended by the test or ran out on
  its own, and a driver's exceptions arriving as themselves. The other
  caller then runs to completion in a window that is chosen rather than hoped
  for, and the outcome is the same on every run and every engine.

  And a harness that never intercepts anything would make all of that green
  over nothing, so `a-naive-store-is-caught-by-this-very-harness` runs the same
  machinery against an implementation that decides from its read. If that test
  ever goes green, nothing else in this namespace means anything."
  (:require [clojure.test :refer [deftest is testing]]
            [demo-tasks.accounts :as accounts]
            [demo-tasks.auth-store :as auth-store]
            [demo-tasks.support :as support :refer [with-db]]
            [dev.arkaitz.auth-base.store :as store]
            [dev.arkaitz.db-base.testing :as dbt]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [clojure.lang ExceptionInfo]))

(def ^:private ada "ada@example.test")
(def ^:private a-token (apply str (repeat 43 "A")))

;; --- the harness ----------------------------------------------------------

(def ^:private a-delete
  "What the harness parks on: the first statement that deletes, however it is spelt."
  #(re-find #"(?i)^\s*delete" %))

(defn- naive-store
  "A store that decides from its read: it selects, deletes, and returns what the
  select found whatever the delete did. This is the implementation the harness
  must catch, and it exists only to prove that it does."
  [db]
  (let [one (fn [sql & params]
              (jdbc/execute-one! (:datasource db) (into [sql] params)
                                 {:builder-fn rs/as-unqualified-lower-maps}))]
    (reify store/Store
      (put-challenge! [this _ _ _] this)
      (take-challenge! [_ token]
        (when-let [row (one "SELECT identifier, expires_at FROM login_challenge WHERE token = ?" token)]
          (one "DELETE FROM login_challenge WHERE token = ?" token)
          {:ab/identifier (:identifier row) :ab/expires-at (:expires_at row)}))
      (subject-for [_ identifier] (accounts/subject-for db identifier))
      (generation [_ subject] (accounts/generation db subject))
      (bump-generation! [_ subject] (accounts/bump-generation! db subject)))))

(defn- race-one-take
  "Plants a challenge, suspends one caller inside its take, lets another finish,
  then releases the first. Answers `{:winners [...] :threw [...] :parked-in-time?
  bool :exit kw :slow-returned? bool :rows-left n}` — every outcome named
  separately, so a failure says which of them happened. `:winners` counts only
  callers that came back, so `:slow-returned?` is what keeps a caller that never
  returns from reading as one that lost. `:exit` is how the suspension ended, and only
  `:released` means the window was the one this builds: a guard that ran out
  first would have let the callers take any order at all."
  [db path make-store]
  (store/put-challenge! (auth-store/store db) a-token ada 9999999999999)
  (let [{:keys [arrived release! exit] parked :handle} (dbt/parking db a-delete 20000)
        slow     (make-store parked)
        fast     (make-store db)
        results  (atom [])
        thrown   (atom [])
        runner   (fn [store]
                   (try (swap! results conj (store/take-challenge! store a-token))
                        (catch Throwable t (swap! thrown conj t))))
        ;; A daemon, so a caller that never returns is a red below and not a test
        ;; run that cannot exit.
        a        (doto (Thread. #(runner slow)) (.setDaemon true) (.start))]
    (try
      (let [in-time? (not= ::hang (deref arrived 20000 ::hang))]
        (when in-time? (runner fast))
        (release!)
        (.join a 30000)
        {:slow-returned?  (not (.isAlive a))
         :winners         (vec (remove nil? @results))
         :threw           @thrown
         :parked-in-time? in-time?
         :exit            (deref exit 5000 ::hang)
         :rows-left       (count (support/rows path "SELECT token FROM login_challenge WHERE token = ?" a-token))})
      (finally (release!)))))

;; --- the property ---------------------------------------------------------

(deftest a-challenge-is-taken-once-even-when-a-caller-is-suspended-mid-take
  (with-db [db path]
    (is (= [["wal"]] (support/rows path "PRAGMA journal_mode"))
        (str "precondition: the journal is WAL — under the default rollback journal the"
             " suspended caller below would block the other one and this test would"
             " deadlock into its own hang guard instead of interleaving"))
    (let [{:keys [winners threw parked-in-time? exit slow-returned? rows-left]}
          (race-one-take db path auth-store/store)]
      (is (= [] threw)
          (str "neither caller threw, so a nil below is a caller that lost and never an"
               " exception wearing its clothes: " (pr-str (mapv ex-message threw))))
      (is parked-in-time?
          (str "the harness intercepted a DELETE and suspended a caller — if the statement"
               " is ever rewritten so this regex stops matching, THIS is the assertion that"
               " says so, instead of the whole test passing over nothing for ever"))
      (is (= :released exit)
          (str "and the suspension ended because the test released it, after the other"
               " caller had finished — a guard that ran out first means the order below"
               " was not the one this test claims"))
      (is (true? slow-returned?)
          (str "and the suspended caller came back once released — one that hangs would"
               " otherwise look like the caller that lost, and losing must answer nil"))
      (is (= 1 (count winners))
          (str "exactly one caller was handed the row: " (pr-str winners)))
      (is (= {:ab/identifier ada :ab/expires-at 9999999999999} (first winners))
          "and it is the row that was planted, under the keys the port names")
      (is (= 0 rows-left)
          "and the challenge is gone, read through this test's own connection"))))

(deftest a-naive-store-is-caught-by-this-very-harness
  ;; The control. Without it every green above is unfalsifiable: a proxy that
  ;; intercepted nothing, a thread that never ran, a barrier that quietly
  ;; deadlocked would all look exactly like success.
  (with-db [db path]
    (let [{:keys [winners parked-in-time? exit slow-returned?]} (race-one-take db path naive-store)]
      (is parked-in-time? "the harness suspended a caller here too")
      (is (= :released exit) "and released it only after the other caller finished")
      (is (true? slow-returned?) "and it came back")
      (is (= 2 (count winners))
          (str "and a store that decides from its read hands the SAME row to both callers — "
               "which is what makes the single winner above an observation rather than a hope: "
               (pr-str winners))))))

;; --- the rest of the port -------------------------------------------------

(deftest put-challenge!-refuses-a-non-numeric-expiry-before-writing-anything
  (with-db [db path]
    (let [s (auth-store/store db)]
      (is (thrown-with-msg? ExceptionInfo #"expiry must be a number"
                            (store/put-challenge! s a-token ada "soon"))
          "an expiry that cannot be compared is refused")
      (is (= [[0]] (support/rows path "SELECT COUNT(*) FROM login_challenge"))
          (str "and refused BEFORE the insert — the count is the assertion here, because the"
               " throw alone is satisfied by a store that wrote the row and complained after"))
      (is (identical? s (store/put-challenge! s a-token ada 9999999999999))
          "while a good one returns the store, as the port says it must"))))

(deftest an-expired-challenge-is-still-consumed-by-the-attempt-that-finds-it
  ;; The port is explicit: expiry is the ceremony's judgement, not the store's.
  ;; A `WHERE expires_at > ?` in the take would leave an expired link redeemable
  ;; again and again until something swept it, which is single use quietly ending.
  (with-db [db path]
    (let [s (auth-store/store db)]
      (store/put-challenge! s a-token ada 1)
      (is (= {:ab/identifier ada :ab/expires-at 1} (store/take-challenge! s a-token))
          "an expired row is handed over, for the ceremony to refuse")
      (is (nil? (store/take-challenge! s a-token))
          "and it is gone — the attempt that found it expired spent it")
      (is (= [[0]] (support/rows path "SELECT COUNT(*) FROM login_challenge"))
          "with nothing left behind"))))

(deftest a-token-the-store-never-held-is-answered-with-nothing
  (with-db [db _path]
    (is (nil? (store/take-challenge! (auth-store/store db) (apply str (repeat 43 "B"))))
        "no row, no answer, and no exception")))

(deftest the-account-half-of-the-port-is-wired-to-the-account-namespace
  ;; Thin delegation, so what is asserted here is that it is wired at all —
  ;; the behaviour itself is pinned in `accounts-test`, and repeating it would
  ;; be two tests failing for one defect.
  (with-db [db _path]
    (let [s       (auth-store/store db)
          subject (accounts/register! db ada)]
      (is (= subject (store/subject-for s ada)) "subject-for reaches the accounts table")
      (is (= 0 (store/generation s subject)) "generation answers 0 for a subject never revoked")
      (is (= 1 (store/bump-generation! s subject)) "and bump-generation! moves it")
      (is (= 1 (store/generation s subject)) "which the next read agrees with"))))

(deftest reclaim-expired!-removes-what-has-died-and-nothing-else
  (with-db [db path]
    (let [s (auth-store/store db)]
      (store/put-challenge! s a-token ada 100)
      (store/put-challenge! s (apply str (repeat 43 "C")) ada 300)
      (is (= 1 (auth-store/reclaim-expired! db 200))
          "one challenge had expired by then")
      (is (= [[(apply str (repeat 43 "C"))]]
             (support/rows path "SELECT token FROM login_challenge ORDER BY token"))
          "and the one that had not is untouched")
      (testing "and it is the operator's, never a timer"
        (is (= 0 (auth-store/reclaim-expired! db 200))
            "calling it again reclaims nothing, because nothing new has expired")))))

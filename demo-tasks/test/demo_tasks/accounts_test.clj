(ns demo-tasks.accounts-test
  "Registration, and the revocation generation that hangs off it.

  Two contracts of auth-base's `Store` port are being honoured here and are
  worth naming, because both fail silently: `subject-for` must never create,
  and `bump-generation!` must work for a subject with no account row."
  (:require [clojure.test :refer [deftest is testing]]
            [demo-tasks.accounts :as accounts]
            [demo-tasks.support :as support :refer [with-db]]
            [dev.arkaitz.db-base.testing :as dbt]
            [next.jdbc :as jdbc])
  (:import [java.sql SQLException]
           [java.util.concurrent Callable CyclicBarrier Executors TimeUnit]))

(def ^:private ada "ada@example.test")

(deftest subject-for-answers-nothing-for-an-unknown-address-and-creates-nothing
  (with-db [db path]
    (is (nil? (accounts/subject-for db ada))
        "an address with no account is nobody")
    (is (= [[0]] (support/rows path "SELECT COUNT(*) FROM account"))
        (str "and asking did not make one — the assertion that separates a read from the"
             " create-on-read the port forbids"))
    (let [subject (accounts/register! db ada)]
      (is (= [[1]] (support/rows path "SELECT COUNT(*) FROM account"))
          "control: registering does make one, so the zero above is not a broken table")
      (is (= subject (accounts/subject-for db ada))
          (str "and what register! returned is exactly what subject-for answers from then"
               " on — auth-base freezes the first into the session and revokes by the"
               " second, so two different values would be a session nothing can end")))))

(deftest registering-the-same-address-twice-finds-the-first-account
  (with-db [db path]
    (let [first-time  (accounts/register! db ada)
          second-time (accounts/register! db ada)]
      (is (= first-time second-time)
          (str "the same subject both times — a second account here is somebody whose tasks"
               " vanished when they logged in again, which is what this actually costs"))
      (is (= [[1]] (support/rows path "SELECT COUNT(*) FROM account"))
          "and one row, not two"))))

(deftest the-engine-refuses-a-second-account-for-one-address
  ;; Behavioural rather than a read of the DDL looking for the word UNIQUE: the
  ;; constraint is what makes `register!` safe under contention, and a test that
  ;; matched schema text would stay green through an honest rewrite of the
  ;; migration and red on a harmless one.
  (with-db [_db path]
    (jdbc/execute-one! (support/datasource path)
                       ["INSERT INTO account (subject, identifier, created_at) VALUES (?, ?, ?)"
                        "subject-one" ada 1])
    (is (thrown? SQLException
                 (jdbc/execute-one! (support/datasource path)
                                    ["INSERT INTO account (subject, identifier, created_at) VALUES (?, ?, ?)"
                                     "subject-two" ada 2]))
        "a second row for the same address is refused by the database itself")
    (is (= [["subject-one"]] (support/rows path "SELECT subject FROM account WHERE identifier = ?" ada))
        "and the first row is untouched")))

(deftest a-caller-that-loses-the-race-to-register-is-handed-the-winners-subject
  ;; The deterministic version of the test below. One caller is suspended at its
  ;; INSERT — which it reaches only because its read found nothing — while another
  ;; registers the same address to completion. No other test reaches the branch
  ;; where the insert is refused: every other call finds the row on its first read.
  (with-db [db path]
    (is (= [[0]] (support/rows path "SELECT COUNT(*) FROM account"))
        (str "precondition: no account yet, so the first caller's read must miss and a"
             " failure to arrive below is the park or the statement, never a row already there"))
    (let [{:keys [arrived release! exit] parked :handle}
          (dbt/parking db #(re-find #"^INSERT INTO account " %) 10000)
          [_ loser] (let [result (promise)]
                      [(doto (Thread. #(deliver result (try [:ok (accounts/register! parked ada)]
                                                            (catch Throwable t [:threw t]))))
                         (.setDaemon true)
                         (.start))
                       result])]
      (try
        (is (re-find #"^INSERT INTO account " (str (deref arrived 5000 ::hang)))
            (str "the first caller read, found nothing, and was suspended at its insert — the"
                 " only way into the branch this test is about"))
        (let [winner (accounts/register! db ada)]
          (is (false? (realized? exit))
              "the second caller registered while the first was still suspended")
          (release!)
          (is (= :released (deref exit 5000 ::hang)) "and the suspension ended by release")
          (is (= [:ok winner] (deref loser 5000 [::hang]))
              (str "the caller whose insert was refused is handed the winner's subject — not"
                   " the value it minted, and not the engine's refusal"))
          (is (= [[winner]] (support/rows path "SELECT subject FROM account WHERE identifier = ?" ada))
              "and the one account there is the winner's")
          (is (= [[1]] (support/rows path "SELECT COUNT(*) FROM account"))
              "one account in all: the value the loser minted appears nowhere"))
        (finally (release!))))))

(deftest an-insert-the-engine-refuses-for-another-reason-reaches-the-caller-and-creates-nobody
  ;; The other half of the race above: a refused insert with no account there to
  ;; find. Handing back a subject anyway would give `:on-unknown` somebody with no
  ;; row, and auth-base would freeze that ghost into a session. A null address is
  ;; the refusal nothing upstream stops, since `subject-for` of nil finds nothing.
  (with-db [db path]
    (is (thrown? SQLException (accounts/register! db nil))
        "the engine's refusal of a null address reaches the caller")
    (is (= [[0]] (support/rows path "SELECT COUNT(*) FROM account"))
        "and no account exists")
    (is (string? (accounts/register! db ada))
        "control: the same path with an address registers, so the refusal above is the null")))

(deftest registering-concurrently-still-yields-one-account
  ;; **Exploratory, and its green is not a proof.** SQLite serialises writers at
  ;; the file, so this may never interleave the read and the insert that
  ;; `register!` puts between the barrier and the constraint — a broken
  ;; implementation could pass it every time on this machine. It earns its place
  ;; as a net for interleavings nobody enumerated; the deterministic version,
  ;; which parks one caller between its two statements, is the test above.
  (with-db [db path]
    (let [threads 8
          barrier (CyclicBarrier. threads)
          pool    (Executors/newFixedThreadPool threads)
          answers (try
                    (->> (repeat threads
                                 (reify Callable
                                   (call [_] (.await barrier 10 TimeUnit/SECONDS)
                                     (accounts/register! db ada))))
                         (into [])
                         (.invokeAll pool)
                         (mapv #(.get ^java.util.concurrent.Future % 30 TimeUnit/SECONDS)))
                    (finally (.shutdownNow pool)))]
      (is (= 1 (count (distinct answers)))
          (str "every caller was handed the same subject: " (pr-str (distinct answers))))
      (is (= [[1]] (support/rows path "SELECT COUNT(*) FROM account"))
          "and exactly one account exists")
      (is (= [[(first answers)]]
             (support/rows path "SELECT subject FROM account WHERE identifier = ?" ada))
          "which is the one they were all given"))))

(deftest a-generation-starts-at-zero-and-moves-for-a-subject-with-no-account-row
  (with-db [db path]
    (let [stranger (str (random-uuid))]
      (is (= 0 (accounts/generation db stranger))
          (str "a subject the store has never seen is generation 0 — `=` against the literal 0"
               " and never `zero?`, because a nil here compares equal to a session's nil"
               " generation and would authenticate everybody for ever"))
      (is (= [[0]] (support/rows path "SELECT COUNT(*) FROM account WHERE subject = ?" stranger))
          "precondition: and they have no account row at all, which is the case that matters")
      (is (= 1 (accounts/bump-generation! db stranger))
          "revoking them works anyway, and answers the new value")
      (is (= 1 (accounts/generation db stranger))
          "which the next read agrees with")
      (is (= 2 (accounts/bump-generation! db stranger))
          "and again")
      (is (= 2 (accounts/generation db stranger))
          "still agreeing — an update that wrote one place and read another would diverge here")
      (is (= [[2]] (support/rows path "SELECT generation FROM account_generation WHERE subject = ?" stranger))
          "and the row itself says so, read through this test's own connection")
      (testing "and it moves nobody else's"
        (is (= 0 (accounts/generation db (str (random-uuid))))
            "another subject is still 0")))))

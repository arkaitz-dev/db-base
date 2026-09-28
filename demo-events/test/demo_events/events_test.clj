(ns demo-events.events-test
  "Places and the waiting list, read back through a connection of the test's own.

  **What SQLite can and cannot say here.** The host's URL carries
  `transaction_mode=IMMEDIATE`, so a transaction takes the write lock when it begins
  and two transactions never interleave on this engine (FRICTION.md, F6). The
  conditional `UPDATE … WHERE going < capacity` is what keeps two people from both
  taking the last place on PostgreSQL, where transactions do run side by side; on
  SQLite a check-then-update inside the transaction would be just as correct, and no
  test here can tell the two apart. What IS testable: the capacity as the schema's
  CHECK, the order of the waiting list, the column staying equal to the rows, that a
  read-then-write outside a transaction over-books under the same harness, and that
  the host's URL keeps a transaction that read first from failing.

  Every waiting order below is planted: `joined_at` is a clock reading, and two joins
  in one millisecond fall back to the subject, a random UUID. The planted order is the
  reverse of the subjects' order, so a query that sorted by subject would be caught."
  (:require [clojure.test :refer [deftest is testing]]
            [demo-events.events :as events]
            [hosts.support :as support :refer [with-db]]
            [dev.arkaitz.auth-base.jdbc :as auth-jdbc]
            [dev.arkaitz.db-base :as db]
            [dev.arkaitz.db-base.testing :as dbt]
            [next.jdbc :as jdbc])
  (:import [java.sql SQLException]))

(defn- people [db n]
  (vec (for [i (range n)] (auth-jdbc/register! (:datasource db) (str "p" i "@example.test")))))

(defn- plant-order!
  "Gives `subjects`' answers to `ev` ascending join times in the reverse of their
  subject order, and answers the subjects in that planted order."
  [path ev subjects]
  (let [ordered (vec (sort #(compare %2 %1) subjects))]
    (doseq [[i s] (map-indexed vector ordered)]
      (jdbc/execute-one! (support/datasource path)
                         ["UPDATE rsvp SET joined_at = ? WHERE event_id = ? AND subject = ?" (* 100 (inc i)) ev s]))
    ordered))

(defn- state
  "[the event's going column, the rows that say going, the waiting subjects in join order]."
  [path ev]
  [(support/one path "SELECT going FROM event WHERE id = ?" ev)
   (support/one path "SELECT COUNT(*) FROM rsvp WHERE event_id = ? AND status = 'going'" ev)
   (mapv first (support/rows path "SELECT subject FROM rsvp WHERE event_id = ? AND status = 'waiting'
                                   ORDER BY joined_at" ev))])

(deftest capacity-is-taken-by-going-and-the-rest-wait-in-order
  (with-db [db path]
    (let [[a b c d] (people db 4)
          ev        (events/create-event! db a "Dinner" 2 0)]
      (is (= [:going :going :waiting :waiting] (mapv #(events/join! db % ev) [a b c d]))
          "two places, four people")
      (is (= :already (events/join! db a ev)) "answering twice changes nothing")
      (is (nil? (events/join! db a (str (random-uuid)))) "an event that does not exist answers nil")
      (let [order (plant-order! path ev [c d])]
        ;; The order here is planted, so only who waits is a signal; the order itself is
        ;; the next test's, which plants it and then watches the promotions follow it.
        (is (= [2 2 order] (state path ev)) "the column says two, two rows say going, the other two wait"))
      (is (= 4 (support/one path "SELECT COUNT(*) FROM rsvp")) "and nothing was written by the repeat or the missing event")
      (is (instance? SQLException
                     (try (jdbc/execute-one! (support/datasource path) ["UPDATE event SET going = 3 WHERE id = ?" ev])
                          nil (catch SQLException e e)))
          "the schema itself refuses a going count above the capacity"))))

(deftest leaving-promotes-the-earliest-waiting-or-gives-the-place-back
  ;; Not killable here: the promotion's `AND status = 'waiting'`, whose purpose is two
  ;; people leaving at once on an engine that runs their transactions side by side.
  ;;
  ;; A second event runs beside it with its own answers, from some of the same people,
  ;; waiting since EARLIER than anybody here: each statement of `leave!` that forgot
  ;; which event it was about would reach into it, and its state is checked after every
  ;; step. (`join!`'s own promotion forgetting its event is the next-but-one test's.)
  (with-db [db path]
    (let [[a b c d e] (people db 5)
          ev          (events/create-event! db a "Dinner" 1 0)
          other       (events/create-event! db a "Lunch" 1 0)]
      ;; There, a goes and e, b, c and d wait. b, c and d wait here too, so whoever is
      ;; promoted here is also waiting there, and a promotion that forgot its event would
      ;; move them in both. e waits there before anybody and is not here at all, so a
      ;; search for "the earliest waiting" that forgot its event finds e and promotes
      ;; nobody here.
      (mapv #(events/join! db % other) [a e b c d])
      (doseq [[s t] [[e 5] [b 10] [c 20] [d 30]]]
        (jdbc/execute-one! (support/datasource path) ["UPDATE rsvp SET joined_at = ? WHERE event_id = ? AND subject = ?" t other s]))
      (mapv #(events/join! db % ev) [a b c d])
      (let [untouched [1 1 [e b c d]]
            check     #(is (= untouched (state path other)) (str "the other event is untouched " %))
            [first-in second-in third-in] (plant-order! path ev [b c d])]
        (check "after the joins here")
        (is (= [1 1 [first-in second-in third-in]] (state path ev)) "witness: a goes, three wait in the planted order")
        (is (true? (events/leave! db a ev)) "the one going leaves")
        (is (= [1 1 [second-in third-in]] (state path ev)) "and the earliest waiting took the place")
        (check "after a leave here promoted somebody")
        (is (= "going" (support/one path "SELECT status FROM rsvp WHERE event_id = ? AND subject = ?" ev first-in)))
        (is (true? (events/leave! db second-in ev)) "a waiting one leaves")
        (is (= [1 1 [third-in]] (state path ev)) "promoting nobody, and the place is still taken")
        (is (true? (events/leave! db third-in ev)))
        (is (= [1 1 []] (state path ev)))
        (is (true? (events/leave! db first-in ev)) "the one going leaves with nobody waiting")
        (is (= [0 0 []] (state path ev)) "and the place is given back")
        (is (not (events/leave! db first-in ev)) "leaving twice finds nothing to leave")
        (is (= [0 0 []] (state path ev)) "and changes nothing")
        (check "after everybody left here")))))

(deftest a-join-records-when-it-was-made
  (with-db [db path]
    (let [[a] (people db 1)
          ev  (events/create-event! db a "Dinner" 1 0)
          t0  (System/currentTimeMillis)
          _   (events/join! db a ev)
          t1  (System/currentTimeMillis)
          at  (support/one path "SELECT joined_at FROM rsvp WHERE event_id = ?" ev)]
      (is (<= t0 at t1) (str "joined_at " at " is the clock between " t0 " and " t1
                             " — the waiting list is ordered by it")))))

(deftest the-lists-are-ordered-and-each-event-answers-for-itself
  (with-db [db path]
    (let [[a b c] (people db 3)
          late    (events/create-event! db a "Late" 1 200)
          soon    (events/create-event! db a "Soon" 1 100)]
      (mapv #(events/join! db % late) [a b c])
      (events/join! db b soon)
      (let [who     #(auth-jdbc/identifier-for (:datasource db) %)
            [w1 w2] (plant-order! path late [b c])]
        (is (= [[(who a) "going"] [(who w1) "waiting"] [(who w2) "waiting"]]
               (mapv (juxt :identifier :status) (events/attendees db late)))
            "attendees: whoever is going, then the waiting in the order they answered"))
      (is (= [["Soon" 0 "going"] ["Late" 2 "waiting"]]
             (mapv (juxt :title :waiting :mine) (events/upcoming db b)))
          (str "upcoming for p1: soonest first, one row per event, each with its own waiting count"
               " and what p1 answered to it"))
      (is (= [["Soon" nil] ["Late" nil]]
             (mapv (juxt :title :mine) (events/upcoming db (auth-jdbc/register! (:datasource db) "nobody@example.test"))))
          "and somebody who answered nothing sees every event once, answering nothing"))))

(deftest a-read-then-write-outside-a-transaction-over-books-and-join-does-not
  (with-db [db path]
    (let [[a b] (people db 2)]
      (testing "control: the naive shape, a read and then a write, loses the last place twice"
        (let [ev     (events/create-event! db a "Naive" 1 0)
              naive! (fn [handle s]
                       (let [ds (:datasource handle)
                             n  (:going (jdbc/execute-one! ds ["SELECT going FROM event WHERE id = ?" ev]
                                                           {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps}))]
                         (when (< n 1)
                           (jdbc/execute-one! ds ["UPDATE event SET going = ? WHERE id = ?" (inc n) ev])
                           (jdbc/execute-one! ds ["INSERT INTO rsvp (event_id, subject, status, joined_at) VALUES (?, ?, 'going', 0)" ev s]))))
              {:keys [arrived release! exit] parked :handle} (dbt/parking db #(.startsWith ^String % "UPDATE event") 10000)
              done (promise)]
          (doto (Thread. #(deliver done (try (naive! parked a) (catch Throwable t t)))) (.setDaemon true) (.start))
          (try
            (is (string? (deref arrived 5000 nil)) "witness: the first caller read the count and is parked before its write")
            (naive! db b)
            (release!)
            (is (= :released (deref exit 5000 ::hang)))
            (deref done 5000 nil)
            (is (= 2 (support/one path "SELECT COUNT(*) FROM rsvp WHERE event_id = ? AND status = 'going'" ev))
                "two people going to an event with one place: the harness really interleaved them")
            (finally (release!)))))
      (testing "join! under the same park keeps one place for one person"
        (let [ev (events/create-event! db a "Real" 1 0)
              {:keys [arrived release! exit] parked :handle} (dbt/parking db #(.startsWith ^String % "UPDATE event") 10000)
              mine  (promise)
              other (promise)]
          (doto (Thread. #(deliver mine (try (events/join! parked a ev) (catch Throwable t [:threw (ex-message t)]))))
            (.setDaemon true) (.start))
          (try
            (is (string? (deref arrived 5000 nil)) "witness: the first join is parked before it takes the place")
            (doto (Thread. #(deliver other (try (events/join! db b ev) (catch Throwable t [:threw (ex-message t)]))))
              (.setDaemon true) (.start))
            (deref other 1000 nil)
            (release!)
            (is (= :released (deref exit 5000 ::hang)))
            (is (= [:going :waiting] [(deref mine 10000 ::hang) (deref other 10000 ::hang)])
                "the parked one kept its turn and the other one waits")
            (is (= [1 1 [b]] (state path ev)))
            (finally (release!))))))))

(deftest a-join-parked-after-its-read-survives-a-writer-committing-meanwhile
  ;; The pin of `transaction_mode=IMMEDIATE`: parked after its read and before its first
  ;; write, the join holds the lock, and the other join waits for it. Under the deferred
  ;; default the other one would finish inside the second and the parked one would fail
  ;; with SQLITE_BUSY_SNAPSHOT. The second is well inside the host's busy_timeout of five.
  (with-db [db path]
    (let [[a b] (people db 2)
          ev    (events/create-event! db a "Dinner" 1 0)
          {:keys [arrived release! exit] parked :handle} (dbt/parking db #(.startsWith ^String % "INSERT INTO rsvp") 10000)
          mine  (promise)
          other (promise)]
      (doto (Thread. #(deliver mine (try (events/join! parked a ev) (catch Throwable t [:threw (ex-message t)]))))
        (.setDaemon true) (.start))
      (try
        (is (string? (deref arrived 5000 nil)) "witness: the join read the event and is parked before writing")
        (doto (Thread. #(deliver other (try (events/join! db b ev) (catch Throwable t [:threw (ex-message t)]))))
          (.setDaemon true) (.start))
        (is (nil? (deref other 1000 nil)) "the other join is waiting for the lock, not finished")
        (release!)
        (is (= :released (deref exit 5000 ::hang)))
        (is (= [:going :waiting] [(deref mine 10000 ::hang) (deref other 10000 ::hang)])
            "neither failed: the one that read first took the place")
        (is (= [1 1 [b]] (state path ev)))
        (finally (release!))))))

(deftest only-the-owner-deletes-and-the-answers-go-with-it
  (let [run (fn [handle path]
              (let [[a b c d] (people handle 4)
                    ev        (events/create-event! handle a "Dinner" 1 0)
                    counts    #(vector (support/one path "SELECT COUNT(*) FROM event")
                                       (support/one path "SELECT COUNT(*) FROM rsvp"))]
                (events/join! handle b ev)
                (events/join! handle c ev)
                [(mapv #(events/delete-event! handle % ev) [b d])
                 (events/delete-event! handle a (str (random-uuid)))
                 (counts)
                 (events/delete-event! handle a ev)
                 (counts)]))]
    (with-db [db path]
      (is (= [[0 0] 0 [1 2] 1 [0 0]] (run db path))
          "a guest, an outsider and a wrong id delete nothing; the owner deletes the event and its answers"))
    (let [path (support/temp-db-path)]
      (try
        (let [handle (db/start (assoc (support/config path) :jdbc-url (support/url-without-foreign-keys path)))]
          (try
            (is (= [[0 0] 0 [1 2] 1 [0 2]] (run handle path))
                "control: without foreign_keys=true the answers outlive the event — the cascade above is the pragma's")
            (finally (db/stop handle))))
        (finally (support/delete-db! path))))))

(ns demo-tasks.devices-test
  "The host's own record of where somebody is signed in — the third door db-base
  §12 said did not exist.

  What is asserted here is the data layer. That ending one session really does
  leave the others working, through the whole stack and with a cookie store as
  the control, is the seam test's claim and a different one."
  (:require [clojure.test :refer [deftest is testing]]
            [demo-tasks.devices :as devices]
            [demo-tasks.support :as support :refer [with-db]]
            [next.jdbc :as jdbc])
  (:import [java.sql SQLException]))

(def ^:private ada "11111111-1111-1111-1111-111111111111")
(def ^:private bob "22222222-2222-2222-2222-222222222222")

(deftest a-session-is-recorded-once-however-many-requests-arrive-on-it
  (with-db [db path]
    (devices/seen! db ada "session-one" "Firefox")
    (devices/seen! db ada "session-one" "Firefox")
    (devices/seen! db ada "session-one" "Firefox")
    (is (= [["session-one" ada "Firefox"]]
           (support/rows path "SELECT session_id, subject, user_agent FROM device"))
        (str "one row after three requests — the primary key is the arbiter, so the"
             " ordinary case of arriving again costs nothing and changes nothing"))
    (devices/seen! db ada "session-two" "Safari")
    (is (= [["session-one"] ["session-two"]]
           (support/rows path "SELECT session_id FROM device ORDER BY session_id"))
        "and a second session of the same person's is a second row")))

(deftest nothing-is-recorded-without-both-a-subject-and-a-session
  ;; An anonymous request has no subject, and the login request itself has a
  ;; `:session/key` naming the session being replaced rather than the new one.
  ;; Both arrive here as nil, and a row for either would be a device nobody can
  ;; end and a list nobody can trust.
  (with-db [db path]
    (devices/seen! db nil "session-one" "Firefox")
    (devices/seen! db ada nil "Firefox")
    (devices/seen! db nil nil "Firefox")
    (is (= [[0]] (support/rows path "SELECT COUNT(*) FROM device"))
        "nothing was written")
    (devices/seen! db ada "session-one" "Firefox")
    (is (= [[1]] (support/rows path "SELECT COUNT(*) FROM device"))
        "control: with both, it is written — so the zero above is a refusal and not a broken insert")))

(deftest the-list-is-one-persons-and-forgetting-is-too
  (with-db [db path]
    (devices/seen! db ada "ada-one" "Firefox")
    (devices/seen! db ada "ada-two" "Safari")
    (devices/seen! db bob "bob-one" "Chrome")

    (testing "each sees only their own"
      (is (= ["ada-one" "ada-two"] (mapv :session_id (devices/list-devices db ada)))
          "ada's two")
      (is (= ["bob-one"] (mapv :session_id (devices/list-devices db bob)))
          "bob's one, which is what says the filter is the query"))

    (testing "and forgetting carries the owner, on the same id in the same test"
      (is (= 0 (devices/forget! db ada "bob-one"))
          "ada cannot forget bob's session: zero rows")
      (is (= [[1]] (support/rows path "SELECT COUNT(*) FROM device WHERE session_id = ?" "bob-one"))
          "and it is still there")
      (is (= 1 (devices/forget! db bob "bob-one"))
          (str "while bob can forget his own, on that very id — so the zero above is the"
               " owner predicate rather than a statement that never works"))
      (is (= [[0]] (support/rows path "SELECT COUNT(*) FROM device WHERE session_id = ?" "bob-one"))
          "and then it is gone"))

    (testing "and the others are untouched"
      (is (= ["ada-one" "ada-two"] (mapv :session_id (devices/list-devices db ada)))
          "ada still has both — ending one place does not end the rest"))))

(deftest an-engine-complaint-that-is-not-a-duplicate-reaches-the-caller
  ;; `seen!` catches SQLException because arriving again on a session it already
  ;; knows is the ordinary case, not a failure. A bare catch would read as "the
  ;; row was already there" and mean "the engine said something", turning a
  ;; column that refuses a null, a full disk or a dropped table into a silent
  ;; no-op on the one path that makes a person's sessions visible to them.
  (with-db [db path]
    (jdbc/execute-one! (support/datasource path) ["DROP TABLE device"])
    (is (thrown? SQLException (devices/seen! db ada "session-one" "Firefox"))
        (str "the complaint reaches the caller, because the row it would name is not"
             " there — which is how this tells a duplicate from everything else"))))

(deftest a-long-user-agent-is-cut-rather-than-refused
  ;; The header is whatever a stranger's browser sends, and the column is
  ;; bounded. SQLite would store any length and PostgreSQL would refuse the
  ;; write, so a host that passed it straight through would work here and lose
  ;; somebody's sign-in there.
  (with-db [db path]
    (devices/seen! db ada "session-one" (apply str (repeat 500 "x")))
    (is (= [[200]] (support/rows path "SELECT LENGTH(user_agent) FROM device"))
        "cut to what the column holds, in the host rather than by the engine")))

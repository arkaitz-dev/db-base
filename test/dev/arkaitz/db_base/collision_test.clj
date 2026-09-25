(ns dev.arkaitz.db-base.collision-test
  "`arbitrate!`, whose whole claim is one sentence: a refused write is answered by
  looking at whether what it was for is there, never by the engine's code for why it
  refused. Most of what follows needs no engine, because the function never touches one
  — the host's two functions do. The engine half exists for the one fact a stub cannot
  supply: that H2 and SQLite really do refuse a duplicate key with an `SQLException`, and
  really do disagree about what to call it."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.db-base.collision :as collision]
            [dev.arkaitz.db-base.test-support :as ts])
  (:import [java.sql SQLException]))

(defn- refusal
  "A refused write as the engine would hand it over. `state` is the SQLSTATE, which is
  set on purpose in some of these: the function must not read it."
  ([] (refusal "refused" nil))
  ([message state] (SQLException. ^String message ^String state)))

(defn- counting
  "A `re-read` that answers `answer` and counts how often it was asked."
  [calls answer]
  (fn [] (swap! calls inc) answer))

(defn- thrown
  "What `f` threw, whatever it was, or ::none."
  [f]
  (try (f) ::none (catch Throwable t t)))

;; --- the contract, with no engine ------------------------------------------

(deftest a-write-that-succeeds-is-answered-by-itself-and-nobody-looks
  (let [calls (atom 0)]
    (is (= :written (collision/arbitrate! (constantly :written) (counting calls :somebody-else)))
        "the write's own value comes back")
    (is (= 0 @calls)
        (str "and the re-read was never asked — a function that always looked would cost a"
             " round trip on every write that did not collide, which is every write"))))

(deftest a-refused-write-is-answered-by-what-the-re-read-finds
  (let [calls (atom 0)]
    (is (= {:row "already there"}
           (collision/arbitrate! #(throw (refusal)) (counting calls {:row "already there"})))
        "the row that was already there comes back in the write's place")
    (is (= 1 @calls) "asked exactly once")))

(deftest a-re-read-that-finds-nothing-rethrows-the-write's-own-exception--the-same-object
  ;; `identical?`, not merely the same class or message: a function that wrapped the
  ;; refusal, or built a fresh one, would pass an `instance?` check and break every
  ;; caller that pins the exception it expects — §7's lock does, through
  ;; `migrations_test`.
  (doseq [[label answer] [["nil" nil] ["false, which is how `readable?` says no" false]]]
    (let [refused (refusal)
          calls   (atom 0)]
      (is (identical? refused (thrown #(collision/arbitrate! (fn [] (throw refused))
                                                             (counting calls answer))))
          (str "the very exception the write threw leaves, unwrapped: " label))
      (is (= 1 @calls) (str "after one look: " label)))))

(deftest the-engine's-code-for-a-refusal-is-never-read
  ;; The control against the most tempting optimisation there is: "23505 means a
  ;; duplicate, so skip the look". SQLite leaves the state empty for the same refusal,
  ;; so a function that trusted the code would answer differently on two engines for
  ;; one event. Both directions are pinned: a duplicate-key code with nothing there
  ;; still rethrows, and no code at all with the row there still finds it.
  (let [duplicate (refusal "duplicate key" "23505")]
    (is (identical? duplicate (thrown #(collision/arbitrate! (fn [] (throw duplicate))
                                                             (constantly nil))))
        (str "SQLSTATE 23505 with nothing there: rethrown as it came — the code for a"
             " duplicate is not evidence of one")))
  (is (= :found (collision/arbitrate! #(throw (refusal "no state at all" nil)) (constantly :found)))
      "and no state at all, which is SQLite's spelling, with the row there: found"))

(deftest nothing-but-an-SQLException-is-caught
  (let [calls (atom 0)
        boom  (ex-info "the host's own bug" {})]
    (is (identical? boom (thrown #(collision/arbitrate! (fn [] (throw boom)) (counting calls :row))))
        "an exception of the host's own passes through untouched")
    (is (= 0 @calls)
        "and nobody looked — a refusal by the engine is the only thing a re-read can answer"))
  (let [calls (atom 0)
        err   (AssertionError. "an Error")]
    (is (identical? err (thrown #(collision/arbitrate! (fn [] (throw err)) (counting calls :row))))
        "an Error passes through too")
    (is (= 0 @calls) "without a look")))

(deftest a-re-read-that-fails-leaves-the-write's-exception-in-charge
  ;; The library's own convention (`release-after-failure!`, `close-after-failure!`):
  ;; a second failure while dealing with a first never replaces it. Two shapes of second
  ;; failure, because the re-read is host code: an engine refusing the read, and the
  ;; host's own code breaking — next.jdbc's ex-info, a nil in its predicate. With only
  ;; the first, treating every non-SQL failure as urgent would have passed, and the
  ;; host's bug would have replaced the engine's refusal in the caller's hands.
  (doseq [[label also-bad] [["the engine refusing the read too"
                             (SQLException. "the re-read could not run either")]
                            ["the host's own re-read breaking"
                             (ex-info "a bug in the host's predicate" {})]]]
    (let [refused (refusal)
          calls   (atom 0)
          out     (thrown #(collision/arbitrate! (fn [] (throw refused))
                                                 (fn [] (swap! calls inc) (throw also-bad))))]
      (is (identical? refused out)
          (str "the write's exception is the one that leaves: " label))
      (is (= [also-bad] (vec (.getSuppressed ^Throwable out)))
          (str "carrying the re-read's as suppressed, so nothing about the second failure is"
               " lost: " label))
      (is (= 1 @calls)
          (str "and the re-read was tried once, not retried — a retry doubles what a refused"
               " write costs, and the docstring promises once: " label)))))

(deftest an-Error-or-an-interrupt-from-the-re-read-is-not-swallowed
  ;; Suppressing these would lose what `start` promises to let through (SPEC §6): an
  ;; interrupt that a caller is waiting for, or a JVM in trouble.
  (doseq [[label make] [["an Error" #(AssertionError. "the JVM is in trouble")]
                        ["an interrupt" #(InterruptedException. "the caller asked to stop")]]]
    (let [refused (refusal)
          urgent  (make)
          out     (thrown #(collision/arbitrate! (fn [] (throw refused)) (fn [] (throw urgent))))]
      (is (identical? urgent out)
          (str label " leaves instead of the write's exception"))
      (is (= [refused] (vec (.getSuppressed ^Throwable out)))
          (str "carrying the write's, so that is not lost either: " label))
      (is (empty? (.getSuppressed ^Throwable refused))
          (str "and only that way round — suppressing each in the other makes a cycle that"
               " prints for ever in a stack trace: " label)))))

;; --- the engines ------------------------------------------------------------

(deftest a-real-duplicate-key-is-answered-with-the-row-that-won--on-both-engines
  ;; The shape the docstring asks for and not the one it warns against: the refused write
  ;; is the SAME row somebody else got in first — a lock row, a device, an account being
  ;; created twice — and the re-read asks for that whole row, not merely its key. A
  ;; re-read by key alone would hand the caller another writer's row as its own, which
  ;; is the one way this function can lie; a test written that way would be the
  ;; canonical example of misuse.
  (doseq [[engine url] (ts/engines)]
    (ts/execute! url "CREATE TABLE account (identifier VARCHAR(40) NOT NULL PRIMARY KEY, subject VARCHAR(40) NOT NULL)")
    (ts/execute! url "INSERT INTO account (identifier, subject) VALUES ('ada', 'ada-subject')")
    (let [refusals (atom [])
          answer   (collision/arbitrate!
                    (fn []
                      (try (ts/execute! url "INSERT INTO account (identifier, subject) VALUES ('ada', 'ada-subject')")
                           (catch SQLException e (swap! refusals conj e) (throw e)))
                      :inserted)
                    #(ffirst (ts/query url (str "SELECT subject FROM account"
                                                " WHERE identifier = 'ada' AND subject = 'ada-subject'"))))]
      (is (= 1 (count @refusals))
          (str engine ": precondition — the engine really did refuse the second insert, so what"
               " follows is the re-read's doing and not a write that quietly succeeded"))
      (is (= "ada-subject" answer)
          (str engine ": the row the write was for is there, so it is the answer — arrived by"
               " somebody else, which is what a collision is"))
      (is (= [["ada-subject"]] (ts/query url "SELECT subject FROM account"))
          (str engine ": and the table still holds exactly that one row")))))

(deftest a-refusal-that-was-not-a-collision-is-rethrown--on-both-engines
  ;; The failure a bare catch would swallow. The row the write was for is not there,
  ;; because the write was refused for being malformed rather than for arriving second.
  (doseq [[engine url] (ts/engines)]
    (ts/execute! url "CREATE TABLE account (identifier VARCHAR(40) NOT NULL PRIMARY KEY, subject VARCHAR(40) NOT NULL)")
    (let [refused (atom nil)
          out     (thrown #(collision/arbitrate!
                            (fn []
                              (try (ts/execute! url "INSERT INTO account (identifier, subject) VALUES ('ada', NULL)")
                                   (catch SQLException e (reset! refused e) (throw e))))
                            (fn [] (seq (ts/query url "SELECT subject FROM account WHERE identifier = 'ada'")))))]
      (is (instance? SQLException @refused)
          (str engine ": precondition — the engine refused a null where it forbids one"))
      (is (identical? @refused out)
          (str engine ": and that very refusal reaches the caller, because nothing was there to"
               " find — the case the first host's device table would have turned into a silent"
               " no-op, had a mutant not caught it before it was committed")))))

(deftest the-two-engines-disagree-about-what-to-call-the-same-refusal
  ;; A characterisation, and labelled as one: it kills no mutant of `arbitrate!`. It pins
  ;; the measurement the function's whole design rests on, so that the day the engines
  ;; agree the reason is visible, and the day somebody proposes reading SQLSTATE after
  ;; all, the disagreement is here to read first.
  (let [states (into {} (for [[engine url] (ts/engines)]
                          (do (ts/execute! url "CREATE TABLE t (id INTEGER NOT NULL PRIMARY KEY)")
                              (ts/execute! url "INSERT INTO t (id) VALUES (1)")
                              [engine (.getSQLState ^SQLException
                                                    (thrown #(ts/execute! url "INSERT INTO t (id) VALUES (1)")))])))]
    (is (= "23505" (states "H2")) (str "H2 names a duplicate key 23505: " (pr-str states)))
    ;; The value itself and not merely a difference: the docstring says SQLite gives no
    ;; state at all, and a `not=` would stay green the day the driver started saying
    ;; 23000 — a change worth knowing about before anyone reads this as permission to
    ;; branch on the code.
    (is (nil? (states "SQLite"))
        (str "and SQLite gives the same refusal no SQLSTATE at all, which is why the answer is"
             " a look and never a code: " (pr-str states)))))

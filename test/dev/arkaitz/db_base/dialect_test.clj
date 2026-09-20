(ns dev.arkaitz.db-base.dialect-test
  "SPEC §3: nothing in this library may be PostgreSQL-shaped, and a suite that runs on
  two engines from different dialect families is how that is proven. This is the guard's
  own check — §3's *the guard is checked before it is trusted* — because a pair that has
  drifted proves nothing and says nothing while it does so: H2 in PostgreSQL mode accepts
  `ON CONFLICT DO NOTHING` and rejects the upsert form, which is neither what one would
  guess nor what it is usually said to do.

  Each forbidden form is fired at a database of its own, on the URL helpers the rest of
  the suite boots with, after an arrange in plain ANSI that proves the table is there and
  holds a row — so a `SQLException` can only be about the clause. The connection is this
  test's own rather than the pool's, which is what §3 asks for and also the shape of what
  it cannot see: a mode set on the pool's own path, by one of the JVM properties §6
  records as the host's, would not reach here. The whole matrix is pinned engine by
  engine, not only §3's rule that at least one of the two must refuse each form: the weaker claim stays green while the pair drifts, as long as every
  form keeps a refuser by accident, and it never says which engine is the strict one.
  Both are asserted, and each reds with its own message.

  Measured 2026-09-20 on H2 2.5.250 and SQLite 3.53.4.0: H2 refuses eight of the nine and
  SQLite refuses the ANSI `MERGE` that H2 takes, so neither engine of the pair is
  redundant. A cell that moves is a driver that changed or a compatibility mode that
  crept in, and either is a decision to retake, not a number to update.

  What a green here cannot say: this reads no source, so a dialect the library spells but
  never runs is `structure_test`'s literal scan, not this, and the two read one list of
  tokens so they cannot drift apart. Seven of the nine forms have an engine that accepts
  them, and that acceptance is what proves the statement is SQL at all; the two neither
  engine takes are told from a typo by the refusal naming the word that was sent, which
  is the last assertion here. A form both engines accept — the existence clause on
  `CREATE TABLE`, which Derby refuses — can have no cell at all, and the scan is where it
  is said instead."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.db-base.test-support :as ts])
  (:import [java.sql Connection DriverManager ResultSet SQLException]))

(def ^:private forbidden
  "The forms §3 and CLAUDE.md name, each written so that the only thing separating it
  from a statement both engines take is the dialect itself. `:then` is what an engine
  that accepted it then shows: a value wider than its column is the opposite mistake to a
  dialect, one the permissive engine does not refuse at all, and pinning what comes back
  is the only way to see it. `:word` is the word a refusal has to name, and it is required
  of every form both engines refuse — see the last assertion."
  [{:form "ON CONFLICT … DO NOTHING"
    :sql  "INSERT INTO probe (id, v) VALUES (1, 'ab') ON CONFLICT (id) DO NOTHING"}
   {:form "ON CONFLICT … DO UPDATE"
    :sql  "INSERT INTO probe (id, v) VALUES (1, 'ab') ON CONFLICT (id) DO UPDATE SET v = 'cd'"}
   {:form "INSERT … RETURNING"
    :sql  "INSERT INTO probe (id, v) VALUES (2, 'ab') RETURNING id"}
   {:form "DELETE … RETURNING"
    :sql  "DELETE FROM probe WHERE id = 1 RETURNING id"}
   {:form "a jsonb column"
    :sql  "CREATE TABLE probe_json (doc jsonb)"}
   {:form "LISTEN"
    :sql  "LISTEN probe_channel"
    :word "LISTEN"}
   {:form "NOTIFY"
    :sql  "NOTIFY probe_channel, 'hello'"
    :word "NOTIFY"}
   {:form "MERGE INTO … WHEN MATCHED"
    :sql  (str "MERGE INTO probe t USING (SELECT 1 AS id, 'cd' AS v) s ON (t.id = s.id)"
               " WHEN MATCHED THEN UPDATE SET t.v = s.v")}
   {:form "a value wider than the column"
    :sql  "INSERT INTO probe (id, v) VALUES (3, 'abcde')"
    :then "SELECT v FROM probe WHERE id = 3"}])

(defn- outcome
  "What one engine does with one forbidden form, on a database of its own: `:rejected`,
  `:accepted`, or `[:accepted <what it then shows>]`. The arrange is ANSI that both
  engines take, and it is read back before the form runs, so a table that never existed
  cannot pass for a dialect this engine refuses."
  [url {:keys [sql then]}]
  (with-open [^Connection c (DriverManager/getConnection url ts/user-sentinel ts/password-sentinel)
              st (.createStatement c)]
    (.execute st "CREATE TABLE probe (id INTEGER PRIMARY KEY, v VARCHAR(2))")
    (.execute st "INSERT INTO probe (id, v) VALUES (1, 'ab')")
    (let [arranged (with-open [^ResultSet rs (.executeQuery st "SELECT COUNT(*) FROM probe")]
                     (.next rs)
                     (.getInt rs 1))]
      (if (not= 1 arranged)
        [::the-arrange-never-happened arranged]
        ;; DELIBERATE: the exception IS the observation, and only this class counts —
        ;; a driver that is not there, or a ClassCastException, must never read as a
        ;; dialect this engine refuses.
        (let [ran (try (.execute st sql) true (catch SQLException _ false))]
          (cond
            (not ran) :rejected
            then      [:accepted (with-open [^ResultSet rs (.executeQuery st then)]
                                   (when (.next rs) (.getString rs 1)))]
            :else     :accepted))))))

(defn- refusal
  "What the engine said when it refused `sql`, or `::accepted` when it did not."
  [url sql]
  (with-open [^Connection c (DriverManager/getConnection url ts/user-sentinel ts/password-sentinel)
              st (.createStatement c)]
    (try (.execute st sql) ::accepted (catch SQLException e (str (ex-message e))))))

(def ^:private matrix
  "Measured 2026-09-20, JDK 26, H2 2.5.250 and SQLite 3.53.4.0 (a scratch probe, not this
  test's own output). SQLite is the permissive half: it takes the PostgreSQL forms it has
  grown and keeps `abcde` in a `VARCHAR(2)` without a word. H2 is the strict half, and
  the one place it is not is the ANSI `MERGE`, which is exactly why the pair is two."
  {["H2"     "ON CONFLICT … DO NOTHING"]     :rejected
   ["H2"     "ON CONFLICT … DO UPDATE"]      :rejected
   ["H2"     "INSERT … RETURNING"]           :rejected
   ["H2"     "DELETE … RETURNING"]           :rejected
   ["H2"     "a jsonb column"]               :rejected
   ["H2"     "LISTEN"]                       :rejected
   ["H2"     "NOTIFY"]                       :rejected
   ["H2"     "MERGE INTO … WHEN MATCHED"]    :accepted
   ["H2"     "a value wider than the column"] :rejected
   ["SQLite" "ON CONFLICT … DO NOTHING"]     :accepted
   ["SQLite" "ON CONFLICT … DO UPDATE"]      :accepted
   ["SQLite" "INSERT … RETURNING"]           :accepted
   ["SQLite" "DELETE … RETURNING"]           :accepted
   ["SQLite" "a jsonb column"]               :accepted
   ["SQLite" "LISTEN"]                       :rejected
   ["SQLite" "NOTIFY"]                       :rejected
   ["SQLite" "MERGE INTO … WHEN MATCHED"]    :rejected
   ["SQLite" "a value wider than the column"] [:accepted "abcde"]})

(deftest each-forbidden-dialect-form-is-refused-by-the-engine-the-matrix-says-refuses-it
  (let [engines  [["H2" #(ts/h2-memory-url ts/url-sentinel)]
                  ["SQLite" #(ts/sqlite-file-url ts/url-sentinel)]]
        scheme   #(second (re-find #"^jdbc:([^:]+):" %))
        observed (into {} (for [[engine new-url] engines
                                form             forbidden]
                            [[engine (:form form)]
                             ;; DELIBERATE: an arrange that fails is reported as the value of
                             ;; its own cell, so the matrix names which one and why instead of
                             ;; ending the whole run in one exception with no cell attached.
                             (try (outcome (new-url) form)
                                  (catch SQLException e
                                    [::the-cell-could-not-be-set-up (ex-message e)]))]))]
    (testing "preconditions"
      (is (= ["h2" "sqlite"] (mapv (fn [[_ new-url]] (scheme (new-url))) engines))
          "the two URLs the whole suite boots on name two different drivers")
      (is (= (set (keys matrix)) (set (keys observed)))
          "every form fired at both engines is a cell the matrix speaks about")
      (is (= [] (vec (remove (fn [token]
                               (let [whole (re-pattern (str "\\b" token "\\b"))]
                                 (some #(re-find whole (:sql %)) forbidden)))
                             ts/dialect-tokens)))
          (str "every token the source scan refuses to find in src is a form this guard"
               " actually fires — the two halves of §3's proof read one list")))
    (is (= [] (vec (for [[cell expected] (sort matrix)
                         :when           (not= expected (observed cell))]
                     {:cell cell :matrix expected :observed (observed cell)})))
        (str "SPEC §3: the guard has drifted — a cell that moved is a driver that changed"
             " or an engine in a compatibility mode, and the pair must be decided again"))
    (is (= [] (vec (for [form  forbidden
                         :when (not-any? #(= :rejected (observed [% (:form form)])) ["H2" "SQLite"])]
                     (:form form))))
        (str "SPEC §3: a form that neither engine refuses is one this pair proves nothing"
             " about, whatever the matrix says about the cells"))
    ;; The forms every engine refuses are the ones nothing else here can tell from a
    ;; statement nobody meant to write: the other seven are proved to be SQL by the engine
    ;; that takes them. What is matched is this test's own text echoed back, never the
    ;; engine's prose, which is localised and arrives in Spanish on this machine — and the
    ;; boundary is the point, since `LISTEN` is a substring of `LISTENX`.
    (is (= [] (vec (for [form             forbidden
                         :when            (every? #(= :rejected (observed [% (:form form)]))
                                                  ["H2" "SQLite"])
                         [engine new-url] engines
                         :let             [word (:word form)
                                           said (refusal (new-url) (:sql form))]
                         :when            (not (and word
                                                    (string? said)
                                                    (re-find (re-pattern (str "\\b" word "\\b"))
                                                             said)))]
                     [engine (:form form) word said])))
        (str "a form no engine of the pair takes is told from a statement nobody meant to"
             " write only by the refusal naming the word that was sent, so every such form"
             " carries that word and every refusal has to spell it"))))

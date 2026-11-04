(ns dev.arkaitz.db-base.value-test
  "SPEC §10's `value/bool`, over what each test engine really answers for a BOOLEAN
  column and for a comparison — read through a connection of the test's own, so the
  inputs are the drivers' and never a literal the function was written against."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.db-base.test-support :as ts]
            [dev.arkaitz.db-base.value :as value])
  (:import [clojure.lang ExceptionInfo]))

(defn- read-back
  "`[active of the TRUE row, active of the FALSE row, (n > 100) of each]` as `url`'s
  driver answers them, untouched."
  [url]
  (ts/execute! url "CREATE TABLE flag (n BIGINT NOT NULL, active BOOLEAN NOT NULL)")
  (ts/execute! url "INSERT INTO flag (n, active) VALUES (470, TRUE)")
  (ts/execute! url "INSERT INTO flag (n, active) VALUES (30, FALSE)")
  (ts/query url "SELECT active, (n > 100) FROM flag ORDER BY n DESC"))

(deftest a-boolean-column-and-a-comparison-read-as-booleans-on-both-engines
  (doseq [[engine url] (ts/engines)]
    (testing engine
      (let [rows (read-back url)]
        (is (= 2 (count rows)) "witness: both rows were read")
        (is (= [[true true] [false false]] (mapv #(mapv value/bool %) rows))
            (str engine " answered " (pr-str rows) " (" (pr-str (map class (first rows))) "): read as booleans")))))
  (testing "witness: the engines really differ, or this test proves nothing"
    (let [[[_ h2] [_ sqlite]] (ts/engines)]
      (is (= [Boolean Integer] [(class (ffirst (read-back h2))) (class (ffirst (read-back sqlite)))])
          "H2 answers a Boolean and SQLite an Integer"))))

(deftest anything-but-a-boolean-1-or-0-is-refused-by-name
  (is (= [false true false true] (mapv value/bool [false true (long 0) (long 1)])) "a Long 1 or 0 too")
  (doseq [v [nil 2 -1 "1" "true" 1.0 0.0 :true]]
    (is (= ["db-base: not a boolean column's value" {:value v}]
           (try (value/bool v) :answered (catch ExceptionInfo e [(ex-message e) (ex-data e)])))
        (str (pr-str v) ": refused, never guessed"))))

(ns dev.arkaitz.db-base.pool-stats-test
  "SPEC §6: `pool-stats` reports the pool's load as HikariCP counts it. Every number
  below is caused by the test — connections it holds, a thread it leaves waiting — so
  each one is exact; the only thing waited for is the background fill, and that wait is
  a guard, never the claim."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dev.arkaitz.db-base :as db]
            [dev.arkaitz.db-base.test-support :as ts])
  (:import [java.sql Connection]
           [javax.sql DataSource]))

(defn- config [url]
  (-> (ts/config url "three")
      (assoc :migrations :none)
      (assoc :pool {:max 3 :timeout-ms 5000})))

(defn- settled
  "`pool-stats` once `ok?` holds, or the last reading after 10 s. A guard only: the
  assertion is made on what this returns."
  [handle ok?]
  (let [deadline (+ (System/currentTimeMillis) 10000)]
    (loop []
      (let [stats (db/pool-stats handle)]
        (if (or (ok? stats) (< deadline (System/currentTimeMillis)))
          stats
          (do (Thread/sleep 20) (recur)))))))

(deftest pool-stats-counts-what-the-test-holds-and-who-waits
  (doseq [[engine url] (ts/engines)]
    (let [handle (db/start (config url))
          ^DataSource ds (:datasource handle)
          borrow #(.getConnection ds)]
      (try
        (is (= {:active 0 :idle 3 :total 3 :waiting 0} (settled handle #(= 3 (:total %))))
            (str engine ": at rest, once the background fill reached [:pool :max]"))
        (let [one ^Connection (borrow)]
          (is (= {:active 1 :idle 2 :total 3 :waiting 0} (db/pool-stats handle))
              (str engine ": one lent out"))
          (let [two ^Connection (borrow) three ^Connection (borrow)]
            (is (= {:active 3 :idle 0 :total 3 :waiting 0} (db/pool-stats handle))
                (str engine ": all three lent out, nobody waiting yet"))
            (let [fourth (future (with-open [_ (borrow)] :served))]
              (is (= {:active 3 :idle 0 :total 3 :waiting 1} (settled handle #(= 1 (:waiting %))))
                  (str engine ": a fourth borrower is waiting"))
              (.close one)
              (is (= :served (deref fourth 10000 ::hang))
                  (str engine ": witness: the waiter was served by the connection given back"))
              (.close two)
              (.close three)
              (is (= {:active 0 :idle 3 :total 3 :waiting 0} (settled handle #(zero? (:active %))))
                  (str engine ": all given back")))))
        (finally (db/stop handle))))))

(deftest pool-stats-refuses-what-is-not-an-open-pool-without-echoing-it
  (let [config  {:jdbc-url ts/url-sentinel :user ts/user-sentinel :password ts/password-sentinel
                 :pool {:max 1 :timeout-ms 1000} :migrations :none :sessions :none}
        refusal ["db-base: pool-stats takes the handle start returned, whose :datasource is the pool it opened"
                 {:config-key [:datasource]}]]
    (doseq [[label handle] [["nil" nil]
                            ["the configuration map, secrets and all" config]
                            ["a handle whose :datasource is the configuration map" {:datasource config}]
                            ["a DataSource that is not the pool" {:datasource (reify DataSource)}]]]
      (let [e (try (db/pool-stats handle) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= refusal (ts/pair e)) (str label ": refused by name"))
        (is (not-any? #(str/includes? (pr-str (ts/pair e)) %)
                      [ts/url-sentinel ts/user-sentinel ts/password-sentinel])
            (str label ": and nothing of the configuration is echoed")))))
  (let [[_ url] (first (ts/engines))
        handle  (db/start (config url))]
    (is (map? (db/pool-stats handle)) "control: the open pool answers")
    (db/stop handle)
    (is (= ["db-base: pool-stats: the pool has been stopped" {:config-key [:datasource]}]
           (try (db/pool-stats handle) nil (catch clojure.lang.ExceptionInfo e (ts/pair e))))
        "a stopped pool is refused, not reported as zeros")))

(ns dev.arkaitz.db-base.ready-test
  "SPEC §6: `ready?` answers whether the database answers, through
  `Connection/isValid` with the caller's timeout; it refuses a handle that is not one
  and a timeout that is not an integer from 1 to 2147483647 before touching anything,
  and otherwise never throws.

  What `isValid` receives cannot be seen through either test engine, because H2 and
  SQLite both ignore the argument. So the timeout, the close and the mapping of
  exceptions are observed through a recording `DataSource`, which is all `ready?`
  needs; H2 then shows the same answers through a real pool and a real driver. No
  test here bounds how long `ready?` takes: that bound is the driver's (SPEC §6), and
  the guards below only turn a hang into a failure that says so."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.db-base :as db]
            [dev.arkaitz.db-base.test-support :as ts])
  (:import [com.zaxxer.hikari HikariDataSource]
           [java.io IOException]
           [java.sql Connection DriverManager SQLException]
           [javax.sql DataSource]
           [org.h2.tools Server]))

(defn- outcome
  "What `f` returns, or what it throws, as a value an assertion can name."
  [f]
  (try (f) (catch Throwable t t)))

(defn- guarded
  "`outcome` of `f`, which calls a real driver, or `::ts/hang` after 15 s. A hang guard
  only: no pass criterion depends on the number."
  [f]
  (first (ts/elapsed-ms 15000 #(outcome f))))

(defn- recording-handle
  "A handle whose datasource lends one connection. `on-borrow`, `on-valid` and
  `on-close` run inside the matching call and may throw; every call is appended to
  `calls` in order. Any other method of the connection is AbstractMethodError, so a
  `ready?` that ran a statement instead of `isValid` names itself."
  [{:keys [on-borrow on-valid on-close]
    :or   {on-borrow (constantly nil) on-valid (constantly true) on-close (constantly nil)}}]
  (let [calls (atom [])
        conn  (reify Connection
                (isValid [_ t] (swap! calls conj [:isValid t]) (boolean (on-valid)))
                (close [_] (swap! calls conj [:close]) (on-close)))]
    {:calls  calls
     :handle {:datasource (reify DataSource
                            (getConnection [_] (swap! calls conj [:getConnection]) (on-borrow) conn))}}))

(def ^:private one-borrow-validated-with-7 [[:getConnection] [:isValid 7] [:close]])

(deftest ready?-refuses-what-is-not-a-handle-from-start-without-echoing-it
  (let [config  {:jdbc-url ts/url-sentinel :user ts/user-sentinel :password ts/password-sentinel
                 :pool {:max 1 :timeout-ms 1000} :migrations :none}
        refusal ["db-base: ready? takes the handle start returned, whose :datasource is a javax.sql.DataSource"
                 {:config-key [:datasource]}]]
    (doseq [[label handle] [["nil" nil]
                            ["an empty map" {}]
                            ["the configuration map, secrets and all" config]
                            ["a handle whose :datasource is the configuration map" {:datasource config}]
                            ["a handle whose :datasource is the URL" {:datasource ts/url-sentinel}]
                            ["a handle whose :datasource is nil" {:datasource nil}]
                            ["a list" (list :datasource 1 2)]
                            ["a number" 42]]]
      ;; ExceptionInfo only: without the refusal these read as false, which is ::no-throw here.
      (is (= refusal (ts/attempt #(db/ready? handle 2)))
          (str label " is refused by name, and its value is not echoed")))
    (let [{:keys [calls handle]} (recording-handle {})]
      (is (= [refusal []] [(ts/attempt #(db/ready? (:datasource handle) 2)) @calls])
          "the datasource itself, passed without its handle, is refused before it is borrowed from"))))

(deftest ready?-refuses-a-timeout-that-is-not-an-integer-from-1-to-2147483647-before-touching-the-datasource
  (doseq [t [0 -1 nil 1.0 1.5M 3/2 "2" true 2147483648 2147483648N]]
    (let [{:keys [calls handle]} (recording-handle {})]
      ;; ExceptionInfo only: a refusal turned into false by the catch, which is what a
      ;; bound past the int range becomes, reads here as ::no-throw.
      (is (= ["db-base: [:timeout] must be an integer from 1 to 2147483647 seconds"
              {:config-key [:timeout] :value t}]
             (ts/attempt #(db/ready? handle t)))
          (str (pr-str t) " is refused by name"))
      (is (= [] @calls) (str (pr-str t) " is refused before the datasource is touched"))))
  (doseq [[t sent] [[1 1] [2147483647 2147483647] [2N 2] [(int 7) 7]]]
    (let [{:keys [calls handle]} (recording-handle {})]
      (is (= [true [[:getConnection] [:isValid sent] [:close]]]
             [(outcome #(db/ready? handle t)) @calls])
          (str (pr-str t) " is accepted and reaches isValid as " sent)))))

(deftest ready?-answers-what-isvalid-says-with-the-callers-timeout-and-closes-the-connection
  (doseq [[label opts expected]
          [["isValid says true" {}
            [true one-borrow-validated-with-7]]
           ["isValid says false" {:on-valid (constantly false)}
            [false one-borrow-validated-with-7]]
           ["the borrow throws SQLException, as a closed or exhausted pool does"
            {:on-borrow #(throw (SQLException. "borrow"))}
            [false [[:getConnection]]]]
           ["isValid throws SQLException" {:on-valid #(throw (SQLException. "valid"))}
            [false one-borrow-validated-with-7]]
           ["isValid throws a RuntimeException" {:on-valid #(throw (IllegalStateException. "valid"))}
            [false one-borrow-validated-with-7]]
           ;; A JVM driver not written in Java can throw a checked exception undeclared.
           ["isValid throws a checked exception that is not SQL" {:on-valid #(throw (IOException. "valid"))}
            [false one-borrow-validated-with-7]]
           ["isValid throws SQLException and closing throws one too"
            {:on-valid #(throw (SQLException. "valid")) :on-close #(throw (SQLException. "close"))}
            [false one-borrow-validated-with-7]]
           ["close throws SQLException, as HikariCP's does on a dead H2 connection"
            {:on-close #(throw (SQLException. "close"))}
            [false one-borrow-validated-with-7]]]]
    (let [{:keys [calls handle]} (recording-handle opts)]
      (is (= expected [(outcome #(db/ready? handle 7)) @calls])
          (str label ": [answer calls]"))))
  (testing "an Error, from isValid or from closing the connection, is not an answer"
    (let [close-failure (SQLException. "close")
          [e1 e2 e3 e4 e5 e6 other-error] (repeatedly #(Error. "sentinel"))]
      (doseq [[label error opts suppressed]
              [["isValid throws it" e1 {:on-valid #(throw e1)} []]
               ["isValid throws it and closing fails too" e2
                {:on-valid #(throw e2) :on-close #(throw close-failure)} [close-failure]]
               ["isValid throws it and closing rethrows that same Error" e3
                {:on-valid #(throw e3) :on-close #(throw e3)} []]
               ["isValid throws it and closing throws another Error" e6
                {:on-valid #(throw e6) :on-close #(throw other-error)} [other-error]]
               ["isValid throws an Exception, about to become false, and closing throws it" e4
                {:on-valid #(throw (SQLException. "valid")) :on-close #(throw e4)} []]
               ["isValid answers and closing throws it" e5 {:on-close #(throw e5)} []]]]
        (let [{:keys [calls handle]} (recording-handle opts)
              r                      (outcome #(db/ready? handle 7))]
          (is (= [true suppressed one-borrow-validated-with-7]
                 [(identical? error r) (vec (.getSuppressed ^Throwable error)) @calls])
              (str label ": [propagated unchanged, suppressed, calls] " (pr-str r))))))))

(deftest ready?-is-true-on-a-started-handle-false-while-its-pool-is-exhausted-and-false-after-stop--h2
  (let [before (ts/pool-number)
        handle (db/start {:jdbc-url (ts/h2-memory-url "ready") :user "" :password ""
                          :pool {:max 1 :timeout-ms 1000} :migrations :none})
        ds     ^HikariDataSource (:datasource handle)]
    (try
      (is (= (inc (or before 0)) (ts/pool-number)) "precondition: start constructed exactly one pool")
      (is (= 1 (.getMaximumPoolSize ds))
          "precondition: [:pool :max] 1 reached the pool, so a second borrow can only wait")
      (is (= [true true] [(guarded #(db/ready? handle 3)) (guarded #(db/ready? handle 3))])
          (str "a started handle is ready, and still ready on a second call: the first gave back "
               "the only connection of [:pool :max] 1"))
      (with-open [_ (.getConnection ds)]
        (let [r (guarded #(db/ready? handle 3))]
          (is (= false r)
              (str "with the only connection held, the borrow times out and ready? answers false "
                   "rather than throwing: " (pr-str r)))))
      (is (= true (guarded #(db/ready? handle 3))) "ready again once the connection is back")
      (finally (db/stop handle)))
    (is (= SQLException (class (ts/thrown-any #(.getConnection ds))))
        "precondition: stop closed the pool")
    (is (= false (guarded #(db/ready? handle 3))) "after stop ready? answers false rather than throwing")))

(deftest ready?-is-false-rather-than-a-throw-when-the-server-behind-a-started-handle-stops--h2-tcp
  (let [bypass "com.zaxxer.hikari.aliveBypassWindowMs"]
    ;; Guarded rather than merely asserted: the property is set and cleared below, and
    ;; clearing one the JVM had set would change every pool built after this test.
    (when (is (nil? (System/getProperty bypass)) (str "precondition: the JVM does not set " bypass))
      (let [server (.start (Server/createTcpServer (into-array String ["-tcpPort" "0" "-ifNotExists"])))
            url    (str "jdbc:h2:tcp://127.0.0.1:" (.getPort server) "/mem:ready-" (random-uuid) ";DB_CLOSE_DELAY=-1")]
        (try
          ;; HikariCP reads the window when the pool is constructed and, through the driver,
          ;; validates any idle connection older than it before lending it. Widened for this
          ;; pool only, the dead connection is lent as it is, so the answer comes from what
          ;; ready? does with it rather than from the pool refusing to lend.
          (let [handle (try (System/setProperty bypass "600000")
                            (db/start {:jdbc-url url :user "" :password ""
                                       :pool {:max 1 :timeout-ms 1000} :migrations :none})
                            (finally (System/clearProperty bypass)))
                ds     ^HikariDataSource (:datasource handle)]
            (try
              (is (= true (guarded #(db/ready? handle 2))) "positive control: ready while the server runs")
              (.stop server)
              (is (= false (.isRunning server false)) "precondition: the server stopped")
              (let [fresh (guarded #(DriverManager/getConnection url "" ""))]
                ;; The driver's own subclass, whichever it is: the witness is that it refuses.
                (is (instance? SQLException fresh)
                    (str "witness: the database no longer accepts a fresh connection: " (pr-str fresh))))
              (let [r (guarded #(db/ready? handle 2))]
                (is (= 1 (.getTotalConnections (.getHikariPoolMXBean ds)))
                    "witness: the pool still holds its dead connection, so it lent it to ready? unvalidated")
                (is (= [false true]
                       (guarded (fn []
                                  (let [c (.getConnection ds)]
                                    [(outcome #(.isValid c 2))
                                     (instance? SQLException (ts/thrown-any #(.close c)))]))))
                    (str "witness: on that connection H2's isValid answers false and closing it throws, "
                         "so the false below is both mapped at once; which one ready? consults is the "
                         "recording double's to show"))
                (is (= false r) (str "with the server gone ready? answers false rather than throwing: " (pr-str r))))
              (finally (db/stop handle))))
          (finally (when (.isRunning server false) (.stop server))))))))

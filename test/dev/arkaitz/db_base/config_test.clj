(ns dev.arkaitz.db-base.config-test
  "SPEC §6: configuration is refused before anything opens, naming the key, with
  the JDBC URL, the user and the password reported by presence and never by value.

  Every refusal is `base` with exactly one fault, and `base` is first shown to
  start, so the fault is the only reason for the refusal. The expected strings
  are argued from §6 and the code's messages, not regenerated from output: an
  edit to one of them has to be argued the same way.

  \"Before anything opens\" is observed through HikariCP's pool counter, which
  moves exactly when a pool is constructed — also for one closed again at once,
  which is the mutant a thread snapshot cannot see.

  H2 only, and no engine is involved in the claim: every refusal here happens before a
  URL is opened, so the engine named in it is never reached. What runs on both engines is
  §7's SQL, in `migrations_test`, and the pair itself is checked in `dialect_test`."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.db-base :as db]
            [dev.arkaitz.db-base.test-support :as ts])
  (:import [com.zaxxer.hikari HikariDataSource]
           [java.io File]
           [java.sql Driver DriverManager DriverPropertyInfo SQLException]
           [java.util.concurrent TimeUnit]))

(def ^:private base
  {:jdbc-url   (str "jdbc:h2:mem:" ts/url-sentinel ";DB_CLOSE_DELAY=-1")
   :user       ts/user-sentinel
   :password   ts/password-sentinel
   :pool       {:max 1 :timeout-ms 5000}
   :migrations :none :sessions :none})

(def ^:private top-keys "[:jdbc-url :migrations :password :pool :sessions :user]")

(defn- refusal [message data] [(str "db-base: " message) data])

(def ^:private refusals
  "`[label config expected]`, one fault each."
  (let [url      (refusal ":jdbc-url must be a non-blank string" {:config-key [:jdbc-url]})
        user     (refusal ":user must be a string (\"\" is a value)" {:config-key [:user]})
        password (refusal ":password must be a string (\"\" is a value)" {:config-key [:password]})
        pool     #(refusal ":pool must be a map of :max and :timeout-ms" {:config-key [:pool] :value %})
        pool-max #(refusal "[:pool :max] must be an integer from 1 to 2147483647" {:config-key [:pool :max] :value %})
        pool-ms  #(refusal "[:pool :timeout-ms] must be an integer from 250 to 2147483646 milliseconds"
                           {:config-key [:pool :timeout-ms] :value %})
        migs     #(refusal ":migrations must be :none or a map of :dir and :lock-wait-ms"
                           {:config-key [:migrations] :value %})
        dir      #(refusal "[:migrations :dir] must be a non-blank string" {:config-key [:migrations :dir] :value %})
        lock     #(refusal "[:migrations :lock-wait-ms] must be an integer from 0 to 2147483647 milliseconds"
                           {:config-key [:migrations :lock-wait-ms] :value %})
        sess     #(refusal ":sessions must be :none or a map of :lock-wait-ms"
                           {:config-key [:sessions] :value %})
        sess-ms  #(refusal "[:sessions :lock-wait-ms] must be an integer from 0 to 2147483647 milliseconds"
                           {:config-key [:sessions :lock-wait-ms] :value %})]
    (concat
     [["not a map: 42" 42 (refusal "configuration must be a map" {:config-key []})]
      ["not a map: nil" nil (refusal "configuration must be a map" {:config-key []})]
      ["not a map: []" [] (refusal "configuration must be a map" {:config-key []})]
      [":migration typed for :migrations — the typo is named, not the absence"
       (-> base (dissoc :migrations) (assoc :migration :none))
       (refusal (str "unknown key [:migration] — it takes " top-keys) {:config-key [:migration]})]
      ["two unknown keys, reported sorted"
       (assoc base :b 1 :a 2)
       (refusal (str "unknown keys [:a :b] — it takes " top-keys) {:config-key [:a]})]
      ["unknown keys of mixed types cannot make the refusal throw"
       (assoc base "x" 1 1 2 :x 3)
       (refusal (str "unknown keys [\"x\" 1 :x] — it takes " top-keys) {:config-key ["x"]})]
      ["a nil key" (assoc base nil 1)
       (refusal (str "unknown key [nil] — it takes " top-keys) {:config-key [nil]})]]
     (for [[label cfg] [["missing" (dissoc base :jdbc-url)] ["nil" (assoc base :jdbc-url nil)]
                        ["\"\"" (assoc base :jdbc-url "")] ["blank" (assoc base :jdbc-url "   ")]
                        ["a number" (assoc base :jdbc-url 42)]
                        ["a keyword carrying the URL" (assoc base :jdbc-url (keyword ts/url-sentinel))]]]
       [(str ":jdbc-url " label) cfg url])
     (for [[label cfg] [["missing" (dissoc base :user)] ["nil" (assoc base :user nil)]
                        ["a keyword" (assoc base :user :alice)] ["a number" (assoc base :user 42)]
                        ["a symbol carrying the user" (assoc base :user (symbol ts/user-sentinel))]]]
       [(str ":user " label) cfg user])
     (for [[label cfg] [["missing" (dissoc base :password)] ["nil" (assoc base :password nil)]
                        ["a keyword" (assoc base :password :x)] ["a number" (assoc base :password 42)]
                        ["a vector carrying the password" (assoc base :password [ts/password-sentinel])]
                        ["a char array" (assoc base :password (char-array ts/password-sentinel))]]]
       [(str ":password " label) cfg password])
     [[":pool missing" (dissoc base :pool) (pool nil)]
      [":pool a number" (assoc base :pool 10) (pool 10)]
      [":pool a vector" (assoc base :pool [10 5000]) (pool [10 5000])]
      [":pool with an unknown key"
       (assoc-in base [:pool :min] 0)
       (refusal "unknown key [:min] in [:pool] — it takes [:max :timeout-ms]" {:config-key [:pool :min]})]
      [":pool with two unknown keys"
       (update base :pool assoc :min 0 :idle 1)
       (refusal "unknown keys [:idle :min] in [:pool] — it takes [:max :timeout-ms]" {:config-key [:pool :idle]})]
      [":pool with only an unknown key: unknown is named before missing"
       (assoc base :pool {:min 0})
       (refusal "unknown key [:min] in [:pool] — it takes [:max :timeout-ms]" {:config-key [:pool :min]})]]
     ;; 1.5M and 3/2 are EDN a host can write, and a pool setter would truncate them silently.
     (for [v [::missing 0 -1 1.0 1.5M 10M 3/2 "10" 2147483648 2147483648N]]
       [(str "[:pool :max] " (pr-str v))
        (if (= ::missing v) (update base :pool dissoc :max) (assoc-in base [:pool :max] v))
        (pool-max (when-not (= ::missing v) v))])
     ;; 2147483647 is HikariCP's own "no timeout", the hang §6 forbids.
     (for [v [::missing 0 249 -1 250.0 5000.5M 2147483647 "5000"]]
       [(str "[:pool :timeout-ms] " (pr-str v))
        (if (= ::missing v) (update base :pool dissoc :timeout-ms) (assoc-in base [:pool :timeout-ms] v))
        (pool-ms (when-not (= ::missing v) v))])
     [[":migrations missing" (dissoc base :migrations) (migs nil)]
      [":migrations an unknown keyword" (assoc base :migrations :nothing) (migs :nothing)]
      [":migrations a bare string" (assoc base :migrations "db/migration") (migs "db/migration")]
      [":migrations a vector" (assoc base :migrations []) (migs [])]
      [":migrations {}" (assoc base :migrations {}) (dir nil)]
      [":migrations :dir \"\"" (assoc base :migrations {:dir ""}) (dir "")]
      [":migrations :dir blank" (assoc base :migrations {:dir "  "}) (dir "  ")]
      [":migrations :dir a keyword" (assoc base :migrations {:dir :x}) (dir :x)]
      [":migrations with an unknown key"
       (assoc base :migrations {:directory "x"})
       (refusal "unknown key [:directory] in [:migrations] — it takes [:dir :lock-wait-ms]"
                {:config-key [:migrations :directory]})]
      [":migrations with both keys wrong: the prefix is named first"
       (assoc base :migrations {:dir "" :lock-wait-ms -1})
       (dir "")]]
     ;; 0 is a valid wait (one attempt), so the floor is -1; the ceiling is the pool's own.
     (for [v [::missing nil -1 1.5 1.5M 3/2 "10" 2147483648 2147483648N]]
       [(str "[:migrations :lock-wait-ms] " (pr-str v))
        (assoc base :migrations (cond-> {:dir "db/migration"} (not= ::missing v) (assoc :lock-wait-ms v)))
        (lock (when-not (= ::missing v) v))])
     ;; SPEC §8's key, asked for like every other and never defaulted. `:none` is the
     ;; opt-out, exactly as it is for :migrations, so a host says what it wants.
     [[":sessions missing" (dissoc base :sessions) (sess nil)]
      [":sessions an unknown keyword" (assoc base :sessions :nothing) (sess :nothing)]
      [":sessions a bare string" (assoc base :sessions "yes") (sess "yes")]
      [":sessions a vector" (assoc base :sessions []) (sess [])]
      [":sessions true" (assoc base :sessions true) (sess true)]
      [":sessions {}" (assoc base :sessions {}) (sess-ms nil)]
      [":sessions with :dir, the copy-paste from :migrations"
       (assoc base :sessions {:dir "x"})
       (refusal "unknown key [:dir] in [:sessions] — it takes [:lock-wait-ms]"
                {:config-key [:sessions :dir]})]
      [":sessions with an unknown key beside a wrong wait: the unknown is named first"
       (assoc base :sessions {:table "x" :lock-wait-ms -1})
       (refusal "unknown key [:table] in [:sessions] — it takes [:lock-wait-ms]"
                {:config-key [:sessions :table]})]]
     (for [v [nil -1 1.5 1.5M 3/2 "10" 2147483648 2147483648N]]
       [(str "[:sessions :lock-wait-ms] " (pr-str v))
        (assoc base :sessions {:lock-wait-ms v})
        (sess-ms v)]))))

(deftest start-refuses-each-malformed-configuration-with-an-exact-message-and-config-key
  (let [handle (db/start base)]
    (is (= [:datasource] (keys handle)) "positive control: base itself starts")
    (db/stop handle))
  ;; The floor is accepted by the pool too, not only by validation: HikariCP applies
  ;; its own floor to more than one setting, and a value validation lets through that
  ;; the pool then rejects would surface as the pool's exception instead of a key.
  (is (= ::ts/no-throw (ts/attempt #(db/stop (db/start (assoc-in base [:pool :timeout-ms] 250)))))
      "[:pool :timeout-ms] 250, the floor itself, starts a pool")
  (doseq [[label cfg expected] refusals]
    (let [e (ts/thrown #(db/start cfg))]
      (is (not= ::ts/no-throw e) (str label " — start did not refuse"))
      (when-not (= ::ts/no-throw e)
        (is (= expected [(ex-message e) (ex-data e)]) label)
        (is (nil? (ex-cause e)) (str label " — a refusal wraps nothing")))))
  (testing "values that are accepted, observed on the pure validator so no pool opens"
    (doseq [[label cfg] [[":user \"\"" (assoc base :user "")]
                         [":password \"\"" (assoc base :password "")]
                         ["[:pool :max] 1" (assoc-in base [:pool :max] 1)]
                         ["[:pool :max] Integer/MAX_VALUE" (assoc-in base [:pool :max] Integer/MAX_VALUE)]
                         ["[:pool :max] an int" (assoc-in base [:pool :max] (int 5))]
                         ["[:pool :max] a BigInt: any integer type in range, decided with the user"
                          (assoc-in base [:pool :max] 10N)]
                         ["[:pool :timeout-ms] a BigInt" (assoc-in base [:pool :timeout-ms] 5000N)]
                         ["[:pool :timeout-ms] 2147483646, the largest real deadline"
                          (assoc-in base [:pool :timeout-ms] 2147483646)]
                         ["[:pool :timeout-ms] 250, the floor itself" (assoc-in base [:pool :timeout-ms] 250)]
                         ["[:migrations :lock-wait-ms] 0, a single attempt"
                          (assoc base :migrations {:dir "db/migration" :lock-wait-ms 0})]
                         ["[:migrations :lock-wait-ms] 2147483647, the ceiling itself"
                          (assoc base :migrations {:dir "db/migration" :lock-wait-ms 2147483647})]
                         ["[:migrations :lock-wait-ms] a BigInt" (assoc base :migrations {:dir "db/migration" :lock-wait-ms 10N})]
                         ["[:migrations :lock-wait-ms] an int" (assoc base :migrations {:dir "db/migration" :lock-wait-ms (int 5)})]
                         ["[:sessions :lock-wait-ms] 0, a single attempt"
                          (assoc base :sessions {:lock-wait-ms 0})]
                         ["[:sessions :lock-wait-ms] 2147483647, the ceiling itself"
                          (assoc base :sessions {:lock-wait-ms 2147483647})]
                         ["[:sessions :lock-wait-ms] a BigInt" (assoc base :sessions {:lock-wait-ms 10N})]
                         ["[:sessions :lock-wait-ms] an int" (assoc base :sessions {:lock-wait-ms (int 5)})]]]
      (is (= ::ts/no-throw (ts/attempt #(#'db/validate! cfg))) label)))
  (testing "a URL no driver accepts is refused before the pool sees it, naming :jdbc-url"
    (let [e (ts/thrown #(db/start (assoc base :jdbc-url (str "jdbc:nope://" ts/user-sentinel ":"
                                                               ts/password-sentinel "@host/" ts/url-sentinel))))]
      (is (= ["db-base: no JDBC driver on the classpath accepts :jdbc-url" {:config-key [:jdbc-url]}]
             [(ex-message e) (ex-data e)]))
      (is (and (instance? SQLException (ex-cause e)) (= "08001" (.getSQLState ^SQLException (ex-cause e))))
          "DriverManager's refusal is the cause")
      ;; Bounded, because a cause chain can be made cyclic and a scan that follows one hangs.
      (let [chain (take 32 (take-while some? (iterate ex-cause (ex-cause e))))]
        (is (= [] (vec (mapcat #(ts/leaks-in :no-driver-cause %)
                               (concat chain (mapcat #(.getSuppressed ^Throwable %) chain)))))
            "the cause chain and its suppressed exceptions are made at db-base's request, so all of it is scanned")))))

(deftest the-jdbc-url-the-user-and-the-password-are-never-echoed-by-a-refusal
  (is (= [[:control :message :url] [:control :data :password]]
         (ts/leaks-in :control (ex-info (str "x " ts/url-sentinel) {:v ts/password-sentinel})))
      "positive control: the detector fires on the message and on the data")
  (let [cases  (conj (vec refusals)
                     ["a URL no driver accepts, with user and password inside it"
                      (assoc base :jdbc-url (str "jdbc:nope://" ts/user-sentinel ":" ts/password-sentinel
                                                 "@host/" ts/url-sentinel))])
        thrown (for [[label cfg] cases] [label (ts/thrown #(db/start cfg))])]
    (is (= [] (vec (for [[label e] thrown :when (= ::ts/no-throw e)] label)))
        "precondition: every case threw, so every case was checked for an echo")
    (is (= [] (vec (mapcat (fn [[label e]] (when-not (= ::ts/no-throw e) (ts/leaks-in label e))) thrown)))
        "SPEC §6: a secret was echoed")))

(deftest a-pool-that-refuses-the-timeout-reports-the-key--under-a-jvm-raised-floor
  ;; HikariCP reads com.zaxxer.hikari.timeoutMs.floor once, into a static final, so
  ;; only a JVM started with it raised can show the refusal. The child is bounded:
  ;; 30 s is policy over the 0.35–0.39 s measured.
  (let [java    (str (System/getProperty "java.home") "/bin/java")
        program (pr-str
                 '(do (require 'dev.arkaitz.db-base)
                      (prn [:result
                            (try ((resolve 'dev.arkaitz.db-base/start)
                                  {:jdbc-url "jdbc:h2:mem:raised-floor;DB_CLOSE_DELAY=-1" :user "" :password ""
                                   :pool {:max 1 :timeout-ms 1000} :migrations :none :sessions :none})
                                 :started
                                 (catch clojure.lang.ExceptionInfo e
                                   [(ex-message e) (ex-data e) (some-> e ex-cause class .getName)
                                    (some-> e ex-cause ex-message)])
                                 (catch Throwable t [:raw (.getName (class t)) (.getMessage t)]))])
                      (System/exit 0)))
        ;; The classpath may hold entries relative to this JVM's working directory, where
        ;; they resolve; stderr is inherited so only the result reaches the pipe.
        process (.start (doto (ProcessBuilder. ^java.util.List
                                               [java "-Dcom.zaxxer.hikari.timeoutMs.floor=2000"
                                                "-cp" (System/getProperty "java.class.path")
                                                "clojure.main" "-e" program])
                          (.directory (java.io.File. (System/getProperty "user.dir")))
                          (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)))
        done?   (.waitFor process 30 TimeUnit/SECONDS)]
    (when-not done? (.destroyForcibly process))
    (when (is done? "the child JVM did not finish within 30 s")
      (let [output (slurp (.getInputStream process))
            result (some->> (str/split-lines output) (filter #(str/starts-with? % "[:result")) first
                            edn/read-string second)]
        (when (and (is (vector? result)
                       (str "the child did not refuse — :started means the raised floor never reached the pool, "
                            "nil that it printed no result: " (pr-str result) " " output))
                   (is (not= :raw (first result))
                       (str "start let the pool's refusal escape unwrapped: " (pr-str result))))
          (let [[message data cause-class cause-message] result]
            (is (and (string? cause-message) (str/includes? cause-message "2000ms"))
                (str "precondition: the raised floor reached the pool, whose own refusal is the cause: "
                     (pr-str result)))
            (is (= ["db-base: the pool refused [:pool :timeout-ms]"
                    {:config-key [:pool :timeout-ms] :value 1000}
                    "java.lang.IllegalArgumentException"]
                   [message data cause-class])
                "SPEC §6: the pool's refusal arrives as ex-info naming the key, with HikariCP's exception kept")))))))

(deftest a-configuration-file-the-host-jvm-names-reaches-hikaricp-and-its-failure-arrives-unwrapped
  ;; SPEC §6 records this as the host's JVM rather than the library's configuration, and
  ;; HikariCP reads the property on every construction. Covered: every refusal of the map
  ;; still comes first; a file that cannot be found fails in HikariCP, unwrapped; a file
  ;; that loads configures the pool db-base hands back. Not covered, and not failing the
  ;; way the missing file does: a file that cannot be read (a RuntimeException whose own
  ;; message omits the path, which the IOException beneath it names), a malformed one (an
  ;; IllegalArgumentException from java.util.Properties), one with a property HikariCP
  ;; cannot apply (thrown from PropertyElf), and one that loads but describes a pool
  ;; HikariCP refuses when it is constructed.
  (let [property "hikaricp.configurationFile"
        previous (System/getProperty property)]
    (when (is (nil? previous) (str "precondition: this JVM does not already set " property ": " previous))
      (testing "a file that cannot be found"
        (let [missing (str "/nonexistent-" (random-uuid) "/pool.properties")]
          (try
            (System/setProperty property missing)
            ;; Every refusal, not one: a probe that never enters a refusal's branch would let
            ;; that refusal move after the pool is configured unseen.
            (doseq [[label cfg [_ data]] (conj (vec refusals)
                                               ["a URL no driver accepts" (assoc base :jdbc-url "jdbc:nope:x")
                                                [nil {:config-key [:jdbc-url]}]])]
              (let [e (ts/thrown-any #(db/stop (db/start cfg)))]
                (is (and (instance? clojure.lang.ExceptionInfo e) (= data (ex-data e)))
                    (str label " — still refused before HikariCP reads the file: " (pr-str e)))))
            (let [e (ts/thrown-any #(db/stop (db/start base)))]
              (is (and (instance? IllegalArgumentException e) (not (instance? clojure.lang.ExceptionInfo e)))
                  (str "HikariCP's own exception arrives unwrapped: " (pr-str e)))
              (when (is (and (instance? Throwable e) (seq (.getStackTrace ^Throwable e)))
                        (str "precondition: an exception with a stack trace, so its thrower can be read: "
                             (pr-str e)))
                (is (= "com.zaxxer.hikari.HikariConfig" (.getClassName (first (.getStackTrace ^Throwable e))))
                    (str "it is thrown by HikariCP itself, not rethrown by db-base: " (pr-str e))))
              (is (and (instance? Throwable e) (str/includes? (str (ex-message e)) missing))
                  (str "it names the file HikariCP looked for, so the property is what reached the pool: "
                       (pr-str e))))
            (finally (System/clearProperty property)))))
      (testing "a file that loads"
        (let [file      (File/createTempFile "db-base-pool" ".properties")
              pool-name (str "db-base-cf-" (random-uuid))]
          (try
            (spit file (str "poolName=" pool-name "\n"))
            (System/setProperty property (.getPath file))
            (let [handle (db/start base)]
              (try
                (is (= pool-name (.getPoolName ^HikariDataSource (:datasource handle)))
                    "a host that sets the property gets the pool its JVM asked for, for what db-base does not set")
                (finally (db/stop handle))))
            (finally
              (System/clearProperty property)
              (.delete file))))))))

(deftest a-refusal-happens-before-any-pool-is-constructed
  (let [before (ts/pool-number)]
    (db/stop (db/start base))
    (is (= (inc (or before 0)) (ts/pool-number))
        "positive control: the pool counter is alive in this JVM and moves by one per pool"))
  (doseq [[label cfg] (conj (vec refusals)
                            ["a URL no driver accepts" (assoc base :jdbc-url "jdbc:nope:x")])]
    (let [before  (ts/pool-number)
          outcome (try (db/start cfg) (catch clojure.lang.ExceptionInfo e e))]
      (when (map? outcome) (db/stop outcome))
      ;; A case that did not refuse must not read as a pool built before a refusal.
      (when (is (instance? clojure.lang.ExceptionInfo outcome) (str label " — start did not refuse"))
        (is (= before (ts/pool-number))
            (str "a pool was constructed before the refusal of " label))))))

(deftest a-driver-only-a-clojure-loader-can-see-is-refused-before-any-pool-is-constructed
  ;; `DriverManager` answers by the CALLER's loader, so a driver added to a running JVM —
  ;; `add-lib` at a REPL — is found by this library and not by HikariCP, whose own class
  ;; came from the application loader. Measured 2026-09-20: the pool's refusal for that is
  ;; a raw `RuntimeException` carrying the JDBC URL, from outside `start`'s try. A `reify`
  ;; is compiled into a `DynamicClassLoader` exactly like that REPL-added driver, which is
  ;; what makes the case reproducible here with no REPL and no jar.
  ;;
  ;; Not covered, and stated rather than pretended: swapping HikariCP's loader for
  ;; `ClassLoader/getSystemClassLoader` in the check is invisible, because they are the
  ;; same loader in this JVM. The positive control — that an ordinary driver is not
  ;; refused — is every boot in this suite, starting with the one at the top of
  ;; `a-refusal-happens-before-any-pool-is-constructed`.
  (let [url     (str "jdbc:testonly:" ts/url-sentinel)
        reified (reify Driver
                  (acceptsURL [_ u] (= u url))
                  ;; nil makes HikariCP refuse cleanly if it ever gets this far, so a
                  ;; regression shows up as its own failure rather than as an Error.
                  (connect [_ _ _] nil)
                  (getMajorVersion [_] 0)
                  (getMinorVersion [_] 0)
                  (jdbcCompliant [_] false)
                  (getParentLogger [_] nil)
                  (getPropertyInfo [_ _ _] (make-array DriverPropertyInfo 0)))]
    (DriverManager/registerDriver reified)
    (try
      (testing "preconditions: this library's loader finds it and HikariCP's cannot"
        (is (identical? reified (DriverManager/getDriver url))
            "a caller compiled by Clojure gets back the very driver registered here")
        (is (nil? (try (Class/forName (.getName (class reified)) false
                                      (.getClassLoader HikariDataSource))
                       (catch ClassNotFoundException _ nil)))
            "and HikariCP's own loader cannot resolve that class at all, which is the whole case"))
      (let [before (ts/pool-number)
            e      (ts/thrown #(db/start {:jdbc-url url :user ts/user-sentinel
                                          :password ts/password-sentinel
                                          :pool {:max 1 :timeout-ms 1000} :migrations :none :sessions :none}))
            after  (ts/pool-number)]
        (is (= ["db-base: the JDBC driver that accepts :jdbc-url is not one the pool can use"
                {:config-key [:jdbc-url] :driver (.getName (class reified))}]
               [(ex-message e) (ex-data e)])
            "the refusal is this library's, and it names the driver rather than the URL")
        (is (= before after)
            (str "and nothing was opened: HikariCP names a pool inside its own constructor,"
                 " before it ever reaches the driver, so the counter moving means the check"
                 " ran too late"))
        (is (= [] (ts/leaks-in :driver-not-visible e)) "SPEC §6: a secret was echoed"))
      ;; Deregistration is loader-filtered too, so it has to happen from test code, and it
      ;; has to happen: every later DriverManager lookup in this JVM would ask this driver.
      (finally (DriverManager/deregisterDriver reified)))))

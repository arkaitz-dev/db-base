(ns demo.seam-test
  "The demo is the acceptance test of SPEC §12: if this host needs anything db-base does
  not provide, the seam is in the wrong place. Five claims, each about the join rather
  than about either library — the library's own suite already owns `start`, `stop`,
  `ready?` and §7's failure class, and repeating them here would prove nothing new. Where
  an assertion below does touch library ground it says why it earns its place.

  Everything is built through the host's own `config.edn`, read here rather than
  hand-written, so that the values these claims depend on are the ones the host ships.
  Not every value in that resource is observable from a mock request — `:secure?` is
  web-base's cookie attribute and no request map can see it — so the file is in the loop,
  not under test. The one value overridden is the database: the resource names `demo.db`
  in the working directory, which is the developer's, and a test that wrote there would
  decide what a parallel run sees.

  What only a browser can see stays the manual smoke recorded in CLAUDE.md."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [demo.system]
            [dev.arkaitz.web-base.config :as config]
            [dev.arkaitz.web-base.integrant :as wbi]
            [dev.arkaitz.web-base.testing :as wt]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [ring.mock.request :as mock])
  (:import [clojure.lang ExceptionInfo]))

(def ^:private session-key
  "A key of this test's own. The suite must not need `env.local.edn` — that is the file
  which never reaches the repository — and `config/env-readers` still lets the
  environment win, so an operator who exported the variable is not overruled here."
  "AAECAwQFBgcICQoLDA0ODw==")

(defn- config
  "The host's system, over `path` instead of the `demo.db` its resource names."
  [path]
  (assoc-in (wbi/read-string (config/env-readers {"WB_SESSION_KEY" session-key} "demo.seam-test")
                             (slurp (io/resource "config.edn")))
            [:dev.arkaitz.db-base/database :jdbc-url] (str "jdbc:sqlite:" path)))

(defn- temp-db-path
  "An absolute path nothing else holds. Deleted at once, because SQLite creates the file
  and an empty one is a database with no schema either way — which is what the first
  boot's migration is for."
  []
  (let [file (java.io.File/createTempFile "db-base-seam-" ".db")]
    (.delete file)
    (.deleteOnExit file)
    (.getAbsolutePath file)))

(defn- delete-db!
  "The file and the three siblings SQLite may leave beside it, each named in full: a
  cleanup written as a pattern is a cleanup that can remove something it was not shown."
  [path]
  (doseq [suffix ["" "-journal" "-wal" "-shm"]]
    (.delete (io/file (str path suffix)))))

(defn- datasource
  "A datasource of this test's own, opened on the file directly. Every reading below
  goes through it and never through the handle under test, whose own report of what it
  did is the thing being checked."
  [path]
  (jdbc/get-datasource {:jdbcUrl (str "jdbc:sqlite:" path)}))

(defn- rows [path sql]
  (vec (rest (jdbc/execute! (datasource path) [sql] {:builder-fn rs/as-arrays}))))

(defn- one [path sql] (ffirst (rows path sql)))

(defn- boot
  "Integrant, from `config.edn` down to web-base's handler: the database, the host's
  `:demo/web-config` and the handler. No port — four of the five claims are about what
  the seam serves, and the fifth asks for the whole map on purpose."
  [path]
  (ig/init (config path) [:dev.arkaitz.web-base/handler]))

(defn- app [system] (get system :dev.arkaitz.web-base/handler))

(defn- page [app] (:body (app (mock/request :get "/"))))

(defn- health [app]
  (let [response (app (mock/request :get "/health"))]
    [(:status response) (:body response)]))

(defn- post-note!
  "A note added the way a browser adds one: the page first, for the CSRF token and the
  session cookie that holds it, then the POST carrying both. Without them web-base
  answers 403 and the note is never written — which would make every assertion that
  follows one about an empty table, so both callers assert the status they got."
  [app body]
  (let [form (app (mock/request :get "/"))]
    (app (-> (mock/request :post "/notes" {"body" body "__anti-forgery-token" (wt/csrf-token form)})
             (wt/with-cookies form)))))

(defn- redirected-home [response]
  [(:status response) (get-in response [:headers "Location"])])

(defn- threads
  "The names of the live threads matching `pattern`. Jetty names the thread holding a
  listening socket `qtp…-N-acceptor-0@…{0.0.0.0:<port>}`, and HikariCP names its own
  `HikariPool-N:…`: both are how a server and a pool are observed here without reaching
  into either library's classes."
  [pattern]
  (into #{}
        (comp (map #(.getName ^Thread %)) (filter #(re-find pattern %)))
        (keys (Thread/getAllStackTraces))))

(def ^:private acceptor-threads #"-acceptor-")
(def ^:private pool-threads #"^HikariPool-")

(defn- eventually
  "`(f)` once `done?` accepts it, or its last value when `deadline-ms` runs out. The
  deadline is a hang guard and never the pass criterion: the assertion that follows is
  about the value, and a wait that expires hands the wrong value over rather than hiding
  it. Two things here are asynchronous to the call that causes them — Jetty renames its
  acceptor from inside the job the connector queued, on a pool thread, so the name lands
  after `ig/init` has returned, and HikariCP's close returns before its housekeeper has
  finished dying."
  [f done? deadline-ms]
  (let [until (+ (System/currentTimeMillis) deadline-ms)]
    (loop []
      (let [value (f)]
        (if (or (done? value) (>= (System/currentTimeMillis) until))
          value
          (do (Thread/sleep 10) (recur)))))))

(deftest the-first-boot-applies-the-migration-and-the-second-applies-none
  (let [path (temp-db-path)]
    (try
      (let [system (boot path)]
        (is (= 1 (:migrations-applied (get system :dev.arkaitz.db-base/database)))
            "the first boot on an empty file applies 001-notes, and says so")
        (is (str/includes? (page (app system)) "· 1 migration(s) applied")
            (str "and the page says the same, which is the seam's visible half: the count above"
                 " is a key of the handle, and a host that rendered it from anywhere else would"
                 " part company with it here. Read with the separator in front, so the number"
                 " is this one and not the tail of a longer one"))
        (ig/halt! system))
      (is (= [["db_base_migration_lock"] ["note"] ["ragtime_migrations"]]
             (rows path "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name"))
          (str "and the migration did its work, not merely its bookkeeping: `note` is there,"
               " which is the host's own classpath prefix resolving through the library"))
      (is (= [["001-notes"]] (rows path "SELECT id FROM ragtime_migrations ORDER BY id"))
          (str "recorded under its own id. The count above is db-base's own report of itself;"
               " this is the file, and a boot that applied a migration without recording it"
               " would report 1 just the same and run it again on every later boot"))
      (is (= [[0]] (rows path "SELECT COUNT(*) FROM db_base_migration_lock"))
          (str "with the lock row given back. Library ground, kept here on purpose: a row this"
               " host's own boot left behind is what makes its NEXT boot wait out"
               " `:lock-wait-ms` and then fail, and nothing else in this file would see it"))
      (let [system (boot path)]
        (is (= 0 (:migrations-applied (get system :dev.arkaitz.db-base/database)))
            (str "the second boot on the same file applies none. Zero is the assertion and not"
                 " a floor: a boot that re-applied 001-notes would meet `CREATE TABLE note` on"
                 " a table that already exists, and §7 turns that into a failed boot. That the"
                 " decision is made from the plan rather than from the mere presence of history"
                 " is the library's, and `migrations_test` measures it against sources this"
                 " host does not have"))
        (is (str/includes? (page (app system)) "· 0 migration(s) applied")
            (str "and this boot's page says 0. Pinned at both values on purpose: either alone"
                 " leaves a host free to render that very constant and stay green, and one of"
                 " the two is exactly the constant such a host would pick"))
        (ig/halt! system))
      (is (= [["001-notes"]] (rows path "SELECT id FROM ragtime_migrations ORDER BY id"))
          "and the control table still holds exactly one row: the second boot recorded nothing")
      (finally (delete-db! path)))))

(deftest a-note-written-through-the-application-outlives-the-system
  (let [path (temp-db-path)
        kept (str "kept-" (random-uuid))]
    (try
      (let [first-system (boot path)
            first-app    (app first-system)]
        (is (= [303 "/"] (redirected-home (post-note! first-app kept)))
            "the form's POST is accepted and redirects: the note was written, not refused")
        (ig/halt! first-system)
        (is (= [503 "the database is not answering"] (health first-app))
            (str "witness, before anything else boots: the first system really is down. Without"
                 " it, a green below could mean the page was served by a pool that never closed,"
                 " which is not a restart and proves nothing about the file")))
      (let [second-system (boot path)]
        (try
          (is (str/includes? (page (app second-system)) kept)
              "a second system, on the same file, serves the note the first one wrote")
          (finally (ig/halt! second-system))))
      (is (= [[kept]] (rows path "SELECT body FROM note"))
          "and the file holds exactly that row, read through a connection of this test's own")
      (finally (delete-db! path)))))

(deftest health-answers-from-the-database-and-not-from-a-constant
  (let [path (temp-db-path)]
    (try
      (let [system  (boot path)
            handler (app system)]
        (is (= [200 "ok"] (health handler)) "while the pool is open the route says so")
        (ig/halt! system)
        (is (= [503 "the database is not answering"] (health handler))
            (str "and not once `ig/halt!` has closed it — through the same handler, so the only"
                 " thing that changed is the database. The exact pair matters twice over: a"
                 " `halt-key!` that never ran leaves a 200, and a `ready?` that threw on a"
                 " closed pool would be a 500, which `not= 200` would have accepted")))
      (finally (delete-db! path)))))

(deftest a-database-that-cannot-be-reached-stops-the-boot-and-leaves-nothing-listening
  ;; The only test here that opens a port. "Nothing is listening" is a claim about the
  ;; whole system, and web-base's server is not in the graph until it is asked for.
  (let [path (temp-db-path)]
    (try
      (testing "control: a boot that succeeds does leave something listening"
        (let [control (try (ig/init (assoc-in (config path) [:dev.arkaitz.web-base/server :port] 0))
                           (catch Throwable t t))]
          (is (map? control)
              (str "the premise of this test, said in its own words: the control boot has to"
                   " succeed. An exception escaping here would abort the whole test and read as"
                   " the claim below failing, when the claim was never evaluated"))
          (when (map? control)
            (try
              (let [port (get-in control [:dev.arkaitz.web-base/server :port])]
                (is (pos? port)
                    (str "the server bound an ephemeral port and reported it. The resource names"
                         " 3000, and a developer's own demo sitting on it must not decide this"))
                (is (seq (eventually #(threads (re-pattern (str "-acceptor-.*:" port "\\}")))
                                     seq 5000))
                    (str "and an acceptor thread holds that port's socket. This is what keeps"
                         " the assertion further down from being vacuous — an observation that"
                         " could never see a server reports none whether or not one is there."
                         " The name carries the port, so another Jetty in this JVM answers for"
                         " itself and not for this one. How MANY is deliberately not pinned:"
                         " Jetty derives that count from `availableProcessors`, so a number"
                         " measured on this machine would be a red on a larger one naming no"
                         " defect")))
              (finally (ig/halt! control))))))
      (let [listening   (threads acceptor-threads)
            pooled      (threads pool-threads)
            unreachable (-> (config path)
                            (assoc-in [:dev.arkaitz.db-base/database :jdbc-url]
                                      "jdbc:sqlite:/no-such-directory-7f3a/demo.db")
                            ;; SQLite has no accounts and ignores it; it is here to be looked
                            ;; for, because a claim about a password nobody can tell apart from
                            ;; the empty string the resource carries is not a claim.
                            (assoc-in [:dev.arkaitz.db-base/database :password] "pw-sentinel-7f3a")
                            ;; Policy, not an invariant and not invented: HikariCP's own
                            ;; floor for this value is 250 ms, and the refusal underneath is
                            ;; SQLITE_CANTOPEN, which is immediate and does not move under
                            ;; load. It shortens a failing boot from the resource's 5000 ms
                            ;; and bounds nothing the assertions below depend on.
                            (assoc-in [:dev.arkaitz.db-base/database :pool :timeout-ms] 300)
                            (assoc-in [:dev.arkaitz.web-base/server :port] 0))
            outcome     (try (ig/halt! (ig/init unreachable)) ::it-booted
                             (catch ExceptionInfo e e))]
        (is (instance? ExceptionInfo outcome)
            "a database that cannot be reached stops the boot rather than the first request")
        (when (instance? ExceptionInfo outcome)
          (try
            (let [data (ex-data outcome)]
              (is (= [:integrant.core/build-threw-exception :dev.arkaitz.db-base/database []]
                     [(:reason data) (:key data) (vec (keys (:system data)))])
                  (str "integrant names the key that threw and hands back everything it had"
                       " already built — nothing, because `:demo/web-config` reaches the"
                       " database with #ig/ref and the server depends on that in turn. A host"
                       " that wired the server without that reference would find it in this"
                       " list, listening in front of a schema that is not there"))
              (is (= [true true]
                     [(str/includes? (pr-str data) "no-such-directory-7f3a")
                      (str/includes? (pr-str data) "pw-sentinel-7f3a")])
                  (str "and integrant's own wrapper carries the JDBC URL AND the password, in"
                       " `:value`, because that is the configuration it was handed. Both are"
                       " looked for: a check that found only the URL would leave the half that"
                       " matters unsaid. Pinned rather than left to be discovered, because it"
                       " is the host's to handle and what an operator may be shown is the cause"
                       " below, never this. A release of integrant that began redacting"
                       " `:value` reds here, and that red is the notice to stop worrying")))
            (let [cause (ex-cause outcome)
                  chain (take-while some? (iterate ex-cause cause))]
              (is (= [true {:config-key [:pool :timeout-ms]}]
                     [(str/includes? (str (ex-message cause)) "no connection to the database")
                      (ex-data cause)])
                  (str "the cause is db-base's own refusal, naming the key an operator can act"
                       " on. How long it waited is not pinned: that number is this test's"
                       " override, so a red over it would name no defect"))
              (is (some #(str/includes? (str (ex-message %)) "HikariPool-") chain)
                  (str "the boot did get as far as constructing a pool — the pool itself says"
                       " so, in the link of the chain it contributed. Two later assertions rest"
                       " on this: that the chain walked here is not empty, so the leak check"
                       " below has something to inspect, and that there was a pool for the one"
                       " at the end of this test to find closed"))
              (is (not-any? #(str/includes? (pr-str [(ex-message %) (ex-data %)])
                                            "no-such-directory-7f3a")
                            chain)
                  (str "and no link of that chain echoes the JDBC URL — every link, because what"
                       " reaches a log is the whole `Caused by:` run and not one frame of it")))
            (is (= #{} (set/difference (threads acceptor-threads) listening))
                (str "nothing ever started listening. Race-free rather than merely quick: the"
                     " assertion above shows integrant never called the server's init-key, so"
                     " there is no queued job whose rename could still be on its way"))
            (is (= #{} (set/difference (threads pool-threads) pooled))
                (str "and no pool outlived the failed boot: `start` closes what it opened"
                     " before it leaves, and the control above says there was one to close."
                     " Read with no wait at all, because none is needed — measured, the threads"
                     " are already gone when it returns — so a close moved off the failing path,"
                     " onto a thread of its own or behind a delay, reds here"))
            (finally (some-> (:system (ex-data outcome)) ig/halt!)))))
      (finally (delete-db! path)))))

(deftest the-page-is-rendered-from-the-database-and-not-from-the-application-s-memory
  (let [path            (temp-db-path)
        through-the-app (str "app-" (random-uuid))
        behind-its-back (str "sql-" (random-uuid))]
    (try
      (let [system (boot path)]
        (try
          (is (= [303 "/"] (redirected-home (post-note! (app system) through-the-app)))
              "the application's own write was accepted, and not refused for a missing token")
          ;; Written with this test's own connection, which the application never hears
          ;; about: a page rendered from an atom, from a cache, or from whatever `create`
          ;; just returned cannot show this row, however green the rest would be.
          (jdbc/execute-one! (datasource path)
                             ["INSERT INTO note (body, written_at) VALUES (?, ?)" behind-its-back 1])
          (let [rendered (page (app system))]
            (is (str/includes? rendered behind-its-back)
                "the page shows a row the application never wrote: it is reading the table")
            (is (str/includes? rendered through-the-app)
                "and the row it did write, through the pool db-base handed over")
            (is (str/includes? rendered (str ">" (one path "SELECT COUNT(*) FROM note") " kept · "))
                (str "and counts what the table holds — the number read here through this test's"
                     " own connection. Anchored to the tag and the separator, so `2 kept` cannot"
                     " be matched inside `12 kept`; taking it from `notes/list-notes` instead"
                     " would compare the page with itself and pass over a query that drops rows"))
            (is (< (.indexOf ^String rendered ^String behind-its-back)
                   (.indexOf ^String rendered ^String through-the-app))
                (str "newest first, which is what the page's `ORDER BY id DESC` promises. The row"
                     " inserted above carries `written_at 1`, the older of the two, so an order"
                     " taken from that column reds here as well")))
          (finally (ig/halt! system))))
      (finally (delete-db! path)))))

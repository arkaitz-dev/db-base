(ns demo-tasks.support
  "What every suite here needs: a database of its own, and a way to read it that
  does not go through the code under test.

  **The second connection is the point.** A test that verifies a write by
  calling the function that performed it is asking the same code twice and
  believing it the second time. Everything below reads through db-base's
  `testing/rows`, over a connection of the test's own opened from the same
  configuration the host boots with, so what it observes is what another process
  would see."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dev.arkaitz.db-base :as db]
            [dev.arkaitz.db-base.testing :as dbt]
            [dev.arkaitz.web-base.integrant :as wbi]
            [dev.arkaitz.web-base.testing :as wt]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [ring.mock.request :as mock]))

(defn temp-db-path []
  (let [file (java.io.File/createTempFile "demo-tasks-" ".db")]
    (.delete file)
    (.deleteOnExit file)
    (.getAbsolutePath file)))

(defn delete-db!
  "The file and the three siblings SQLite may leave beside it, each named in
  full: a cleanup written as a pattern can remove something it was not shown."
  [path]
  (doseq [suffix ["" "-journal" "-wal" "-shm"]]
    (.delete (io/file (str path suffix)))))

(def ^:private pragmas
  "Journal mode and a busy timeout, in the JDBC URL because that is the host's
  and db-base's configuration surface is closed to driver knobs on purpose.

  **WAL is load-bearing for the interleaving test, not a performance choice.**
  In SQLite's default rollback journal a reader blocks a writer, so a test that
  suspends one caller between its statements and runs another to completion
  deadlocks instead of interleaving — and then the hang guard fires and the
  deadline has become the oracle, which is the one thing such a test may never
  do. The busy timeout bounds the wait for the ordinary contention underneath."
  "?journal_mode=WAL&busy_timeout=5000")

(defn config
  "What this host hands `db-base/start`, over `path` rather than the file its
  own resource names."
  [path]
  {:jdbc-url   (str "jdbc:sqlite:" path pragmas)
   :user       ""
   :password   ""
   :pool       {:max 4 :timeout-ms 5000}
   :migrations {:dir "demo-tasks/migration" :lock-wait-ms 5000}
   :sessions   {:lock-wait-ms 5000}})

(defn datasource
  "For the writes a test plants itself, which db-base's reader does not do."
  [path]
  (jdbc/get-datasource {:jdbcUrl (str "jdbc:sqlite:" path pragmas)}))

(defn rows
  "Every row of `sql`, as vectors, through a connection of the test's own."
  [path sql & params]
  (apply dbt/rows (config path) sql params))

(defn one [path sql & params] (apply dbt/one (config path) sql params))

(defn with-db*
  "Boots the host's database over a temporary file, hands the handle and the
  path to `f`, and takes both down afterwards however it ends."
  [f]
  (let [path (temp-db-path)]
    (try
      (let [handle (db/start (config path))]
        (try (f handle path)
             (finally (db/stop handle))))
      (finally (delete-db! path)))))

(defmacro with-db
  "`(with-db [db path] …)` — the boot, the teardown and the temporary file."
  [[db path] & body]
  `(with-db* (fn [~db ~path] ~@body)))

;; --- the host, and a browser to drive it ----------------------------------

(def session-lifetime-ms
  "Overridden in every boot with a number that appears nowhere else, so a host
  that ignored `:session-lifetime-ms` and wired a literal could not agree with
  a test by coincidence."
  777000)

(defn host-config
  "The host's own `config.edn`, over `path` instead of the file it names."
  [path]
  (-> (wbi/read-string (slurp (io/resource "config.edn")))
      (assoc-in [:dev.arkaitz.db-base/database :jdbc-url]
                (str "jdbc:sqlite:" path "?journal_mode=WAL&busy_timeout=5000"))
      (assoc-in [:demo-tasks/web-config :session-lifetime-ms] session-lifetime-ms)))

(defn browser
  "One person's browser, held in an atom so a test reads as a sequence of clicks.
  The browser itself is web-base's `testing/browser` — the jar that survives a page
  setting no cookie, the token of the last page that had one, redirects followed —
  created at the first request, because it needs the handler."
  []
  (atom nil))

(defn- visit! [app jar method path params]
  (:response (reset! jar (wt/visit (or @jar (wt/browser app)) method path params))))

(defn GET [app jar path] (visit! app jar :get path nil))

(defn POST
  "A form submission carrying the CSRF token of the last page that had one, as a
  real form would."
  [app jar path params]
  (visit! app jar :post path params))

(defn landed
  "`[status path]` of where `jar` is after GETting `path` — the status of the page
  it ended on and the address bar. The pair and never the status alone: redirects
  are followed, so a signed-out visit to `/` ends on the login page with a 200 as
  well, and only the path tells the two apart."
  [app jar path]
  (let [response (GET app jar path)]
    [(:status response) (:path @jar)]))

(defn- carrying-cookies
  "`request` with the cookies `jar`'s browser holds."
  [request jar]
  (cond-> request
    (seq (:jar @jar))
    (mock/header "cookie" (str/join "; " (for [[k v] (:jar @jar)] (str k "=" v))))))

(defn GET-unfollowed
  "One request with this browser's cookies, its redirect NOT followed — for the one
  test that observes the redemption hop itself, which the browser would otherwise
  merge with the page it lands on."
  [app jar path]
  (let [response (app (carrying-cookies (mock/request :get path) jar))]
    ;; The cookies it set go into the jar as the browser's own would, a deletion
    ;; forgotten, so the next ordinary visit carries the session this one minted.
    (swap! jar #(assoc (or % (wt/browser app))
                       :jar (into {} (remove (comp nil? val)) (merge (:jar %) (wt/cookies response)))))
    response))

(defn session-key-of
  "The session cookie this browser holds."
  [jar]
  (get (:jar @jar) "ring-session"))

(defn challenge-token
  "The token of the challenge just issued, read from the table through the
  test's own connection rather than scraped from the console."
  [path]
  (one path "SELECT token FROM login_challenge ORDER BY expires_at DESC, token"))

(defn sign-in!
  "The whole ceremony as a browser walks it: ask for a link, take the token the
  host stored, follow it, and land on the page that follows."
  [app path jar identifier]
  (GET app jar "/login")
  (POST app jar "/login" {"identifier" identifier})
  (let [token (challenge-token path)]
    (GET app jar (str "/login/redeem/" token))
    (GET app jar "/")
    token))

(defn first-hop
  "One request with this browser's cookies and, on a POST, the CSRF token of the last
  page it saw — its redirect NOT followed. A followed redirect hides who answered: a
  handler that sends to a gated page lands on the login exactly as the gate would."
  [app jar method path]
  (app (cond-> (carrying-cookies (mock/request method path) jar)
         (= :post method) (mock/header "X-CSRF-Token" (:token @jar)))))

(defn with-system*
  "The whole system up over a temporary database, handed over whole — for a test
  that reads what the handler was built from — and down however it ends."
  [f]
  (let [path (temp-db-path)]
    (try
      (let [system (ig/init (host-config path) [:dev.arkaitz.web-base/handler])]
        (try (f system path)
             (finally (ig/halt! system))))
      (finally (delete-db! path)))))

(defmacro with-system
  "`(with-system [system path] …)`"
  [[system path] & body]
  `(with-system* (fn [~system ~path] ~@body)))

(defn with-host*
  "The whole system up over a temporary database, and down however it ends."
  [f]
  (with-system* (fn [system path] (f (get system :dev.arkaitz.web-base/handler) path))))

(defmacro with-host
  "`(with-host [app path] …)`"
  [[app path] & body]
  `(with-host* (fn [~app ~path] ~@body)))

(ns demo-events.support
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
            [dev.arkaitz.auth-base.jdbc :as aj]
            [dev.arkaitz.db-base :as db]
            [dev.arkaitz.db-base.testing :as dbt]
            [dev.arkaitz.web-base.integrant :as wbi]
            [dev.arkaitz.web-base.testing :as wt]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]))

(defn temp-db-path []
  (let [file (java.io.File/createTempFile "demo-events-" ".db")]
    (.delete file)
    (.deleteOnExit file)
    (.getAbsolutePath file)))

(defn delete-db!
  "The file and the three siblings SQLite may leave beside it, each named in
  full: a cleanup written as a pattern can remove something it was not shown."
  [path]
  (doseq [suffix ["" "-journal" "-wal" "-shm"]]
    (.delete (io/file (str path suffix)))))

(def ^:private host-url
  "The JDBC URL the host's own `config.edn` names. Tests keep its query string and
  change only the file, so a pragma deleted from the resource — `foreign_keys=true`
  above all — is deleted from every test too, instead of surviving in a copy here."
  (get-in (wbi/read-string (slurp (io/resource "config.edn"))) [:dev.arkaitz.db-base/database :jdbc-url]))

(def ^:private pragmas (subs host-url (str/index-of host-url "?")))

(defn url-without-foreign-keys
  "The host's URL over `path` with `foreign_keys=true` taken out — for the one control
  that shows the cascade is that pragma's doing, and for planting a row the schema
  would refuse."
  [path]
  (str "jdbc:sqlite:" path (str/replace pragmas "&foreign_keys=true" "")))

(defn config
  "What this host hands `db-base/start`, over `path` rather than the file its
  own resource names."
  [path]
  {:jdbc-url   (str "jdbc:sqlite:" path pragmas)
   :user       ""
   :password   ""
   :pool       {:max 4 :timeout-ms 5000}
   :migrations {:dir "demo-events/migration" :lock-wait-ms 5000}
   :sessions   {:lock-wait-ms 5000}})

(defn datasource
  "For the writes a test plants itself, which db-base's reader does not do. With
  `{:foreign-keys? false}`, a connection that lets a test plant what the schema
  would refuse."
  ([path] (datasource path {:foreign-keys? true}))
  ([path {:keys [foreign-keys?]}]
   (jdbc/get-datasource {:jdbcUrl (if foreign-keys?
                                    (str "jdbc:sqlite:" path pragmas)
                                    (url-without-foreign-keys path))})))

(defn rows
  "Every row of `sql`, as vectors, through a connection of the test's own."
  [path sql & params]
  (apply dbt/rows (config path) sql params))

(defn one [path sql & params] (apply dbt/one (config path) sql params))

(defn sessions
  "Every row of db-base's session table, read by its owner's reader."
  [path]
  (dbt/sessions (config path)))

(defn session
  "The session table's row for `id`, or nil."
  [path id]
  (dbt/session (config path) id))

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
      (assoc-in [:dev.arkaitz.db-base/database :jdbc-url] (str "jdbc:sqlite:" path pragmas))
      (assoc-in [:demo-events/web-config :session-lifetime-ms] session-lifetime-ms)))

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
  "`[status path]` of where `jar` is after GETting `path`: the status of the page it
  ended on and the address bar. The pair and never the status alone — redirects are
  followed, so a signed-out visit ends on the login page with a 200 as well."
  [app jar path]
  (let [response (GET app jar path)]
    [(:status response) (:path @jar)]))

(defn posted
  "`[status path]` after POSTing `params` to `path`, as `landed` reads a GET."
  [app jar path params]
  (let [response (POST app jar path params)]
    [(:status response) (:path @jar)]))

(defn session-key-of
  "The session cookie this browser holds."
  [jar]
  (get (:jar @jar) "ring-session"))

(defn challenge-token
  "The token of the challenge last issued for `identifier`, read through auth-base's
  own reader of its table rather than scraped from the console."
  [path identifier]
  (aj/latest-challenge-token (datasource path) identifier))

(defn sign-in!
  "The whole ceremony as a browser walks it: ask for a link, take the token the
  host stored, follow it, and land on the page that follows."
  [app path jar identifier]
  (GET app jar "/login")
  (POST app jar "/login" {"identifier" identifier})
  (let [token (challenge-token path identifier)]
    (GET app jar (str "/login/redeem/" token))
    (GET app jar "/")
    token))

(defn hop
  "One request with this browser's cookies — and, on a POST, the CSRF token of the
  last page it saw — its redirect NOT followed, as `testing/visit` sends it with
  `{:follow? false}`: a followed redirect hides who answered. The jar still takes what
  the response set."
  [app jar method path]
  (:response (reset! jar (wt/visit (or @jar (wt/browser app)) method path nil {:follow? false}))))

(defn with-system*
  "The whole system up over a temporary database, handed over whole — for a test
  that reads what the handler was built from — and down however it ends."
  [f]
  (let [path (temp-db-path)]
    (try
      (let [system (wbi/init (host-config path) [:dev.arkaitz.web-base/handler])]
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

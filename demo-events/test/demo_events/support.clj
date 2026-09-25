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
            [dev.arkaitz.db-base :as db]
            [dev.arkaitz.db-base.testing :as dbt]
            [dev.arkaitz.web-base.integrant :as wbi]
            [dev.arkaitz.web-base.testing :as wt]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [ring.mock.request :as mock]))

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
  "A cookie jar and the last page, which together are what a browser is.

  It **accumulates**: `wt/with-cookies` merges one response's cookies into a
  request that already carries some, and every request here is built fresh, so
  chaining from the previous response alone would send only whatever that one
  response happened to set — and the session cookie, set once at login, would be
  dropped by the next page that set none. Measured the hard way: every test past
  the login looked gated."
  []
  (atom {:cookies {} :last nil}))

(defn- keep-cookies
  "The jar after a response: a cookie set again replaces the old value, and one
  the response deletes — which `wt/cookies` reports as nil — is forgotten."
  [cookies set-by-response]
  (reduce (fn [acc [name value]] (if (nil? value) (dissoc acc name) (assoc acc name value)))
          cookies set-by-response))

(defn- send! [app jar request]
  (let [{:keys [cookies]} @jar
        request  (cond-> request
                   (seq cookies) (mock/header "cookie"
                                              (str/join "; " (for [[k v] cookies] (str k "=" v)))))
        response (app request)]
    (swap! jar (fn [j] {:cookies (keep-cookies (:cookies j) (wt/cookies response))
                        :last    response}))
    response))

(defn GET [app jar path] (send! app jar (mock/request :get path)))

(defn POST
  "A form submission carrying the CSRF token of whatever page this browser is
  looking at — what a real form does, and what makes a missing token a failure
  of the host rather than of the test."
  [app jar path params]
  (send! app jar (mock/request :post path
                               (assoc params "__anti-forgery-token"
                                      (wt/csrf-token (:last @jar))))))

(defn location [response] (get-in response [:headers "Location"]))

(defn session-key-of
  "The session cookie this browser holds — from the jar and not from the last
  response, because the last response usually sets no cookie at all."
  [jar]
  (get (:cookies @jar) "ring-session"))

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

(defn with-host*
  "The whole system up over a temporary database, and down however it ends."
  [f]
  (let [path (temp-db-path)]
    (try
      (let [system (ig/init (host-config path) [:dev.arkaitz.web-base/handler])]
        (try (f (get system :dev.arkaitz.web-base/handler) path)
             (finally (ig/halt! system))))
      (finally (delete-db! path)))))

(defmacro with-host
  "`(with-host [app path] …)`"
  [[app path] & body]
  `(with-host* (fn [~app ~path] ~@body)))

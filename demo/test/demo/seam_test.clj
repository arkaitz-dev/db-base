(ns demo.seam-test
  "The demo is the acceptance test of SPEC §12: if this host needs anything db-base does
  not provide, the seam is in the wrong place. Two halves now — the pool and its
  migrations, which the host has had since it existed, and §8's session store, which it
  asked for on 2026-09-21 because a cookie could not do what it wanted.

  **What a cookie cannot do, said exactly, because it is the only reason this host asked.**
  §8: a copied cookie never expires, since the sealed payload carries no timestamp and
  `Max-Age` is enforced by an honest client; and `delete-session` under the cookie store
  cannot revoke anything, because it seals a fresh empty value while the old one stays
  cryptographically valid forever. Ending ONE session — one stolen device, the others
  left alive — needs a row.

  **So the tests below model a thief and not an honest browser.** `wt/cookies` turns a
  deletion header into nil and `wt/with-cookies` then forgets it, which is what a
  well-behaved browser does; a test written that way is green over a cookie store and
  proves nothing. The cookie's value is captured as a string BEFORE the logout and
  replayed verbatim afterwards, and a control boots the very same host over the cookie
  store to show that this observation does see the difference.

  Everything is built through the host's own `config.edn`, with only the database file
  overridden. What only a browser can see stays the manual smoke recorded in CLAUDE.md."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [demo.handlers :as handlers]
            [demo.system]
            [dev.arkaitz.db-base.session :as session]
            [dev.arkaitz.db-base.testing :as dbt]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.integrant :as wbi]
            [dev.arkaitz.web-base.testing :as wt]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [ring.mock.request :as mock])
  (:import [clojure.lang ExceptionInfo]))

(defn- config
  "The host's system, over `path` instead of the `demo.db` its resource names. No reader
  map is passed any more: since the session became a row this host has no secret to read
  from the environment, which is one thing §8 bought that nobody asked for."
  [path]
  (-> (wbi/read-string (slurp (io/resource "config.edn")))
      (assoc-in [:dev.arkaitz.db-base/database :jdbc-url] (str "jdbc:sqlite:" path))
      ;; Overridden like the database file, and for the same reason: with the resource's
      ;; own 3600000 left in place, a host that ignored `:session-lifetime-ms` and wired a
      ;; literal would agree with this test by coincidence. 777000 appears nowhere else.
      (assoc-in [:demo/web-config :session-lifetime-ms] 777000)))

(defn- lifetime
  "The session lifetime the host names, read from the resource rather than from the store
  it built: comparing a row against the number that wrote it is the host agreeing with
  itself."
  [path]
  (get-in (config path) [:demo/web-config :session-lifetime-ms]))

(defn- temp-db-path []
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

(defn- datasource [path] (jdbc/get-datasource {:jdbcUrl (str "jdbc:sqlite:" path)}))

(defn- rows [path sql]
  (vec (rest (jdbc/execute! (datasource path) [sql] {:builder-fn rs/as-arrays}))))

(defn- one [path sql] (ffirst (rows path sql)))

(defn- session-rows
  "Every session row as `[id data expires-at]`, read by db-base's own reader of its
  table through a connection of this test's own."
  [path]
  (mapv (juxt :id :data :expires-at) (dbt/sessions {:jdbc-url (str "jdbc:sqlite:" path) :user "" :password ""})))

(defn- session-of [path id]
  (some (fn [[row-id data _]] (when (= id row-id) (edn/read-string data))) (session-rows path)))

(defn- boot [path] (ig/init (config path) [:dev.arkaitz.web-base/handler]))
(defn- app [system] (get system :dev.arkaitz.web-base/handler))
(defn- page [app] (:body (app (mock/request :get "/"))))
(defn- redirected-home [response] [(:status response) (get-in response [:headers "Location"])])

(defn- health [app]
  (let [response (app (mock/request :get "/health"))]
    [(:status response) (:body response)]))

(defn- cookie-of [response] (get (wt/cookies response) "ring-session"))

(defn- carrying
  "`request` presenting `value` as its session cookie, verbatim. Not `wt/with-cookies`,
  which models a browser that obeyed a deletion header; this is what someone holding a
  copy of the string can send."
  [request value]
  (assoc-in request [:headers "cookie"] (str "ring-session=" value)))

(defn- page-carrying
  "The page as someone presenting `value` as their session cookie sees it."
  [app value]
  (:body (app (carrying (mock/request :get "/") value))))

(defn- name-yourself!
  "A visitor naming themselves the way a browser does: the page first, for the CSRF token
  and the session cookie that holds it, then the POST carrying both."
  [app visitor]
  (let [form     (app (mock/request :get "/"))
        response (app (-> (mock/request :post "/session" {"visitor" visitor
                                                          "__anti-forgery-token" (wt/csrf-token form)})
                          (wt/with-cookies form)))]
    [response (cookie-of response)]))

(defn- post-note! [app body]
  (let [form (app (mock/request :get "/"))]
    (app (-> (mock/request :post "/notes" {"body" body "__anti-forgery-token" (wt/csrf-token form)})
             (wt/with-cookies form)))))

(defn- threads [pattern]
  (into #{}
        (comp (map #(.getName ^Thread %)) (filter #(re-find pattern %)))
        (keys (Thread/getAllStackTraces))))

(def ^:private acceptor-threads #"-acceptor-")
(def ^:private pool-threads #"^HikariPool-")

(defn- eventually
  "`(f)` once `done?` accepts it, or its last value when `deadline-ms` runs out. The
  deadline is a hang guard and never the pass criterion. Two things here are asynchronous
  to the call that causes them — Jetty renames its acceptor from inside the job the
  connector queued, and HikariCP's close returns before its housekeeper has died."
  [f done? deadline-ms]
  (let [until (+ (System/currentTimeMillis) deadline-ms)]
    (loop []
      (let [value (f)]
        (if (or (done? value) (>= (System/currentTimeMillis) until))
          value
          (do (Thread/sleep 10) (recur)))))))

(defn- uuid-shaped? [s]
  (boolean (and (string? s)
                (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" s))))

;; --------------------------------------------------------------------------
;; §8: what this host asked the library for
;; --------------------------------------------------------------------------

(deftest a-session-is-a-row-the-store-minted-and-the-page-shows-what-that-row-holds
  (let [path    (temp-db-path)
        visitor (str "ada-" (random-uuid))]
    (try
      (let [system (boot path)]
        (try
          (let [[response cookie] (name-yourself! (app system) visitor)]
            (is (= [303 "/"] (redirected-home response))
                "naming yourself is accepted and redirects")
            (is (uuid-shaped? cookie)
                (str "the cookie carries a key the store minted, not a sealed payload. This"
                     " is the line a revert to `:session {:key …}` reds on: a cookie store's"
                     " value is base64 and an HMAC, and no part of it is a UUID"))
            (is (= {:visitor visitor}
                   (dissoc (session-of path cookie)
                           :ring.middleware.anti-forgery/anti-forgery-token))
                (str "and the row under that key holds the name, read from the database"
                     " through a connection of this test's own. The CSRF token is set aside"
                     " by its real key — `:__anti-forgery-token` is the FORM FIELD's name"
                     " and not the session's, so a dissoc of that would be dead code"))
            (is (str/includes? (page-carrying (app system) cookie)
                               (str "writing as <strong>" visitor "</strong>"))
                "and the page greets that visitor, from that row"))
          (finally (ig/halt! system))))
      (finally (delete-db! path)))))

(deftest naming-yourself-rotates-the-session-id-and-the-old-row-goes
  (let [path (temp-db-path)]
    (try
      (let [system (boot path)
            handle (app system)]
        (try
          (let [anon   (handle (mock/request :get "/"))
                before (cookie-of anon)
                named  (handle (-> (mock/request :post "/session"
                                                 {"visitor" "ada"
                                                  "__anti-forgery-token" (wt/csrf-token anon)})
                                   (wt/with-cookies anon)))
                after  (cookie-of named)]
            (is (uuid-shaped? before)
                (str "precondition: the anonymous visit already has a row — web-base keeps"
                     " the CSRF token in the session, so a session exists before anyone"
                     " names themselves"))
            (is (= [303 "/"] (redirected-home named)) "the POST is accepted")
            (is (uuid-shaped? after)
                (str "and the response carries a session cookie. Without `rotate` Ring would"
                     " update the row in place and re-set nothing, so there would be no"
                     " cookie here at all"))
            (is (not= before after)
                (str "under a DIFFERENT key: `session/rotate` is the defence against"
                     " fixation, and a host that only assoc'd the name would leave a visitor"
                     " on the id they arrived with"))
            (is (= [after] (mapv first (session-rows path)))
                (str "and the row they arrived with is gone — rotation deletes, it does not"
                     " accumulate. A `delete-session` that did nothing would leave two")))
          (finally (ig/halt! system))))
      (finally (delete-db! path)))))

(deftest a-cookie-copied-before-a-logout-is-anonymous-afterwards
  (let [path    (temp-db-path)
        visitor (str "ada-" (random-uuid))]
    (try
      (let [system (boot path)
            handle (app system)]
        (try
          (let [[_ stolen] (name-yourself! handle visitor)
                signed-in  (handle (carrying (mock/request :get "/") stolen))]
            (is (str/includes? (:body signed-in) (str "writing as <strong>" visitor "</strong>"))
                (str "precondition, and the one that stops `anonymous` below from meaning"
                     " `this store cannot read anything`: the copy works before the logout"))
            (let [ended (handle (-> (mock/request :post "/session/end"
                                                  {"__anti-forgery-token" (wt/csrf-token signed-in)})
                                    (carrying stolen)))]
              (is (= [303 "/"] (redirected-home ended))
                  "the logout is accepted — a 403 for a missing token would make the rest vacuous")
              (is (= [] (session-rows path))
                  (str "the row is gone. A logout that set `:session {}` instead of nil would"
                       " leave the page anonymous and the row alive, and only this line would"
                       " say so"))
              (is (nil? (cookie-of ended))
                  (str "and the logout set NO session cookie — said after the row check,"
                       " because on its own this line is equally true of a logout that did"
                       " nothing at all. Pinned so the design cannot drift: under this store"
                       " `delete-session` answers nil, so Ring re-sets nothing, which is why"
                       " threading the logout's cookies forward looks identical to replaying"
                       " the copy TODAY and would silently stop being identical if that"
                       " changed"))
              (let [replayed (page-carrying handle stolen)]
                (is (not (str/includes? replayed "writing as"))
                    (str "AND THE COPY IS ANONYMOUS. This is the sentence §8 exists for: the"
                         " sealed value of a cookie store stays valid forever and a logout"
                         " cannot revoke it, so a copied cookie outlives it. A row can be"
                         " deleted, and this is what deleting it looks like from outside"))
                (is (str/includes? replayed "name=\"visitor\"")
                    "the page offers the form again, which is what anonymous looks like here"))))
          (finally (ig/halt! system))))
      (finally (delete-db! path)))))

(deftest the-same-host-over-a-cookie-store-serves-the-copy-after-the-logout
  ;; The control for the test above, and the reason it is not merely an assertion. The
  ;; product has no switch: this builds web-base's handler over the very map the host's own
  ;; component produced, with `:session` replaced. If replaying a captured string could not
  ;; see the difference between a row and a sealed cookie, this would be green too — and
  ;; the test above would be proving nothing.
  (let [path    (temp-db-path)
        visitor (str "ada-" (random-uuid))]
    (try
      (let [system (boot path)
            built         (ig/init-key :demo/web-config
                                       {:db (get system :dev.arkaitz.db-base/database)
                                        :session-lifetime-ms (lifetime path)
                                        :secure? false})
            ;; Only the store is replaced. The host's own cookie attributes are carried
            ;; over, so "the same host, the same handler, only the store changed" is a
            ;; description of what this does and not a hope about it.
            over-a-cookie (wb/handler
                           (assoc built :session {:key "AAECAwQFBgcICQoLDA0ODw=="
                                                  :cookie-attrs (get-in built [:session :cookie-attrs])}))]
        (try
          (let [form      (over-a-cookie (mock/request :get "/"))
                named     (over-a-cookie (-> (mock/request :post "/session"
                                                           {"visitor" visitor
                                                            "__anti-forgery-token" (wt/csrf-token form)})
                                             (wt/with-cookies form)))
                stolen    (cookie-of named)
                signed-in (over-a-cookie (carrying (mock/request :get "/") stolen))]
            (is (and (some? stolen) (not (uuid-shaped? stolen)))
                (str "precondition: this really is the cookie store — its value is a sealed"
                     " payload and not a key the store minted"))
            (is (str/includes? (:body signed-in) (str "writing as <strong>" visitor "</strong>"))
                "precondition: the copy works before the logout here too")
            ;; The logout is performed by an HONEST browser — under the cookie store every
            ;; write re-seals, so the request that ends the session must carry the cookie
            ;; that request was last given, not the copy. The copy is what gets replayed
            ;; afterwards, which is the whole point.
            (let [ended (over-a-cookie (-> (mock/request :post "/session/end"
                                                         {"__anti-forgery-token" (wt/csrf-token signed-in)})
                                           (wt/with-cookies named)
                                           (wt/with-cookies signed-in)))]
              (is (= [303 "/"] (redirected-home ended))
                  "the same logout, accepted the same way")
              (is (some? (cookie-of ended))
                  (str "and HERE the logout does set a cookie — a freshly sealed empty one,"
                       " which is all it can do"))
              (is (str/includes? (page-carrying over-a-cookie stolen)
                                 (str "writing as <strong>" visitor "</strong>"))
                  (str "AND THE COPY STILL NAMES THE VISITOR. The same host, the same"
                       " handler, the same observation — only the store changed. §8's claim"
                       " is not an opinion about cookies: this is it, failing"))))
          (finally (ig/halt! system))))
      (finally (delete-db! path)))))

(deftest ending-one-of-a-visitors-sessions-leaves-their-other-one-alive
  ;; §8's sentence is ONE SUBJECT with several sessions — "one stolen device, the others
  ;; left alive" — so both browsers name the SAME visitor. Two different names would be
  ;; two subjects, which a revocation by subject rather than by id would also leave alone,
  ;; and a cookie store would too.
  (let [path    (temp-db-path)
        visitor (str "ada-" (random-uuid))]
    (try
      (let [system (boot path)
            handle (app system)]
        (try
          (let [[a-response key-a] (name-yourself! handle visitor)
                [b-response key-b] (name-yourself! handle visitor)]
            (is (= [[303 "/"] [303 "/"]]
                   [(redirected-home a-response) (redirected-home b-response)])
                "precondition: both devices really did sign in, rather than being refused")
            (is (= [{:visitor visitor} {:visitor visitor}]
                   [(dissoc (session-of path key-a) :ring.middleware.anti-forgery/anti-forgery-token)
                    (dissoc (session-of path key-b) :ring.middleware.anti-forgery/anti-forgery-token)])
                (str "precondition, about the rows and not their number: two sessions of the"
                     " SAME visitor. A count alone would be satisfied by the anonymous rows"
                     " the CSRF token leaves behind"))
            (is (not= key-a key-b) "under keys of their own")
            (let [signed-in (handle (carrying (mock/request :get "/") key-a))]
              (handle (-> (mock/request :post "/session/end"
                                        {"__anti-forgery-token" (wt/csrf-token signed-in)})
                          (carrying key-a))))
            (is (= [key-b] (mapv first (session-rows path)))
                (str "one device's row went and the other stayed — the same visitor, one"
                     " session ended. This is §8's sentence exactly, and it is what a"
                     " `DELETE` without its `WHERE` would take with it, and equally what a"
                     " revocation BY SUBJECT would: both sessions belong to one visitor"))
            (is (str/includes? (page-carrying handle key-b)
                               (str "writing as <strong>" visitor "</strong>"))
                (str "and the other device is still signed in. Without this, `anonymous` in"
                     " the test above could equally mean a store that reads nothing")))
          (finally (ig/halt! system))))
      (finally (delete-db! path)))))

(deftest the-expiry-is-the-servers-and-the-cookie-carries-only-a-courtesy
  (let [path (temp-db-path)
        want (lifetime path)]
    (try
      (let [system (boot path)
            handle (app system)]
        (try
          (let [before            (System/currentTimeMillis)
                [response cookie] (name-yourself! handle "ada")
                after             (System/currentTimeMillis)
                [[_ _ expires]]   (session-rows path)]
            (is (= [303 "/"] (redirected-home response))
                (str "precondition, which every other caller of this helper already makes:"
                     " the visitor really was named. The anonymous row the CSRF token leaves"
                     " behind falls inside the same window, so the bound below is green for"
                     " the wrong reason without it"))
            (is (<= (+ before want) expires (+ after want))
                (str "the row expires one lifetime from when it was written, and the lifetime"
                     " is the one `config.edn` names — read from the resource, not from the"
                     " store that wrote the row, which would be the host agreeing with"
                     " itself"))
            (is (= (str (quot want 1000))
                   (second (re-find #"Max-Age=(\d+)"
                                    (str (first (get-in response [:headers "Set-Cookie"]))))))
                (str "and the cookie says the same, in seconds. Read as the whole number and"
                     " never as a prefix: `Max-Age=3600000` contains `Max-Age=3600`, so a"
                     " substring match passes a cookie promising a thousand times what the"
                     " row holds — measured, it survived. That number is a courtesy to the"
                     " browser and nothing more: §8's point is that under a cookie store it"
                     " is the ONLY expiry there is, and an honest client the only thing"
                     " enforcing it"))
            (is (str/includes? (page-carrying handle cookie) "writing as")
                (str "witness, before the row is aged: this cookie IS served while its expiry"
                     " is ahead. Without it, `stops being served` below is equally true of a"
                     " store that serves nothing at all"))
            (jdbc/execute! (datasource path)
                           ["UPDATE db_base_sessions SET expires_at = 1 WHERE id = ?" cookie])
            (is (not (str/includes? (page-carrying handle cookie) "writing as"))
                (str "a row whose expiry has passed stops being served — aged by SQL and"
                     " never by sleeping, so this measures the predicate and not the clock"))
            (is (= 1 (count (filter #(= cookie (first %)) (session-rows path))))
                (str "and the row is still there: §8 puts the expiry in the READ, so an"
                     " expired session is invisible rather than swept, and sweeping stays"
                     " the operator's")))
          (finally (ig/halt! system))))
      (finally (delete-db! path)))))

(deftest an-anonymous-visit-costs-a-row-and-the-host-can-reclaim-it
  ;; Two things, and only the second is db-base's. **The cost**: a page that renders a
  ;; form keeps its CSRF token in the session, so a visitor who opens one and never comes
  ;; back leaves a row. Since web-base 0.4.0 that is the only way: a request that renders
  ;; no token writes no session, and a health probe reads none — three probes, zero rows,
  ;; pinned below, where on 2026-09-21 three probes cost three.
  ;; **What is db-base's**, and what nothing else in this suite exercised: the host can
  ;; sweep those rows itself, through the handle it already holds, and §11 records that as
  ;; the only way they ever go.
  (let [path (temp-db-path)]
    (try
      (let [system (boot path)
            db     (get system :dev.arkaitz.db-base/database)
            handle (app system)]
        (try
          (is (= 0 (count (session-rows path))) "precondition: nothing stored yet")
          (dotimes [_ 3] (handle (mock/request :get "/health")))
          (is (= 0 (count (session-rows path))) "three health probes leave no row")
          (handle (mock/request :get "/"))
          (is (= 1 (count (session-rows path)))
              (str "a visitor who has not even named themselves already costs a row"))
          (is (= 0 (session/reclaim-expired! db))
              (str "and a sweep now takes none of it: the row is live, and reclaiming is"
                   " about disk rather than about revoking"))
          (jdbc/execute! (datasource path) ["UPDATE db_base_sessions SET expires_at = 1"])
          (is (= 1 (session/reclaim-expired! db))
              (str "once it has expired the host's own sweep takes it, and says how many —"
                   " which is the number an operator schedules on. Nothing does this on a"
                   " timer and nothing does it on write, so this call is the only thing"
                   " that ever removes a row"))
          (is (= [] (session-rows path)) "and the table is empty afterwards")
          (finally (ig/halt! system))))
      (finally (delete-db! path)))))

;; --------------------------------------------------------------------------
;; §6 and §7: what this host has had since it existed
;; --------------------------------------------------------------------------

(deftest the-first-boot-applies-both-runs-and-the-second-applies-neither
  (let [path (temp-db-path)]
    (try
      (let [system (boot path)]
        (is (= [1 1] [(:session-migrations-applied (get system :dev.arkaitz.db-base/database))
                      (:migrations-applied (get system :dev.arkaitz.db-base/database))])
            (str "the first boot applies this library's own migration and the host's, and"
                 " counts them apart"))
        (is (str/includes? (page (app system)) "· 1 migration(s) applied")
            (str "and the page reports the HOST's, which is the only one it knows about — a"
                 " page that added the two would say 2 here"))
        (ig/halt! system))
      (is (= [["db_base_migration_lock"] ["db_base_migrations"] ["db_base_sessions"]
              ["note"] ["ragtime_migrations"]]
             (rows path "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name"))
          (str "and both runs did their work: §8's table beside the host's `note`, each"
               " recorded in a control table of its own"))
      (is (= [[["001-sessions"]] [["001-notes"]]]
             [(rows path "SELECT id FROM db_base_migrations ORDER BY id")
              (rows path "SELECT id FROM ragtime_migrations ORDER BY id")])
          (str "recorded apart, which is what makes a host's own reset survivable: §8"
               " insists on two control tables and this is the line that says so"))
      (is (= [[0]] (rows path "SELECT COUNT(*) FROM db_base_migration_lock"))
          "with both lock rows given back")
      (let [system (boot path)]
        (is (= [0 0] [(:session-migrations-applied (get system :dev.arkaitz.db-base/database))
                      (:migrations-applied (get system :dev.arkaitz.db-base/database))])
            (str "the second boot applies neither. Zero is the assertion and not a floor:"
                 " re-applying either would meet a table that is already there"))
        (is (str/includes? (page (app system)) "· 0 migration(s) applied")
            "and the page says so too")
        (ig/halt! system))
      (finally (delete-db! path)))))

(deftest a-note-written-through-the-application-outlives-the-system
  (let [path (temp-db-path)
        kept (str "kept-" (random-uuid))]
    (try
      (let [first-system (boot path)
            pooled       (threads pool-threads)]
        (is (= [303 "/"] (redirected-home (post-note! (app first-system) kept)))
            "the form's POST is accepted and redirects: the note was written, not refused")
        (ig/halt! first-system)
        ;; The witness that the first system really is down. It used to be a 503 from
        ;; `/health`; since the session became a row that is unreachable — the session layer
        ;; throws on a closed pool before any route runs — so the observation moved to the
        ;; pool's own threads, which is where it was always true.
        (is (= #{} (eventually #(set/difference (threads pool-threads) pooled) empty? 5000))
            (str "witness, before anything else boots: the first system's pool is gone."
                 " Without it a green below could mean the page was served by a pool that"
                 " never closed, which is not a restart")))
      (let [second-system (boot path)]
        (try
          (is (str/includes? (page (app second-system)) kept)
              "a second system, on the same file, serves the note the first one wrote")
          (finally (ig/halt! second-system))))
      (is (= [[kept]] (rows path "SELECT body FROM note"))
          "and the file holds exactly that row, read through a connection of this test's own")
      (finally (delete-db! path)))))

(deftest the-health-route-answers-from-ready-through-the-whole-stack-with-the-pool-closed
  ;; Until web-base 0.4.0 the 503 below never left: the route sat inside the session
  ;; layer, whose read threw on a closed pool before any route ran, so this test pinned
  ;; the thrown SQLException and said it would red the day that boundary moved. It moved:
  ;; `/health` is `sessionless` now, answered before the session, and what `ready?`
  ;; computes reaches the probe.
  (let [path (temp-db-path)]
    (try
      (let [system  (boot path)
            db      (get system :dev.arkaitz.db-base/database)
            handler (app system)]
        (is (= [200 "ok"] (health handler))
            "while the pool is open the whole request works, end to end")
        ;; A probe that carries a session cookie — a browser's, or a monitor that kept
        ;; one — is what tells the sessionless mount from a route: behind the session
        ;; layer its cookie makes the store read, and on a closed pool that read throws
        ;; before any route runs. A probe with no cookie reads nothing either way.
        (let [cookie (cookie-of (handler (mock/request :get "/")))]
          (is (some? cookie) "precondition: the home page gave this visitor a session")
          (ig/halt! system)
          (let [probe (handler (carrying (mock/request :get "/health") cookie))]
            (is (= [503 "the database is not answering"] [(:status probe) (:body probe)])
                (str "a probe carrying a session cookie gets the 503 with the pool closed —"
                     " it never touched the session layer, whose read would have thrown"))))
        (is (= [503 "the database is not answering"]
               ((juxt :status :body) (handlers/health db (mock/request :get "/health"))))
            (str "and the ROUTE answers from `ready?` with the pool closed — asked of the"
                 " handler function directly, so a health that never consulted `ready?`"
                 " cannot pass on a constant. `ready?` answers false for a borrow that fails"
                 " rather than throwing, which is what lets this route exist at all"))
        (is (= [503 "the database is not answering"] (health handler))
            (str "and through the whole stack too: the probe gets the 503, where before it"
                 " got a SQLException from the session layer and an operator saw a pool"
                 " error instead of the sentence")))
      (finally (delete-db! path)))))

(deftest a-database-that-cannot-be-reached-stops-the-boot-and-leaves-nothing-listening
  (let [path (temp-db-path)]
    (try
      (testing "control: a boot that succeeds does leave something listening"
        (let [control (try (ig/init (assoc-in (config path) [:dev.arkaitz.web-base/server :port] 0))
                           (catch Throwable t t))]
          (is (map? control)
              (str "the premise of this test, said in its own words: the control boot has to"
                   " succeed. An exception escaping here would abort the whole test and read"
                   " as the claim below failing, when the claim was never evaluated"))
          (when (map? control)
            (try
              (let [port (get-in control [:dev.arkaitz.web-base/server :port])]
                (is (pos? port) "the server bound an ephemeral port and reported it")
                (is (seq (eventually #(threads (re-pattern (str "-acceptor-.*:" port "\\}")))
                                     seq 5000))
                    (str "and an acceptor thread holds that port's socket, which is what"
                         " keeps the assertion below from being vacuous")))
              (finally (ig/halt! control))))))
      (let [listening   (threads acceptor-threads)
            pooled      (threads pool-threads)
            unreachable (-> (config path)
                            (assoc-in [:dev.arkaitz.db-base/database :jdbc-url]
                                      "jdbc:sqlite:/no-such-directory-7f3a/demo.db")
                            (assoc-in [:dev.arkaitz.db-base/database :password] "pw-sentinel-7f3a")
                            ;; Policy, not an invariant: HikariCP's floor is 250 ms and the
                            ;; refusal underneath is immediate, so this only shortens a
                            ;; failing boot from the resource's 5000 ms.
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
                       " built — nothing, because every other component reaches the database"
                       " with #ig/ref"))
              (is (= [true true]
                     [(str/includes? (pr-str data) "no-such-directory-7f3a")
                      (str/includes? (pr-str data) "pw-sentinel-7f3a")])
                  (str "and integrant's own wrapper carries the JDBC URL AND the password in"
                       " `:value`. Pinned rather than left to be discovered: what an operator"
                       " may be shown is the cause below, never this")))
            (let [cause (ex-cause outcome)
                  chain (take-while some? (iterate ex-cause cause))]
              (is (some #(str/includes? (str (ex-message %)) "HikariPool-") chain)
                  (str "the boot did get as far as constructing a pool, which is what makes"
                       " the assertion about pools below one about a pool that existed"))
              (is (= [true {:config-key [:pool :timeout-ms]}]
                     [(str/includes? (str (ex-message cause)) "no connection to the database")
                      (ex-data cause)])
                  "and the cause is db-base's own refusal, naming the key an operator can act on")
              (is (not-any? #(str/includes? (pr-str [(ex-message %) (ex-data %)])
                                            "no-such-directory-7f3a")
                            chain)
                  "without any link of that chain echoing the JDBC URL"))
            (is (= #{} (set/difference (threads acceptor-threads) listening))
                (str "nothing ever started listening: the assertion above shows integrant"
                     " never called the server's init-key, so no queued rename is on its way"))
            (is (= #{} (set/difference (threads pool-threads) pooled))
                (str "and no pool outlived the failed boot, read with no wait at all because"
                     " `start` closes what it opened before it leaves"))
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
          ;; about: a page rendered from an atom, a cache, or whatever `create` returned
          ;; cannot show this row.
          (jdbc/execute-one! (datasource path)
                             ["INSERT INTO note (body, written_at) VALUES (?, ?)" behind-its-back 1])
          (let [rendered (page (app system))]
            (is (str/includes? rendered behind-its-back)
                "the page shows a row the application never wrote: it is reading the table")
            (is (str/includes? rendered through-the-app)
                "and the row it did write, through the pool db-base handed over")
            (is (str/includes? rendered (str ">" (one path "SELECT COUNT(*) FROM note") " kept · "))
                (str "and counts what the table holds — the number read here through this"
                     " test's own connection, anchored to the tag and the separator so `2"
                     " kept` cannot be matched inside `12 kept`"))
            (is (< (.indexOf ^String rendered ^String behind-its-back)
                   (.indexOf ^String rendered ^String through-the-app))
                "newest first, which is what the page's `ORDER BY id DESC` promises"))
          (finally (ig/halt! system))))
      (finally (delete-db! path)))))

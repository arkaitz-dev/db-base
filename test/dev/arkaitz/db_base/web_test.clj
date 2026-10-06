(ns dev.arkaitz.db-base.web-test
  "The plugin, installed in a real web-base host over a real pool: what it brings is
  observed where it lands — a row in §8's table, a status line from the probe — never in
  the map it returns."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.db-base :as db]
            [dev.arkaitz.db-base.test-support :as ts]
            [dev.arkaitz.db-base.testing :as dbt]
            [dev.arkaitz.db-base.web :as db-web]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.session :as wb-session]
            [dev.arkaitz.web-base.testing :as wbt]
            [ring.middleware.session.store :as store]))

(defn- booted [url]
  (db/start (assoc (ts/config url "three") :migrations :none :sessions {:lock-wait-ms 1000})))

(defn- host
  "A web-base host whose only session and probe are the plugin's. `/count` writes the
  session; `/` touches nothing."
  [handle opts]
  (wb/handler {:plugins [(db-web/plugin handle opts)]
               :routes  [["/" {:get (fn [_] {:status 200 :body "home"})}]
                         ["/count" {:get (fn [r] (let [n (inc (get-in r [:session :n] 0))]
                                                   {:status 200 :body (str n) :session {:n n}}))}]]}))

(deftest the-session-lives-in-a-row-of-the-table-under-the-cookie-the-host-named
  (let [url    (ts/h2-memory-url "web-session")
        handle (booted url)]
    (try
      (let [app (host handle {:session {:lifetime-ms 60000 :cookie-name "app-session"}})
            b   (-> (wbt/browser app) (wbt/visit :get "/count") (wbt/visit :get "/count"))
            key (get-in b [:jar "app-session"])]
        (is (= "2" (get-in b [:response :body])) "the session carried the count from one request to the next")
        (is (some? key) "under the cookie the host named")
        (is (= [key "{:n 2}"] ((juxt :id :data) (dbt/session (ts/config url "three") key)))
            "and it is a row of §8's table, keyed by that cookie"))
      (finally (db/stop handle)))))

(deftest the-store-takes-the-lifetime-and-readers--and-the-cookie-its-attributes
  (let [url    (ts/h2-memory-url "web-store")
        handle (booted url)]
    (try
      (let [app    (wb/handler {:plugins [(db-web/plugin handle {:session {:lifetime-ms  60000
                                                                          :readers      {'x/y #(tagged-literal 'x/y %)}
                                                                          :cookie-attrs {:max-age 60}}})]
                                :routes  [["/tag" {:get (fn [_] {:status 200 :body "set"
                                                                 :session {:v (tagged-literal 'x/y 1)}})}]
                                          ["/show" {:get (fn [r] {:status 200 :body (pr-str (get-in r [:session :v]))})}]]})
            before (System/currentTimeMillis)
            b      (wbt/visit (wbt/browser app) :get "/tag")
            after  (System/currentTimeMillis)
            row    (dbt/session (ts/config url "three") (get-in b [:jar "ring-session"]))]
        (is (re-find #"(?i)Max-Age=60" (str/join ";" (get-in b [:response :headers "Set-Cookie"])))
            "the host's cookie attributes reach the cookie")
        (is (<= (+ before 60000) (:expires-at row) (+ after 60000))
            "the row expires the host's lifetime after it was written")
        (is (= "#x/y 1" (get-in (wbt/visit b :get "/show") [:response :body]))
            "and a tagged value is read back through the host's readers"))
      (finally (db/stop handle)))))

(deftest a-due-session-moves-its-row-and-its-cookie-forward--a-fresh-one-neither
  (let [url    (ts/h2-memory-url "web-renew")
        handle (booted url)]
    (try
      (let [plugin (db-web/plugin handle {:session {:lifetime-ms  60000
                                                    :cookie-attrs {:max-age 60}
                                                    :renew        {:every-ms 30000 :absolute-ms 600000}}})
            app    (wb/handler {:plugins [plugin]
                                :routes  [["/" {:get (fn [_] {:status 200 :body "home"})}]]})
            plant  (fn [renewed] (store/write-session (get-in plugin [:session :store]) nil
                                                      {:n 1 ::wb-session/renewed-at renewed
                                                       ::wb-session/born-at (- (System/currentTimeMillis) 60000)}))
            row    #(dbt/session (ts/config url "three") %)
            visit  #(wbt/visit (assoc-in (wbt/browser app) [:jar "ring-session"] %) :get "/")
            due    (plant (- (System/currentTimeMillis) 30000))
            fresh  (plant (- (System/currentTimeMillis) 15000))
            [due-before fresh-before] (map (comp :expires-at row) [due fresh])
            before (System/currentTimeMillis)
            b-due  (visit due)
            after  (System/currentTimeMillis)
            b-fresh (visit fresh)]
        (is (= "home" (get-in b-due [:response :body])) "witness: the page answered")
        (is (<= (+ before 60000) (:expires-at (row due)) (+ after 60000))
            (str "the due row expires a lifetime from this request, where it was written for " due-before))
        (is (re-find #"(?i)Max-Age=60" (str/join ";" (get-in b-due [:response :headers "Set-Cookie"])))
            "and its cookie goes again with the host's Max-Age")
        (is (= fresh-before (:expires-at (row fresh))) "a fresh row is not written")
        (is (empty? (get-in b-fresh [:response :headers "Set-Cookie"])) "nor its cookie sent"))
      (finally (db/stop handle)))))

(deftest the-plugin-brings-a-session-and-a-probe-and-nothing-else
  (let [handle (booted (ts/h2-memory-url "web-keys"))]
    (try
      (let [p (db-web/plugin handle {:session {:lifetime-ms 1000 :cookie-name "c"}})]
        (is (= #{:wb.plugin/name :session :sessionless} (set (keys p))))
        (is (= #{:store :cookie-name} (set (keys (:session p))))))
      (finally (db/stop handle)))))

(deftest the-probe-asks-ready?-with-the-timeout-it-was-given
  (let [handle (booted (ts/h2-memory-url "web-timeout"))
        asked  (atom [])]
    (try
      (with-redefs [db/ready? (fn [h t] (swap! asked conj [(identical? h handle) t]) true)]
        (let [probe (get-in (db-web/plugin handle {:health {:timeout-s 7}}) [:sessionless "/health"])]
          (is (= 200 (:status (probe {:request-method :get :uri "/health"}))))
          (is (= [[true 7]] @asked) "ready? is asked once, of this handle, with the host's timeout")))
      (finally (db/stop handle)))))

(deftest the-probe-answers-whether-the-database-does--and-writes-no-session
  (let [url    (ts/h2-memory-url "web-health")
        handle (booted url)
        app    (host handle {:session {:lifetime-ms 60000}})
        probe  #(app {:request-method :get :uri "/health" :headers {}})]
    (try
      (let [r (probe)]
        (is (= [200 "ok" nil] [(:status r) (:body r) (get-in r [:headers "Set-Cookie"])])
            "an open pool answers ok, with no session cookie")
        (is (= [] (dbt/sessions (ts/config url "three"))) "and no row was written"))
      (finally (db/stop handle)))
    (is (= [503 "unavailable"] ((juxt :status :body) (probe))) "a closed pool answers 503")))

(deftest the-probe-moves-or-goes-as-the-host-says
  (let [handle (booted (ts/h2-memory-url "web-probe"))]
    (try
      (let [status (fn [opts uri] (:status ((host handle (merge {:session {:lifetime-ms 1000}} opts))
                                           {:request-method :get :uri uri :headers {}})))]
        (is (= [200 404] [(status {:health {:path "/ready"}} "/ready") (status {:health {:path "/ready"}} "/health")])
            "a path of the host's replaces the default")
        (is (= 404 (status {:health false} "/health")) "false brings no probe")
        (is (= 200 (status {} "/health")) "control: absent, the default probe is there"))
      (finally (db/stop handle)))))

(deftest every-malformed-option-is-refused-naming-its-key
  (let [handle (booted (ts/h2-memory-url "web-refusals"))]
    (try
      (doseq [[label opts expected]
              [["options not a map" nil ["db-base web: the options must be a map" {:config-key []}]]
               ["an unknown key" {:sesion {}}
                ["db-base web: unknown key [:sesion] in the options — it takes [:health :session]" {:config-key [:sesion]}]]
               ["an unknown session key" {:session {:lifetime-ms 1 :max-age 5}}
                ["db-base web: unknown key [:max-age] in [:session] — it takes [:cookie-attrs :cookie-name :lifetime-ms :readers :renew]"
                 {:config-key [:session :max-age]}]]
               ["a renewal no shorter than the row's life" {:session {:lifetime-ms 60000 :renew {:every-ms 60000 :absolute-ms 600000}}}
                ["db-base web: [:session :renew :every-ms] must be shorter than [:session :lifetime-ms]"
                 {:config-key [:session :renew :every-ms]}]]
               ["health neither map nor false" {:health true}
                ["db-base web: :health must be a map, or false for no probe" {:config-key [:health]}]]
               ["a relative probe path" {:health {:path "health"}}
                ["db-base web: [:health :path] must be a path starting with /, with no query, fragment or final /"
                 {:config-key [:health :path]}]]
               ["a probe path with a query" {:health {:path "/h?x=1"}}
                ["db-base web: [:health :path] must be a path starting with /, with no query, fragment or final /"
                 {:config-key [:health :path]}]]
               ["a probe path that is a prefix" {:health {:path "/h/"}}
                ["db-base web: [:health :path] must be a path starting with /, with no query, fragment or final /"
                 {:config-key [:health :path]}]]
               ["the root as a probe path" {:health {:path "/"}}
                ["db-base web: [:health :path] must be a path starting with /, with no query, fragment or final /"
                 {:config-key [:health :path]}]]
               ["a zero timeout" {:health {:timeout-s 0}}
                [(str "db-base web: [:health :timeout-s] must be an integer from 1 to " Integer/MAX_VALUE " seconds")
                 {:config-key [:health :timeout-s]}]]]]
        (is (= expected (ts/attempt #(db-web/plugin handle opts))) (str "refused: " label)))
      (is (= [:session :lifetime-ms] (:config-key (second (ts/attempt #(db-web/plugin handle {:session {}})))))
          "the store's own refusal reaches the host at construction")
      (is (map? (db-web/plugin handle {:session {:lifetime-ms 60000 :renew {:every-ms 59999 :absolute-ms 600000}}}))
          "control: a renewal just inside the row's life is accepted")
      (finally (db/stop handle))))
  (let [url    (ts/h2-memory-url "web-no-table")
        handle (db/start (assoc (ts/config url "three") :migrations :none))]
    (try
      (is (str/includes? (str (first (ts/attempt #(db-web/plugin handle {:session {:lifetime-ms 1000}})))) "db_base_sessions")
          "a boot that made no session table is refused when the plugin is built, not at the first request")
      (is (map? (db-web/plugin handle {})) "control: with no session asked for, the probe alone needs no table")
      (finally (db/stop handle)))))

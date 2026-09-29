(ns dev.arkaitz.db-base.web
  "db-base as a web-base plugin (since 0.3.0): sessions in a row and a readiness probe,
  one line of the host's config.

      (wb/handler {:plugins [(db-web/plugin db {:session {:lifetime-ms (* 14 24 3600 1000)}})]
                   :routes  my-routes})

  Optional as `.session` is, and for the same reason: web-base and ring are the host's,
  and a host that only wanted a pool never loads this. SPEC §10 records why web-base,
  alone among this library's siblings, may be named here: it is the root the set is
  built on, by the user's decision of 2026-09-29, and never auth-base.

  The plugin brings two things and nothing else, both keys web-base already takes from a
  host: `:session`, §8's store over this handle's pool, and `:sessionless`, a probe
  answering whether the database does. The host's own `:session` wins over the plugin's,
  as web-base merges every scalar."
  (:require [dev.arkaitz.db-base :as db]
            [dev.arkaitz.db-base.session :as session]
            [dev.arkaitz.web-base.response :as response]))

(def ^:private session-keys #{:lifetime-ms :readers :cookie-attrs :cookie-name})

(def ^:private health-defaults {:path "/health" :timeout-s 2})

(defn- fail! [message config-key]
  ;; Never the value: what a mis-wired caller passed may be the configuration map.
  (throw (ex-info (str "db-base web: " message) {:config-key config-key})))

(defn- check-keys! [m allowed where]
  (when-not (map? m)
    (fail! (str (if (seq where) (pr-str where) "the options") " must be a map") where))
  (when-let [unknown (not-empty (sort-by pr-str (remove allowed (keys m))))]
    (fail! (str "unknown key" (when (next unknown) "s") " " (pr-str (vec unknown)) " in "
                (if (seq where) (pr-str where) "the options")
                " — it takes " (pr-str (vec (sort allowed))))
           (conj where (first unknown)))))

(defn plugin
  "The plugin value for web-base's `:plugins`, over `handle`, what `db/start` returned.

    :session  `{:lifetime-ms n}` for §8's store, which also takes `:readers`; web-base's
              `:cookie-attrs` and `:cookie-name` ride beside it. Absent, the plugin
              brings no session and the host gives its own.
    :health   `{:path \"/health\" :timeout-s 2}`, the defaults: a sessionless probe
              answering 200 when `ready?` does and 503 otherwise. `:timeout-s` is
              `ready?`'s, and bounds the `isValid` call only: the borrow before it waits
              up to the pool's `:timeout-ms`, so a pool exhausted by load answers 503
              too, and that late. `false` brings none.

  Refused when it is built, here or by web-base's handler, which refuses a probe path a
  route or another sessionless path already holds. The store reads its table once, so a
  boot that said `:sessions :none` is refused now rather than at the first request."
  [handle {:keys [session health] :as opts}]
  (check-keys! opts #{:session :health} [])
  (when (some? session) (check-keys! session session-keys [:session]))
  (when-not (or (false? health) (nil? health) (map? health))
    (fail! ":health must be a map, or false for no probe" [:health]))
  (when (map? health) (check-keys! health (set (keys health-defaults)) [:health]))
  (let [probe (when-not (false? health) (merge health-defaults health))]
    (when probe
      ;; A path ending in / is a prefix to web-base's :sessionless, and a probe answering
      ;; under every path beneath it is not what anybody asked for.
      (when-not (and (string? (:path probe)) (re-matches #"/[^?#\s]*[^/?#\s]" (:path probe)))
        (fail! "[:health :path] must be a path starting with /, with no query, fragment or final /"
               [:health :path]))
      (when-not (and (integer? (:timeout-s probe)) (<= 1 (:timeout-s probe) Integer/MAX_VALUE))
        (fail! (str "[:health :timeout-s] must be an integer from 1 to " Integer/MAX_VALUE " seconds")
               [:health :timeout-s])))
    (cond-> {:wb.plugin/name :db-base}
      session
      (assoc :session (merge {:store (session/store handle (merge {:readers {}}
                                                                   (select-keys session [:lifetime-ms :readers])))}
                             (select-keys session [:cookie-attrs :cookie-name])))
      probe
      (assoc :sessionless {(:path probe) (response/health #(db/ready? handle (:timeout-s probe)))}))))

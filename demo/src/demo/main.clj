(ns demo.main
  "Boots the host. The order is the point: the database is a dependency of the web
  configuration, so Integrant starts it first and, on the way down, stops it last — a
  schema that is not there yet cannot be served by a port that is already open.

  Configuration is read from a resource the host names, with `#wb/env` for what must not
  be committed. `env.local.edn` is web-base's development convention: an absent file is
  not an error, it is the production case."
  (:require [clojure.java.io :as io]
            [demo.system]
            [dev.arkaitz.web-base.config :as config]
            [dev.arkaitz.web-base.integrant :as wbi]
            [integrant.core :as ig])
  (:gen-class))

(def ^:private env-file "env.local.edn")

(defn config
  "The system map, with `port` overriding the one in the resource when given."
  ([] (config nil))
  ([port]
   (cond-> (wbi/read-string (config/env-file-readers env-file) (slurp (io/resource "config.edn")))
     port (assoc-in [:dev.arkaitz.web-base/server :port] port))))

(defn -main [& [port]]
  (let [system (ig/init (config (some-> port parse-long)))]
    (println "demo: serving on port"
             (get-in system [:dev.arkaitz.web-base/server :port])
             "· migrations applied this boot:"
             (get-in system [:dev.arkaitz.db-base/database :migrations-applied] 0))
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(ig/halt! system)))
    ;; Jetty does not join, so the process would otherwise exit with the server running.
    @(promise)))

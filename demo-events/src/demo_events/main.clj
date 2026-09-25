(ns demo-events.main
  "Boots the host. `port`, when given, moves both the server and the origin the
  sign-in link is built from, which nothing else keeps in step."
  (:require [clojure.java.io :as io]
            [demo-events.system]
            [dev.arkaitz.web-base.config :as config]
            [dev.arkaitz.web-base.integrant :as wbi]
            [integrant.core :as ig])
  (:gen-class))

(defn config
  ([] (config nil))
  ([port]
   (cond-> (wbi/read-string (config/env-file-readers "env.local.edn") (slurp (io/resource "config.edn")))
     port (-> (assoc-in [:dev.arkaitz.web-base/server :port] port)
              (assoc-in [:demo-events/auth-config :base-url] (str "http://localhost:" port))))))

(defn -main [& [port]]
  (let [parsed (some-> port parse-long)]
    (when (and port (not (<= 1 (or parsed 0) 65535)))
      (binding [*out* *err*]
        (println "demo-events: the port must be a number from 1 to 65535, not" (pr-str port)))
      (System/exit 1))
    (let [system (ig/init (config parsed))]
      (println "demo-events: serving on port" (get-in system [:dev.arkaitz.web-base/server :port])
               "· migrations applied this boot:"
               (get-in system [:dev.arkaitz.db-base/database :migrations-applied] 0))
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(ig/halt! system)))
      @(promise))))

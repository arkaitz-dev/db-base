(ns demo-tasks.main
  "Boots the host. The order is the point, and Integrant derives it from the
  refs rather than from anything written down: the database is a dependency of
  the ceremony and of the web configuration, so it starts first and — on the way
  down — stops last. A schema that is not there yet cannot be served by a port
  that is already open.

  Configuration is read from a resource this host names, with `#wb/env` for
  anything that must not be committed. `env.local.edn` is web-base's development
  convention: an absent file is not an error, it is the production case. Today
  this host has no secret at all — SQLite needs no password and the session is a
  row rather than a sealed cookie — and the reader is wired anyway, because the
  line that changes is `:jdbc-url` and nothing else."
  (:require [clojure.java.io :as io]
            [demo-tasks.system]
            [dev.arkaitz.web-base.config :as config]
            [dev.arkaitz.web-base.integrant :as wbi]
            [integrant.core :as ig])
  (:gen-class))

(def ^:private env-file "env.local.edn")

(defn config
  "The system map, with `port` overriding the one in the resource when given.

  **Two values move together**, which the first demo in this repository never
  had to care about: the port the server listens on, and the origin the sign-in
  link is built from. auth-base validates the shape of `:base-url` and has no
  way to check it against a server it knows nothing about, so a port given here
  and not there would print links to a door nobody is standing at."
  ([] (config nil))
  ([port]
   (cond-> (wbi/read-string (config/env-file-readers env-file) (slurp (io/resource "config.edn")))
     port (-> (assoc-in [:dev.arkaitz.web-base/server :port] port)
              (assoc-in [:demo-tasks/auth-config :base-url] (str "http://localhost:" port))))))

(defn -main [& [port]]
  (let [parsed (some-> port parse-long)]
    (when (and port (not (<= 1 (or parsed 0) 65535)))
      (binding [*out* *err*]
        (println "demo-tasks: the port must be a number from 1 to 65535, not" (pr-str port)))
      (System/exit 1))
    (let [system (ig/init (config parsed))]
      (println "demo-tasks: serving on port"
               (get-in system [:dev.arkaitz.web-base/server :port])
               "· migrations applied this boot:"
               (get-in system [:dev.arkaitz.db-base/database :migrations-applied] 0))
      (println "demo-tasks: sign-in links are printed here; any address creates an account")
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(ig/halt! system)))
      ;; Jetty does not join, so the process would otherwise exit with the
      ;; server still running.
      @(promise))))

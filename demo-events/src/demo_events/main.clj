(ns demo-events.main
  "Boots the host through web-base's `run!`: the config resource with `#wb/env` for what
  must not be committed, `env.local.edn` read only because this names it (an absent file
  is the production case), a port on the command line put where the server and the
  sign-in link both read it, and the system halted on the way down. Integrant derives the
  order from the refs: the database starts first and stops last, so a schema that is not
  there yet is never served by a port that is already open."
  (:require [demo-events.system]
            [dev.arkaitz.web-base.integrant :as wbi])
  (:gen-class))

(defn -main [& args]
  (wbi/run! {:config    "config.edn"
             :env-file  "env.local.edn"
             :port-path [:demo-events/port]
             :banner    #(str "demo-events: serving on port " (get-in % [:dev.arkaitz.web-base/server :port])
                              " · migrations applied this boot: "
                              (get-in % [:dev.arkaitz.db-base/database :migrations-applied] 0))}
            args))

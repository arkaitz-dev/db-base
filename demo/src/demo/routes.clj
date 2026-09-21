(ns demo.routes
  "What this host serves: a page of notes, the form that adds one, and a health route
  whose answer is `ready?`'s. The database handle is closed over rather than reached for,
  because SPEC §9 says a library holds no ambient state and a host needs none either."
  (:require [demo.handlers :as handlers]
            [demo.views :as views]))

(defn routes [db]
  [["" {:wb/layouts [views/shell-layout]}
    ["/"      {:get  {:handler (partial handlers/home db)}}]
    ["/notes" {:post {:handler (partial handlers/create db)}}]]
   ["/health" {:get {:handler (partial handlers/health db)}}]])

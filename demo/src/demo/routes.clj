(ns demo.routes
  "What this host serves: a page of notes, the form that adds one, a session a visitor
  can name and end, and a health route whose answer is `ready?`'s. The database handle is closed over rather than reached for,
  because SPEC §9 says a library holds no ambient state and a host needs none either."
  (:require [demo.handlers :as handlers]
            [demo.views :as views]))

(defn routes [db]
  [["" {:wb/layouts [views/shell-layout]}
    ["/"            {:get  {:handler (partial handlers/home db)}}]
    ["/notes"       {:post {:handler (partial handlers/create db)}}]
    ;; §8's half of this host: a session that holds something and can be ended on its
    ;; own. Not "log out everywhere" — auth-base §10 revokes by generation and works
    ;; with a cookie too, so that would ask db-base for nothing.
    ["/session"     {:post {:handler (partial handlers/sign-in db)}}]
    ["/session/end" {:post {:handler (partial handlers/end-session db)}}]]
   ["/health" {:get {:handler (partial handlers/health db)}}]])

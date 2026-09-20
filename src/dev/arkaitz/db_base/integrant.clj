(ns dev.arkaitz.db-base.integrant
  "Optional: the Integrant method for a host that uses it (SPEC §10). The only
  namespace of this library that requires integrant; nothing else depends on it, and a
  host that wires by hand calls `dev.arkaitz.db-base/start` and `stop` itself.

  **One key, never two.** The pool and its migrations are one component. Two keys would
  let a host wire the pool and omit the migrations, which deletes §7's guarantee — the
  schema is there before anything serves — with no symptom until the first request meets
  a missing table.

  The key takes exactly the map `start` takes, so a host can write it in EDN — spelled in
  full there, since auto-resolution is the Clojure reader's and not EDN's — with no
  function in it:

      {:dev.arkaitz.db-base/database
       {:jdbc-url \"jdbc:…\" :user \"…\" :password \"…\"
        :pool {:max 10 :timeout-ms 5000}
        :migrations {:dir \"db/migration\" :lock-wait-ms 60000}}}

  and a component that needs the pool refers to it: `#ig/ref :dev.arkaitz.db-base/database`
  hands over the whole handle, whose `:datasource` is the pool and whose
  `:migrations-applied` says what this boot ran — a key that is absent, as §6 has it, when
  the host asked for `:migrations :none`."
  (:require [dev.arkaitz.db-base :as db]
            [integrant.core :as ig]))

(defmethod ig/init-key ::db/database [_ config]
  (db/start config))

(defmethod ig/halt-key! ::db/database [_ handle]
  (db/stop handle))

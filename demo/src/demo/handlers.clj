(ns demo.handlers
  "Request in, response out. Every handler takes the database handle db-base returned,
  which is what a host does with it: hold it, and hand it to whatever needs a connection."
  (:require [clojure.string :as str]
            [demo.notes :as notes]
            [demo.views :as views]
            [dev.arkaitz.web-base.response :as response]
            [dev.arkaitz.web-base.session :as session]))

(defn home [db request]
  (response/ok (views/notes-page (notes/list-notes db) (:migrations-applied db 0) request)))

(defn sign-in
  "Puts a name in the session, through web-base's `rotate`: a fresh session id for the
  one that is about to hold something, which is the defence against fixation. Under a
  server-side store that also asks db-base to delete the old row — with a nil key when
  the visitor was anonymous, which is the ordinary case here."
  [_db request]
  (let [visitor (str/trim (str (get-in request [:params "visitor"])))]
    ;; The form's own 40, so a name cannot grow the session past what the session table
    ;; holds, which the store would refuse as the base's 500.
    (if (or (str/blank? visitor) (< 40 (count visitor)))
      (response/see-other "/")
      (session/rotate (response/see-other "/") {:visitor visitor}))))

(defn end-session
  "Ends THIS session and no other. Under a cookie this cannot be done at all: the sealed
  value the browser holds stays valid forever, and all `delete-session` can do is hand
  back a fresh empty one. Under db-base's store it is a row, and the row goes."
  [_db _request]
  (assoc (response/see-other "/") :session nil))

(defn create
  "Writes the note, unless the parameter is missing or longer than `note.body`'s 200 —
  counted as Java counts a String, which never undercounts PostgreSQL's VARCHAR: SQLite
  would store it whole and PostgreSQL refuse it with a 500, so the host decides first and
  writes nothing."
  [db request]
  (let [body (get-in request [:params "body"])]
    (when (and (string? body) (<= (count body) 200))
      (notes/add-note! db body)))
  (response/see-other "/"))

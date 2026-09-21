(ns demo.views
  "Hiccup, which web-base turns into a document. Plain forms and no htmx: what this demo
  is proving is that a page can be rendered from rows a migration created, and htmx is
  web-base's business, tested in its own repository."
  (:require [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.shell :as shell]))

(defn shell-layout
  "The outermost layout: web-base's shell with this host's slots filled."
  [{:keys [content request]}]
  (shell/page
   {:request request
    :title   "db-base demo"
    :header  (list [:h1 "db-base demo"]
                   [:p "A host that wires web-base and db-base. The notes below live in "
                    [:code "demo.db"] ", created by a migration db-base applied at boot."])
    :content content
    :footer  [:span "db-base"]}))

(defn- note-item [{:keys [id body written_at]}]
  [:li [:span.body body] " " [:small (str "#" id " · " written_at)]])

(defn- visitor-bar
  "Who is writing, and the way to stop being them. The name is decoration; the session's
  lifecycle is the point, and this is where it becomes visible."
  [request]
  (if-let [visitor (get-in request [:session :visitor])]
    [:p.visitor "writing as " [:strong visitor] " · "
     [:form {:method "post" :action "/session/end" :style "display:inline"}
      (security/csrf-field request)
      [:button {:type "submit"} "end this session"]]]
    [:form {:method "post" :action "/session"}
     (security/csrf-field request)
     [:label {:for "visitor"} "Your name"]
     [:input {:id "visitor" :name "visitor" :maxlength "40" :required true
              :placeholder "who is writing"}]
     [:button {:type "submit"} "Start a session"]]))

(defn notes-page [notes migrations-applied request]
  (list
   (visitor-bar request)
   [:form {:method "post" :action "/notes"}
    (security/csrf-field request)
    [:label {:for "body"} "A note"]
    [:input {:id "body" :name "body" :maxlength "200" :required true
             :placeholder "something worth keeping"}]
    [:button {:type "submit"} "Keep it"]]
   [:p (str (count notes) " kept · " migrations-applied
            " migration(s) applied on the boot that is serving you")]
   [:ul#notes (map note-item notes)]))

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

(defn notes-page [notes migrations-applied request]
  (list
   [:form {:method "post" :action "/notes"}
    (security/csrf-field request)
    [:label {:for "body"} "A note"]
    [:input {:id "body" :name "body" :maxlength "200" :required true
             :placeholder "something worth keeping"}]
    [:button {:type "submit"} "Keep it"]]
   [:p (str (count notes) " kept · " migrations-applied
            " migration(s) applied on the boot that is serving you")]
   [:ul#notes (map note-item notes)]))

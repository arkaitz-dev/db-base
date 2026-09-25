(ns demo-tasks.views
  "Hiccup, which web-base turns into a document. Plain forms and no htmx: what
  this host is demonstrating is three libraries meeting, and htmx is web-base's
  business and tested in its own repository.

  The views never see a database handle and never see the ceremony. They are
  handed what to draw, which is what keeps `routes` the only file where the
  three libraries are named together."
  (:require [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.shell :as shell]))

(defn shell-layout
  "The outermost layout: web-base's shell with this host's slots filled."
  [{:keys [content request]}]
  (shell/page
   {:request request
    :title   "Tasks"
    :header  (list [:h1 "Tasks"]
                   [:p "A small application on three libraries: web-base serves it, "
                    "auth-base decides who you are, and db-base opened the database "
                    "and applied the schema before any of it answered a request."])
    :content content
    :footer  (let [subject (:wb/subject request)]
               (if subject
                 [:form {:method "post" :action "/logout" :style "display:inline"}
                  (security/csrf-field request)
                  [:button {:type "submit"} "Log out"]
                  " · "
                  [:button {:type "submit" :formaction "/revoke"}
                   "Log out everywhere"]]
                 [:span "not signed in"]))}))

(defn login
  "The one view auth-base asks this host for. It is handed the request and one
  of four states and returns Hiccup, without auth-base knowing that web-base
  or this layout exist."
  [request {:keys [sent? spent? limited?]}]
  (list
   [:h2 "Sign in"]
   (when sent?
     [:p.ok [:strong "If that address can receive mail, a link is on its way."]
      " In this demo the link is printed to the server's console."])
   (when spent?
     [:p.error [:strong "That link no longer works."] " It is good once, and it expires."])
   (when limited?
     [:p.error [:strong "Too many attempts from here."] " Wait a few minutes and try again."])
   [:form {:method "post" :action "/login"}
    (security/csrf-field request)
    [:label "Email address "
     [:input {:type "email" :name "identifier" :required true :autofocus true
              :placeholder "ada@example.test"}]]
    " "
    [:button {:type "submit"} "Send me a link"]]
   [:p [:small "Any address works: the first time you follow a link, an account is "
        "created for it. The answer is the same whether the address is known or not — "
        "if it were not, this page would be telling strangers who has an account."]]))

(defn- task-item [request {:keys [id body done]}]
  [:li {:class (when (= 1 done) "done")}
   [:form {:method "post" :action (str "/tasks/" id) :style "display:inline"}
    (security/csrf-field request)
    [:input {:type "text" :name "body" :value body :maxlength "200" :required true}]
    [:button {:type "submit"} "Rename"]
    [:button {:type "submit" :formaction (str "/tasks/" id "/toggle")}
     (if (= 1 done) "Not done" "Done")]
    [:button {:type "submit" :formaction (str "/tasks/" id "/delete")} "Delete"]]])

(defn tasks-page
  "Everything this person owns, and nothing else — which is the query's doing
  rather than this page's."
  [request identifier tasks]
  (list
   [:h2 "Your tasks"]
   [:p "Signed in as " [:strong identifier] "."]
   [:form {:method "post" :action "/tasks"}
    (security/csrf-field request)
    [:label "A task "
     [:input {:type "text" :name "body" :maxlength "200" :required true
              :placeholder "something worth doing"}]]
    " "
    [:button {:type "submit"} "Add"]]
   (if (seq tasks)
     [:ul#tasks (map (partial task-item request) tasks)]
     [:p [:em "Nothing yet."]])
   [:p [:a {:href "/sessions"} "Where you are signed in"]]))

(defn- device-item [request current {:keys [session_id user_agent first_seen]}]
  (let [here? (= session_id current)]
    [:li {:class (when here? "current")}
     [:code (subs session_id 0 8)] " · " [:span.agent user_agent]
     " · since " first_seen
     (when here? " · this one")
     " "
     [:form {:method "post" :action (str "/sessions/" session_id "/end") :style "display:inline"}
      (security/csrf-field request)
      [:button {:type "submit"} (if here? "End this one" "End it")]]]))

(defn sessions-page
  "The one page in this application a sealed cookie could not serve. Logging out
  ends the session in front of you and revoking ends all of them; ending *one*
  of several needs the session to be a row somebody else can delete."
  [request devices current]
  (list
   [:h2 "Where you are signed in"]
   [:p "Each of these is a session this host has seen you arrive on. Ending one "
    "leaves the others working — which is only possible because the session is a "
    "row in the database rather than a sealed value in your browser."]
   [:ul#sessions (map (partial device-item request current) devices)]
   [:p [:a {:href "/"} "Back to your tasks"]]))

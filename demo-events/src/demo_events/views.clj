(ns demo-events.views
  "Hiccup for web-base to render, handed what to draw."
  (:require [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.shell :as shell]))

(defn shell-layout [{:keys [content request]}]
  (shell/page
   {:request request
    :title   "Events"
    :header  (list [:h1 [:a {:href "/"} "Events"]]
                   [:p "Places and a waiting list, on three libraries: web-base serves it, "
                    "auth-base decides who you are, db-base keeps count."])
    :content content
    :footer  (if (:wb/subject request)
               [:form {:method "post" :action "/logout"}
                (security/csrf-field request)
                [:button {:type "submit"} "Log out"]]
               [:span "not signed in"])}))

(defn login
  "auth-base's view, in its five states; opening a link is `:confirm?`, one button whose
  POST signs in."
  [request {:keys [sent? spent? limited? confirm? action field]}]
  (if confirm?
    (list [:h2 "Sign in"]
          [:form {:method "post" :action action}
           (security/csrf-field request)
           [:button {:type "submit"} "Sign in"]])
  (list
   [:h2 "Sign in"]
   (when sent? [:p.ok [:strong "A link is on its way."] " In this demo it is printed to the server's console."])
   (when spent? [:p.error [:strong "That link no longer works."]])
   (when limited? [:p.error [:strong "Too many attempts from here."] " Wait a few minutes and try again."])
   [:form {:method "post" :action action}
    (security/csrf-field request)
    [:label "Email address "
     [:input {:type "email" :name field :required true :autofocus true}]]
    " " [:button {:type "submit"} "Send me a link"]])))

(defn- post-form [request action & body]
  (into [:form {:method "post" :action action :style "display:inline"}]
        (cons (security/csrf-field request) body)))

(defn home
  "`form` is what `wb/rerender` hands a refused event back with — the values typed
  and the fields refused — or nil on an ordinary visit."
  [request identifier events {:keys [values errors]}]
  (list
   [:p "Signed in as " [:strong identifier] "."]
   [:h2 "Upcoming"]
   (if (seq events)
     [:ul#events
      (for [{:keys [id title capacity going waiting mine]} events]
        [:li [:a {:href (str "/events/" id)} title] " · " going "/" capacity " going"
         (when (pos? waiting) (str " · " waiting " waiting"))
         (when mine (str " · you: " mine))])]
     [:p [:em "Nothing planned."]])
   [:h2 "New event"]
   (when (contains? errors :event) [:p.error "A title and a capacity from 1 to 1000 are needed."])
   (post-form request "/events"
              [:label "Title " [:input {:type "text" :name "title" :maxlength "120" :required true
                                       :value (:title values)}]]
              " "
              [:label "Places " [:input {:type "number" :name "capacity" :min "1" :max "1000" :required true
                                        :value (:capacity values)}]]
              " " [:button {:type "submit"} "Create"])))

(defn event-page [request {:keys [id title capacity going]} attendees mine owner?]
  (list
   [:h2 title]
   [:p going "/" capacity " going"]
   (case mine
     "going"   [:p "You are going. " (post-form request (str "/events/" id "/leave") [:button {:type "submit"} "Leave"])]
     "waiting" [:p "You are waiting for a place. " (post-form request (str "/events/" id "/leave") [:button {:type "submit"} "Leave the list"])]
     (post-form request (str "/events/" id "/join") [:button {:type "submit"} "I'm going"]))
   [:ol#attendees (for [{:keys [identifier status]} attendees] [:li identifier " · " status])]
   (when owner?
     (post-form request (str "/events/" id "/delete") [:button {:type "submit"} "Delete event"]))))

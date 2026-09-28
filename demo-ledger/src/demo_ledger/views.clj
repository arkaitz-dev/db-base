(ns demo-ledger.views
  "Hiccup for web-base to render. The views are handed what to draw and never see a
  database handle or the ceremony."
  (:require [demo-ledger.money :as money]
            [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.shell :as shell]))

(defn shell-layout [{:keys [content request]}]
  (shell/page
   {:request request
    :title   "Ledger"
    :header  (list [:h1 [:a {:href "/"} "Ledger"]]
                   [:p "Shared expenses on three libraries: web-base serves it, auth-base "
                    "decides who you are, db-base keeps the books."])
    :content content
    :footer  (if (:wb/subject request)
               [:form {:method "post" :action "/logout"}
                (security/csrf-field request)
                [:button {:type "submit"} "Log out"]]
               [:span "not signed in"])}))

(defn login [request {:keys [sent? spent? limited? action field]}]
  (list
   [:h2 "Sign in"]
   (when sent? [:p.ok [:strong "A link is on its way."] " In this demo it is printed to the server's console."])
   (when spent? [:p.error [:strong "That link no longer works."]])
   (when limited? [:p.error [:strong "Too many attempts from here."] " Wait a few minutes and try again."])
   [:form {:method "post" :action action}
    (security/csrf-field request)
    [:label "Email address "
     [:input {:type "email" :name field :required true :autofocus true}]]
    " " [:button {:type "submit"} "Send me a link"]]))

(defn- post-form [request action & body]
  (into [:form {:method "post" :action action}]
        (cons (security/csrf-field request) body)))

(defn home [request identifier groups invitations]
  (list
   [:p "Signed in as " [:strong identifier] "."]
   (when (seq invitations)
     (list [:h2 "Invitations"]
           [:ul#invitations
            (for [{:keys [group_id name]} invitations]
              [:li name " "
               (post-form request (str "/invitations/" group_id "/accept")
                          [:button {:type "submit"} "Join"])])]))
   [:h2 "Your groups"]
   (if (seq groups)
     [:ul#groups (for [{:keys [id name members]} groups]
                   [:li [:a {:href (str "/groups/" id)} name] " · " members " members"])]
     [:p [:em "None yet."]])
   (post-form request "/groups"
              [:label "New group "
               [:input {:type "text" :name "name" :maxlength "80" :required true}]]
              " " [:button {:type "submit"} "Create"])))

(defn group-page
  "`form` is what `wb/rerender` hands a refused expense back with — the values
  typed and the fields refused — or nil on an ordinary visit."
  [request {:keys [id name]} members expenses balances {:keys [values errors]}]
  (list
   [:h2 name]
   [:h3 "Balances"]
   [:table#balances
    [:tbody (for [{:keys [identifier balance]} balances]
              [:tr [:td identifier] [:td.amount (money/format-cents balance)]])]]
   [:h3 "Expenses"]
   (when (contains? errors :amount) [:p.error "That amount is not one this form accepts, such as 12.50."])
   (post-form request (str "/groups/" id "/expenses")
              [:label "What " [:input {:type "text" :name "description" :maxlength "120" :required true
                                     :value (:description values)}]]
              " "
              [:label "Amount " [:input {:type "text" :name "amount" :inputmode "decimal" :required true
                                         :placeholder "12.50" :value (:amount values)}]]
              " " [:button {:type "submit"} "I paid this"])
   (if (seq expenses)
     [:ul#expenses
      (for [{e-id :id :keys [description amount_cents payer_identifier my_share]} expenses]
        [:li description " · " (money/format-cents amount_cents) " paid by " payer_identifier
         " · your share " (money/format-cents my_share) " "
         (post-form request (str "/groups/" id "/expenses/" e-id "/delete")
                    [:button {:type "submit"} "Delete"])])]
     [:p [:em "No expenses yet."]])
   [:h3 "Members"]
   [:ul#members (for [{:keys [identifier]} members] [:li identifier])]
   (post-form request (str "/groups/" id "/invitations")
              [:label "Invite " [:input {:type "email" :name "identifier" :required true}]]
              " " [:button {:type "submit"} "Invite"])))

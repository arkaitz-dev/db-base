(ns demo-ledger.routes-test
  "No route of this host is open except the ones it declares public.

  Read from the tree the running system built its handler from, never from one this
  test builds, and compared with literal sets: a route added later answers here until
  somebody decides whether it is private. The structural half reads the gate each
  compiled endpoint carries — the route's data as reitit merged it from its parents,
  and the method's own map over that — and requires it to be `wb/subject-present?`
  itself, since a child's own gate replaces the group's. The behavioural half watches
  the FIRST hop, because a followed redirect hides who answered, and recognises the
  gate by the headers only its refusal carries: a handler that redirects to the login
  page by itself, as `/revoke` does for a visitor with no subject, sends neither."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hosts.support :as s]
            [demo-ledger.system]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.testing :as wt]
            [reitit.core :as r]
            [reitit.ring :as ring]))

(def ^:private public
  {"/login" [:get :post] "/login/redeem/:token" [:get] "/logout" [:post]})

(def ^:private gated
  {"/" [:get] "/groups" [:post] "/groups/:id" [:get] "/groups/:id/expenses" [:post]
   "/groups/:id/expenses/:expense/delete" [:post] "/groups/:id/invitations" [:post]
   "/invitations/:id/accept" [:post]})

(defn- endpoints
  "`{path {method gate}}` for every method the host declared on a route — never the
  OPTIONS reitit answers by itself — with the gate its compiled endpoint carries. A
  route-level `:handler` answers every method nobody declared, so it counts as `:any`,
  its gate read from the endpoint of such a method."
  [tree]
  (into {}
        (for [[path data result] (r/compiled-routes (ring/router tree))]
          [path (cond-> (into {}
                              (for [method (filter ring/http-methods (keys data))]
                                [method (get-in result [method :data :wb/gate])]))
                  (contains? data :handler)
                  (assoc :any (get-in result [(first (remove #(contains? data %) (sort ring/http-methods)))
                                              :data :wb/gate])))])))

(defn- where
  "`{path [methods]}` of the endpoints whose gate satisfies `pred`."
  [pred endpoints]
  (into {}
        (for [[path by-method] endpoints
              :let  [methods (vec (sort (for [[method gate] by-method :when (pred gate)] method)))]
              :when (seq methods)]
          [path methods])))

(defn- the-gate? [gate] (identical? wb/subject-present? gate))

(deftest every-route-of-this-host-is-gated-except-the-ones-it-declares-public
  (s/with-system [system path]
    (let [web  (get system :demo-ledger/web-config)
          app  (get system :dev.arkaitz.web-base/handler)
          tree (:routes web)]
      (is (= public (where nil? (endpoints tree)))
          "the endpoints without a gate are exactly the declared public ones: an extra one is open to anyone, a missing one moved")
      (is (= {} (where #(and (some? %) (not (the-gate? %))) (endpoints tree)))
          "no endpoint carries a gate other than wb/subject-present?, which could admit anyone")
      (is (= gated (where the-gate? (endpoints tree)))
          "the gated endpoints are exactly these; a new one must be added here on purpose")
      (is (= #{"/health"} (set (keys (:sessionless web))))
          "nothing but the probe is answered before the session, where no gate can reach")
      (is (nil? (:static web)) "no static root serves files outside the router")
      (let [b (s/browser)]
        (s/sign-in! app path b "someone@example.com")
        (is (= [200 "/"] (s/landed app b "/"))
            "control: the gate opens for a subject, so the refusals below are the gate's"))
      (let [b (s/browser)]
        (is (= 200 (:status (s/hop app b :get "/login"))) "control: a public page answers the anonymous")
        (is (= [303 "/login?ab=spent"] ((juxt :status #(get-in % [:headers "Location"])) (s/hop app b :get "/login/redeem/x")))
            "control: a public route runs its own handler for the anonymous"))
      (doseq [[path methods] gated
              method         methods]
        (let [b        (s/browser)
              _        (s/GET app b "/login")
              response (s/hop app b method (str/replace path #":[a-z]+" "x"))]
          (is (and (= 303 (:status response)) (wt/gate-refusal? response "/login"))
              (str (name method) " " path ": expected the gate's refusal on the first hop, got "
                   (:status response) " " (select-keys (:headers response) ["Location" "Cache-Control" "Vary"])
                   " — a 403 means CSRF refused it before any gate, a 303 without the refusal's headers"
                   " that the handler answered, a 500 that something threw")))))))

(ns demo-ledger.bounds-test
  "The widths of this host's text columns are enforced by the host, before any statement:
  SQLite stores an over-long value whole, so without this check the development engine
  accepts what PostgreSQL refuses with a 500. Each field: one character past its column
  writes nothing, and exactly its column's width is written — the control that the POST
  reached the handler at all, and that the bound is not one short."
  (:require [clojure.test :refer [deftest is]]
            [demo-ledger.support :as s :refer [with-host]]
            [demo-ledger.system]))

(defn- text [n] (apply str (repeat n "x")))

(defn- address [n] (str (text (- n (count "@x.test"))) "@x.test"))

(deftest group-names-descriptions-and-invitations-past-their-columns-write-nothing
  (with-host [app path]
    (let [b (s/browser)]
      (s/sign-in! app path b "ada@example.test")
      (s/POST app b "/groups" {"name" (text 81)})
      (is (= [] (s/rows path "SELECT name FROM ledger_group")) "a group name of 81 characters: no group")
      (s/POST app b "/groups" {"name" (text 80)})
      (is (= [[(text 80)]] (s/rows path "SELECT name FROM ledger_group")) "control: 80, written whole")
      (let [g (s/one path "SELECT id FROM ledger_group")]
        (is (= 422 (:status (s/POST app b (str "/groups/" g "/expenses") {"description" (text 121) "amount" "1.00"})))
            "a description of 121 characters is refused as a missing one is, with the form again")
        (is (= [] (s/rows path "SELECT description FROM expense")) "and no expense is written")
        (s/POST app b (str "/groups/" g "/expenses") {"description" (text 120) "amount" "1.00"})
        (is (= [[(text 120)]] (s/rows path "SELECT description FROM expense")) "control: 120, written whole")
        (s/GET app b (str "/groups/" g))
        (s/POST app b (str "/groups/" g "/invitations") {"identifier" (address 321)})
        (is (= [] (s/rows path "SELECT identifier FROM invitation")) "an address of 321 characters invites nobody")
        (s/GET app b (str "/groups/" g))
        (let [lengthening (str "\u0130" (subs (address 320) 1))]
          (s/POST app b (str "/groups/" g "/invitations") {"identifier" lengthening})
          (is (= [320 []] [(count lengthening) (s/rows path "SELECT identifier FROM invitation")])
              "320 as typed and 321 once lower-cased, which is what would be stored: bounded after normalising"))
        (s/GET app b (str "/groups/" g))
        (s/POST app b (str "/groups/" g "/invitations") {"identifier" (address 320)})
        (is (= [[(address 320)]] (s/rows path "SELECT identifier FROM invitation")) "control: 320, invited")))))

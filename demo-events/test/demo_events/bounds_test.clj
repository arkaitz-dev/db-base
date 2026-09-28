(ns demo-events.bounds-test
  "The widths of this host's text columns are enforced by the host, before any statement:
  SQLite stores an over-long value whole, so without this check the development engine
  accepts what PostgreSQL refuses with a 500. Each field: one character past its column
  writes nothing, and exactly its column's width is written — the control that the POST
  reached the handler at all, and that the bound is not one short."
  (:require [clojure.test :refer [deftest is]]
            [hosts.support :as s :refer [with-host]]
            [demo-events.system]))

(defn- text [n] (apply str (repeat n "x")))

(deftest an-event-title-past-its-column-writes-nothing-and-one-as-wide-is-written
  (with-host [app path]
    (let [b (s/browser)]
      (s/sign-in! app path b "ada@example.test")
      (is (= 422 (:status (s/POST app b "/events" {"title" (text 121) "capacity" "5"})))
          "a title of 121 characters is refused as a missing one is, with the form again")
      (is (= [] (s/rows path "SELECT title FROM event")) "and no event is written")
      (s/POST app b "/events" {"title" (text 120) "capacity" "5"})
      (is (= [[(text 120)]] (s/rows path "SELECT title FROM event")) "control: 120, written whole"))))

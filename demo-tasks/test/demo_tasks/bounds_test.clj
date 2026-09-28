(ns demo-tasks.bounds-test
  "The widths of this host's text columns are enforced by the host, before any statement:
  SQLite stores an over-long value whole, so without this check the development engine
  accepts what PostgreSQL refuses with a 500. Each field: one character past its column
  writes nothing, and exactly its column's width is written — the control that the POST
  reached the handler at all, and that the bound is not one short."
  (:require [clojure.test :refer [deftest is]]
            [hosts.support :as s :refer [with-host]]
            [demo-tasks.system]))

(defn- text [n] (apply str (repeat n "x")))

(deftest a-task-body-past-its-column-writes-nothing-and-one-as-wide-is-written
  (with-host [app path]
    (let [b (s/browser)]
      (s/sign-in! app path b "ada@example.test")
      (s/POST app b "/tasks" {"body" (text 201)})
      (is (= [] (s/rows path "SELECT body FROM task")) "201 characters: no task")
      (s/POST app b "/tasks" {"body" (text 200)})
      (is (= [[(text 200)]] (s/rows path "SELECT body FROM task")) "control: 200 characters, written whole")
      (let [id (s/one path "SELECT id FROM task")]
        (s/POST app b (str "/tasks/" id) {"body" (text 201)})
        (is (= [[(text 200)]] (s/rows path "SELECT body FROM task")) "a rename to 201 characters changes nothing")
        (s/POST app b (str "/tasks/" id) {"body" "short"})
        (is (= [["short"]] (s/rows path "SELECT body FROM task")) "control: a rename within the width is applied")))))

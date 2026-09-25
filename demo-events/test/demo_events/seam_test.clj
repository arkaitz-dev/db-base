(ns demo-events.seam-test
  "The host through its own `config.edn`, driven by browsers of the test's own and
  read back through a second connection."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [demo-events.support :as support
             :refer [GET POST browser location sign-in! with-host]]
            [demo-events.system]))

(defn- status-of [path ev who]
  (support/one path "SELECT r.status FROM rsvp r JOIN account a ON a.subject = r.subject
                     WHERE r.event_id = ? AND a.identifier = ?" ev who))

(deftest the-events-through-http
  (with-host [app path]
    (let [ada (browser)
          bob (browser)]
      (sign-in! app path ada "ada@example.test")
      (let [t0      (System/currentTimeMillis)
            created (POST app ada "/events" {"title" "Dinner" "capacity" "1"})
            t1      (System/currentTimeMillis)
            ev      (support/one path "SELECT id FROM event")
            page    (str "/events/" ev)]
        (is (= page (location created)) "creating an event lands on its page")
        (is (<= t0 (support/one path "SELECT starts_at FROM event") t1) "and it starts when it was created")
        (GET app ada page)
        (is (= page (location (POST app ada (str page "/join") {}))))
        (is (= "going" (status-of path ev "ada@example.test")) "ada took the one place")
        (sign-in! app path bob "bob@example.test")
        (is (= 200 (:status (GET app bob page))) "witness: bob is signed in and sees the event")
        (is (= page (location (POST app bob (str page "/join") {}))))
        (is (= "waiting" (status-of path ev "bob@example.test")) "bob waits")
        (is (str/includes? (str (:body (GET app bob page))) "You are waiting for a place"))
        (let [home (str (:body (GET app bob "/")))]
          (is (str/includes? home "1/1 going · 1 waiting · you: waiting"))
          (is (= 1 (count (re-seq #"Dinner" home))) "the event is listed once, not once per answer"))
        (is (not (str/includes? (str (:body (GET app bob page))) "Delete event")) "bob does not own it and is offered no delete")
        (is (str/includes? (str (:body (GET app ada page))) "Delete event") "ada, who owns it, is")
        (GET app ada page)
        (is (= page (location (POST app ada (str page "/leave") {}))))
        (is (= "going" (status-of path ev "bob@example.test")) "when ada leaves, bob has the place")
        (is (str/includes? (str (:body (GET app bob page))) "You are going."))
        (is (str/includes? (str (:body (GET app bob "/"))) "1/1 going · you: going")
            "and home says so, with no waiting count when nobody waits")
        (is (= [[1 1]] (support/rows path "SELECT going, (SELECT COUNT(*) FROM rsvp WHERE status = 'going') FROM event")))
        (testing "an event the form does not accept goes back and writes nothing"
          (doseq [params [{"title" "X" "capacity" "0"} {"title" "X" "capacity" "1001"}
                          {"title" "X" "capacity" "abc"} {"title" "" "capacity" "3"}]]
            (GET app ada "/")
            (is (= "/?error=event" (location (POST app ada "/events" params))) (pr-str params)))
          (is (= 1 (support/one path "SELECT COUNT(*) FROM event")))
          (is (str/includes? (str (:body (GET app ada "/?error=event"))) "from 1 to 1000")))
        (testing "an event that does not exist is not found"
          (let [nowhere (str "/events/" (random-uuid))]
            (is (= 404 (:status (GET app ada nowhere))))
            (GET app ada "/")
            (is (= 404 (:status (POST app ada (str nowhere "/join") {}))))
            (is (= "/login" (location (GET app (browser) page))) "control: nobody signed in meets the gate first")))
        (testing "only the owner's delete removes the event, with its answers"
          (GET app bob page)
          (POST app bob (str page "/delete") {})
          (is (= 1 (support/one path "SELECT COUNT(*) FROM event")) "bob's delete removed nothing")
          (GET app ada page)
          (is (= "/" (location (POST app ada (str page "/delete") {}))))
          (is (= [[0] [0]] [[(support/one path "SELECT COUNT(*) FROM event")]
                            [(support/one path "SELECT COUNT(*) FROM rsvp")]])
              "ada's did, and the answers went with it"))))))

(ns demo-events.seam-test
  "The host through its own `config.edn`, driven by browsers of the test's own and
  read back through a second connection."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [demo-events.support :as support
             :refer [GET POST browser landed posted sign-in! with-host]]
            [demo-events.system]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]))

(defn- status-of [path ev who]
  (support/one path "SELECT r.status FROM rsvp r JOIN account a ON a.subject = r.subject
                     WHERE r.event_id = ? AND a.identifier = ?" ev who))

(deftest the-events-through-http
  (with-host [app path]
    (let [ada (browser)
          bob (browser)]
      (sign-in! app path ada "ada@example.test")
      (let [t0      (System/currentTimeMillis)
            created (posted app ada "/events" {"title" "Dinner" "capacity" "1"})
            t1      (System/currentTimeMillis)
            ev      (support/one path "SELECT id FROM event")
            page    (str "/events/" ev)]
        (is (= [200 page] created) "creating an event lands on its page")
        (is (<= t0 (support/one path "SELECT starts_at FROM event") t1) "and it starts when it was created")
        (GET app ada page)
        (is (= [200 page] (posted app ada (str page "/join") {})))
        (is (= "going" (status-of path ev "ada@example.test")) "ada took the one place")
        (sign-in! app path bob "bob@example.test")
        (is (= [200 page] (landed app bob page)) "witness: bob is signed in and sees the event")
        (is (= [200 page] (posted app bob (str page "/join") {})))
        (is (= "waiting" (status-of path ev "bob@example.test")) "bob waits")
        (is (str/includes? (str (:body (GET app bob page))) "You are waiting for a place"))
        (let [home (str (:body (GET app bob "/")))]
          (is (str/includes? home "1/1 going · 1 waiting · you: waiting"))
          (is (= 1 (count (re-seq #"Dinner" home))) "the event is listed once, not once per answer"))
        (is (not (str/includes? (str (:body (GET app bob page))) "Delete event")) "bob does not own it and is offered no delete")
        (is (str/includes? (str (:body (GET app ada page))) "Delete event") "ada, who owns it, is")
        (GET app ada page)
        (is (= [200 page] (posted app ada (str page "/leave") {})))
        (is (= "going" (status-of path ev "bob@example.test")) "when ada leaves, bob has the place")
        (is (str/includes? (str (:body (GET app bob page))) "You are going."))
        (is (str/includes? (str (:body (GET app bob "/"))) "1/1 going · you: going")
            "and home says so, with no waiting count when nobody waits")
        (is (= [[1 1]] (support/rows path "SELECT going, (SELECT COUNT(*) FROM rsvp WHERE status = 'going') FROM event")))
        (testing "an event the form does not accept is home again, 422, with what was typed"
          (doseq [params [{"title" "X" "capacity" "0"} {"title" "X" "capacity" "1001"}
                          {"title" "X" "capacity" "abc"} {"title" "" "capacity" "3"}]]
            (GET app ada "/")
            (is (= [422 "/events"] (posted app ada "/events" params)) (pr-str params))
            (let [body (str (:body (:response @ada)))]
              (is (str/includes? body "from 1 to 1000") (str "saying why: " (pr-str params)))
              (is (str/includes? body (str "value=\"" (get params "capacity") "\""))
                  (str "with the capacity typed back in its field: " (re-seq #"<input[^>]*>" body)))
              (is (str/includes? body "Dinner") "on home itself, the events listed")))
          (is (= 1 (support/one path "SELECT COUNT(*) FROM event")) "and nothing was written")
          (is (not (str/includes? (str (:body (GET app ada "/"))) "from 1 to 1000"))
              "control: an ordinary visit carries no error"))
        (testing "an event that does not exist is not found"
          (let [nowhere (str "/events/" (random-uuid))]
            (is (= 404 (:status (GET app ada nowhere))))
            (GET app ada "/")
            (is (= 404 (:status (POST app ada (str nowhere "/join") {}))))
            (is (= [200 "/login"] (landed app (browser) page)) "control: nobody signed in meets the gate first")))
        (testing "only the owner's delete removes the event, with its answers"
          (GET app bob page)
          (POST app bob (str page "/delete") {})
          (is (= 1 (support/one path "SELECT COUNT(*) FROM event")) "bob's delete removed nothing")
          (GET app ada page)
          (is (= [200 "/"] (posted app ada (str page "/delete") {})))
          (is (= [[0] [0]] [[(support/one path "SELECT COUNT(*) FROM event")]
                            [(support/one path "SELECT COUNT(*) FROM rsvp")]])
              "ada's did, and the answers went with it"))))))

(deftest the-sixth-sign-in-from-one-address-shows-the-login-page-saying-why
  (with-host [app path]
    (let [jar (browser)]
      (GET app jar "/login")
      (dotimes [_ 5] (POST app jar "/login" {"identifier" "ada@example.test"}))
      (is (str/includes? (str (:body (:response @jar))) "A link is on its way")
          "control: the fifth was still accepted, so the refusal below is the sixth's")
      (let [refused (POST app jar "/login" {"identifier" "ada@example.test"})
            wait    (some-> (get-in refused [:headers "Retry-After"]) parse-long)]
        (is (= 429 (:status refused)) "the sixth is refused")
        (is (str/includes? (str (:body refused)) "Too many attempts from here")
            (str "with this host's login page saying why: " (:body refused)))
        (is (and wait (< 0 wait) (<= wait 900)) (str "and Retry-After within the window: " wait))
        (is (= [[5]] (support/rows path "SELECT COUNT(*) FROM login_challenge")) "and no challenge was stored for it")))))

(deftest a-copy-of-auth-bases-tables-that-drifted-stops-the-boot-naming-the-column
  (let [path (support/temp-db-path)]
    (try
      (ig/halt! (ig/init (support/host-config path) [:dev.arkaitz.web-base/handler]))
      (jdbc/execute! (support/datasource path) ["ALTER TABLE login_challenge RENAME COLUMN expires_at TO expiry"])
      (let [e      (try (ig/init (support/host-config path) [:dev.arkaitz.web-base/handler]) nil
                        (catch clojure.lang.ExceptionInfo e e))
            causes (take-while some? (iterate ex-cause e))]
        (when-let [partial (:system (ex-data e))] (ig/halt! partial))
        (is (some? e) "the second boot was refused")
        (is (some #(str/includes? (str/lower-case (str (ex-message %))) "expires_at") causes)
            (str "by a cause naming the missing column: " (mapv ex-message causes)))
        (is (= :demo-events/auth-config (:key (ex-data e))) "at the key that builds the store"))
      (finally (support/delete-db! path)))))

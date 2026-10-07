(ns demo-tasks.support-test
  "The shared test browser's own contract (hosts-test/hosts/support.clj): a browser keeps
  the handler it was made with, so one used with another is refused rather than
  answering from the first in silence (sendmail-base/example FRICTION E7), and a request
  is varied by `opts`, which do reach the handler."
  (:require [clojure.test :refer [deftest is]]
            [hosts.support :as support :refer [GET POST browser hop]]))

(defn- recording-app
  "A handler answering 200 and noting, per call, its name and the request's address."
  [calls the-name]
  (fn [request]
    (swap! calls conj [the-name (:remote-addr request)])
    {:status 200 :headers {"Content-Type" "text/plain"} :body "ok"}))

(deftest a-browser-used-with-another-handler-is-refused--not-answered-by-the-first
  (let [calls  (atom [])
        first  (recording-app calls :first)
        second (recording-app calls :second)
        jar    (browser)]
    (is (= 200 (:status (GET first jar "/"))) "witness: the browser is made over the first")
    (is (= "this browser was made over another handler: pass the same app, or vary the request with opts"
           (try (GET second jar "/") nil (catch clojure.lang.ExceptionInfo e (ex-message e))))
        "a second handler is refused, naming why")
    (doseq [[label f] [["POST" #(POST second jar "/" {})] ["hop" #(hop second jar :get "/")]]]
      (is (thrown? clojure.lang.ExceptionInfo (f)) (str label " refuses it too")))
    (is (= [[:first "127.0.0.1"]] @calls) "and the second handler was never called — nor the first again")
    (is (= 200 (:status (GET first jar "/"))) "control: the first goes on working")))

(deftest a-request-is-varied-by-opts--which-reach-the-handler
  (let [calls (atom [])
        app   (recording-app calls :app)
        jar   (browser)]
    (GET app jar "/" {:remote-addr "10.0.0.1"})
    (hop app jar :get "/" {:remote-addr "10.0.0.2"})
    (is (= [[:app "10.0.0.1"] [:app "10.0.0.2"]] @calls) "each request arrives from the address it was given")))

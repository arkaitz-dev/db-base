(ns demo-events.routes
  "Where the three libraries meet: auth-base's routes, this host's, one layout."
  (:require [clojure.string :as str]
            [demo-events.events :as events]
            [demo-events.views :as views]
            [dev.arkaitz.auth-base :as auth]
            [dev.arkaitz.auth-base.jdbc :as auth-jdbc]
            [dev.arkaitz.db-base :as db]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.error :as error]
            [dev.arkaitz.web-base.response :as response]))

(defn- subject [request] (:wb/subject request))

(defn- param [request k] (some-> (get-in request [:params k]) str/trim not-empty))

(defn- event-id [request] (get-in request [:path-params :id]))

(defn- home [db request]
  (let [s (subject request)]
    (response/ok (views/home request (auth-jdbc/identifier-for (:datasource db) s) (events/upcoming db s)
                             (:wb/form request)))))

(defn- create-event [db request]
  (let [title    (param request "title")
        capacity (some-> (param request "capacity") parse-long)]
    (if (and title capacity (<= 1 capacity 1000))
      (response/see-other (str "/events/" (events/create-event! db (subject request) title capacity
                                                                (System/currentTimeMillis))))
      ;; Home again, with what was typed and why — a 422 at this URL, where a
      ;; redirect would have lost the values.
      (wb/rerender request "/" {:values {:title    (param request "title")
                                         :capacity (param request "capacity")}
                                :errors #{:event}}))))

(defn- event-page [db request]
  (let [s  (subject request)
        id (event-id request)]
    (if-let [event (events/event-for db id)]
      (response/ok (views/event-page request event (events/attendees db id)
                                     (:mine (first (filter #(= id (:id %)) (events/upcoming db s))))
                                     (= s (:owner event))))
      (error/throw! {:status 404}))))

(defn- join [db request]
  (if (events/join! db (subject request) (event-id request))
    (response/see-other (str "/events/" (event-id request)))
    (error/throw! {:status 404})))

(defn- leave [db request]
  (events/leave! db (subject request) (event-id request))
  (response/see-other (str "/events/" (event-id request))))

(defn- delete-event [db request]
  (events/delete-event! db (subject request) (event-id request))
  (response/see-other "/"))

(defn- health [db _]
  (if (db/ready? db 2)
    {:status 200 :headers {"content-type" "text/plain"} :body "ok"}
    {:status 503 :headers {"content-type" "text/plain"} :body "the database is not answering"}))

(defn routes [db ceremony]
  [["" {:wb/layouts [views/shell-layout]}
    (into (auth/routes ceremony {:view        views/login
                                 :login-path  "/login"
                                 :logout-path "/logout"
                                 :rate-limit  {:limit 5 :window-ms (* 15 60 1000)}})
          [["" {:wb/gate wb/subject-present?}
            ["/" {:get {:handler (partial home db)}}]
            ["/events" {:post {:handler (partial create-event db)}}]
            ["/events/:id" {:get {:handler (partial event-page db)}}]
            ["/events/:id/join" {:post {:handler (partial join db)}}]
            ["/events/:id/leave" {:post {:handler (partial leave db)}}]
            ["/events/:id/delete" {:post {:handler (partial delete-event db)}}]]])]])

(defn sessionless
  "What web-base answers before the session: a probe must not need one, and with a
  session in a row it could not get its answer out when the pool was down."
  [db]
  {"/health" (partial health db)})

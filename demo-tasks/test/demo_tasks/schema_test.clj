(ns demo-tasks.schema-test
  "The host's schema, proved by booting the library that applies it and then
  reading the database through a connection of this test's own. Nothing here
  goes through web-base or Integrant: at this point the host is two SQL files
  and a claim that db-base will find them, and a test that needed the whole
  system to say so would be testing the wrong thing."
  (:require [clojure.test :refer [deftest is]]
            [hosts.support :refer [config delete-db! rows temp-db-path]]
            [dev.arkaitz.db-base :as db])
  (:import [clojure.lang ExceptionInfo]))

(deftest the-hosts-tables-and-the-librarys-own-arrive-on-the-first-boot-and-neither-run-repeats
  (let [path (temp-db-path)]
    (try
      (let [first-boot (db/start (config path))]
        (is (= 2 (:migrations-applied first-boot))
            "the host's own two migrations were found under its classpath prefix and applied")
        (is (= {"auth_base_migrations" 6} (:library-migrations-applied first-boot))
            "auth-base's six came from its jar, through :libraries, and were not copied here")
        (is (= 1 (:session-migrations-applied first-boot))
            (str "and this library's own ran too, counted apart — a handle that added the two"
                 " would say 9 here, and a host reporting its own schema would be wrong"))
        (db/stop first-boot))
      (is (= [["account"] ["account_generation"] ["auth_base_migrations"] ["db_base_migration_lock"] ["db_base_migrations"]
              ["db_base_sessions"] ["device"] ["login_attempt"] ["login_challenge"] ["ragtime_migrations"] ["task"]]
             (rows path "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name"))
          "every table, the host's beside the library's, each run recorded in a control table of its own")
      (is (= [[["001-sessions"]]
              [["001-accounts"] ["002-generations"] ["003-challenges"] ["004-challenge-identifier"]
               ["005-login-attempts"] ["006-login-attempt-expiry"]]
              [["004-tasks"] ["005-devices"]]]
             [(rows path "SELECT id FROM db_base_migrations ORDER BY id")
              (rows path "SELECT id FROM auth_base_migrations ORDER BY id")
              (rows path "SELECT id FROM ragtime_migrations ORDER BY id")])
          (str "recorded apart, which is what makes a host's own reset survivable: the library's"
               " schema would outlive a host that dropped ragtime_migrations"))
      (let [second-boot (db/start (config path))]
        (is (= 0 (:migrations-applied second-boot))
            "a second boot applies none of the host's")
        (is (= 0 (:session-migrations-applied second-boot))
            "and none of the library's")
        (is (= {"auth_base_migrations" 0} (:library-migrations-applied second-boot))
            "and none of auth-base's")
        (db/stop second-boot))
      (is (= [[0]] (rows path "SELECT COUNT(*) FROM db_base_migration_lock"))
          "and both boots gave their lock rows back, so a third would not wait on a ghost")
      (finally (delete-db! path)))))

(deftest a-prefix-nobody-wrote-is-refused-rather-than-reported-as-a-schema
  ;; The trap db-base's own notes name: a migration run that applies zero and
  ;; says success is a schema one deploy behind, reporting itself as a bug
  ;; somewhere else entirely, hours later. This host's prefix is a string in a
  ;; config file, so a typo in it is a realistic way to reach exactly that.
  (let [path (temp-db-path)]
    (try
      (is (thrown-with-msg? ExceptionInfo #"(?i)migration"
                            (db/start (assoc-in (config path) [:migrations :dir] "demo-tasks/migrationz")))
          "a prefix holding nothing is refused at boot, not applied as an empty schema")
      (is (= [] (rows path "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'task'"))
          "and nothing of the host's was created, so the refusal came before any of it ran")
      (finally (delete-db! path)))))

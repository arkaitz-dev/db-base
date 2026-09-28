(ns demo-tasks.tasks-test
  "Ownership, proved where it is actually enforced.

  **No test through HTTP can prove this.** A handler that checked the owner and
  a statement that carries the owner in its WHERE clause refuse the same request
  with the same status and the same page; the difference only appears to the
  next caller of these functions, who has no handler around them. So the
  assertions here go straight at the data functions, and the end-to-end half —
  which proves the route layer does not leak what the SQL protects — belongs to
  the seam test and is a different claim."
  (:require [clojure.test :refer [deftest is testing]]
            [hosts.support :as support :refer [with-db]]
            [demo-tasks.tasks :as tasks]))

(def ^:private ada "11111111-1111-1111-1111-111111111111")
(def ^:private bob "22222222-2222-2222-2222-222222222222")

(deftest a-task-belongs-to-one-subject-and-every-statement-says-so
  (with-db [db path]
    (let [ada-task (tasks/add-task! db ada "water the plants")
          bob-task (tasks/add-task! db bob "call the plumber")]
      (testing "the rows exist and are owned as written, read through the test's own connection"
        (is (= [[ada-task ada "water the plants"] [bob-task bob "call the plumber"]]
               (support/rows path "SELECT id, subject, body FROM task ORDER BY subject"))
            "precondition: two tasks, one each — without this the refusals below prove nothing"))

      (testing "a read sees only its own"
        (is (= ["water the plants"] (mapv :body (tasks/list-tasks db ada)))
            "ada sees hers")
        (is (= ["call the plumber"] (mapv :body (tasks/list-tasks db bob)))
            "bob sees his, which is what says the filter is the query and not the caller"))

      (testing "every write refuses another subject's id, and the same call succeeds for the owner"
        ;; The two halves are on the SAME id in the SAME test on purpose: a
        ;; function that returned 0 for everything would pass the refusals alone.
        ;; Each caller renames to a body naming itself, so the row afterwards
        ;; says WHO wrote it rather than only that something was written.
        (doseq [[label f] [["rename" (fn [who] (tasks/rename-task! db who bob-task (str "renamed by " who)))]
                           ["toggle" (fn [who] (tasks/toggle-task! db who bob-task))]]]
          (is (= 0 (f ada))
              (str "ada cannot " label " bob's task: zero rows changed"))
          (is (= 1 (f bob))
              (str "and bob can " label " his own, on that very id — so the zero above is"
                   " the owner predicate and not a statement that never works")))

        (is (= [[bob-task bob (str "renamed by " bob) 1]]
               (support/rows path "SELECT id, subject, body, done FROM task WHERE subject = ?" bob))
            (str "and bob's row carries what BOB wrote, not what ada attempted — a refusal"
                 " that wrote first and refused afterwards would leave ada's text here"))
        (is (= [] (support/rows path "SELECT id FROM task WHERE body = ?" (str "renamed by " ada)))
            "and ada's attempted text is nowhere in the table at all")

        (is (= 0 (tasks/delete-task! db ada bob-task))
            "ada cannot delete bob's task either")
        (is (= [[1]] (support/rows path "SELECT COUNT(*) FROM task WHERE id = ?" bob-task))
            "and it is still there")
        (is (= 1 (tasks/delete-task! db bob bob-task))
            "while bob can delete his own")
        (is (= [] (support/rows path "SELECT id FROM task WHERE id = ?" bob-task))
            "and then it is gone")))))

(deftest toggling-twice-returns-a-task-to-where-it-started--in-one-statement-each-time
  ;; `SET done = 1 - done` rather than a read and a write: two statements would
  ;; let the owner's own two tabs race and leave the flag where neither asked
  ;; for it. What this can observe is that the value is computed from the row
  ;; rather than from something the caller carried in.
  (with-db [db path]
    (let [id (tasks/add-task! db ada "post the letter")]
      (is (= [[0]] (support/rows path "SELECT done FROM task WHERE id = ?" id))
            "precondition: a new task is not done")
      (is (= 1 (tasks/toggle-task! db ada id)) "the first toggle changes one row")
      (is (= [[1]] (support/rows path "SELECT done FROM task WHERE id = ?" id))
          "and the row says done")
      (is (= 1 (tasks/toggle-task! db ada id)) "the second changes one row too")
      (is (= [[0]] (support/rows path "SELECT done FROM task WHERE id = ?" id))
          "and the row is back where it began, which a statement that wrote a constant could not do"))))

(deftest a-task-nobody-owns-is-answered-exactly-as-one-somebody-else-owns
  ;; Telling "not yours" from "not there" answers, for anyone who can guess an
  ;; id, whether that id is real.
  (with-db [db _path]
    (let [bob-task (tasks/add-task! db bob "renew the insurance")
          absent   "00000000-0000-0000-0000-000000000000"]
      (is (= [(tasks/rename-task! db ada bob-task "x") (tasks/toggle-task! db ada bob-task)
              (tasks/delete-task! db ada bob-task)]
             [(tasks/rename-task! db ada absent "x")   (tasks/toggle-task! db ada absent)
              (tasks/delete-task! db ada absent)])
          "somebody else's id and an id that never existed are answered identically")
      (is (= [0 0 0] [(tasks/rename-task! db ada absent "x") (tasks/toggle-task! db ada absent)
                      (tasks/delete-task! db ada absent)])
          "and the answer is zero rows changed, not a throw and not a truthy map"))))

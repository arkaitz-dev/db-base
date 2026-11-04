(ns dev.arkaitz.db-base.value
  "Values the engines answer differently for the same column, made one (SPEC §10).
  No statement and no driver: a function here takes what a row already holds.")

(defn bool
  "A BOOLEAN column's value, or a comparison's, as a boolean. PostgreSQL's driver answers
  a Boolean and SQLite's an Integer, 1 or 0, measured on both — and 0 is truthy in
  Clojure, so a column read as it comes says true for every row on SQLite, the engine a
  host develops on. Anything else is refused: a NULL or a 2 here is a schema other than
  the one the caller was written for, and guessing would hide it."
  [v]
  (cond (instance? Boolean v)      v
        (and (integer? v) (= 1 v)) true
        (and (integer? v) (= 0 v)) false
        :else (throw (ex-info "db-base: not a boolean column's value" {:value v}))))

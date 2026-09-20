(ns dev.arkaitz.db-base.structure-test
  "Properties of SPEC §3, §5 and §9 that no behavioural test can see, so a scan is
  the only signal any of them will ever produce. Each scan is narrower than the
  rule it serves — it reads what the sources spell, not every way a name can be
  built at run time — and each asserts first that it reached the real sources.

  **What src/ reaches (§3).** A consumer receives a pool and a migration runner. A
  `require` of `next.jdbc` or of `clojure.java.data` — both already on the
  classpath, dragged by ragtime — compiles, loads and passes every other test, and
  makes a transitive dependency part of what this library is. A `clojure.*` name
  is the language only when the Clojure jar itself serves it; the contrib
  libraries share the prefix and are not the language.

  **What src/ never looks for (§5).** A deny-list of the spellings with which
  Clojure code looks for a file or a resource by name — `clojure.java.io`, `slurp`,
  `spit`, `file-seq`, `java.io.File` with its input and output streams, reader and
  writer, `RandomAccessFile`, `java.nio.file`, the `getResource` family written as
  `.m`, `Class/.m`, in a dot form or through `memfn`, `ResourceBundle` and its loaders —
  plus environment and system-property reads and writes, and string literals naming a
  configuration file. A green means none of those spellings appears in src, not that
  src can open nothing. Known and not covered, among others: a static method called as
  if on an instance, `(.getSystemResourceAsStream ClassLoader …)` or `(.getenv System …)`,
  `java.net.URL/openStream`, `ProcessBuilder` and `clojure.java.shell`,
  a `PrintWriter`, `PrintStream` or `Formatter` given a file name, `ZipFile` and
  `JarFile`, `ServiceLoader`, `java.util.prefs`, `clojure.lang.Compiler/loadFile`,
  `RT/load` and `RT/loadResourceScript`, reflection by a method name built from
  strings, a name written as a regular expression rather than a string, which is how §7's
  own check for a file ragtime would pass over spells one, and a literal handed to
  ragtime's `load-resources`, which is how §8 will
  legitimately load its own migration. `clojure.edn` is allowed, because §8 reads EDN from a
  column rather than a file; catching `java.io.FileNotFoundException` is not looking
  for anything; and ragtime reading the directory the host names happens in ragtime.
  src does list that directory itself, through `resauce`, ragtime's own reader, because
  §7 refuses a file ragtime would ignore rather than let it pass as a migration that
  never runs — the host still names the prefix, and nothing here looks for a name.
  Configuration names are checked in every string outside the `ns` form, messages
  included, so a message ending in one reds visibly. Lifting a ban for §7 or anything
  else is a decision recorded with its reason, never a quiet edit.

  **What a consumer receives (§3)** is resolved the way a consumer resolves it: by
  the CLI, with no alias and no user configuration (`clojure -Srepro -Spath`). The
  basis of a test run cannot say it, because tools.deps blanks the parents of any
  library an alias pins at top level — H2, SQLite and the test runner today — so a
  runtime dependency dragging one of them in would be invisible there. Anything on
  that classpath that is not accepted, each with its reason, reds and names itself;
  a library that stops arriving is not a defect of §3.

  **What binds a logging backend.** HikariCP brings slf4j-api, a facade that logs
  nowhere without a provider; the day something on the classpath provides one,
  every consumer's logs are routed by this library's choice. That scan covers the
  test classpath, which contains `:deps`: a backend arriving through a test extra
  reds falsely but visibly, and the fix is to move it, never to filter the scan.

  **What lives in a var root (§9).** Not a datasource, not a box that could hold
  one. Checked while a pool is open, which is when such a root would be filled.

  **What src/ never spells (§3).** The dialects that belong to one engine family:
  `ON CONFLICT`, `RETURNING`, `MERGE INTO`, `WHEN MATCHED`, `LISTEN`, `NOTIFY` and
  `jsonb`, matched case-sensitively in every string literal, docstrings included —
  lower-cased, `merge` is a function of the language and `listen` is English, and the
  prose of this repository would fire the scan. It is the half of §3's proof that a
  running suite cannot give: SQL that no test executes is SQL no engine ever refuses.
  The other half, firing each of those forms at both engines to check the guard before
  trusting it, is `dialect_test`, and both read the list from `test-support` so the two
  cannot drift apart. `CREATE TABLE IF NOT EXISTS` is scanned and not fired: both test
  engines take it and Derby does not, so no cell of that matrix could ever refuse it.

  What this scan cannot see: SQL built by concatenation in pieces, a name written as a
  regular expression — a `Pattern` is not a string, and a control below says so — and a
  literal in a `#?(:cljs …)` branch, which the reader this borrows discards by choosing
  the JVM's own. That reader also rewrites `::` to `:` before anything is read, which
  hides none of the tokens as they stand and would hide a `::` cast if one were ever
  added. Runs of whitespace inside a literal are flattened first, so a clause wrapped
  across lines is still the clause, and metadata docstrings are walked. `TEXT` is not
  here: §8 measured that HSQLDB and Derby refuse it, and until §8 has code, that
  measurement is all there is."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.db-base :as db]
            [dev.arkaitz.db-base.test-support :as ts])
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.io File PushbackReader StringReader]
           [java.lang.ref Reference WeakReference]
           [java.lang.reflect Field Modifier]
           [java.sql Connection]
           [java.util IdentityHashMap]
           [java.util.concurrent CompletableFuture Future TimeUnit]
           [java.util.concurrent.atomic AtomicReference AtomicReferenceArray]
           [java.util.regex Pattern]
           [javax.sql DataSource]))

(def ^:private anchor-path "dev/arkaitz/db_base.clj")

(def ^:private library-roots
  "`resauce` is ragtime's own resource reader, and src lists the prefix with it for the
  reason the docstring above gives (decided with the user 2026-09-19). `integrant` is
  SPEC §10's, and only `dev.arkaitz.db-base.integrant` may name it — which this scan
  cannot express, since it asks the same question of every file, so a scan of its own
  says that (decided with the user 2026-09-20, when the first host asked)."
  #{"ragtime" "resauce" "integrant" "dev.arkaitz.db-base"})

(def ^:private class-roots #{"java" "javax" "clojure.lang" "com.zaxxer.hikari"})

(def ^:private loading-primitives
  '#{require use load load-file load-string import resolve ns-resolve
     requiring-resolve eval Class/forName})

(defn- under? [roots s]
  (boolean (some #(or (= s %) (str/starts-with? s (str % "."))) roots)))

(def ^:private reading-namespaces #{"clojure.java.io"})

(defn- reading-class? [c]
  (boolean (or (re-matches #"java\.io\.(File|FileInputStream|FileOutputStream|FileReader|FileWriter|RandomAccessFile)" c)
               (under? #{"java.nio.file"} c)
               (= "java.util.ResourceBundle" c)
               (str/starts-with? c "java.util.ResourceBundle$"))))

(def ^:private reading-symbols
  '#{slurp spit file-seq
     System/getenv System/getProperty System/getProperties
     System/setProperty System/clearProperty System/setProperties
     Long/getLong Integer/getInteger Boolean/getBoolean
     ClassLoader/getSystemResource ClassLoader/getSystemResourceAsStream ClassLoader/getSystemResources
     RT/resourceAsStream RT/getResource})

(def ^:private reading-methods #{"getResource" "getResourceAsStream" "getResources"})

(def ^:private configuration-file-name #"(?i)\.(edn|properties|json|ya?ml|conf|toml|env)$")

(defn- src-root
  "The src directory, located through the classpath so a different working
  directory cannot turn the scan into a vacuous walk over nothing."
  []
  (let [url (io/resource anchor-path)]
    (when (and url (= "file" (.getProtocol url)))
      (let [anchor (io/file url)
            depth  (count (str/split anchor-path #"/"))]
        (nth (iterate #(.getParentFile ^File %) anchor) depth)))))

(defn- source-files [^File root]
  (let [prefix (inc (count (.getPath root)))]
    (into {} (for [^File f (file-seq root)
                   :when (and (.isFile f) (re-find #"\.clj[cs]?$" (.getName f)))]
               [(str/replace (subs (.getPath f) prefix) File/separator "/") f]))))

(defn- read-all-forms
  "Reads with the JVM's own reader-conditional choice, because that branch is what
  loads here: a `#?(:clj …)` require in a .cljc is a require."
  [text]
  (with-open [rdr (LineNumberingPushbackReader. (StringReader. (str/replace text "::" ":")))]
    (binding [*read-eval* false *data-readers* {} *default-data-reader-fn* tagged-literal]
      (loop [forms []]
        (let [form (read {:read-cond :allow :eof ::eof} ^PushbackReader rdr)]
          (if (= ::eof form) forms (recur (conj forms form))))))))

(defn- jar-of [url]
  (let [s (str url)]
    (when (and (str/starts-with? s "jar:") (str/includes? s "!/"))
      (subs s 0 (str/index-of s "!/")))))

(def ^:private clojure-jar (jar-of (io/resource "clojure/core.clj")))

(defn- served-by-clojure?
  "True when the namespace's source or class is inside the jar that serves
  `clojure.core` — provenance, not prefix."
  [nom]
  (let [base (-> nom (str/replace "-" "_") (str/replace "." "/"))]
    (boolean (some #(some-> (io/resource (str base %)) jar-of (= clojure-jar))
                   [".clj" ".cljc" "__init.class"]))))

(defn- allowed-namespace? [nom]
  (or (under? library-roots nom) (served-by-clojure? nom)))

(defn- ns-form? [form] (and (seq? form) (= 'ns (first form))))

(defn- prefix-list [prefix children]
  (for [child children]
    (symbol (str prefix "." (if (coll? child) (first child) child)))))

(defn- required-namespaces
  "Every namespace an `ns` form loads, prefix lists expanded whether written as a
  vector `[ragtime [core :as c]]` or a list `(ragtime core)`: neither spells the
  full name anywhere."
  [form]
  (when (ns-form? form)
    (for [clause (filter seq? form)
          :when  (#{:require :use} (first clause))
          spec   (rest clause)
          nom    (cond
                   (symbol? spec) [spec]
                   (vector? spec) (if (or (empty? (rest spec)) (keyword? (second spec)))
                                    [(first spec)]
                                    (prefix-list (first spec) (rest spec)))
                   (seq? spec)    (prefix-list (first spec) (rest spec))
                   :else nil)]
      (str nom))))

(defn- imported-classes [form]
  (when (ns-form? form)
    (for [clause (filter seq? form)
          :when  (= :import (first clause))
          spec   (rest clause)
          nom    (if (symbol? spec)
                   [(str spec)]
                   (for [cls (rest spec)] (str (first spec) "." cls)))]
      nom)))

(defn- walk-with-tags
  "Every node, plus the `:tag` of any node that carries one — a hint such as
  `^org.slf4j.Logger` names a class and lives in metadata a plain walk skips — and, for a
  tagged literal, its tag and what it wraps: `#ig/ref :x` is a `TaggedLiteral`, which is
  not a collection, so `tree-seq` stops at it and both names inside would be invisible."
  [form]
  (mapcat (fn [node]
            (concat [node]
                    (some-> node meta :tag list)
                    (when (instance? clojure.lang.TaggedLiteral node)
                      (cons (:tag node) (walk-with-tags (:form node))))))
          (tree-seq coll? seq form)))

(defn- body-nodes [forms]
  (mapcat walk-with-tags (remove ns-form? forms)))

(defn- dot-form-methods
  "The method-name positions of `(. target m …)`, `(. target (m …))` and
  `(.. target m (n …))`: symbols that name a method, never a var."
  [node]
  (when (and (seq? node) (#{'. '..} (first node)))
    (for [m (if (= '. (first node)) [(nth node 2 nil)] (drop 2 node))
          :let [sym (if (seq? m) (first m) m)]
          :when (symbol? sym)]
      sym)))

(defn- method-symbols
  "Method-name symbols by identity, so `(. path resolve x)` is not the var `resolve`
  while a `resolve` anywhere else still is."
  [forms]
  (let [seen (IdentityHashMap.)]
    (doseq [node (body-nodes forms) sym (dot-form-methods node)]
      (.put seen sym true))
    seen))

(defn- dotted-references
  "Symbols outside `ns` forms naming a class or a namespace by a dotted name:
  `a.b.C`, `a.b.C.`, `a.b/f`, `a.b.C/m`, and Clojure 1.12's qualified method
  `a.b.C/.m`, which names its class. An unqualified method call (`.m`) and dotless
  names are not references; the `ns` form's own names are read by the two functions
  above."
  [forms]
  (for [node (body-nodes forms)
        :when (symbol? node)
        :let [s (if-let [n (namespace node)] n (str/replace (name node) #"\.$" ""))]
        :when (and (or (namespace node) (not (str/starts-with? (name node) ".")))
                   (str/includes? s "."))]
    s))

(defn- canonical
  "The unqualified spelling of a symbol the scans know by that spelling, so
  `clojure.core/require` and `java.lang.System/getenv` are not a way around them."
  [sym]
  (let [short (get {"clojure.core"          nil
                    "java.lang.Class"       "Class"
                    "java.lang.System"      "System"
                    "java.lang.Long"        "Long"
                    "java.lang.Integer"     "Integer"
                    "java.lang.Boolean"     "Boolean"
                    "java.lang.ClassLoader" "ClassLoader"
                    "clojure.lang.RT"       "RT"}
                   (namespace sym) ::keep)]
    (cond (= ::keep short) sym
          (nil? short)     (symbol (name sym))
          :else            (symbol short (name sym)))))

(defn- static-dot-call
  "`(. System getenv \"X\")`, `(. System (getenv \"X\"))` and `(.. System (getenv \"X\"))`
  spell the same call as `(System/getenv \"X\")`."
  [node]
  (when-let [m (first (dot-form-methods node))]
    (when (symbol? (second node))
      (canonical (symbol (str (second node)) (str m))))))

(defn- runtime-loads
  "A loading primitive anywhere outside the `ns` form, called or passed — `(-> s
  requiring-resolve)` never puts it at the head of a list — and a `:load` clause
  inside it. Any of these would evade the namespace scan."
  [forms]
  (let [methods (method-symbols forms)]
    (concat (for [form forms :when (ns-form? form)
                  clause (filter seq? form) :when (= :load (first clause))]
              clause)
            (for [node (body-nodes forms)
                  :let [sym (cond (and (symbol? node) (not (.containsKey methods node))) (canonical node)
                                  (seq? node) (static-dot-call node))]
                  :when (contains? loading-primitives sym)]
              sym))))

(defn- looks-outside-the-map
  "Every spelling in the forms of one file that looks for a file, a resource, the
  environment or a system property by name (see the namespace docstring)."
  [forms]
  (let [methods (method-symbols forms)
        classes (concat (mapcat imported-classes forms) (dotted-references forms))]
    (concat (filter reading-namespaces (mapcat required-namespaces forms))
            (filter #(under? reading-namespaces %) (dotted-references forms))
            (filter reading-class? classes)
            (for [node (body-nodes forms)
                  :let [sym (cond (and (symbol? node) (not (.containsKey methods node))) (canonical node)
                                  (seq? node) (static-dot-call node))]
                  :when (contains? reading-symbols sym)]
              (str sym))
            ;; Any symbol spelling a lookup method: `.m`, Clojure 1.12's `Class/.m`, the
            ;; method position of a dot form, or the name `memfn` turns into a call.
            (for [node (body-nodes forms)
                  :when (and (symbol? node)
                             (or (nil? (namespace node)) (str/starts-with? (name node) ".")))
                  :let [method (str/replace (name node) #"^\." "")]
                  :when (contains? reading-methods method)]
              (str "." method))
            (for [node (body-nodes forms)
                  :when (and (string? node) (re-find configuration-file-name node))]
              node))))

(defn- violations
  "Everything in the forms of one file that reaches outside SPEC §3 or looks
  outside the configuration map of §5."
  [forms]
  {:namespaces (vec (remove allowed-namespace? (mapcat required-namespaces forms)))
   :classes    (vec (remove #(or (under? class-roots %) (allowed-namespace? %))
                            (concat (mapcat imported-classes forms) (dotted-references forms))))
   :loads      (vec (runtime-loads forms))
   :looks      (vec (distinct (looks-outside-the-map forms)))})

(defn- violations-in [text] (violations (read-all-forms text)))

(deftest src-reaches-only-what-spec-3-allows-and-looks-for-nothing-outside-its-configuration
  (testing "preconditions of the provenance rule"
    (is (some? clojure-jar) "precondition: clojure.core is served from a jar, so provenance can be read")
    (is (served-by-clojure? "clojure.string") "precondition: a namespace of the language is recognised")
    (is (some? (io/resource "clojure/java/data.clj"))
        "precondition: clojure.java.data is on the classpath, so the control below tests provenance, not presence"))
  (testing "positive controls: each shape of reaching outside is seen"
    (is (= ["next.jdbc"] (:namespaces (violations-in "(ns x (:require [next.jdbc :as j]))")))
        "a plain require")
    (is (= ["clojure.java.data"] (:namespaces (violations-in "(ns x (:require [clojure.java.data :as jd]))")))
        "a contrib library sharing the language's prefix")
    (is (= ["org.slf4j.core"] (:namespaces (violations-in "(ns x (:require [org.slf4j [core :as c]]))")))
        "a vector prefix-list require")
    (is (= ["next.jdbc"] (:namespaces (violations-in "(ns x (:require (next jdbc)))")))
        "a list prefix-list require")
    (is (= ["next.jdbc"] (:namespaces (violations-in "(ns x #?(:clj (:require [next.jdbc])))")))
        "a require inside a reader conditional")
    (is (= ["org.slf4j.LoggerFactory"] (:classes (violations-in "(ns x (:import [org.slf4j LoggerFactory]))")))
        "an import")
    (is (= ["org.slf4j.LoggerFactory"]
           (:classes (violations-in "(ns x) (defn f [] (org.slf4j.LoggerFactory/getLogger \"x\"))")))
        "a fully qualified static call in a body, with no import")
    (is (= ["org.slf4j.Logger"] (:classes (violations-in "(ns x) (defn f [^org.slf4j.Logger l] l)")))
        "a fully qualified type hint")
    (is (= ["next.jdbc"] (:classes (violations-in "(ns x) (defn f [ds] (next.jdbc/execute! ds [\"x\"]))")))
        "a namespace reached transitively without a require")
    (is (= ["javassist.Foo"] (:classes (violations-in "(ns x (:import [javassist Foo]))")))
        "a root matched as a prefix without its dot")
    (is (= ["org.slf4j.Logger"] (:classes (violations-in "(ns x) (defn f [l] (org.slf4j.Logger/.info l \"x\"))")))
        "a Clojure 1.12 qualified method names its class"))
  (testing "positive controls: loading outside the ns form"
    (is (= '[require] (:loads (violations-in "(ns x) (defn f [] (require 'next.jdbc))")))
        "a require outside the ns form")
    (is (= '[requiring-resolve]
           (:loads (violations-in "(ns x) (defn f [] (-> (symbol \"next.jdbc\" \"execute!\") clojure.core/requiring-resolve))")))
        "a qualified requiring-resolve passed rather than called, with the name built from strings")
    (is (= '[Class/forName]
           (:loads (violations-in "(ns x) (defn f [] (java.lang.Class/forName \"org.slf4j.LoggerFactory\"))")))
        "a fully qualified reflective class load")
    (is (= '[(:load "/next/jdbc")] (:loads (violations-in "(ns x (:load \"/next/jdbc\"))")))
        "a :load clause in the ns form")
    (is (= '[Class/forName] (:loads (violations-in "(ns x) (defn f [] (. Class forName \"org.slf4j.LoggerFactory\"))")))
        "a reflective class load through the dot form")
    (is (= '[Class/forName] (:loads (violations-in "(ns x) (defn f [] (.. Class (forName \"org.slf4j.LoggerFactory\")))")))
        "a reflective class load through the double-dot form")
    (is (= [] (:loads (violations-in "(ns x) (defn f [p] (. p resolve \"V1.sql\"))")))
        "control: a method named resolve on a Path is not the var resolve"))
  (testing "positive controls: looking for a file, a resource or the environment"
    (doseq [[expected text label]
            [[["clojure.java.io"] "(ns x (:require [clojure.java.io :as io]))" "a require of clojure.java.io"]
             [["clojure.java.io"] "(ns x) (defn f [] (clojure.java.io/resource \"db-base-defaults\"))"
              "a resource looked up without a require and without an extension"]
             [["clojure.java.io"] "(ns x) (defn f [config] (clojure.java.io/file (:dir config)))"
              "a file the host named is still a file API here: lifting that is a recorded decision"]
             [["slurp"] "(ns x) (defn f [p] (slurp p))" "a slurp"]
             [["spit"] "(ns x) (defn f [p s] (spit p s))" "a spit"]
             [["java.io.RandomAccessFile"] "(ns x (:import [java.io RandomAccessFile]))" "a random-access file"]
             [[".getResources"] "(ns x) (defn f [l] (.getResources l \"db-base-defaults\"))" "every resource by name"]
             [[".getResourceAsStream"] "(ns x) (defn f [] (map (memfn getResourceAsStream n) [(clojure.lang.RT/baseLoader)]))"
              "a lookup method named through memfn"]
             [["RT/resourceAsStream"] "(ns x) (defn f [] (clojure.lang.RT/resourceAsStream nil \"db-base-defaults\"))"
              "what clojure.java.io/resource calls underneath"]
             [["RT/getResource"] "(ns x) (defn f [] (clojure.lang.RT/getResource nil \"db-base-defaults\"))" "its sibling"]
             [["ClassLoader/getSystemResource"] "(ns x) (defn f [] (ClassLoader/getSystemResource \"x\"))" "a system resource URL"]
             [["ClassLoader/getSystemResources"] "(ns x) (defn f [] (java.lang.ClassLoader/getSystemResources \"x\"))"
              "system resources, fully qualified"]
             [["System/getProperties"] "(ns x) (defn f [] (System/getProperties))" "every system property"]
             [["System/setProperty"] "(ns x) (defn f [] (System/setProperty \"hikaricp.configurationFile\" \"x\"))"
              "a system property written, which returns the one it replaced"]
             [["System/clearProperty"] "(ns x) (defn f [] (System/clearProperty \"hikaricp.configurationFile\"))"
              "a system property cleared, which returns it"]
             [["System/setProperties"] "(ns x) (defn f [] (System/setProperties nil))"
              "every system property replaced at once"]
             [["Integer/getInteger"] "(ns x) (defn f [] (Integer/getInteger \"x\"))" "an integer system property"]
             [["Boolean/getBoolean"] "(ns x) (defn f [] (Boolean/getBoolean \"x\"))" "a boolean system property"]
             [["file-seq"] "(ns x) (defn f [ds] (map file-seq ds))" "a file-seq passed rather than called"]
             [["java.io.File"] "(ns x) (defn f [d] (java.io.File. d))" "a file"]
             [["java.io.FileInputStream"] "(ns x (:import [java.io FileInputStream]))" "a file input stream"]
             [["java.io.FileOutputStream"] "(ns x (:import [java.io FileOutputStream]))" "a file output stream"]
             [["java.io.FileReader"] "(ns x (:import [java.io FileReader]))" "a file reader"]
             [["java.io.FileWriter"] "(ns x (:import [java.io FileWriter]))" "a file writer"]
             [["java.util.ResourceBundle$Control"] "(ns x (:import [java.util ResourceBundle$Control]))"
              "a resource bundle loader"]
             [[".getResourceAsStream"] "(ns x) (defn f [l] (ClassLoader/.getResourceAsStream l \"db-base-defaults\"))"
              "a Clojure 1.12 qualified method"]
             [["java.nio.file.Files"] "(ns x) (defn f [p] (java.nio.file.Files/readString p))" "an NIO read"]
             [["java.util.ResourceBundle"] "(ns x) (defn f [] (java.util.ResourceBundle/getBundle \"db\"))" "a resource bundle"]
             [[".getResourceAsStream"] "(ns x) (defn f [] (.getResourceAsStream (clojure.lang.RT/baseLoader) \"db-base-defaults\"))"
              "a classpath resource by method"]
             [[".getResource"] "(ns x) (defn f [l] (. l (getResource \"db-base-defaults\")))" "a classpath resource by the dot form"]
             [["ClassLoader/getSystemResourceAsStream"] "(ns x) (defn f [] (ClassLoader/getSystemResourceAsStream \"x\"))"
              "a system resource"]
             [["System/getenv"] "(ns x) (defn f [] (System/getenv \"DB_PASSWORD\"))" "an environment variable"]
             [["System/getenv"] "(ns x) (defn f [] (. System getenv \"DB_PASSWORD\"))" "the dot form"]
             [["System/getenv"] "(ns x) (defn f [] (. System (getenv \"DB_PASSWORD\")))" "the dot form with a list"]
             [["System/getenv"] "(ns x) (defn f [] (.. System (getenv \"DB_PASSWORD\")))" "the double-dot form"]
             [["System/getProperty"] "(ns x) (defn f [] (java.lang.System/getProperty \"p\"))" "a qualified system property"]
             [["Long/getLong"] "(ns x) (defn f [] (Long/getLong \"db-base.timeout\"))" "a system property through a boxed type"]
             [["db-base.EDN"] "(ns x) (defn f [] (str \"db-base.EDN\"))" "a configuration file name, in any case"]]]
      (is (= expected (:looks (violations-in text))) label))
    (is (= [] (:looks (violations-in "(ns x (:require [clojure.edn :as edn])) (defn f [s] (edn/read-string s))")))
        "control: EDN read from a string, as §8 does from a column, is not looking for anything")
    (is (= [] (:looks (violations-in "(ns x (:import [java.io FileNotFoundException])) (defn f [g] (try (g) (catch FileNotFoundException _ nil)))")))
        "control: catching a missing file is not looking for one")
    (doseq [extension ["properties" "json" "yml" "yaml" "conf" "toml" "env"]]
      (is (= [(str "db." extension)]
             (:looks (violations-in (str "(ns x) (defn f [] (str \"db." extension "\"))"))))
          (str "a configuration file name ending in ." extension))))
  (let [root (src-root)]
    (is (some? root) (str anchor-path " is not on the classpath as a file"))
    (when root
      (let [files (source-files root)
            read  (into {} (for [[path f] files] [path (read-all-forms (slurp f))]))]
        (testing "the scan reached the real sources"
          (is (contains? files anchor-path) (str "precondition: " anchor-path " not among " (keys files)))
          (let [refs (set (concat (mapcat imported-classes (get read anchor-path))
                                  (dotted-references (get read anchor-path))))]
            (is (and (contains? refs "com.zaxxer.hikari.HikariDataSource")
                     (contains? refs "java.sql.Connection"))
                (str "precondition: the reader saw the file's content, not an empty walk: " (sort refs)))))
        (let [found (into {} (for [[path forms] read
                                   :let [v (violations forms)]
                                   :when (some seq (vals v))]
                               [path v]))]
          (is (= {} found)
              (str "SPEC §3/§5: src may require only the language and " (sort library-roots)
                   ", name classes only under " (sort class-roots)
                   ", load nothing outside the ns form and look for nothing outside its configuration — found "
                   found)))))))

(def ^:private accepted-closure
  "Every library a consumer is accepted to receive, with the reason. A new arrival is
  a decision, taken here with its reason; nothing arrives by a version bump alone."
  '{org.clojure/clojure                  "the language"
    org.clojure/spec.alpha               "the language's own dependency"
    org.clojure/core.specs.alpha         "the language's own dependency"
    com.zaxxer/HikariCP                  "the pool"
    org.slf4j/slf4j-api                  "HikariCP's logging facade, which logs nowhere without a provider"
    dev.weavejester/ragtime.next-jdbc    "the migration runner"
    dev.weavejester/ragtime.core         "ragtime.next-jdbc's"
    dev.weavejester/ragtime.sql          "ragtime.next-jdbc's"
    com.github.seancorfield/next.jdbc    "ragtime.next-jdbc's"
    camel-snake-kebab/camel-snake-kebab  "next.jdbc's"
    org.clojure/java.data                "next.jdbc's"
    resauce/resauce                      "ragtime.sql's"
    ;; Decided 2026-09-20, when the first host asked to wire with Integrant (SPEC §10,
    ;; §12). It reaches every consumer, which is the cost of the one key
    ;; `dev.arkaitz.db-base.integrant` ships, and web-base pays the same cost for the
    ;; same reason. A consumer that does not use Integrant loads neither.
    integrant/integrant                  "SPEC §10's optional key, loaded only by dev.arkaitz.db-base.integrant"
    weavejester/dependency               "integrant's"})

(defn- maven-entry-pattern [lib]
  (let [group    (namespace lib)
        artifact (name lib)]
    (re-pattern (str "/" (Pattern/quote (str/replace group "." "/")) "/" (Pattern/quote artifact)
                     "/[^/]+/" (Pattern/quote artifact) "-[^/]*\\.jar$"))))

(defn- consumer-classpath
  "The classpath a consumer of this project resolves: no alias, no user
  configuration. Bounded, because a subprocess is a blocking call; 60 s is policy
  over the 18–483 ms measured."
  [^File project-root]
  ;; stderr is inherited, not merged: the CLI reports downloads there, and merged
  ;; into the classpath they would read as entries nobody accepted.
  (let [process (try (.start (doto (ProcessBuilder. ^java.util.List ["clojure" "-Srepro" "-Spath"])
                               (.directory project-root)
                               (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)))
                     (catch java.io.IOException e e))]
    (if (instance? Throwable process)
      {:error (str "the clojure CLI could not be run: " (ex-message process))}
      (let [^Process process process]
        (if-not (.waitFor process 60 TimeUnit/SECONDS)
          (do (.destroyForcibly process)
              {:error "clojure -Srepro -Spath did not finish within 60 s"})
          (let [output (slurp (.getInputStream process))]
            (if (zero? (.exitValue process))
              {:entries (str/split (str/trim output) #":")}
              {:error (str "clojure -Srepro -Spath exited " (.exitValue process)
                           " (its stderr is in the test output above): " output)})))))))

(deftest a-consumer-receives-the-language-a-pool-a-migration-runner-and-nothing-unaccepted
  (let [project-root (.getParentFile ^File (src-root))
        deps         (edn/read-string (slurp (io/file project-root "deps.edn")))
        declared     (set (keys (:deps deps)))
        {:keys [entries error]} (consumer-classpath project-root)
        lib-of       (fn [entry] (first (for [lib (keys accepted-closure)
                                              :when (re-find (maven-entry-pattern lib) entry)]
                                          lib)))]
    (is (= '#{org.clojure/clojure com.zaxxer/HikariCP dev.weavejester/ragtime.next-jdbc
              resauce/resauce integrant/integrant}
           declared)
        (str "SPEC §3: deps.edn declares the language, a pool, a migration runner, the"
             " resource reader src calls to list a prefix, and §10's Integrant, which only"
             " the optional namespace may load — found " (sort declared)))
    (when (is (nil? error) (str "precondition: the consumer's classpath was resolved — " error))
      (let [received (set (keep lib-of entries))]
        (testing "controls: the resolution is a consumer's, not this test run's"
          (is (every? received declared)
              (str "every declared dependency is recognised on it: " (sort received)))
          (is (contains? received 'org.slf4j/slf4j-api) "a transitive dependency is received")
          (is (not-any? #(str/includes? % "/com/h2database/") entries)
              "no test engine is on it, so no alias was applied"))
        (is (= [] (vec (remove #(or (contains? (set (:paths deps)) %) (lib-of %)) entries)))
            "SPEC §3: arrived on every consumer's classpath and is not accepted — decide it, with its reason, above")))))

(def ^:private backend-markers
  ["META-INF/services/org.slf4j.spi.SLF4JServiceProvider"
   "org/slf4j/impl/StaticLoggerBinder.class"
   "META-INF/services/org.apache.logging.log4j.spi.Provider"])

(defn- resource-urls [^ClassLoader loader path]
  (mapv str (enumeration-seq (.getResources loader path))))

(deftest nothing-on-the-classpath-binds-a-logging-backend
  (let [loader (ClassLoader/getSystemClassLoader)]
    (is (<= 2 (count (resource-urls loader "META-INF/services/java.sql.Driver")))
        (str "positive control: the lookup reads service files inside jars (H2 and SQLite each "
             "register a driver), so an empty answer below is an answer: "
             (resource-urls loader "META-INF/services/java.sql.Driver")))
    (is (seq (resource-urls loader "org/slf4j/LoggerFactory.class"))
        "precondition: the facade is present, so the absence of a binding is what is being tested")
    (doseq [marker backend-markers]
      (is (= [] (resource-urls loader marker))
          (str "SPEC §3: a logging backend is bound through " marker " by "
               (resource-urls loader marker))))
    (is (= "org.slf4j.helpers.NOPLoggerFactory"
           (.getName (class (org.slf4j.LoggerFactory/getILoggerFactory))))
        "corroboration: SLF4J itself found no provider")))

(defn- module-namespaces
  "Every namespace of the library, from the sources on disk and then loaded. Read from
  disk because a filtered run loads only what it needs, and a walk over `all-ns` would
  miss the rest; loaded because `the-ns` answers only for what is already there, and an
  optional namespace — §10's Integrant one — is exactly what nothing else requires."
  []
  (for [[path _] (source-files (src-root))
        :let [nom (symbol (-> path (str/replace #"\.clj[cs]?$" "") (str/replace "/" ".")
                              (str/replace "_" "-")))]]
    (do (require nom) nom)))

(defn- children
  "What `x` holds, one hop out. Containers are opened through their public API —
  reflecting into the JDK's own classes throws under strong encapsulation. A named
  var or a namespace is not opened: a protocol or a multimethod names its own var,
  and every namespace holds its mappings in a box, which says nothing about what
  this library keeps. An anonymous var is opened, because it is only a box."
  [x]
  (cond
    (.isArray (class x))               (seq x)
    (instance? java.util.Map x)        (concat (keys x) (vals x))
    (instance? java.util.Collection x) (seq x)
    (instance? clojure.lang.Var x)     (when (nil? (.ns ^clojure.lang.Var x)) [(.getRawRoot ^clojure.lang.Var x)])
    (instance? clojure.lang.Namespace x) nil
    (re-find #"^(java|javax|jdk|sun|com\.sun)\." (.getName (class x))) nil
    :else (keep (fn [^Field f]
                  (when-not (Modifier/isStatic (.getModifiers f))
                    (.setAccessible f true)
                    (.get f x)))
                (.getDeclaredFields (class x)))))

(defn- state?
  "A datasource, a connection, or a box that could come to hold one."
  [x]
  (some #(instance? % x) [DataSource Connection clojure.lang.Atom clojure.lang.Ref clojure.lang.Agent
                          clojure.lang.Volatile AtomicReference AtomicReferenceArray ThreadLocal
                          Reference Future]))

(defn- reaches?
  "Whether anything `pred` answers to sits within `depth` hops of `value`."
  [pred value depth]
  (let [seen (IdentityHashMap.)]
    (letfn [(walk [x d]
              (cond
                (nil? x)              false
                (pred x)              true
                (neg? d)              false
                (.containsKey seen x) false
                :else (do (.put seen x true)
                          (boolean (some #(walk % (dec d)) (children x))))))]
      (walk value depth))))

(defn- reaches-state? [value depth] (reaches? state? value depth))

(def ^:private uuid-shaped
  #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

(defn- minted?
  "The identity-shaped residue of a generator: a UUID, a generator itself, or a string
  carrying a UUID's spelling anywhere in it — `db-base-<uuid>` counts. What it cannot see,
  stated rather than implied: a clock reading, an identity hash, a pid, a `gensym`, or
  anything else whose baked value is indistinguishable from a constant. A memoize cache or
  a `delay` holding one of those is an atom, and §9's scan already reds on those."
  [x]
  (or (instance? java.util.UUID x)
      (instance? java.util.Random x)
      (and (string? x) (boolean (re-find uuid-shaped x)))))

(defn- walked-roots
  "Every interned var with a root in `namespaces` — private ones included, because a
  private var is baked exactly as hard as a public one."
  [namespaces]
  (vec (for [n     namespaces
             [_ v] (ns-interns (the-ns n))
             :when (.hasRoot ^clojure.lang.Var v)]
         v)))

(defn- minted-roots [namespaces]
  (vec (remove #(not (reaches? minted? (var-get %) 4)) (walked-roots namespaces))))

(defprotocol ^:private ProtocolShaped (probe [x]))

(deftest no-var-root-holds-a-datasource-or-a-box--not-even-while-a-pool-is-open
  (let [handle (db/start {:jdbc-url (str "jdbc:h2:mem:var-roots-" (random-uuid) ";DB_CLOSE_DELAY=-1")
                          :user "" :password "" :pool {:max 1 :timeout-ms 1000} :migrations :none})]
    (try
      (testing "positive controls: the walk sees state directly, in a collection and closed over by a fn"
        (is (reaches-state? (atom nil) 4))
        (is (reaches-state? {:pool (:datasource handle)} 4))
        (is (reaches-state? (let [box (volatile! nil)] (fn [] box)) 4))
        (is (reaches-state? (ThreadLocal.) 4) "a thread-local, which holds its value outside any object graph")
        (is (reaches-state? (WeakReference. :x) 4) "a reference")
        (is (reaches-state? (CompletableFuture.) 4) "a future")
        (is (reaches-state? (AtomicReferenceArray. 1) 4) "an atomic array")
        (is (reaches-state? (doto (clojure.lang.Var/create) (.bindRoot (:datasource handle))) 4)
            "an anonymous var holding a datasource"))
      (testing "controls: what is not state"
        (is (not (reaches-state? [1 "two" {:three #{4}}] 4)) "plain values")
        (is (not (reaches-state? ProtocolShaped 4)) "a protocol, whose root names its var and namespace")
        (is (not (reaches-state? #'clojure.core/map 4)) "a named var"))
      (let [namespaces (vec (module-namespaces))]
        (is (some #{'dev.arkaitz.db-base} namespaces)
            (str "precondition: the scan found the library's namespace: " (pr-str namespaces)))
        (is (= [] (vec (for [n     namespaces
                             [_ v] (ns-interns (the-ns n))
                             :when (and (.hasRoot ^clojure.lang.Var v) (reaches-state? (var-get v) 4))]
                         v)))
            "SPEC §9: no var root holds a datasource, a connection or a box that could hold one"))
      (finally (db/stop handle)))))

(def ^:private dialect-tokens (into ts/dialect-tokens ts/scan-only-tokens))

(defn- string-literals
  "Every string a source spells: the `ns` form included, because a docstring is a literal
  and is where someone will justify the upsert §8 forbids, and metadata included, because
  `^{:doc \"…\"}` is a docstring the plain walk does not reach."
  [forms]
  (filter string?
          (tree-seq #(or (coll? %) (some? (meta %)))
                    #(concat (when (coll? %) (seq %)) (some-> % meta vals))
                    forms)))

(defn- dialect-in
  "Every [token literal] a source spells. Newlines and runs of spaces inside a literal
  count as one space, so a clause wrapped across two lines is still the clause."
  [text]
  (vec (for [literal (string-literals (read-all-forms text))
             :let    [flat (str/replace literal #"\s+" " ")]
             token   dialect-tokens
             :when   (str/includes? flat token)]
         [token literal])))

(deftest src-spells-no-dialect-that-belongs-to-one-engine-family
  (testing "positive controls: each shape a dialect would arrive in"
    (is (= [["ON CONFLICT" "INSERT INTO s (k, v) VALUES (?, ?) ON CONFLICT (k) DO NOTHING"]]
           (dialect-in (str "(ns x) (defn write! [ds k v]"
                            " (execute! ds [\"INSERT INTO s (k, v) VALUES (?, ?) ON CONFLICT (k) DO NOTHING\" k v]))")))
        "the upsert §8 forbids, inside a function body")
    (is (= [["RETURNING" "DELETE FROM challenge WHERE token = ? RETURNING subject"]]
           (dialect-in "(ns x) (def take-challenge \"DELETE FROM challenge WHERE token = ? RETURNING subject\")"))
        "the single-statement take auth-base would want")
    (is (= [["jsonb" "CREATE TABLE s (data jsonb)"]]
           (dialect-in "(ns x) (defn ddl [] (str \"CREATE TABLE s (data jsonb)\"))"))
        "a column type inside a str, which is how this library builds every statement it has")
    (is (= [["MERGE INTO" "MERGE INTO s t USING v ON (t.k = v.k) WHEN MATCHED THEN UPDATE SET t.v = v.v"]
            ["WHEN MATCHED" "MERGE INTO s t USING v ON (t.k = v.k) WHEN MATCHED THEN UPDATE SET t.v = v.v"]]
           (dialect-in (str "(ns x) (def m \"MERGE INTO s t USING v ON (t.k = v.k)"
                            " WHEN MATCHED THEN UPDATE SET t.v = v.v\")")))
        "ANSI MERGE, which H2 accepts and only SQLite refuses — one literal can spell two")
    (is (= [["LISTEN" "LISTEN sessions"] ["NOTIFY" "NOTIFY sessions, 'revoked'"]]
           (dialect-in "(ns x) (def a \"LISTEN sessions\") (def b \"NOTIFY sessions, 'revoked'\")"))
        "the sweeper §9 forbids, written the way PostgreSQL would have it")
    (is (= [["RETURNING" "Reads the row the delete removed, RETURNING it to the caller."]]
           (dialect-in "(ns x \"Reads the row the delete removed, RETURNING it to the caller.\")"))
        "a docstring is a literal: the ns form is scanned too")
    (is (= [["ON CONFLICT" "INSERT INTO s (k) VALUES (?) ON\n     CONFLICT (k) DO NOTHING"]]
           (dialect-in "(ns x) (def q \"INSERT INTO s (k) VALUES (?) ON\n     CONFLICT (k) DO NOTHING\")"))
        "a clause wrapped between its own two words is still the clause")
    (is (= [["ON CONFLICT" "INSERT INTO s (k) VALUES (?) ON CONFLICT (k) DO NOTHING"]]
           (dialect-in "(ns x) (def ^{:doc \"INSERT INTO s (k) VALUES (?) ON CONFLICT (k) DO NOTHING\"} q 1)"))
        "a docstring written as metadata, which a walk over the form alone does not reach")
    (is (= [["IF NOT EXISTS" "CREATE TABLE IF NOT EXISTS db_base_migration_lock (id VARCHAR(64))"]]
           (dialect-in (str "(ns x) (defn ddl [] (str \"CREATE TABLE IF NOT EXISTS"
                            " db_base_migration_lock (id VARCHAR(64))\"))")))
        "SPEC §7: the form Derby rejects, which both test engines take — the scan is the only place it can be said"))
  (testing "controls: what must not fire"
    (is (= [] (dialect-in "(ns x) (defn f [a b] (merge a b))")) "the language's own merge")
    (is (= [] (dialect-in "(ns x) (def s \"on conflict do nothing\")"))
        "lower case is prose: matching it case-insensitively would fire on this repository's own writing")
    (is (= [] (dialect-in "(ns x) (def s \"a conflict between two instances, returning nothing\")"))
        "the words alone, which is how §7's own messages talk")
    (is (= [] (dialect-in "(ns x) (def p #\"RETURNING\")"))
        "a regular expression is a Pattern, not a string — stated as a control because it is what this scan cannot see"))
  (is (= #{"ON CONFLICT" "RETURNING" "MERGE INTO" "WHEN MATCHED" "LISTEN" "NOTIFY" "jsonb"
           "IF NOT EXISTS"}
         (set dialect-tokens))
      (str "every token above has a control of its own, written by hand: adding one to the"
           " shared list means writing its control here, and this is what says so"))
  (let [root (src-root)]
    (is (some? root) (str anchor-path " is not on the classpath as a file"))
    (when root
      (let [files (source-files root)
            text  (into {} (for [[path f] files] [path (slurp f)]))]
        (testing "the scan reached the real sources"
          (is (contains? files anchor-path) (str "precondition: " anchor-path " not among " (keys files)))
          (let [literals (string-literals (read-all-forms (get text anchor-path)))]
            (is (= [true true] [(boolean (some #(str/includes? % "CREATE TABLE ") literals))
                                (boolean (some #(str/includes? % "INSERT INTO ") literals))])
                (str "precondition: the walk sees the SQL that lives inside function bodies, not"
                     " only docstrings — found " (count literals) " literals"))))
        (is (= [] (vec (for [[path source] (sort text)
                             hit           (dialect-in source)]
                         (into [path] hit))))
            (str "SPEC §3: src spells a dialect that belongs to one engine family. The only SQL"
                 " of this library's own is §7's lock — a dialect of one engine family (§3),"
                 " or the existence clause §7 avoids because Derby rejects it. For the forms the"
                 " test pair can refuse, dialect_test proves this list means something; for the"
                 " existence clause, which both engines take, this scan is all there is"))))))

(defn- scan-of
  "Interns `value` in a namespace of its own and runs the real scan over it, so a control
  below exercises `ns-interns`, `.hasRoot` and the walk rather than the predicate alone.
  Returns the names the scan reported, and leaves no namespace behind."
  [value private?]
  (let [nom (symbol (str "db-base-test.minted-" (System/nanoTime)))
        v   (intern (create-ns nom) 'planted value)]
    (when private? (alter-meta! v assoc :private true))
    (try (mapv #(name (.-sym ^clojure.lang.Var %)) (minted-roots [nom]))
         (finally (remove-ns nom)))))

(deftest no-var-root-reaches-a-value-minted-when-the-namespace-loaded
  ;; SPEC §11, measured 2026-09-20 on GraalVM CE 25.3.4.1 with an AOT-compiled Clojure
  ;; namespace: in a native image built the ordinary way, whatever a namespace computes
  ;; while it loads is computed at BUILD time and frozen into the binary — the same value
  ;; in every run of every instance — while a value minted inside a function stays fresh.
  ;; The library mints one UUID, §7's lock holder, per boot; this is what keeps it so.
  ;; The hazard itself cannot be observed from a JVM, so what is observed is the shape of
  ;; what a var root holds, and `minted?`'s docstring says what that cannot see.
  (testing "positive controls: the scan itself, over real interned vars"
    (doseq [[what value] [["a UUID" (random-uuid)]
                          ["a UUID spelled as a string" (str (random-uuid))]
                          ["a UUID inside a longer string" (str "db-base-" (random-uuid))]
                          ["a generator, which is what mints them" (java.security.SecureRandom.)]
                          ["one hop inside a map" {:holder (str (random-uuid))}]
                          ;; Two hops: the predicate is asked before the depth is, so a
                          ;; walk that stopped at the root would still see one hop.
                          ["two hops down, in a map inside a map" {:pool {:holder (str (random-uuid))}}]
                          ["one closed over by a function" (let [id (random-uuid)] (fn [] id))]]]
      (is (= ["planted"] (scan-of value false)) (str "the scan reaches " what)))
    (is (= ["planted"] (scan-of (random-uuid) true))
        "a private var is walked too — it is baked exactly as hard as a public one"))
  (testing "controls: what must not fire"
    (doseq [[what value] [["a sentinel that is not a UUID" ts/url-sentinel]
                          ["this library's own table names" {:lock "db_base_migration_lock"
                                                             :control "ragtime_migrations"}]
                          ["a pattern that describes a UUID" uuid-shaped]
                          ["a number, a keyword and a set" #{:dir :lock-wait-ms 2147483647}]]]
      (is (= [] (scan-of value false)) (str what " is not a minted value"))))
  (let [namespaces (vec (module-namespaces))
        walked     (set (walked-roots namespaces))]
    (testing "preconditions: the walk reached this library's own vars, private ones included"
      (is (some #{'dev.arkaitz.db-base} namespaces)
          (str "the scan found the library's namespace: " (pr-str namespaces)))
      (is (= [true true] [(contains? walked #'dev.arkaitz.db-base/start)
                          (contains? walked #'dev.arkaitz.db-base/lock-table)])
          (str "a public var and a private one are both in scope, so a green means the walk"
               " happened — " (count walked) " roots walked")))
    (is (= [] (minted-roots namespaces))
        (str "SPEC §11: a var root reaches a value minted when the namespace loaded. Under a"
             " native image built with --initialize-at-build-time that value is baked into"
             " every instance, and §9's scan would not see it because it hunts state rather"
             " than constants"))))

(def ^:private integrant-exempt-path "dev/arkaitz/db_base/integrant.clj")
(def ^:private integrant-exempt-ns 'dev.arkaitz.db-base.integrant)

(defn- integrant-name?
  "A name that ties a source to Integrant: one in `integrant` or `integrant.*`, the tag
  of an `#ig/…` literal, or the exempt namespace itself — requiring that namespace from
  anywhere else is how Integrant stops being optional and starts being imposed."
  [x]
  (let [hit? (fn [s] (and s (or (= s "integrant") (str/starts-with? s "integrant."))))]
    (and (or (symbol? x) (keyword? x))
         (boolean (or (hit? (namespace x))
                      (hit? (name x))
                      ;; Only in namespace position: `#ig/ref` and `ig/init-key` are theirs,
                      ;; while a bare `ig` is a name anyone may bind, and a scan that reds on
                      ;; one reports a local rather than a dependency.
                      (= "ig" (namespace x))
                      (= (str integrant-exempt-ns) (namespace x))
                      (= (str integrant-exempt-ns) (str x)))))))

(defn- integrant-in [text]
  (vec (distinct (filter integrant-name? (mapcat walk-with-tags (read-all-forms text))))))

(deftest only-the-integrant-namespace-references-integrant
  ;; SPEC §10: Integrant is used, not imposed. A require of it anywhere else compiles,
  ;; loads and passes every other test — this scan is the only signal, which is why
  ;; `library-roots` above lets `integrant` through and leaves the boundary to be said
  ;; here. The exempt file is the control that proves the scan reads the sources: run
  ;; over it alone, it must find exactly what that file legitimately spells.
  (testing "positive controls: each shape a reference arrives in"
    (is (= '[integrant.core] (integrant-in "(ns x (:require [integrant.core :as ig]))"))
        "a plain require, which is what names them — an alias alone is a name anyone may bind")
    (is (= '[integrant] (integrant-in "(ns x (:require [integrant [core :as ig]]))"))
        "a vector prefix-list require, where the prefix is what the reader leaves")
    (is (= '[integrant.core] (integrant-in "(ns x #?(:clj (:require [integrant.core])))"))
        "a require inside a reader conditional")
    (is (= '[integrant.core/init] (integrant-in "(ns x) (defn f [c] (integrant.core/init c))"))
        "a fully qualified call with no require")
    (is (= [:integrant.core/system] (integrant-in "(ns x) (def k :integrant.core/system)"))
        "a keyword of theirs")
    (is (= '[ig/ref] (integrant-in "(ns x) (def c {:a #ig/ref :b})"))
        "the tag of a literal, which is not a collection and hides what it wraps")
    (is (= [integrant-exempt-ns] (integrant-in (str "(ns x (:require [" integrant-exempt-ns "]))")))
        "and the exempt namespace named from elsewhere, which imposes it just as surely"))
  (testing "controls: what must not fire"
    (is (= [] (integrant-in "(ns x (:require [dev.arkaitz.db-base :as db]))")) "this library itself")
    (is (= [] (integrant-in "(ns x) (def s \"an integrant part of the whole\")"))
        "the English word in a string")
    (is (= [] (integrant-in "(ns x) (defn ignore [_] nil)")) "a name that merely starts with those letters")
    (is (= [] (integrant-in "(ns x) (defn f [{:keys [ig]}] ig)"))
        "a local someone called ig, which is a binding and not a dependency"))
  (let [root (src-root)]
    (is (some? root) (str anchor-path " is not on the classpath as a file"))
    (when root
      (let [files (source-files root)]
        (testing "preconditions: the scan reads the sources, and the exemption is what makes it green"
          (is (contains? files anchor-path) (str "precondition: " anchor-path " not among " (keys files)))
          (is (contains? files integrant-exempt-path)
              (str "precondition: the exempt file is where its name says; if it moved, this scan"
                   " has been exempting nothing — found " (sort (keys files))))
          ;; Filtered rather than spelled out: what this proves is that the scan reads the
          ;; file on disk and sees integrant there. Pinning every name the file happens to
          ;; contain would red on any honest edit to it, which is coupling, not signal.
          (is (= '[integrant.core] (filterv #{'integrant.core}
                                            (integrant-in (slurp (get files integrant-exempt-path)))))
              (str "the exempt file does reference integrant, read from disk: a scan that found"
                   " nothing there would be finding nothing anywhere")))
        (is (= [] (vec (for [[path file] (sort (dissoc files integrant-exempt-path))
                             offender    (integrant-in (slurp file))]
                         [path offender])))
            (str "SPEC §10: Integrant is used, not imposed — only " integrant-exempt-ns
                 " may name it, and a require of it elsewhere compiles, loads and passes every"
                 " other test"))))))

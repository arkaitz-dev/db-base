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
  one. Checked while a pool is open, which is when such a root would be filled."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.db-base :as db])
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
  reason the docstring above gives (decided with the user 2026-09-19)."
  #{"ragtime" "resauce" "dev.arkaitz.db-base"})

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
  "Every node, plus the `:tag` of any node that carries one: a hint such as
  `^org.slf4j.Logger` names a class and lives in metadata a plain walk skips."
  [form]
  (mapcat (fn [node] (cons node (some-> node meta :tag list)))
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
    resauce/resauce                      "ragtime.sql's"})

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
              resauce/resauce}
           declared)
        (str "SPEC §3: deps.edn declares the language, a pool, a migration runner and the"
             " resource reader src calls to list a prefix — found " (sort declared)))
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

(defn- module-namespaces []
  (for [[path _] (source-files (src-root))]
    (symbol (-> path (str/replace #"\.clj[cs]?$" "") (str/replace "/" ".") (str/replace "_" "-")))))

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

(defn- reaches-state? [value depth]
  (let [seen (IdentityHashMap.)]
    (letfn [(walk [x d]
              (cond
                (nil? x)              false
                (state? x)            true
                (neg? d)              false
                (.containsKey seen x) false
                :else (do (.put seen x true)
                          (boolean (some #(walk % (dec d)) (children x))))))]
      (walk value depth))))

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

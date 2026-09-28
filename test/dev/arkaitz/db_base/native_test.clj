(ns dev.arkaitz.db-base.native-test
  "The native image's directory listing, through resauce's own dispatch. A JVM never
  produces a `resource:` URL, so the URL here carries a handler of the test's own that
  answers what an image answers for a registered directory: its entries' names, one per
  line."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dev.arkaitz.db-base.native]
            [resauce.core :as resauce])
  (:import [java.io ByteArrayInputStream]
           [java.net URL URLConnection URLStreamHandler]))

(defn- image-dir
  "A `resource:` URL for `path` whose connection reads `listing`, the text an image
  gives for a directory. The handler parses an absolute spec as GraalVM 25.0.4's does,
  measured: it puts the module prefix `/0!` in front of it again."
  [path listing]
  ;; Built as the image builds its own, without parsing a spec: the five-argument
  ;; constructor never calls the handler's parseURL, which only resolution does.
  (URL. "resource" nil -1 ^String path
          (proxy [URLStreamHandler] []
            (parseURL [u spec start limit]
              (let [spec' (if (and (str/starts-with? path "/0!") (str/starts-with? (subs spec start) "/"))
                            (str (subs spec 0 start) "/0!" (subs spec start))
                            spec)]
                (proxy-super parseURL u spec' start (+ limit (- (count spec') (count spec))))))
            (openConnection [u]
              (proxy [URLConnection] [u]
                (connect [] nil)
                (getInputStream [] (ByteArrayInputStream. (.getBytes ^String listing "UTF-8"))))))))

(deftest a-directory-inside-an-image-lists-its-entries-as-urls-under-it
  (is (= ["resource:/shop/migration/001-accounts.up.sql" "resource:/shop/migration/002-generations.up.sql"]
         (mapv str (resauce/url-dir (image-dir "/shop/migration" "001-accounts.up.sql\n002-generations.up.sql\n\n"))))
      "each name the image lists becomes a URL under the directory, and a blank line is no entry")
  (is (= ["resource:/shop/migration/001-accounts.up.sql"]
         (mapv str (resauce/url-dir (image-dir "/shop/migration/" "001-accounts.up.sql\n"))))
      "a directory URL that already ends in a slash gains no second one")
  (is (= ["resource:/0!/shop/migration/001-accounts.up.sql"]
         (mapv str (resauce/url-dir (image-dir "/0!/shop/migration" "001-accounts.up.sql\n"))))
      "on GraalVM 25.0.4 a directory carries its module, /0!, and the children keep it once")
  (let [child (first (resauce/url-dir (image-dir "/shop/migration" "001-accounts.up.sql\n")))]
    (is (= "001-accounts.up.sql\n" (slurp child))
        "and a child keeps the directory's handler, so it can be read, which a URL built from a string could not")))

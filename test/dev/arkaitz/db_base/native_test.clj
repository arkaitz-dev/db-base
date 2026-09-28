(ns dev.arkaitz.db-base.native-test
  "The native image's directory listing, through resauce's own dispatch. A JVM never
  produces a `resource:` URL, so the URL here carries a handler of the test's own that
  answers what an image answers for a registered directory: its entries' names, one per
  line."
  (:require [clojure.test :refer [deftest is]]
            [dev.arkaitz.db-base.native]
            [resauce.core :as resauce])
  (:import [java.io ByteArrayInputStream]
           [java.net URI URL URLConnection URLStreamHandler]))

(defn- image-dir
  "A `resource:` URL for `path` whose connection reads `listing`, the text an image
  gives for a directory."
  [path listing]
  (URL/of (URI. (str "resource:" path))
          (proxy [URLStreamHandler] []
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
  (let [child (first (resauce/url-dir (image-dir "/shop/migration" "001-accounts.up.sql\n")))]
    (is (= "001-accounts.up.sql\n" (slurp child))
        "and a child keeps the directory's handler, so it can be read, which a URL built from a string could not")))

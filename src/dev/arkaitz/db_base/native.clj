(ns dev.arkaitz.db-base.native
  "Optional, like the Integrant namespace: requiring it is the opt-in. It teaches resauce,
  through which ragtime and this library list a migration prefix, to list a directory
  inside a GraalVM native image, where a classpath resource is a `resource:` URL and
  resauce's `url-dir` knows only `file:` and `jar:` — so without it every boot of a
  binary fails saying the migrations under the prefix could not be loaded (found
  2026-09-28 by building the host template's native image).

  A directory registered in an image reads as the names of its entries, one per line,
  which is what this lists. A subdirectory comes back without the trailing slash a jar's
  listing gives it, so a migration prefix must hold files only, which `start` already
  asks for. The method is inert on a JVM, which never produces the protocol; resauce's
  multimethod is a stable third party's port, and `:resource`, unlike a key of ours, is
  bare — whoever loads last wins if resauce ever defines it."
  (:require [clojure.string :as str]
            [resauce.core :as resauce])
  (:import [java.io BufferedReader InputStreamReader]
           [java.net URL]))

(defmethod resauce/url-dir "resource" [^URL url]
  ;; Children are resolved against the directory's own URL, so they keep its handler: a
  ;; `resource:` URL cannot be built from a string on a JVM that has none registered.
  (let [path (.getPath url)
        dir  (URL. url (if (str/ends-with? path "/") path (str path "/")))]
    ;; Read through the URL itself, never clojure.java.io, which §5's scan keeps out of
    ;; src: this library opens nothing it was not handed, and it was handed this URL.
    (with-open [reader (BufferedReader. (InputStreamReader. (.openStream url) "UTF-8"))]
      (into [] (comp (remove str/blank?) (map #(URL. dir ^String %))) (line-seq reader)))))

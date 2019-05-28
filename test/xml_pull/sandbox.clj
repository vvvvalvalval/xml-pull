(ns xml-pull.sandbox
  (:require [xml-pull.utils :as u]
            [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.util ArrayList)))

;; ------------------------------------------------------------------------------
;; Version 2

(comment
  (do
    (require '[clojure.java.io :as io])
    (require '[clojure.data.xml :as xml])
    (require 'clojure.xml)
    (require '[clj-async-profiler.core :as prof]))



  (time
    (dotimes [_ 100]
      (pull2 expr
        (xml/parse-str xml-str))))

  (time
    (dotimes [_ 10000]
      (pull2 expr xml-tree)))

  (let [f (compile-query expr)]
    (time
      (dotimes [_ 100]
        (f
          (xml/parse-str xml-str)))))

  (let [f (compile-query expr)]
    (time
      (dotimes [_ 10000]
        (f xml-tree))))

  (def xml-files
    (->>
      (io/file "../linnaeus/linnaeus/imports-resources/extremal-samples/hindawi-corpus-2019-02-14--extremal-sample-10/")
      file-seq
      (filter #(-> % .getName (str/ends-with? ".xml")))
      (take 100)
      vec))

  (def xml-trees
    (->> xml-files
      (mapv #(xml/parse (io/input-stream %)))
      time))

  (let [f (compile-query expr)]
    (time
      (dotimes [_ 1]
        (->> xml-files
          (mapv #(clojure.xml/parse (io/input-stream %)))
          (run! f)))))

  (time
    (dotimes [_ 100]
      (run!
        #(pull2 expr %)
        xml-trees)))

  (def zipcodes-bytes
    (.getBytes
      (slurp "./census-population-zipcodes.xml")
      StandardCharsets/UTF_8))

  (def continue? (atom true))
  (reset! continue? false)



  (prof/profile
    (dotimes [_ 1000]
      (with-open [is (io/input-stream zipcodes-bytes)]
        (clojure.xml/parse is))))

  (prof/serve-files 8080)

  (future
    (while @continue?))

  (defn parse-integer
    [^String s]
    (Long/parseLong s 10))

  (defn parse-double
    [^String s]
    (Double/parseDouble s))

  (def parse1
    (compile-query
      {:xml-pull.opts.error-reporting/throw? true}
      (query
        [(to-tag :row no-key
           [(to-tag :row tag-many (as-key :cities)
              [(to-attr :_id (as-key :city/id))
               (to-attr :_uuid (as-key :city/uuid))
               (to-tag-content-1 :zip_code :city/zip-code)
               (to-tag-content-1 :total_population :city/total-population parse-integer)
               (to-tag-content-1 :median_age :city/median-age parse-double)
               (to-tag-content-1 :total_males :city/total-males parse-integer)
               (to-tag-content-1 :total_females :city/total-females parse-integer)
               (to-tag-content-1 :total_households :city/total-households parse-integer)
               (to-tag-content-1 :average_household_size :city/average-households parse-double)])])
         (to-tag :row no-key
           [(to-tag :row tag-many (as-key :cities_light)
              [(to-attr :_id (as-key :city/id))
               (to-tag-content-1 :zip_code :city/zip-code)
               (to-tag-content-1 :total_population :city/total-population parse-integer)])])]
        (post-process
          #(assoc % :n_cities (count (:cities %)))))))

  (with-open [is (io/input-stream zipcodes-bytes)]
    (parse1 (xml/parse #_clojure.xml/parse is)))

  (prof/profile
    (dotimes [_ 1000]
      (with-open [is (io/input-stream zipcodes-bytes)]
        (parse1 (clojure.xml/parse is)))))

  (prof/profile
    (dotimes [_ 1000]
      (with-open [is (io/input-stream zipcodes-bytes)]
        (parse1
          ( #_clojure.xml/parse xml/parse is)))))

  *1

  *e)


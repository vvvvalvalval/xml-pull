(ns xml-pull.query-test
  (:require [clojure.test :refer :all]
            [xml-pull.engines.jvm-default :as xpe]
            [xml-pull.query-dsl :as xpd]
            [clojure.xml]
            [clojure.java.io :as io]))

(defn example-person-parsed
  []
  (with-open [is (io/input-stream
                   (io/resource "example-person.xml"))]
    (clojure.xml/parse is)))

(defn reports-error-type?
  [pull-result err-type]
  (-> (->> pull-result
        :xml-pull.result/errors
        (map ex-data)
        (map :xml-pull/error-type)
        set)
    (contains? err-type)))

(def person-info-query
  (xpd/query
    [(xpd/to-attr "id" (xpd/as-key :person/id))
     (xpd/to-tag-content-1 "first-name" :person/first-name)
     (xpd/to-tag-content-1 "last-name" :person/last-name)
     (xpd/to-tag "contact-infos" xpd/no-key
       [(xpd/to-tag-with-attr "contact-info" "type" "email" xpd/no-key
          [(xpd/to-content-1 (xpd/as-key :person/email))])
        (xpd/to-tag-with-attr "contact-info" "type" "phone-number" xpd/no-key
          [(xpd/to-content-1 (xpd/as-key :person/phone-number))])])
     (xpd/to-tag "hobbies" xpd/no-key
       [(xpd/to-tag "hobby" xpd/tag-many (xpd/as-key :person/hobbies)
          [(xpd/to-content-1 (xpd/as-key :hobby/name))]
          (xpd/post-process #(mapv :hobby/name %)))])]))

(deftest person-example
  (testing "Nominal case"
    (let [parse-person
          (xpe/compile-query
            {}
            (xpd/query
              [(xpd/to-attr "id" (xpd/as-key :person/id))
               (xpd/to-tag-content-1 "first-name" :person/first-name)
               (xpd/to-tag-content-1 "last-name" :person/last-name)
               (xpd/to-tag "contact-infos" xpd/no-key
                 [(xpd/to-tag-with-attr "contact-info" "type" "email" xpd/no-key
                    [(xpd/to-content-1 (xpd/as-key :person/email))])
                  (xpd/to-tag-with-attr "contact-info" "type" "phone-number" xpd/no-key
                    [(xpd/to-content-1 (xpd/as-key :person/phone-number))])])
               (xpd/to-tag "hobbies" xpd/no-key
                 [(xpd/to-tag "hobby" xpd/tag-many (xpd/as-key :person/hobbies)
                    [(xpd/to-content-1 (xpd/as-key :hobby/name))]
                    (xpd/post-process #(mapv :hobby/name %)))])]))]
      (is
        (=
          (parse-person
            (example-person-parsed))
          {:person/id "a9b051d2-4499-484d-a0eb-f4f672f88180",
           :person/first-name "John",
           :person/last-name "Doe",
           :person/email "john.doe@yahoo.com",
           :person/phone-number "+33 4 56 09 12 88",
           :person/hobbies ["Guitar" "Rock-climbing" "Gardening"],
           :xml-pull.result/errors []}))))
  (testing "Wrong cardinality"
    (let [pull (xpe/compile-query
                 {}
                 (xpd/query
                   [(xpd/to-tag "contact-infos" xpd/no-key
                      [(xpd/to-tag "contact-info" xpd/no-key
                         [(xpd/to-content-1 (xpd/as-key :person/contact))])])]))]
      (is
        (reports-error-type?
          (pull (example-person-parsed))
          :xml-pull.error-types/wrong-cardinality)))))



(comment

  *e)


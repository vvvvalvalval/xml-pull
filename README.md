# xml-pull

A declarative, data-based query language and effiencient query engine for pulling Clojure data structures out of XML documents.

**Status:** alpha quality - breaking changes are possible.

## Rationale

At the time of writing, extracting information from XML documents in Clojure is impractical on several accounts.
 There's a semantic mismatch between XML trees and the EDN-ish associative data structures favoured by Clojure's standard library.
 [clojure.zip](https://clojure.github.io/clojure/clojure.zip-api.html) is the most common way of querying XML in Clojure,
 but it's not very efficient, and Zippers don't address all information extraction needs very well (they're good at diving
 deeply in an XML document for fetching one type of information at a time, not so much extracting and re-bundling several
 types of information); Zippers are also not very declarative, as they're predicate-based rather than data-based.

On the other hand, Clojure and its ecosystem offer very good facilities for dealing with EDN-style data.
 This suggests making a library which sole purpose is to bridge the gap between XML documents and associative data structures.

`xml-pull` does so by:

1. providing a high-level query language, inspired by [Datomic Pull](https://docs.datomic.com/on-prem/pull.html),
 for declaring how to traverse an XML tree and what data to collect along the way;
2. making this query language based on data structures, for programmability;
3. providing an efficient execution engine for this language, in particular via ahead-of-time compilation;
4. limiting itself to very basic capabilities for validating / re-shaping the data: once you have you data in EDN-style data structures,
 you have a wealth of existing solutions for that, therefore `xml-pull` will do no more than giving you the opportunity
 to use them without compromising on performance;
5. not taking care of text parsing - there's already [clojure.xml](https://clojuredocs.org/clojure.xml/parse) and [clojure.data.xml](https://github.com/clojure/data.xml) for that,
 so `xml-pull` deals with the output of those.


## Usage

Imagine you start from the following input:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<person id="a9b051d2-4499-484d-a0eb-f4f672f88180">
  <first-name>John</first-name>
  <last-name>Doe</last-name>
  <contact-infos>
    <contact-info type="email">john.doe@yahoo.com</contact-info>
    <contact-info type="phone-number">+33 4 56 09 12 88</contact-info>
  </contact-infos>
  <hobbies>
    <hobby>Guitar</hobby>
    <hobby>Rock-climbing</hobby>
    <hobby>Gardening</hobby>
  </hobbies>
  <address>
    <city>Babylon</city>
  </address>
</person>
```

You would like the following output:

```clojure
{:person/id "a9b051d2-4499-484d-a0eb-f4f672f88180"
 :person/first-name "John"
 :person/last-name "Doe"
 :person/email "john.doe@yahoo.com"
 :person/phone-number "+33 4 56 09 12 88"
 :person/hobbies ["Guitar" "Rock-climbing" "Gardening"]}
```

You do so by defining a _query_ for extracting the desired information.
 An xml-pull query is a data structure declaring how to traverse an XML document and what data to collect along the way.
 xml-pull queries are a bit verbose as data structures, so for convenience we'll define this one with the small functional
 DSL provided by xml-pull:

```clojure
(require '[xml-pull.query-dsl :as xpd]

(def person-info-query
  (xpd/query
    [(xpd/to-attr :id (xpd/as-key :person/id))
     (xpd/to-tag-content-1 :first-name :person/first-name)
     (xpd/to-tag-content-1 :first-name :person/last-name)
     (xpd/to-tag :contact-infos xpd/no-key
       [(xpd/to-tag-with-attr :contact-info :type "email" xpd/no-key
          [(xpd/to-content-1 (xpd/as-key :person/email))])
        (xpd/to-tag-with-attr :contact-info :type "phone-number" xpd/no-key
          [(xpd/to-content-1 (xpd/as-key :person/phone-number))])])
     (xpd/to-tag :hobbies xpd/no-key
       [(xpd/to-tag :hobby xpd/tag-many (xpd/as-key :person/hobbies)
          [(xpd/to-content-1 (xpd/as-key :hobby/name))]
          (xpd/post-process #(mapv :hobby/name %)))])]))
```

We can now _compile_ that query into a function:

```clojure
(require '[xml-pull.engines.jvm-default :as xpe])

(def pull-person-info
  (xpe/compile {} person-info-query))
```

This function can now be called on the output of e.g `clojure.data.xml/parse-str`:

```clojure
(require '[clojure.data.xml :as xml])

(pull-person-info
  (xml/parse-str
    (slurp "person_john-doe.xml")))
=> {:person/id "a9b051d2-4499-484d-a0eb-f4f672f88180"
    :person/first-name "John"
    :person/last-name "Doe"
    :person/email "john.doe@yahoo.com"
    :person/phone-number "+33 4 56 09 12 88"
    :person/hobbies ["Guitar" "Rock-climbing" "Gardening"]
    :xml-pull.result/errors []}
```

Notice the `:xml-pull.result/errors` key in the result.
 By default, if an Exception is thrown during processing, `xml-pull` will put it here
 instead of aborting the query; this allows you to deal with partial failure.

For transparency, here's the `person-info-query` query we defined with the DSL:

```clojure
person-info-query
=>
#:xml-pull.query{:paths [#:xml-pull.path{:type :xml-pull.path-type/attr, :attr :id, :key :person/id}
                         #:xml-pull.path{:type :xml-pull.path-type/content-tag,
                                         :tag :first-name,
                                         :no-key true,
                                         :query #:xml-pull.query{:paths [#:xml-pull.path{:type :xml-pull.path-type/content-1,
                                                                                         :key :person/first-name}]}}
                         #:xml-pull.path{:type :xml-pull.path-type/content-tag,
                                         :tag :first-name,
                                         :no-key true,
                                         :query #:xml-pull.query{:paths [#:xml-pull.path{:type :xml-pull.path-type/content-1,
                                                                                         :key :person/last-name}]}}
                         #:xml-pull.path{:type :xml-pull.path-type/content-tag,
                                         :tag :contact-infos,
                                         :no-key true,
                                         :query #:xml-pull.query{:paths [#:xml-pull.path{:type :xml-pull.path-type/content-tag-with-attr,
                                                                                         :tag :contact-info,
                                                                                         :attr :type,
                                                                                         :attr-value "email",
                                                                                         :no-key true,
                                                                                         :query #:xml-pull.query{:paths [#:xml-pull.path{:type :xml-pull.path-type/content-1,
                                                                                                                                         :key :person/email}]}}
                                                                         #:xml-pull.path{:type :xml-pull.path-type/content-tag-with-attr,
                                                                                         :tag :contact-info,
                                                                                         :attr :type,
                                                                                         :attr-value "phone-number",
                                                                                         :no-key true,
                                                                                         :query #:xml-pull.query{:paths [#:xml-pull.path{:type :xml-pull.path-type/content-1,
                                                                                                                                         :key :person/phone-number}]}}]}}
                         #:xml-pull.path{:type :xml-pull.path-type/content-tag,
                                         :tag :hobbies,
                                         :no-key true,
                                         :query #:xml-pull.query{:paths [{:xml-pull.path/type :xml-pull.path-type/content-tag,
                                                                          :xml-pull.path/tag :hobby,
                                                                          :xml-pull.tag/cardinality :tag.cardinality/many,
                                                                          :xml-pull.path/key :person/hobbies,
                                                                          :xml-pull.path/query #:xml-pull.query{:paths [#:xml-pull.path{:type :xml-pull.path-type/content-1,
                                                                                                                                        :key :hobby/name}]},
                                                                          :xml-pull/post-process-fn #object[xml_pull.query_test$fn__2060
                                                                                                            0x534b9e3f
                                                                                                            "xml_pull.query_test$fn__2060@534b9e3f"]}]}}]}
```

## License

Copyright © 2016 Valentin Waeselynck and contributors

Distributed under the MIT License.
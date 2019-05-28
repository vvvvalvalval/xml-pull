(ns xml-pull.query-dsl
  "A function-based Domain-Specific Language for writing queries more concisely.

  Many functions have variadic `& clauses` arguments; by default, these clauses
  must be maps, which will be merged in the returned map.

  In specific cases, other type of clauses are accepted and will be treated specially;
  for example, in the `to-tag[...]` functions, a vector clause is interpreted as the list
  of children tag for the subquery.")

(defn query
  [& clauses]
  (reduce merge {}
    (map
      (fn [c]
        (cond
          (vector? c) {:xml-pull.query/paths (vec c)}
          :else c))
      clauses)))

(defn query-paths
  "Declares the paths to navigate to in this Query.
  Use this as a clause in a Query map."
  [paths]
  {:xml-pull.query/paths (vec paths)})

(defn query-label
  "Associates a label to this query, intended for debugging (and in the future for recursion).
  Use this as a clause in a Query map."
  [l]
  {:xml-pull.query/label l})

(defn as-key
  "Sets the key that will be assoc'ed to the result of navigating to this path.
  Use this as a clause in a Path map."
  [k]
  {:xml-pull.path/key k})

(def no-key
  "Declares that the result of navigating to this path should not be assoc'ed at a key,
  but is actually a map that must be merged into the result."
  {:xml-pull.path/no-key true})

(defn post-process
  [f]
  {:xml-pull/post-process-fn f})

(defn- to-tag-path
  [base clauses]
  (reduce merge
    base
    (map (fn [c]
           (cond
             (vector? c) {:xml-pull.path/query {:xml-pull.query/paths c}}
             :else c))
      clauses)))

(defn to-tag
  "A Path that navigates to children of the current element having the given tag."
  [tag & clauses]
  (to-tag-path
    {:xml-pull.path/type :xml-pull.path-type/content-tag
     :xml-pull.path/tag tag}
    clauses))

(defn to-tag-with-attr
  "A Path that navigates to children of the current element having the given tag, and a specific value for a given attribute."
  [tag attr-kw attr-value & clauses]
  (to-tag-path
    {:xml-pull.path/type :xml-pull.path-type/content-tag-with-attr
     :xml-pull.path/tag tag
     :xml-pull.path/attr attr-kw
     :xml-pull.path/attr-value attr-value}
    clauses))

(defn to-tag-matching
  "A Path that navigates to children of the current element matching the given predicate. Generic but not as efficient as other, more specialized paths."
  [pred & clauses]
  (to-tag-path
    {:xml-pull.path/type :xml-pull.path-type/content-elements-matching
     :xml-pull.path/pred pred}
    clauses))

(defn to-current-element
  "A Path that navigates to the current XML element."
  [& clauses]
  (to-tag-path
    {:xml-pull.path/type :xml-pull.path-type/to-current-element}
    clauses))

(def tag-many
  "Declares that the children XML elements matched by this Path may be repeated several times;
  consequently the returned values for each element will be assembled in a vector.

  Use this as a clause in a Path map."
  {:xml-pull.tag/cardinality :tag.cardinality/many})

(defn path-query
  [& clauses]
  {:xml-pull.path/query
   (apply query clauses)})

(defn to-content-1
  "A Path that navigates to the first value in the :content of the XML element."
  [& clauses]
  (reduce merge
    {:xml-pull.path/type :xml-pull.path-type/content-1}
    clauses))

(defn to-attr
  "A Path that navigates to the value of an attribute of the current XML element.
  The default `:xml-pull.path/key` is the name of the attribute."
  [attr-kw & clauses]
  (reduce merge
    {:xml-pull.path/type :xml-pull.path-type/attr,
     :xml-pull.path/attr attr-kw}
    clauses))

(defn to-tag-content-1
  "A shorthand for navigating to a child tag then to its content,
  which is the most common way of 'looking up a key' in XML.

  An optional post-processing function can be supplied to transform the read value."
  ([tag ret-key]
   (to-tag tag no-key
     [(to-content-1 (as-key ret-key))]))
  ([tag ret-key post-process-fn]
   (to-tag tag no-key
     [(to-content-1 (as-key ret-key) (post-process post-process-fn))])))

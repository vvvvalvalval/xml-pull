(ns xml-pull.engines.jvm-default
  (:import (java.util ArrayList)))

(comment
  (set! *warn-on-reflection* true))

(defn get-safe*
  [m m-sym k pred pred-sym]
  (if (map? m)
    (let [v (get m k)]
      (if (pred v)
        v
        (throw
          (if (contains? m k)
            (ex-info
              (str "Missing key " (pr-str k) " in " (pr-str m-sym) ".")
              {m-sym m})
            (ex-info
              (str "Invalid " (pr-str k) " in " (pr-str m-sym) ", failed predicate: " (pr-str pred-sym) ".")
              {m-sym m})))))
    (throw
      (ex-info
        (str (pr-str m-sym) " must be a map.")
        {m-sym m}))))

(defmacro get-safe
  ([m k pred]
   (assert (symbol? m))
   (assert (keyword? k))
   (assert (symbol? pred))
   `(get-safe* ~m '~m ~k ~pred '~pred)))

(defn compile-query
  "Validates and compiles a query to a function that accepts a parsed XML element,
  and returns a Clojure map."
  [{:as _opts
    throw-errors? :xml-pull.opts.error-reporting/throw?}
   q]
  (letfn [(sequential-or-nil? [v]
            (or (nil? v) (sequential? v)))
          (compile-subquery [q]
            (let [a_next-i (atom 0)
                  a_tag->i (atom {})
                  compiled-paths
                  (->> (get-safe q :xml-pull.query/paths sequential-or-nil?)
                    (mapv (fn [path]
                            (let [post-process (get path :xml-pull/post-process-fn identity)]
                              (letfn [(into-transient-map!
                                        [tm m]
                                        (reduce-kv
                                          (fn [tm k v]
                                            (assoc! tm k v))
                                          tm m))
                                      (add-to-ret-fn [default-key]
                                        (if (:xml-pull.path/no-key path)
                                          (fn add-to-ret-nokey [report-error! ret v]
                                            (cond
                                              (nil? v) ret
                                              (map? v) (into-transient-map! ret v)
                                              :else
                                              (do
                                                (report-error!
                                                  (ex-info
                                                    (str "When " (pr-str :xml-pull.path/no-key) "is used, the post-processed value should be a map.")
                                                    {:xml-pull/error-type :xml-pull.error-types/post-processed-value-should-be-a-map
                                                     :v v}))
                                                ret)))
                                          (let [ret-key
                                                (if (nil? default-key)
                                                  (get-safe path :xml-pull.path/key some?)
                                                  (get path :xml-pull.path/key default-key))]
                                            (fn add-to-ret-key [report-error! ret v]
                                              (assoc! ret ret-key v)))))
                                      (handle-raw-v-fn [default-key]
                                        (let [add-to-result (add-to-ret-fn default-key)]
                                          (fn handle-raw-v [report-error! ret raw-v]
                                            (let [v (try
                                                      (post-process raw-v)
                                                      (catch Throwable err
                                                        (report-error!
                                                          (ex-info
                                                            "The user-supplied post-process function failed."
                                                            {:xml-pull/error-type :xml-pull.error-types/post-process-failed
                                                             :xml-pull/path (dissoc path :xml-pull.path/query)
                                                             :raw-value raw-v}
                                                            err))
                                                        nil))]
                                              (add-to-result report-error! ret v)))))

                                      (leaf-path-fn [get-raw-v default-key]
                                        (let [handle-raw-v (handle-raw-v-fn default-key)]
                                          (fn add-leaf [report-error! xml-tree _d_tag->children ret]
                                            (if-some [raw-v (get-raw-v xml-tree)]
                                              (handle-raw-v report-error! ret raw-v)
                                              ret))))
                                      (internal-path-fn [tag children-pred default-key]
                                        (let [tag-i (when (some? tag)
                                                      (or
                                                        (get @a_tag->i tag)
                                                        (let [i @a_next-i]
                                                          (swap! a_next-i inc)
                                                          (swap! a_tag->i assoc tag i)
                                                          i)))
                                              csubq (compile-subquery
                                                      (:xml-pull.path/query path))
                                              handle-raw-v (handle-raw-v-fn default-key)
                                              card-many? (-> path :xml-pull.tag/cardinality (= :tag.cardinality/many))]
                                          (fn internal-path [report-error! xml-tree d_tag->children ret]
                                            (if-some [es (if (some? tag-i)
                                                           #_(get @d_tag->children tag)
                                                           (if-some [l (aget ^objects @d_tag->children tag-i)]
                                                             (->
                                                               (seq
                                                                 (.toArray ^ArrayList l))
                                                               (cond->
                                                                 (some? children-pred)
                                                                 (->> (into []
                                                                        (filter children-pred)))))
                                                             nil)
                                                           (->> xml-tree
                                                             :content
                                                             (remove string?)
                                                             (filter children-pred)
                                                             seq))]
                                              (if card-many?
                                                (let [raw-v
                                                      (mapv
                                                        (fn call-subquery [child]
                                                          (csubq report-error! child))
                                                        es)]
                                                  (handle-raw-v report-error! ret raw-v))
                                                (if (> (count es) 1)
                                                  (do
                                                    (report-error!
                                                      (ex-info
                                                        (str "Found more than one matching child element.")
                                                        {:xml-pull/error-type :xml-pull.error-types/wrong-cardinality
                                                         :xml-pull/path (dissoc path :xml-pull.path/query)}))
                                                    ret)
                                                  (let [raw-v (csubq report-error! (first es))]
                                                    (handle-raw-v report-error! ret raw-v))))
                                              ret))))]
                                (case (get-safe path :xml-pull.path/type keyword?)
                                  :xml-pull.path-type/attr
                                  (let [;; IMPROVEMENT more error infomation. (Val, 26 May 2019)
                                        attr-k (get-safe path :xml-pull.path/attr keyword?)]
                                    (leaf-path-fn
                                      (fn get-attr-k [xml-tree] (-> xml-tree :attrs (get attr-k)))
                                      attr-k))
                                  :xml-pull.path-type/content-1
                                  (leaf-path-fn
                                    (fn get-content-1 [xml-tree] (-> xml-tree :content first))
                                    nil)
                                  :xml-pull.path-type/content
                                  (leaf-path-fn
                                    (fn get-content [xml-tree] (-> xml-tree :content))
                                    nil)
                                  :xml-pull.path-type/to-current-element
                                  (leaf-path-fn
                                    (fn get-self-element [xml-tree] xml-tree)
                                    nil)


                                  :xml-pull.path-type/content-tag
                                  (let [tag (get-safe path :xml-pull.path/tag keyword?)]
                                    (internal-path-fn tag
                                      (constantly true)
                                      tag))
                                  :xml-pull.path-type/content-tag-with-attr
                                  (let [tag (get-safe path :xml-pull.path/tag keyword?)
                                        attr-k (get-safe path :xml-pull.path/attr keyword?)
                                        attr-v (get-safe path :xml-pull.path/attr-value string?)]
                                    (internal-path-fn tag
                                      (fn matches-tag-with-attr [xml-tree]
                                        (= attr-v (get (:attrs xml-tree) attr-k)))
                                      nil))
                                  :xml-pull.path-type/content-elements-matching
                                  (let [pred (get-safe path :xml-pull.path/pred some?)]
                                    (internal-path-fn nil pred nil))

                                  (throw
                                    (ex-info
                                      (str "Invalid path type: " (pr-str (:xml-pull.path/type path)))
                                      {:xml-pull/error-type :xml-pull/invalid-path-type
                                       :xml-pull/path path})))))))
                    (object-array))
                  tag->i @a_tag->i
                  n-k->i @a_next-i
                  post-process (:xml-pull/post-process-fn q)]
              (fn run-subquery [report-error! xml-tree]
                (let [d_tag->children
                      (delay
                        (let [ret (object-array n-k->i)]
                          (doseq [child (:content xml-tree)]
                            (when-not (string? child)
                              (when-some [tag-i (get tag->i (:tag child))]
                                (if-some [^ArrayList l (aget ret tag-i)]
                                  (.add l child)
                                  (aset ret tag-i
                                    (doto (ArrayList.)
                                      (.add child)))))))
                          ret))
                      raw-v
                      (persistent!
                        (areduce compiled-paths idx ret (transient {})
                          (let [add-from-path (aget compiled-paths idx)]
                            (add-from-path report-error! xml-tree d_tag->children ret))))]
                  (if (some? post-process)
                    (try
                      (post-process raw-v)
                      (catch Throwable err
                        (report-error!
                          (ex-info
                            "The user-supplied post-process function failed."
                            {:xml-pull/error-type :xml-pull.error-types/post-process-failed
                             :xml-pull/query (dissoc q :xml-pull.query/paths)
                             :raw-value raw-v}
                            err))
                        nil))
                    raw-v)))))]
    (let [compiled-q (compile-subquery q)]
      (fn run-query [xml-tree]
        (let [a-errors (atom [])
              report-error! (if throw-errors?
                              (fn throw-err [err]
                                (throw err))
                              #(swap! a-errors conj %))]
          (let [raw-v (compiled-q report-error! xml-tree)
                errors @a-errors]
            (if-not (map? raw-v)
              (throw (ex-info
                       (str "The top-level returned value should be a map, so that "
                         (pr-str :xml-pull.result/errors)
                         " can be added to it.")
                       {:xml-pull/error-type :xml-pull.error-types/post-process-failed
                        :xml-pull/query (dissoc q :xml-pull.query/paths)
                        :raw-value raw-v
                        :xml-pull.result/errors errors}))
              (cond-> raw-v
                (not throw-errors?)
                (assoc :xml-pull.result/errors errors)))))))))


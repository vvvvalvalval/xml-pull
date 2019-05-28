(ns xml-pull.utils)


;; ------------------------------------------------------------------------------
;; Utils

(defn index-by
  [f coll]
  (persistent!
    (reduce
      (fn [tm v]
        (assoc! tm (f v) v))
      (transient {}) coll)))

(defn index-and-map-by
  [kf vf coll]
  (persistent!
    (reduce
      (fn [tm v]
        (assoc! tm
          (kf v)
          (vf v)))
      (transient {}) coll)))

(defn group-and-map-by
  [kf vf coll]
  (persistent!
    (reduce
      (fn [ret x]
        (let [k (kf x)
              v (vf x)]
          (assoc! ret k (conj (get ret k []) v))))
      (transient {}) coll)))


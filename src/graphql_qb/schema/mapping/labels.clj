(ns graphql-qb.schema.mapping.labels
  "Creates GraphQL schema mappings from the labels associated with types and values"
  (:require [clojure.spec.alpha :as s]
            [graphql-qb.util :as util]
            [clojure.string :as string]
            [clojure.pprint :as pp])
  (:import [clojure.lang IPersistentMap]))

;;TODO: add/use spec for graphql enum values
(s/def ::graphql-enum keyword?)

(defprotocol ArgumentTransform
  (transform-argument [this graphql-value]))

(defprotocol ResultTransform
  (transform-result [this inner-value]))

(defrecord EnumMappingItem [name value label])

(defrecord EnumMapping [items]
  ArgumentTransform
  (transform-argument [_this graphql-value]
    (->> items
        (util/find-first (fn [{:keys [name]}]
                           (= name graphql-value)))
         (:value)))

  ResultTransform
  (transform-result [_this result]
    (->> items
         (util/find-first (fn [{:keys [value]}]
                            (= value result)))
         (:name))))

(defn map-transform [tm m trans-fn]
  (into {} (map (fn [[k transform]]
                  (let [v (get m k)]
                    [k (trans-fn transform v)]))
                tm)))

(defrecord FnTransform [f]
  ArgumentTransform
  (transform-argument [_this v] (f v))

  ResultTransform
  (transform-result [_this r] (f r)))

(defn ftrans [f] (->FnTransform f))
(def idtrans (->FnTransform identity))

#_(extend-type IPersistentMap
  ArgumentTransform
  (transform-argument [tm m] (map-transform tm m #(transform-argument %1 %2)))

  ResultTransform
  (transform-result [tm m] (map-transform tm m #(transform-result %1 %2))))

;;TODO: move/remove types/get-identifier-segments
(defn get-identifier-segments [label]
  (let [segments (re-seq #"[a-zA-Z0-9]+" (str label))]
    (if (empty? segments)
      (throw (IllegalArgumentException. (format "Cannot construct identifier from label '%s'" label)))
      (let [first-char (ffirst segments)]
        (if (Character/isDigit first-char)
          (cons "a" segments)
          segments)))))

;;TODO: remove types/segments->enum-value
(defn- segments->enum-value [segments]
  (->> segments
       (map string/upper-case)
       (string/join "_")
       (keyword)))

(defn label->enum-name
  ([label]
   (segments->enum-value (get-identifier-segments label)))
  ([label n]
   (let [label-segments (get-identifier-segments label)]
     (segments->enum-value (concat label-segments [(str n)])))))

;;TODO: remove core/code-list->enum-items
(defn create-enum-mapping [code-list]
  (let [by-enum-name (group-by #(label->enum-name (:label %)) code-list)
        items (mapcat (fn [[enum-name item-results]]
                        (if (= 1 (count item-results))
                          (map (fn [{:keys [member label]}]
                                 (->EnumMappingItem enum-name member label))
                               item-results)
                          (map-indexed (fn [n {:keys [member label]}]
                                         (->EnumMappingItem (label->enum-name label (inc n)) member label))
                                       item-results)))
                      by-enum-name)]
    (->EnumMapping (vec items))))

(ns graphql-qb.resolvers
  (:require [graphql-qb.queries :as queries]
            [graphql-qb.types :as types]
            [graphql-qb.util :as util]
            [grafter.rdf.sparql :as sp]
            [graphql-qb.context :as context]
            [com.walmartlabs.lacinia.schema :as ls]
            [graphql-qb.query-model :as qm]
            [clojure.walk :as walk]
            [com.walmartlabs.lacinia.executor :as executor]
            [clojure.spec.alpha :as s]
            [clojure.pprint :as pp])
  (:import (graphql_qb.types Dimension MeasureType)))

(s/def ::order-direction #{:ASC :DESC})
(s/def ::dimension #(instance? Dimension %))
(s/def ::measure #(instance? MeasureType %))
(s/def ::dimension-measure (s/or :dimension ::dimension :measure ::measure))
(s/def ::order-item (s/cat :dimmeasure ::dimension-measure :direction ::order-direction))
(s/def ::order-by (s/coll-of ::order-item))
(s/def ::dimension-filter (constantly true))                ;TODO: specify properly
(s/def ::dimensions-filter (s/map-of ::dimension ::dimension-filter))

(defn un-namespace-keys [m]
  (walk/postwalk (fn [x]
                   (if (map? x)
                     (util/map-keys (fn [k] (keyword (name k))) x)
                     x)) m))

(defn flatten-selections [m]
  (walk/postwalk (fn [x]
                   (if (and (map? x) (contains? x :selections))
                     (:selections x)
                     x)) m))

(defn get-selections [context]
  (-> context (executor/selections-tree) (un-namespace-keys) (flatten-selections)))

(defn get-observation-selections [context]
  (get-in (get-selections context) [:page :observations]))

(defn get-observation-count [repo ds-uri dim-filter]
  (let [query (queries/get-observation-count-query ds-uri dim-filter)
        results (util/eager-query repo query)]
    (:c (first results))))

(defn resolve-observations [context args {:keys [uri] :as ds-field}]
  (let [repo (context/get-repository context)
        dimension-filter (::dimensions-filter args)
        total-matches (get-observation-count repo uri dimension-filter)]
    (merge
      (select-keys args [::dimensions-filter ::order-by])
      {::dataset                     ds-field
       ::observation-selections      (get-observation-selections context)
       :total_matches                total-matches
       :aggregations                 {::dimensions-filter dimension-filter :ds-uri uri}})))

(defn resolve-observations-sparql-query [_context _args obs-field]
  (let [#::{:keys [dataset observation-selections order-by dimensions-filter]} obs-field]
    (queries/get-observation-query dataset dimensions-filter order-by observation-selections)))

(def default-limit 10)
(def max-limit 1000)

(defn get-limit [args]
  (min (max 0 (or (:first args) default-limit)) max-limit))

(defn get-offset [args]
  (max 0 (or (:after args) 0)))

(defn calculate-next-page-offset [offset limit total-matches]
  (let [next-offset (+ offset limit)]
    (if (> total-matches next-offset)
      next-offset)))

(defn resolve-observations-page [context args observations-field]
  (let [repo (context/get-repository context)
        order-by-dim-measures (::order-by observations-field)
        ds-uri (get-in observations-field [::dataset :uri])
        {:keys [dimensions measures] :as dataset} (context/get-dataset context ds-uri)
        limit (get-limit args)
        offset (get-offset args)
        total-matches (:total_matches observations-field)
        observation-selections (::observation-selections observations-field)
        dimension-filter (::dimensions-filter observations-field)
        query (queries/get-observation-page-query ds-uri dataset dimension-filter limit offset order-by-dim-measures observation-selections)
        results (util/eager-query repo query)
        matches (mapv (fn [{:keys [obs] :as bindings}]
                        (let [field-values (map (fn [{:keys [field-name] :as ft}]
                                                  [field-name (types/project-result ft bindings)])
                                                (concat dimensions measures))]
                          (into {:uri obs} field-values)))
                      results)
        next-page (calculate-next-page-offset offset limit total-matches)]
    {:next_page next-page
     :count     (count matches)
     :observations    matches}))

(defn resolve-datasets [context {:keys [dimensions measures uri] :as args} _parent]
  (let [repo (context/get-repository context)
        q (queries/get-datasets-query dimensions measures uri)
        results (util/eager-query repo q)]
    (map (fn [{:keys [title] :as bindings}]
           (-> bindings
               (util/rename-key :ds :uri)
               (update :issued #(some-> % types/grafter-date->datetime))
               (update :modified #(some-> % types/grafter-date->datetime))
               (assoc :schema (name (types/dataset-label->schema-name title)))))
         results)))

(defn exec-observation-aggregation [repo dataset measure dimension-filter aggregation-fn]
  (let [model (queries/get-observation-filter-model dimension-filter)
        q (qm/get-observation-aggregation-query model aggregation-fn (:uri dataset) (:uri measure))
        results (util/eager-query repo q)]
    (get (first results) aggregation-fn)))

(defn resolve-observations-aggregation [aggregation-fn
                                        context
                                        {:keys [measure] :as args}
                                        {:keys [ds-uri] :as aggregation-field}]
  (let [repo (context/get-repository context)
        dataset (context/get-dataset context ds-uri)
        dimension-filter (::dimensions-filter aggregation-field)]
    (exec-observation-aggregation repo dataset measure dimension-filter aggregation-fn)))

(defn resolve-dataset-measures [context _args {:keys [uri] :as ds-field}]
  (let [repo (context/get-repository context)
        results (vec (sp/query "get-measure-types.sparql" {:ds uri} repo))]
    (map (fn [{:keys [mt label]}]
           {:uri       mt
            :label     (str label)
            :enum_name (name (types/enum-label->value-name (str label)))})
         results)))

(defn dimension-enum-value->graphql [{:keys [value label name] :as item}]
  (ls/tag-with-type
    {:uri (str value) :label (str label) :enum_name (clojure.core/name name)}
    :enum_dim_value))

(defn dimension-measure->graphql [{:keys [uri label] :as measure}]
  {:uri   uri
   :label (str label)
   :enum_name  (name (:name (types/to-enum-value measure)))})

(defn dimension->graphql [unmapped-dimensions {:keys [uri type] :as dim}]
  (let [base-dim (dimension-measure->graphql dim)]
    (if (types/is-enum-type? type)
      (assoc base-dim :values (map dimension-enum-value->graphql (:values type)))
      (let [code-list (get unmapped-dimensions uri)]
        (assoc base-dim :values (map (fn [member] (ls/tag-with-type (util/rename-key member :member :uri) :unmapped_dim_value)) code-list))))))

(def measure->graphql dimension-measure->graphql)

(defn resolve-dataset [context {:keys [uri] :as dataset}]
  (context/get-dataset context uri))

(defn resolve-dataset-dimensions [context _args {:keys [uri] :as ds-field}]
  (let [{:keys [dimensions]} (context/get-dataset context uri)
        repo (context/get-repository context)
        unmapped-dimensions (queries/get-unmapped-dimension-values repo ds-field)]
    (map #(dimension->graphql unmapped-dimensions %) dimensions)))

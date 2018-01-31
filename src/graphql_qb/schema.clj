(ns graphql-qb.schema
  (:require [graphql-qb.types :as types]
            [graphql-qb.resolvers :as resolvers]
            [graphql-qb.schema-model :as sm]
            [clojure.pprint :as pp]
            [graphql-qb.schema.mapping.labels :as mapping]))

(def observation-uri-schema-mapping
  {:type :uri
   :description "URI of the observation"
   :->graphql identity})

(defn enum-type-name [dataset {:keys [enum-name] :as enum-type}]
  (types/field-name->type-name enum-name (types/dataset-schema dataset)))

(defn type-schema-type-name [dataset type]
  (cond
    (types/is-ref-area-type? type) :ref_area
    (types/is-ref-period-type? type) :ref_period
    (types/is-enum-type? type) (enum-type-name dataset type)))

(defn type-schema-input-type-name [dataset type]
  (cond
    (types/is-ref-area-type? type) :uri
    (types/is-ref-period-type? type) :ref_period_filter
    (types/is-enum-type? type) (enum-type-name dataset type)))

;;TODO: move to resolvers namespace
(defn argument-mapping-resolver [arg-mapping inner-resolver]
  (fn [context args field]
    (let [mapped-args (mapping/transform-argument arg-mapping args)]
      (inner-resolver context mapped-args field))))

(defn create-aggregation-resolver [aggregation-fn aggregation-measures-enum]
  (let [arg-mapping (mapping/->MapTransform {:measure aggregation-measures-enum})
        inner-resolver (partial resolvers/resolve-observations-aggregation aggregation-fn)]
    (argument-mapping-resolver arg-mapping inner-resolver)))

(defn create-observation-resolver [dataset]
  (fn [context args field]
    (let [mapped-args ((sm/observation-args-mapper dataset) args)]
      (resolvers/resolve-observations context mapped-args field))))

(defn dimension-type-name [dataset dim]
  (type-schema-type-name dataset (:type dim)))

(defn dimension->schema-mapping [dataset {:keys [field-name type doc label] :as dim}]
  (let [->graphql (if (types/is-enum-type? type)
                    (fn [v] (types/to-graphql dim v))
                    identity)]
    {field-name {:type (dimension-type-name dataset dim) :->graphql ->graphql :description (some-> (or doc label) str)}}))

(defn measure->schema-mapping [{:keys [field-name] :as measure}]
  ;;TODO: get measure description?
  {field-name {:type 'String :->graphql str :description ""}})

(defn get-dataset-schema-mapping [{:keys [dimensions measures] :as ds}]
  (let [dim-mappings (map #(dimension->schema-mapping ds %) dimensions)
        measure-mappings (map measure->schema-mapping measures)]
    (into {:uri observation-uri-schema-mapping} (concat dim-mappings measure-mappings))))

(defn apply-schema-mapping [mapping sparql-result]
  (into {} (map (fn [[field-name {:keys [->graphql]}]]
                  [field-name (->graphql (get sparql-result field-name))])
                mapping)))

(defn wrap-observations-mapping [inner-resolver dataset]
  (fn [context args observations-field]
    (let [result (inner-resolver context args observations-field)
          projection (merge {:uri :obs} (types/dataset-result-projection dataset))
          schema-mapping (get-dataset-schema-mapping dataset)
          mapped-result (mapv (fn [obs-bindings]
                                (let [sparql-result (types/project-result projection obs-bindings)]
                                  (apply-schema-mapping schema-mapping sparql-result)))
                              (::resolvers/observation-results result))]
      (assoc result :observations mapped-result))))

(defn create-aggregation-field [field-name aggregation-measures-enum-mapping aggregation-fn]
  {field-name
   {:type    'Float
    :args    {:measure {:type (sm/non-null aggregation-measures-enum-mapping) :description "The measure to aggregate"}}
    :resolve (create-aggregation-resolver aggregation-fn aggregation-measures-enum-mapping)}})

(defn get-aggregations-schema-model [aggregation-measures-enum-mapping]
  {:type
   {:fields
    (merge
      (create-aggregation-field :max aggregation-measures-enum-mapping :max)
      (create-aggregation-field :min aggregation-measures-enum-mapping :min)
      (create-aggregation-field :sum aggregation-measures-enum-mapping :sum)
      (create-aggregation-field :average aggregation-measures-enum-mapping :avg))}})

(defn dataset-observation-dimensions-schema-model [{:keys [dimensions] :as dataset}]
  (into {} (map (fn [{:keys [field-name type] :as dim}]
                  [field-name {:type (type-schema-type-name dataset type)}])
                dimensions)))

;;TODO: combine with dataset-observation-dimensions-schema-model?
(defn dataset-observation-dimensions-input-schema-model [{:keys [dimensions] :as dataset}]
  (into {} (map (fn [{:keys [field-name type] :as dim}]
                  [field-name {:type (type-schema-input-type-name dataset type)}])
                dimensions)))

(defn dataset-observation-schema-model [dataset]
  (let [dimensions-model (dataset-observation-dimensions-schema-model dataset)
        measures (map (fn [{:keys [field-name] :as measure}]
                        [field-name {:type 'String}])
                      (:measures dataset))]
    (into {:uri {:type :uri}}
          (concat dimensions-model measures))))

(defn dataset-order-spec-schema-model [dataset]
  (let [dim-measures (types/dataset-dimension-measures dataset)]
    (into {} (map (fn [{:keys [field-name]}]
                    [field-name {:type :sort_direction}]))
          dim-measures)))

(defn get-observation-schema-model [dataset]
  (let [dimensions-measures-enum-mapping (mapping/dataset-dimensions-measures-enum-group dataset)
        obs-model {:type
                   {:fields
                    {:sparql
                     {:type        'String
                      :description "SPARQL query used to retrieve matching observations."
                      :resolve     :resolve-observation-sparql-query}
                     :page
                     {:type
                      {:fields
                       {:next_page    {:type :SparqlCursor :description "Cursor to the next page of results"}
                        :count        {:type 'Int}
                        :observations {:type [{:fields (dataset-observation-schema-model dataset)}] :description "List of observations on this page"}}}
                      :args        {:after {:type :SparqlCursor}
                                    :first {:type 'Int}}
                      :description "Page of results to retrieve."
                      :resolve     (wrap-observations-mapping resolvers/resolve-observations-page dataset)}
                     :total_matches {:type 'Int}}}
                   :args
                   {:dimensions {:type {:fields (dataset-observation-dimensions-input-schema-model dataset)}}
                    :order      {:type [dimensions-measures-enum-mapping]}
                    :order_spec {:type {:fields (dataset-order-spec-schema-model dataset)}}}
                   :resolve (create-observation-resolver dataset)}
        aggregation-measures-enum-mapping (mapping/dataset-aggregation-measures-enum-group dataset)]
    (if (nil? aggregation-measures-enum-mapping)
      obs-model
      (let [aggregation-fields (get-aggregations-schema-model aggregation-measures-enum-mapping)]
        (assoc-in obs-model [:type :fields :aggregations] aggregation-fields)))))

(defn get-query-schema-model [{:keys [description] :as dataset} dataset-enum-mappings]
  (let [schema-name (types/dataset-schema dataset)
        observations-model (get-observation-schema-model dataset)]
    {schema-name
     {:type
      {:implements  [:dataset_meta]
       :fields      {:uri          {:type :uri :description "Dataset URI"}
                     :title        {:type 'String :description "Dataset title"}
                     :description  {:type 'String :description "Dataset description"}
                     :licence      {:type :uri :description "URI of the licence the dataset is published under"}
                     :issued       {:type :DateTime :description "When the dataset was issued"}
                     :modified     {:type :DateTime :description "When the dataset was last modified"}
                     :publisher    {:type :uri :description "URI of the publisher of the dataset"}
                     :schema       {:type 'String :description "Name of the GraphQL query root field corresponding to this dataset"}
                     :dimensions   {:type        [:dim]
                                    :resolve     (fn [context args _field]
                                                   (resolvers/resolve-dataset-dimensions context args dataset))
                                    :description "Dimensions within the dataset"}
                     :measures     {:type        [:measure]
                                    :description "Measure types within the dataset"}
                     :observations observations-model}
       :description (or description "")}
      :resolve (fn [context args field]
                 (resolvers/resolve-dataset context dataset))}}))

(defn get-dataset-schema [dataset dataset-enum-mapping]
  (let [ds-enums-schema (mapping/dataset-enum-types-schema dataset dataset-enum-mapping)
        enums-schema {:enums ds-enums-schema}

        query-model (get-query-schema-model dataset dataset-enum-mapping)
        query-schema (sm/visit-queries query-model)]
    (-> query-schema
        (sm/merge-schemas enums-schema))))

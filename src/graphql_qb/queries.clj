(ns graphql-qb.queries
  (:require [clojure.string :as string]
            [graphql-qb.types :as types]
            [grafter.rdf.sparql :as sp]
            [graphql-qb.vocabulary :refer :all]
            [graphql-qb.query-model :as qm]
            [graphql-qb.config :as config]
            [graphql-qb.util :as util])
  (:import  [java.net URI]))

(defn get-observation-filter-model [dim-filter]
   (let [m (-> qm/empty-model
                    (qm/add-binding [[:mp (URI. "http://purl.org/linked-data/cube#measureType")]] ::qm/var)
                    (qm/add-binding [[:mv (qm/->QueryVar "mp")]] ::qm/var))]
       (reduce (fn [m [dim value]]
                       (types/apply-filter dim m value))
                     m
                     dim-filter)))

(defn apply-model-projections [filter-model dataset observation-selections config]
  (reduce (fn [m dm]
            (types/apply-projection dm m observation-selections config))
          filter-model
          (types/dataset-dimension-measures dataset)))

(defn apply-model-order-by [model order-by-dims-measures config]
  (reduce (fn [m [dim-measure direction]]
            (types/apply-order-by dim-measure m direction config))
          model
          order-by-dims-measures))

(defn filter-model->observations-query [filter-model dataset order-by observation-selections config]
  (-> filter-model
      (apply-model-projections dataset observation-selections config)
      (apply-model-order-by order-by config)))

(defn get-observation-query [{ds-uri :uri :as dataset} filter-model order-by observation-selections config]
  (let [model (filter-model->observations-query filter-model dataset order-by observation-selections config)]
    (qm/get-query model "obs" ds-uri)))

(defn get-observation-page-query [dataset filter-model limit offset order-by-dim-measures observation-selections config]
  (str
    (get-observation-query dataset filter-model order-by-dim-measures observation-selections config)
    " LIMIT " limit " OFFSET " offset))

(defn get-dimensions-or [{dims-or :or}]
  (if (empty? dims-or)
    (str "{ SELECT DISTINCT ?ds WHERE {"
         "  ?ds a qb:DataSet ."
         "} }")
    (let [union-clauses (map (fn [dim]
                               (str "{ ?struct qb:component ?comp ."
                                    "  ?comp qb:dimension <" dim "> . }"))
                             dims-or)]
      (str
        "{ SELECT DISTINCT ?ds WHERE {"
        "  ?ds a qb:DataSet ."
        "  ?ds qb:structure ?struct ."
        "  ?struct a qb:DataStructureDefinition ."
        (string/join " UNION " union-clauses)
        "} }"))))

(defn get-dimensions-filter [{dims-and :and}]
  (if (empty? dims-and)
    ""
    (let [and-clauses (map-indexed (fn [idx uri]
                                     (let [comp-var (str "?comp" (inc idx))]
                                       (str
                                         "?struct qb:component " comp-var ". \n"
                                         comp-var " a qb:ComponentSpecification .\n"
                                         comp-var " qb:dimension <" (str uri) "> .\n")))
                                   dims-and)]
      (str
        "  ?ds qb:structure ?struct ."
        "  ?struct a qb:DataStructureDefinition ."
        (string/join "\n" and-clauses)))))

(defn get-datasets-query
  [dimensions measures uri configuration lang]
  (let [dataset-label (config/dataset-label configuration)]
    (str
      "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>"
      "PREFIX qb: <http://purl.org/linked-data/cube#>"
      "PREFIX dcterms: <http://purl.org/dc/terms/>"
      "SELECT ?ds ?name ?title ?description ?licence ?issued ?modified ?publisher WHERE {"
      (get-dimensions-or dimensions)
      "  ?ds <" (str dataset-label) "> ?name ."
      "  FILTER(LANG(?name) = '')"
      "  OPTIONAL {"
      "    ?ds <" (str dataset-label) "> ?title ."
      "    FILTER(LANG(?title) = \"" (or lang "") "\")"
      "  }"
      "  OPTIONAL {"
      "    ?ds rdfs:comment ?description ."
      "    FILTER(LANG(?description) = \"" lang "\")"
      "  }"
      "  OPTIONAL { ?ds dcterms:license ?licence }"
      "  OPTIONAL { ?ds dcterms:issued ?issued }"
      "  OPTIONAL { ?ds dcterms:modified ?modified }"
      "  OPTIONAL { ?ds dcterms:publisher ?publisher }"
      (get-dimensions-filter dimensions)
      (if (some? uri)
        (str "FILTER(?ds = <" uri ">) ."))
      "}")))

(defn- process-dataset-bindings [bindings]
  (-> bindings
      (update :title util/label->string)
      (update :description util/label->string)))

(defn get-datasets [repo dimensions measures uri configuration lang]
  (let [q (get-datasets-query dimensions measures uri configuration lang)
        results (util/eager-query repo q)]
    (map process-dataset-bindings results)))

(defn- get-dataset-strings-query [dataset-uri configuration lang]
  (let [label-predicate (str (config/dataset-label configuration))]
    (str
      "PREFIX qb: <http://purl.org/linked-data/cube#>"
      "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>"
      "SELECT ?title ?description WHERE {"
      "  <" dataset-uri "> a qb:DataSet ."
      "  OPTIONAL {"
      "    <" dataset-uri "> <" label-predicate "> ?title ."
      (when lang
        (str "FILTER(LANG(?title) = \"" lang "\")"))
      "  }"
      "  OPTIONAL {"
      "    <" dataset-uri "> rdfs:comment ?description ."
      (when lang
        (str "FILTER(LANG(?description) = \"" lang "\")"))
      "  }"
      "}")))

(defn get-dataset-strings [repo dataset-uri configuration lang]
  (let [q (get-dataset-strings-query dataset-uri configuration lang)
        results (util/eager-query repo q)
        bindings (first results)]
    (process-dataset-bindings bindings)))

(defn get-dimension-codelist-values-query [ds-uri configuration lang]
  (let [codelist-label (config/codelist-label configuration)]
    (str
      "PREFIX qb: <http://purl.org/linked-data/cube#>"
      "PREFIX skos: <http://www.w3.org/2004/02/skos/core#>"
      "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>"
      "PREFIX ui: <http://www.w3.org/ns/ui#>"
      "SELECT ?dim ?member ?label WHERE {"
      "<" (str ds-uri) "> qb:structure ?struct ."
      "?struct a qb:DataStructureDefinition ."
      "?struct qb:component ?comp ."
      "?comp qb:dimension ?dim ."
      (config/codelist-source configuration) " qb:codeList ?list  ."
      "?list skos:member ?member ."
      "OPTIONAL {"
      "  ?member <" (str codelist-label) "> ?label ."
      (when lang
        (str "FILTER(LANG(?label) = \"" lang "\") ."))
      "}"
      "}")))

(defn get-dimension-codelist-values [repo {:keys [uri] :as dataset} config lang]
  (let [dimvalues-query (get-dimension-codelist-values-query uri config lang)
        results (util/eager-query repo dimvalues-query)]
    (group-by :dim results)))

(defn get-datasets-containing-dimension [repo dimension-uri]
  (let [results (vec (sp/query "get-datasets-with-dimension.sparql" {:dim dimension-uri} repo))]
    (into #{} (map :ds results))))

(defn get-dimensions-query
  [dim-uris configuration]
  (let [dataset-label (config/dataset-label configuration)]
    (str
      "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>"
      "PREFIX qb: <http://purl.org/linked-data/cube#>"
      "SELECT ?dim ?label ?comment WHERE {"
      "  VALUES ?dim { " (string/join " " (map #(str "<" % ">") dim-uris)) " }"
      "  ?dim a qb:DimensionProperty ."
      "  ?dim <" (str dataset-label) "> ?label ."
      "  OPTIONAL { ?dim rdfs:comment ?comment }"
      "}")))

(defn get-all-enum-dimension-values
  "Gets all codelist members for all dimensions across all datasets. Each dimension is expected to have a
  single label without a language code. Each codelist item should have at most one label without a language
  code used to generate the enum name."
  [configuration]
  (let [area-dim (config/geo-dimension configuration)
        time-dim (config/time-dimension configuration)
        dataset-label (config/dataset-label configuration)
        codelist-label (config/codelist-label configuration)]
    (str
      "PREFIX qb: <http://purl.org/linked-data/cube#>"
      "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>"
      "PREFIX skos: <http://www.w3.org/2004/02/skos/core#>"
      "SELECT * WHERE {"
      "?ds qb:structure ?struct ."
      "?struct a qb:DataStructureDefinition ."
      "?struct qb:component ?comp ."
      "?comp a qb:ComponentSpecification ."
      "?comp qb:dimension ?dim ."
      "FILTER(?dim != <" (str area-dim) ">)"
      "FILTER(?dim != <" (str time-dim) ">)"
      "?dim <" (str dataset-label) "> ?label ."
      "FILTER(LANG(?label) = '')"
      "OPTIONAL { ?dim rdfs:comment ?doc }"
      (config/codelist-source configuration) " qb:codeList ?list ."
      "?list skos:member ?member ."
      "OPTIONAL {"
      "  ?member <" (str codelist-label) "> ?vallabel ."
      "  FILTER(LANG(?vallabel) = '')"
      "}"
      "}")))

(defn get-measures-by-lang-query [ds-uri lang configuration]
  (str
    "PREFIX qb: <http://purl.org/linked-data/cube#>"
    "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>"
    "SELECT ?mt ?label WHERE {"
    "  <" ds-uri "> qb:structure ?struct ."
    "  ?struct qb:component ?comp ."
    "  ?comp qb:measure ?mt ."
    "  ?mt a qb:MeasureProperty ."
    "  OPTIONAL {"
    "    ?mt <" (config/dataset-label configuration) "> ?label ."
    "    FILTER(LANG(?label) = \"" lang "\")"
    "  }"
    "}"))

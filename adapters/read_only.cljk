(ns adapters.read-only
  (:require [clojure.edn :as edn]
            [adapters.datalog-runtime :as dr]))

(defn load-db
  "Loads the published projection into an immutable datalog store."
  [schema-path tx-path]
  (dr/db (edn/read-string (slurp tx-path))))

(defn corpus-shards
  "The corpus shard files declared by data/corpus/manifest.edn, optionally
   narrowed to one dataset. Reading the manifest rather than globbing the
   directory is deliberate: a shard that is on disk but not declared is not
   part of the published projection, and a declared shard that is missing
   should surface as an error, not as silently thinner coverage."
  ([manifest-path] (corpus-shards manifest-path nil))
  ([manifest-path dataset]
   (let [m (edn/read-string (slurp manifest-path))]
     (cond->> (:corpus/shards m)
       dataset (filter #(= dataset (:dataset %)))))))

(defn load-corpus
  "Adds corpus shards to an existing store value.

   Loading the WHOLE corpus is ~95k law entities and ~646k edges; that is a
   real cost and the caller should choose it explicitly, which is why this is
   a separate call from load-db and why `dataset` narrows it. Asking about
   Japanese statutes must not require loading the EU dependency graph."
  ([store manifest-path] (load-corpus store manifest-path nil))
  ([store manifest-path dataset]
   (reduce (fn [acc {:keys [path]}]
             (dr/db-with acc (edn/read-string (slurp path))))
           store
           (corpus-shards manifest-path dataset))))

(defn text-locator
  "Everything needed to fetch and verify one law's full text. This projection
   never holds the bytes -- it holds their address."
  [store law-key]
  (let [eid (ffirst (dr/q '[:find ?e
                            :in $ ?key
                            :where [?e "law/key" ?key]]
                          store
                          law-key))
        entity (dr/pull store eid
                        [:law/key :law.text/dataset :law.text/path :law.text/archive
                         :law.text/entry :law.text/sha256 :law.text/bytes :law.text/format])]
    (when (:law.text/sha256 entity) entity)))

(defn load-contract [queries-path]
  (edn/read-string (slurp queries-path)))

(defn query
  "Runs one named, published query. No transact function is exposed.

   A query that declares :rules is passed them as the `%` input, so recursive
   walks (e.g. :legal-basis-closure) work through the same published-query
   boundary as everything else -- a consumer never supplies its own rules."
  [store contract query-id & args]
  (let [spec (get-in contract [:queries query-id])]
    (assert spec (str "unknown published query: " query-id))
    (apply dr/q (:query spec) store (concat (when-let [r (:rules spec)] [r]) args))))

(defn require-provenance!
  "Rejects a consumer profile that does not declare the required lineage fields."
  [consumer]
  (let [required (set (:consumer/required-provenance consumer))]
    (assert (seq required) "at least one required-provenance field is mandatory")
    consumer))

#!/usr/bin/env nbb
;; Builds the CORPUS layer: data/corpus/<source>/laws-NNN.edn and
;; relations-NNN.edn, plus data/corpus/manifest.edn.
;;
;; Input: the index/ directory of each locked DataLad source dataset (a
;; sibling checkout, revision-pinned in sources.lock.edn) -- NOT its raw/
;; layer. The index is a few MB of text in git; raw/ is gigabytes in
;; git-annex/B2. A consumer that wants to QUERY the corpus must never be
;; forced to fetch the corpus.
;;
;; Two things this build does NOT do, on purpose:
;;
;;   - It does not copy any legislative text. Each :law entity carries
;;     :law.text/sha256 + :law.text/dataset + :law.text/path, which is enough
;;     to fetch and verify the exact bytes. Duplicating a 2 GB corpus into a
;;     projection repository is how both copies end up stale.
;;   - It does not drop edges whose target is absent. See :law.rel/resolved?
;;     in schema/legislation.edn -- a repeal pointing at something no longer
;;     in force is the useful case, not the broken one.
;;
;; Output is SHARDED per source and capped at :shard-size entities. A single
;; multi-hundred-megabyte tx file would force every consumer to load the whole
;; world to ask about one jurisdiction, and would be unreviewable in a diff.
;;
;; Usage: nbb bin/build-corpus.cljs [source-root]
(ns build-corpus
  (:require [kotoba.lang.text :as str]
            [edamame.core :as edn]))

(def fs (js/require "fs"))
(def crypto (js/require "crypto"))
(def source-root (or (first *command-line-args*) ".."))
(def shard-size 20000)

(defn slurp* [p] (.toString (.readFileSync fs p)))
(defn read-edn [p] (edn/parse-string (slurp* p)))
(defn exists? [p] (.existsSync fs p))
(defn mkdirp! [p] (.mkdirSync fs p #js {:recursive true}))

(defn stabilize [x]
  (cond
    (map? x) (reduce-kv (fn [acc k v] (assoc acc k (stabilize v)))
                        (sorted-map-by #(compare (str %1) (str %2))) x)
    (vector? x) (mapv stabilize x)
    (sequential? x) (map stabilize x)
    :else x))

(defn write! [p x]
  (let [s (str (pr-str (stabilize x)) "\n")]
    (.writeFileSync fs p s)
    {:path p :bytes (.-length (js/Buffer.from s))
     :sha256 (-> (.createHash crypto "sha256") (.update s) (.digest "hex"))}))

;; The four locked source datasets. Adding a jurisdiction means adding a row
;; here and to sources.lock.edn -- nothing else in this file is per-source.
;;
;; :source-id is the CATALOG's id for this source, and it is rewritten onto
;; every entity here rather than taken from the index. A preservation dataset
;; must not have to know this projection's id vocabulary -- but if the two
;; disagree, the catalog<->corpus join (queries/examples.edn
;; :corpus-source-license, which is what answers "under what licence may I
;; use this text?") silently returns nothing. Mapping in one place, at the
;; boundary, is what keeps that join real.
(def datasets
  [{:dataset "jp.go.e-gov.elaws" :source-id "jp-egov" :jurisdiction "JPN"}
   {:dataset "eu.europa.eur-lex" :source-id "eu-eurlex" :jurisdiction "EU"}
   {:dataset "uk.gov.legislation" :source-id "uk-legislation" :jurisdiction "GBR"}
   {:dataset "gov.govinfo.bulkdata" :source-id "us-govinfo" :jurisdiction "USA"}])

(defn law-entity
  "index row -> :law entity. The index deliberately uses :text/* for the
   address of the bytes; the projection uses :law.text/* so the attribute
   namespace matches its entity. Renaming here, once, is cheaper than every
   consumer having to know both spellings."
  [dataset source-id r]
  (let [texts (:law/text-versions r)
        primary (or (first (sort-by :text/entry texts)) r)]
    (cond-> (assoc (into {} (remove (comp nil? val))
                         (select-keys r [:law/key :law/jurisdiction :law/local-id
                                         :law/kind :law/status :law/number :law/title :law/abbrev
                                         :law/category :law/lang :law/url :law/eli :law/resource-type
                                         :law/revision-id :law/promulgated-at :law/effective-at
                                         :law/introduced-at :law/repealed-at :law/revised-as-of]))
                   :law/source-id source-id)
      (:text/sha256 primary) (assoc :law.text/dataset dataset
                                    :law.text/sha256 (:text/sha256 primary)
                                    :law.text/bytes (:text/bytes primary)
                                    :law.text/format (:text/format primary))
      (:text/path primary) (assoc :law.text/path (:text/path primary))
      (:text/archive primary) (assoc :law.text/archive (:text/archive primary)
                                     :law.text/entry (:text/entry primary))
      (:text/lang primary) (assoc :law.text/lang (:text/lang primary))
      (seq texts) (assoc :law.text/version-count (count texts)))))

(defn rel-entity [known source-id r]
  (let [id (str (:rel/from r) "|" (name (:rel/kind r)) "|" (:rel/to r))]
    (cond-> {:law.rel/id id
             :law.rel/from (:rel/from r)
             :law.rel/to (:rel/to r)
             :law.rel/kind (:rel/kind r)
             :law.rel/provenance (:rel/provenance r)
             :law.rel/source-id source-id
             :law.rel/resolved? (contains? known (:rel/to r))}
      (:rel/evidence r) (assoc :law.rel/evidence (:rel/evidence r))
      (:rel/at r) (assoc :law.rel/at (:rel/at r)))))

(defn shard! [dir prefix entities]
  (mkdirp! dir)
  (vec (map-indexed
        (fn [i chunk]
          (let [w (write! (str dir "/" prefix "-" (.padStart (str i) 3 "0") ".edn") (vec chunk))]
            (assoc w :entities (count chunk))))
        (partition-all shard-size entities))))

(defn -main []
  (mkdirp! "data/corpus")
  (let [results
        (vec (for [{:keys [dataset source-id jurisdiction]} datasets
                   :let [root (str source-root "/" dataset)
                         laws-p (str root "/index/laws.edn")
                         rels-p (str root "/index/relations.edn")]]
               (if-not (exists? laws-p)
                 (do (binding [*out* *err*]
                       (println (str "WARN: " dataset " has no index/laws.edn at " laws-p
                                     " -- skipping. This source will be ABSENT from the corpus,"
                                     " and quality-report will say so rather than imply coverage.")))
                     {:dataset dataset :source-id source-id :jurisdiction jurisdiction
                      :present? false :laws 0 :relations 0 :shards []})
                 (let [laws (:laws (read-edn laws-p))
                       rels (if (exists? rels-p) (:relations (read-edn rels-p)) [])
                       known (into #{} (map :law/key) laws)
                       law-es (mapv #(law-entity dataset source-id %) laws)
                       rel-es (mapv #(rel-entity known source-id %) rels)
                       dir (str "data/corpus/" dataset)
                       law-shards (shard! dir "laws" law-es)
                       rel-shards (shard! dir "relations" rel-es)]
                   (println (str dataset ": " (count law-es) " laws ("
                                 (count (filter :law.text/sha256 law-es)) " with text), "
                                 (count rel-es) " relations ("
                                 (count (filter :law.rel/resolved? rel-es)) " resolved)"))
                   {:dataset dataset :source-id source-id :jurisdiction jurisdiction
                    :present? true
                    :laws (count law-es)
                    :laws-with-text (count (filter :law.text/sha256 law-es))
                    :text-bytes (reduce + 0 (keep :law.text/bytes law-es))
                    :relations (count rel-es)
                    :relations-resolved (count (filter :law.rel/resolved? rel-es))
                    :relation-kinds (frequencies (map :law.rel/kind rel-es))
                    :shards (into law-shards rel-shards)}))))]
    (write! "data/corpus/manifest.edn"
            {:corpus/version 1
             :corpus/built-from :locked-datalad-source-datasets
             :corpus/note
             (str "Each :law entity addresses its full text by sha256 in the named dataset. "
                  "This repository stores ZERO bytes of legislative text -- see "
                  ":quality/text-bytes-held-here in data/quality-report.edn.")
             :corpus/sources (mapv #(dissoc % :shards) results)
             :corpus/shards (vec (mapcat (fn [r] (map #(assoc % :dataset (:dataset r)) (:shards r))) results))})
    (println (str "corpus: " (reduce + (map :laws results)) " laws, "
                  (reduce + (map :relations results)) " relations, "
                  (count (mapcat :shards results)) " shards"))))

(-main)

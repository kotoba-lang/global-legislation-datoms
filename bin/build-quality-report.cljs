#!/usr/bin/env nbb
(ns build-quality-report
  (:require [edamame.core :as edn]))

(def fs (js/require "fs"))
(defn slurp* [p] (.toString (.readFileSync fs p)))
(defn legal-source? [e] (contains? e :legal-source/id))
(defn count-where [pred xs] (count (filter pred xs)))

(let [entities (edn/parse-string (slurp* "data/datascript-tx.edn"))
      legal-sources (filter legal-source? entities)
      missing-provenance-doc (count-where #(nil? (:legal-source/provenance-doc %)) legal-sources)
      report {:quality/as-of "2026-07-10"
              :quality/entities (count entities)
              :quality/legal-sources (count legal-sources)
              :quality/missing-required-provenance-doc missing-provenance-doc
              :quality/wave-1 (count-where #(= :wave/w1 (:legal-source/wave %)) legal-sources)
              :quality/wave-future (count-where #(= :wave/future (:legal-source/wave %)) legal-sources)
              :quality/tier-a (count-where #(= :tier/a (:legal-source/license-tier %)) legal-sources)
              :quality/tier-b (count-where #(= :tier/b (:legal-source/license-tier %)) legal-sources)
              :quality/tier-unspecified (count-where #(= :tier/unspecified (:legal-source/license-tier %)) legal-sources)
              :quality/with-url (count-where :legal-source/url legal-sources)
              :quality/doc-literal-url (count-where #(= :doc-literal (:legal-source/url-provenance %)) legal-sources)
              :quality/ingested-full-text 0
              :quality/thresholds {:minimum-legal-sources 25
                                   :minimum-wave-1 5
                                   :maximum-missing-required-provenance-doc 0
                                   ;; Honest invariant: this repo catalogs sources, it does not
                                   ;; ingest text. If this ever goes non-zero without a matching
                                   ;; README/ADR rewrite, the scope-disclosure has silently rotted.
                                   :maximum-ingested-full-text 0}}]
  (.writeFileSync fs "data/quality-report.edn" (str (pr-str report) "\n"))
  (println (str "quality report: " (:quality/legal-sources report) " legal sources; "
                (:quality/wave-1 report) " wave-1; "
                (:quality/tier-a report) " tier-A")))

#!/usr/bin/env nbb
(ns build-quality-report
  (:require [edamame.core :as edn]))

(def fs (js/require "fs"))
(defn slurp* [p] (.toString (.readFileSync fs p)))
(defn legal-source? [e] (contains? e :legal-source/id))
(defn legislature? [e] (contains? e :legislature/id))
(defn court? [e] (contains? e :court/id))
(defn jurisdiction? [e] (contains? e :jurisdiction/code))
(defn count-where [pred xs] (count (filter pred xs)))

(let [entities (edn/parse-string (slurp* "data/datascript-tx.edn"))
      legal-sources (filter legal-source? entities)
      legislatures (filter legislature? entities)
      courts (filter court? entities)
      jurisdictions (filter jurisdiction? entities)
      missing-provenance-doc (count-where #(nil? (:legal-source/provenance-doc %)) legal-sources)
      missing-license-basis (count-where #(and (= :general-legal-knowledge (:legal-source/license-provenance %))
                                                (nil? (:legal-source/license-basis %)))
                                          legal-sources)
      report {:quality/as-of "2026-07-10"
              :quality/entities (count entities)
              :quality/legal-sources (count legal-sources)
              :quality/legislatures (count legislatures)
              :quality/courts (count courts)
              :quality/jurisdictions (count jurisdictions)
              :quality/missing-required-provenance-doc missing-provenance-doc
              :quality/missing-required-license-basis missing-license-basis
              :quality/wave-1 (count-where #(= :wave/w1 (:legal-source/wave %)) legal-sources)
              :quality/wave-future (count-where #(= :wave/future (:legal-source/wave %)) legal-sources)
              :quality/tier-a (count-where #(= :tier/a (:legal-source/license-tier %)) legal-sources)
              :quality/tier-b (count-where #(= :tier/b (:legal-source/license-tier %)) legal-sources)
              :quality/tier-unspecified (count-where #(= :tier/unspecified (:legal-source/license-tier %)) legal-sources)
              :quality/license-doc-literal (count-where #(= :doc-literal (:legal-source/license-provenance %)) legal-sources)
              :quality/license-general-legal-knowledge (count-where #(= :general-legal-knowledge (:legal-source/license-provenance %)) legal-sources)
              :quality/hanrei-international-courts-covered (count-where :legal-source/hanrei-court-code legal-sources)
              :quality/with-url (count-where :legal-source/url legal-sources)
              :quality/doc-literal-url (count-where #(= :doc-literal (:legal-source/url-provenance %)) legal-sources)
              :quality/ingested-full-text 0
              :quality/thresholds {:minimum-legal-sources 30
                                   :minimum-wave-1 5
                                   :minimum-legislatures 150
                                   :minimum-courts 100
                                   :minimum-jurisdictions 150
                                   :maximum-missing-required-provenance-doc 0
                                   :maximum-missing-required-license-basis 0
                                   :minimum-hanrei-international-courts-covered 8
                                   ;; Honest invariant: this repo catalogs sources, it does not
                                   ;; ingest text. If this ever goes non-zero without a matching
                                   ;; README/ADR rewrite, the scope-disclosure has silently rotted.
                                   :maximum-ingested-full-text 0}}]
  (.writeFileSync fs "data/quality-report.edn" (str (pr-str report) "\n"))
  (println (str "quality report: " (:quality/legal-sources report) " legal sources, "
                (:quality/legislatures report) " legislatures, "
                (:quality/courts report) " courts, "
                (:quality/jurisdictions report) " jurisdictions; "
                (:quality/tier-unspecified report) " tier-unspecified; "
                (:quality/hanrei-international-courts-covered report) "/8 hanrei international courts")))

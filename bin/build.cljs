#!/usr/bin/env nbb
;; Rebuilds data/datascript-tx.edn + data/world-legislation.kotoba.edn +
;; data/provenance.edn from the hand-curated data/seed/*.edn catalog.
;;
;; Unlike global-energy-datoms, there is no upstream raw statistical file to
;; parse here: as of this build, no DataLad dataset of ingested world
;; legislation/case-law full text exists yet in the monorepo (ADR-2605262800
;; R0 = ADR + sensor scaffold only). This script instead compiles the
;; already-reviewed catalog facts recorded in data/seed/*.edn (each row
;; citing the exact monorepo doc that asserted it) into the same
;; Datascript-tx / Kotoba-EAVT / provenance triad the sibling "-datoms"
;; repos publish, so it can be superseded in place once real per-jurisdiction
;; raw source datasets land and a later revision of this script parses them.
(ns build
  (:require [edamame.core :as edn]))

(def fs (js/require "fs"))
(defn slurp* [p] (.toString (.readFileSync fs p)))
(defn write! [p x] (.writeFileSync fs p (str (pr-str x) "\n")))
(defn read-edn [p] (edn/parse-string (slurp* p)))

(def unique-id-attrs
  [:legal-source/id :legislature/id :jurisdiction/code
   :prohibited-source/id :hanrei.coverage/id])

(defn entity-id [e]
  (some e unique-id-attrs))

(defn datoms [entities tx]
  (vec (mapcat (fn [e]
                 (for [[a v] (sort-by (comp str key) e)]
                   [(entity-id e) a v tx :add]))
               (sort-by entity-id entities))))

(let [legal-sources (:sources (read-edn "data/seed/legal-sources.edn"))
      legislatures (:legislatures (read-edn "data/seed/legislatures.edn"))
      prohibited (:prohibited-sources (read-edn "data/seed/prohibited-sources.edn"))
      jurisdictions (:jurisdictions (read-edn "data/seed/jurisdictions.edn"))
      coverage [(:hanrei-coverage (read-edn "data/seed/hanrei-coverage.edn"))]
      entities (vec (concat legal-sources legislatures prohibited jurisdictions coverage))
      tx 20260710]
  (.mkdirSync fs "data" #js {:recursive true})
  (write! "data/datascript-tx.edn" entities)
  (write! "data/world-legislation.kotoba.edn"
          {:datom/format :eavt :datom/tx tx :datom/rows (datoms entities tx)})
  (write! "data/provenance.edn"
          {:build/as-of "2026-07-10"
           :sources [{:source/id :source/legal-sources-catalog
                      :source/kind :hand-curated-monorepo-catalog
                      :source/entities (count legal-sources)
                      :source/grounding-docs [:doc/adr-2605262800
                                               :doc/kotodama-py-legal-sensors-readme
                                               :doc/hanrei-claude-md]}
                     {:source/id :source/legislature-join
                      :source/kind :mirrored-from-repo
                      :source/repo "etzhayyim/com-etzhayyim-ooyake"
                      :source/entities (count legislatures)}
                     {:source/id :source/prohibited-vendors
                      :source/kind :hand-curated-monorepo-catalog
                      :source/entities (count prohibited)}
                     {:source/id :source/jurisdiction-reference
                      :source/kind :derived
                      :source/entities (count jurisdictions)}
                     {:source/id :source/hanrei-coverage-snapshot
                      :source/kind :mirrored-from-repo
                      :source/repo "etzhayyim/root 60-apps/etzhayyim-project-hanrei/CLAUDE.md"
                      :source/entities (count coverage)}]})
  (println (str "built " (count legal-sources) " legal-source, "
                (count legislatures) " legislature, "
                (count prohibited) " prohibited-source, "
                (count jurisdictions) " jurisdiction, and "
                (count coverage) " hanrei-coverage entities")))

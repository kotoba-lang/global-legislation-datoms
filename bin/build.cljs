#!/usr/bin/env nbb
;; Rebuilds data/datascript-tx.edn + data/world-legislation.kotoba.edn +
;; data/provenance.edn.
;;
;; Two input classes, kept deliberately separate:
;;
;; 1. data/seed/{legal-sources,prohibited-sources,hanrei-coverage}.edn —
;;    hand-curated catalog facts, each row citing the exact monorepo doc
;;    that asserted it (see data/seed/legal-sources.edn header). There is
;;    still no upstream raw statistical/legal-text dataset to mechanically
;;    parse for THIS class: as of this build, no DataLad dataset of ingested
;;    world legislation/case-law full text exists in the monorepo
;;    (ADR-2605262800 R0 = ADR + sensor scaffold only).
;;
;; 2. :legislature / :court / :jurisdiction-name entities — mechanically
;;    parsed straight from the etzhayyim/com-etzhayyim-ooyake sibling repo's
;;    own real, Wikidata-verified registries (world-legislatures.edn,
;;    world-courts.edn, world-countries.edn + seed.edn's :level :country
;;    rows), the same source-root sibling-repo pattern global-energy-datoms
;;    uses for its four statistical sources. No hand-typed per-country data
;;    -- every legislature/court/jurisdiction-name row traces to an ooyake
;;    :gov.unit/id that a reader can look up directly.
(ns build
  (:require [clojure.string :as str]
            [edamame.core :as edn]))

(def fs (js/require "fs"))
(def source-root (or (first *command-line-args*) ".."))
(defn slurp* [p] (.toString (.readFileSync fs p)))
(defn write! [p x] (.writeFileSync fs p (str (pr-str x) "\n")))
(defn read-edn [p] (edn/parse-string (slurp* p)))
(defn path [& xs] (str/join "/" xs))

(def ooyake-root (path source-root "com-etzhayyim-ooyake"))

(defn compact
  "Drops nil-valued keys -- Datascript rejects nil as a stored value, and a
   handful of ooyake rows omit :official-url (falling back to their Wikidata
   entity per that repo's own doc header) or :wikidata."
  [m]
  (into {} (remove (comp nil? val) m)))

(def supranational-fallback-names
  {"EU" "European Union" "COE" "Council of Europe" "UN" "United Nations"
   "ICC" "International Criminal Court" "ICJ" "International Court of Justice"
   "IACHR" "Inter-American Court of Human Rights"
   "ACHPR" "African Court on Human and Peoples' Rights"
   "ITLOS" "International Tribunal for the Law of the Sea"
   "WTOAB" "WTO Appellate Body"})

(defn read-legislatures []
  (for [u (:units (read-edn (path ooyake-root "registry/gov-units.world-legislatures.edn")))]
    (compact
     {:legislature/id (str "legislature-" (:gov.unit/jurisdiction u))
      :legislature/jurisdiction (str/upper-case (:gov.unit/jurisdiction u))
      :legislature/name (:gov.unit/name-en u)
      :legislature/official-url (:gov.unit/official-url u)
      :legislature/wikidata (:gov.unit/wikidata u)
      :legislature/last-verified (:gov.unit/last-verified u)
      :legislature/ooyake-source-id (:gov.unit/id u)})))

(defn read-courts []
  (for [u (:units (read-edn (path ooyake-root "registry/gov-units.world-courts.edn")))]
    (compact
     {:court/id (str "court-" (:gov.unit/jurisdiction u))
      :court/jurisdiction (str/upper-case (:gov.unit/jurisdiction u))
      :court/name (:gov.unit/name-en u)
      :court/official-url (:gov.unit/official-url u)
      :court/wikidata (:gov.unit/wikidata u)
      :court/last-verified (:gov.unit/last-verified u)
      :court/ooyake-source-id (:gov.unit/id u)})))

(defn read-country-names
  "Union of :level :country rows from world-countries.edn (174, excludes G20) +
   gov-units.seed.edn + gov-units.g20.edn (G20 country rows) -> {ISO3 name-en}."
  []
  (let [world (:units (read-edn (path ooyake-root "registry/gov-units.world-countries.edn")))
        seed (:units (read-edn (path ooyake-root "registry/gov-units.seed.edn")))
        g20 (:units (read-edn (path ooyake-root "registry/gov-units.g20.edn")))
        country? #(= :country (:gov.unit/level %))
        countries (concat world (filter country? seed) (filter country? g20))]
    (into {} (map (fn [u] [(str/upper-case (:gov.unit/jurisdiction u)) (:gov.unit/name-en u)]) countries))))

(defn jurisdiction-kind [code country-names]
  (cond
    (contains? country-names code) :national
    (#{"EU" "COE" "UN"} code) :supranational
    :else :international-court))

(defn build-jurisdictions [legal-sources legislatures courts country-names]
  (let [codes (into #{} (concat (map :legal-source/jurisdiction legal-sources)
                                 (map :legislature/jurisdiction legislatures)
                                 (map :court/jurisdiction courts)))]
    (vec (for [code (sort codes)]
           (let [name (or (get country-names code) (get supranational-fallback-names code))]
             (when-not name
               (binding [*out* *err*] (println (str "WARN: no name found for jurisdiction code " code))))
             {:jurisdiction/code code
              :jurisdiction/name (or name code)
              :jurisdiction/kind (jurisdiction-kind code country-names)})))))

(def unique-id-attrs
  [:legal-source/id :legislature/id :court/id :jurisdiction/code
   :prohibited-source/id :hanrei.coverage/id])

(defn entity-id [e]
  (some e unique-id-attrs))

(defn datoms [entities tx]
  (vec (mapcat (fn [e]
                 (for [[a v] (sort-by (comp str key) e)]
                   [(entity-id e) a v tx :add]))
               (sort-by entity-id entities))))

(let [legal-sources (:sources (read-edn "data/seed/legal-sources.edn"))
      legislatures (vec (read-legislatures))
      courts (vec (read-courts))
      country-names (read-country-names)
      jurisdictions (build-jurisdictions legal-sources legislatures courts country-names)
      prohibited (:prohibited-sources (read-edn "data/seed/prohibited-sources.edn"))
      coverage [(:hanrei-coverage (read-edn "data/seed/hanrei-coverage.edn"))]
      entities (vec (concat legal-sources legislatures courts jurisdictions prohibited coverage))
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
                                               :doc/hanrei-claude-md
                                               :doc/hanrei-actor-manifest]}
                     {:source/id :source/legislature-join
                      :source/kind :mirrored-from-repo
                      :source/repo "etzhayyim/com-etzhayyim-ooyake"
                      :source/file "registry/gov-units.world-legislatures.edn"
                      :source/entities (count legislatures)}
                     {:source/id :source/court-join
                      :source/kind :mirrored-from-repo
                      :source/repo "etzhayyim/com-etzhayyim-ooyake"
                      :source/file "registry/gov-units.world-courts.edn"
                      :source/entities (count courts)}
                     {:source/id :source/jurisdiction-reference
                      :source/kind :derived
                      :source/entities (count jurisdictions)}
                     {:source/id :source/prohibited-vendors
                      :source/kind :hand-curated-monorepo-catalog
                      :source/entities (count prohibited)}
                     {:source/id :source/hanrei-coverage-snapshot
                      :source/kind :mirrored-from-repo
                      :source/repo "etzhayyim/root 60-apps/etzhayyim-project-hanrei/CLAUDE.md"
                      :source/entities (count coverage)}]})
  (println (str "built " (count legal-sources) " legal-source, "
                (count legislatures) " legislature, "
                (count courts) " court, "
                (count jurisdictions) " jurisdiction, "
                (count prohibited) " prohibited-source, and "
                (count coverage) " hanrei-coverage entities")))

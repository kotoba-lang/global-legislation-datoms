#!/usr/bin/env nbb
;; Verifies every data/seed/legal-sources.edn row cites one of the allowed,
;; already-reviewed monorepo docs, and that none of the four prohibited
;; vendors from data/seed/prohibited-sources.edn appear as a :legal-source
;; :name substring (a mechanical guard against ever cataloging a source this
;; repo's own prohibited-vendor list forbids).
(ns verify-seed-provenance
  (:require [clojure.string :as str]
            [edamame.core :as edn]))

(def fs (js/require "fs"))
(defn slurp* [p] (.toString (.readFileSync fs p)))
(defn read-edn [p] (edn/parse-string (slurp* p)))

(def allowed-docs
  #{:doc/adr-2605262800 :doc/kotodama-py-legal-sensors-readme :doc/hanrei-claude-md})

(let [sources (:sources (read-edn "data/seed/legal-sources.edn"))
      prohibited-names (map :prohibited-source/name
                             (:prohibited-sources (read-edn "data/seed/prohibited-sources.edn")))]
  (doseq [{:legal-source/keys [id provenance-doc name]} sources]
    (assert provenance-doc (str id " missing :legal-source/provenance-doc"))
    (assert (contains? allowed-docs provenance-doc)
            (str id " cites unrecognized provenance doc " provenance-doc))
    (doseq [p prohibited-names]
      (assert (not (str/includes? (str/lower-case name) (str/lower-case p)))
              (str id " (" name ") matches a prohibited vendor: " p))))
  (println (str "verified " (count sources) " legal-source rows against "
                (count allowed-docs) " allowed provenance docs and "
                (count prohibited-names) " prohibited-vendor names")))

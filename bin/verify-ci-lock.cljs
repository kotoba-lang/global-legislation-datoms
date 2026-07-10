#!/usr/bin/env nbb
(require '[clojure.string :as str] '[edamame.core :as edn])
(def fs (js/require "fs"))
(let [lock (edn/parse-string (.toString (.readFileSync fs "sources.lock.edn"))) workflow (.toString (.readFileSync fs ".github/workflows/contract.yml"))]
  (doseq [{:keys [repository revision]} (:sources lock)] (assert (str/includes? workflow (str "repository: " repository))) (assert (str/includes? workflow (str "ref: " revision))))
  (println (str "verified " (count (:sources lock)) " locked CI source revisions")))

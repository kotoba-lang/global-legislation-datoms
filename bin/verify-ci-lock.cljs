#!/usr/bin/env nbb
;; Asserts that every source in sources.lock.edn marked :source/ci-checkout
;; (default true) is actually checked out at that exact revision by
;; .github/workflows/contract.yml.
;;
;; A source may opt out with :source/ci-checkout false. That is NOT a way to
;; make a gap go away: an opted-out source is reported loudly on every run,
;; because "CI does not verify this input" has to stay visible. See the
;; comment in sources.lock.edn for why the four corpus datasets are currently
;; opted out.
(require '[clojure.string :as str] '[edamame.core :as edn])
(def fs (js/require "fs"))
(let [lock (edn/parse-string (.toString (.readFileSync fs "sources.lock.edn")))
      workflow (.toString (.readFileSync fs ".github/workflows/contract.yml"))
      {checked true unchecked false}
      (group-by #(not= false (:source/ci-checkout %)) (:sources lock))]
  (doseq [{:keys [repository revision]} checked]
    (assert (str/includes? workflow (str "repository: " repository))
            (str repository " is locked but not checked out by CI"))
    (assert (str/includes? workflow (str "ref: " revision))
            (str repository " is checked out by CI at a revision other than the locked " revision)))
  (when (seq unchecked)
    (binding [*out* *err*]
      (println (str "WARNING: " (count unchecked) " locked source(s) are NOT verified by CI:"))
      (doseq [{:keys [repository revision]} unchecked]
        (println (str "  - " repository " @ " revision)))
      (println "  The projection built from them is committed output that CI does not")
      (println "  independently reproduce. See sources.lock.edn and docs/ci-design.md.")))
  (println (str "verified " (count checked) " locked CI source revisions ("
                (count unchecked) " opted out and reported above)")))

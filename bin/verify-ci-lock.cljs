#!/usr/bin/env nbb
;; Checks sources.lock.edn against the CI that actually verifies this
;; repository.
;;
;; That CI is the **murakumo mac-mini fleet** (`scripts/fleet-ci/` in
;; com-junkawasaki/root), not GitHub Actions. Actions is DISABLED on this
;; repository as of 2026-08-05 (owner instruction "github は使わない";
;; workspace decision ADR-2607300900, taken after every workflow in three orgs
;; stopped starting jobs). `.github/workflows/contract.yml` is still on disk
;; and is INERT -- deleting it needs GitHub's `workflow` OAuth scope, which
;; this workspace's token does not have.
;;
;; What that changes here: the old version asserted that every locked revision
;; appeared as a `ref:` in contract.yml, because Actions checked those sources
;; out. Nothing checks them out now -- the fleet gate
;; (gates/legislation-corpus-check.cljs) verifies the COMMITTED projection
;; instead, by sha256. Keeping the old assertion would leave a check that
;; passes without testing anything, which is worse than no check.
;;
;; What it asserts now:
;;   - the lock is well formed (every row names a repository and a 40-hex sha)
;;   - the inert workflow is not a trap: if it still pins a locked source at a
;;     DIFFERENT revision, say so now rather than when someone re-enables
;;     Actions
(require '[clojure.string :as str] '[edamame.core :as edn])
(def fs (js/require "fs"))

(def lock (edn/parse-string (.toString (.readFileSync fs "sources.lock.edn"))))
(def sources (:sources lock))
(def workflow-path ".github/workflows/contract.yml")

(def problems (atom []))
(defn bad! [& xs] (swap! problems conj (str/join " " (map str xs))))

(doseq [{:keys [repository revision] :as row} sources]
  (when-not (and (string? repository) (str/includes? (str repository) "/"))
    (bad! "lock row has no usable :repository:" (pr-str row)))
  (when-not (re-matches #"[0-9a-f]{40}" (str revision))
    (bad! repository "has a revision that is not a 40-hex sha:" (pr-str revision))))

(when (.existsSync fs workflow-path)
  (let [wf (.toString (.readFileSync fs workflow-path))]
    (doseq [{:keys [repository revision]} sources
            :when (str/includes? wf (str "repository: " repository))]
      (when-not (str/includes? wf (str "ref: " revision))
        (bad! "inert" workflow-path "pins" repository
              "at a revision other than the locked" revision
              "-- fix or delete it before re-enabling Actions")))))

(println (str "lock: " (count sources) " source(s). CI of record = murakumo fleet-ci "
              "(scripts/fleet-ci/gates.edn, gate legislation-corpus-check)."))
(when (.existsSync fs workflow-path)
  (binding [*out* *err*]
    (println (str "NOTE: " workflow-path " is present but INERT -- GitHub Actions is disabled "
                  "on this repository. Removing the file needs the `workflow` OAuth scope."))))
(if (seq @problems)
  (do (binding [*out* *err*]
        (println (str "FAIL -- " (count @problems) " problem(s):"))
        (doseq [p @problems] (println "  -" p)))
      (set! (.-exitCode js/process) 1))
  (println "OK"))

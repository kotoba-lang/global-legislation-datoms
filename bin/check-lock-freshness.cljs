#!/usr/bin/env nbb
;; Reports (never auto-updates) whether each sources.lock.edn pin is behind its
;; own repository's default branch, via the GitHub compare API -- read-only,
;; no live legal-content fetching (that stays out of scope per raw-source-
;; integrity). Mirrors the superproject's own "pin鮮度" (pin-freshness) check
;; convention (root CLAUDE.md: "git push/pull/west update の前に... pin が
;; upstream GitHub の最新から取り残されていないか確認する"). Informational --
;; always exits 0; staleness is a freshness question, not a correctness one,
;; so this must not block CI on every upstream ooyake commit.
(require '[edamame.core :as edn])

(def fs (js/require "fs"))

(defn auth-headers []
  (let [token (aget (.-env js/process) "GITHUB_TOKEN")]
    (if token
      (js-obj "Accept" "application/vnd.github+json" "Authorization" (str "Bearer " token))
      (js-obj "Accept" "application/vnd.github+json"))))

(defn gh-compare [repository base]
  (let [url (str "https://api.github.com/repos/" repository "/compare/" base "...HEAD")]
    (-> (js/fetch url (js-obj "headers" (auth-headers)))
        (.then (fn [res] (.json res))))))

(defn report-one [repository revision]
  (-> (gh-compare repository revision)
      (.then
       (fn [cmp]
         (let [ahead (aget cmp "ahead_by")
               status (aget cmp "status")]
           (if (some? ahead)
             (do
               (println (str repository "@" (subs revision 0 12) " -> HEAD: " status ", " ahead " commit(s) ahead"))
               (when (pos? ahead)
                 (println (str "  STALE: " repository " has moved since this pin;"
                               " re-lock is a separate, deliberate step (re-run build.cljs + re-verify), not automatic."))))
             (println (str repository ": could not compare -- likely GitHub API rate limit without GITHUB_TOKEN; not a failure."))))))))

(defn -main []
  (let [lock (edn/parse-string (.toString (.readFileSync fs "sources.lock.edn")))
        sources (:sources lock)
        reports (map (fn [s] (report-one (:repository s) (:revision s))) sources)]
    (-> (js/Promise.all (clj->js reports))
        (.then (fn [_] (println "lock-freshness check complete (informational only, exit 0 regardless).")))
        (.catch (fn [err] (println "lock-freshness check errored (non-fatal):" err))))))

(-main)

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

(defn interpret-compare
  "PURE: turn a parsed GitHub /compare response (a plain Clojure map, already js->clj'd) into a
  report record. No I/O -- self-tested below with synthetic inputs, independent of network access
  or GITHUB_TOKEN, so the interpretation logic is verified on every run before any fetch happens."
  [repository revision cmp]
  (let [ahead (get cmp "ahead_by")
        status (get cmp "status")]
    (if (some? ahead)
      {:repository repository :revision revision :ahead ahead :status status
       :stale? (pos? ahead)
       :message (str repository "@" (subs revision 0 12) " -> HEAD: " status ", " ahead " commit(s) ahead")}
      {:repository repository :revision revision :ahead nil :status nil :stale? false
       :message (str repository ": could not compare -- likely GitHub API rate limit without GITHUB_TOKEN; not a failure.")})))

(defn print-report [{:keys [repository message stale?] :as r}]
  (println message)
  (when stale?
    (println (str "  STALE: " repository " has moved since this pin;"
                  " re-lock is a separate, deliberate step (re-run build.cljs + re-verify), not automatic.")))
  r)

;; ── self-test: interpret-compare, synthetic inputs, no network (runs on every invocation) ──
(let [ahead-3   (interpret-compare "org/repo" "deadbeefdeadbeef0000" {"ahead_by" 3 "status" "ahead"})
      identical (interpret-compare "org/repo" "deadbeefdeadbeef0000" {"ahead_by" 0 "status" "identical"})
      no-ahead  (interpret-compare "org/repo" "deadbeefdeadbeef0000" {"message" "API rate limit exceeded"})]
  (assert (true? (:stale? ahead-3)) "3 commits ahead must be reported stale")
  (assert (= 3 (:ahead ahead-3)) "ahead count must round-trip")
  (assert (false? (:stale? identical)) "0 commits ahead must NOT be reported stale")
  (assert (false? (:stale? no-ahead)) "a malformed/rate-limited response must NOT be reported stale (fail-safe, not fail-stale)")
  (assert (nil? (:ahead no-ahead)) "a malformed response must not fabricate an ahead count"))

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
      (.then (fn [cmp] (print-report (interpret-compare repository revision (js->clj cmp)))))))

(defn -main []
  (let [lock (edn/parse-string (.toString (.readFileSync fs "sources.lock.edn")))
        sources (:sources lock)
        reports (map (fn [s] (report-one (:repository s) (:revision s))) sources)]
    (-> (js/Promise.all (clj->js reports))
        (.then (fn [_] (println "lock-freshness check complete (informational only, exit 0 regardless).")))
        (.catch (fn [err] (println "lock-freshness check errored (non-fatal):" err))))))

(-main)

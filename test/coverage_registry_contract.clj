;; coverage/registry.edn is a hand-maintained human-readable overview (README:
;; "法域ごとの family coverage と安全な結合キー"), NOT part of the bin/build.cljs
;; pipeline (build.cljs derives data/*.edn solely from data/seed/*.edn plus the
;; mechanically-sourced ooyake legislature/court/jurisdiction data). Nothing
;; previously checked it stays consistent with the legal-source catalog it
;; summarizes, so it could silently drift (a jurisdiction added to
;; data/seed/legal-sources.edn without a matching coverage/registry.edn row, or
;; a wave-1-sources id going stale after a legal-sources.edn edit) without CI
;; ever catching it. This contract closes that gap.
;;
;; 2026-07-10: adapted to compare against legal-sources.edn's own
;; :legal-source/jurisdiction set directly (data/seed/jurisdictions.edn was
;; retired -- :jurisdiction is now derived at build time from the much larger
;; ooyake-sourced legislature/court reference data, which is a superset this
;; registry intentionally does NOT try to mirror; coverage/registry.edn's
;; scope is specifically the legal-source-covered jurisdictions).
(require '[clojure.edn :as edn]
         '[clojure.set :as set])

(let [registry      (edn/read-string (slurp "coverage/registry.edn"))
      legal-sources (:sources (edn/read-string (slurp "data/seed/legal-sources.edn")))
      reg-rows      (:coverage/jurisdictions registry)
      reg-codes     (set (map :jurisdiction/code reg-rows))
      source-codes  (set (map :legal-source/jurisdiction legal-sources))
      source-ids    (set (map :legal-source/id legal-sources))
      w1-ids-by-code (into {}
                           (map (fn [[code srcs]] [code (set (map :legal-source/id srcs))]))
                           (group-by :legal-source/jurisdiction
                                     (filter #(= :wave/w1 (:legal-source/wave %)) legal-sources)))]

  (assert (= reg-codes source-codes)
          (str "coverage/registry.edn jurisdiction codes drifted from data/seed/legal-sources.edn — "
               "only-in-registry=" (set/difference reg-codes source-codes)
               " only-in-legal-sources=" (set/difference source-codes reg-codes)))

  (doseq [row reg-rows]
    (let [code (:jurisdiction/code row)
          claimed (set (:coverage/wave-1-sources row))
          expected (get w1-ids-by-code code #{})]
      (doseq [sid claimed]
        (assert (contains? source-ids sid)
                (str "coverage/registry.edn " code " cites unknown legal-source id " sid)))
      (assert (= expected claimed)
              (str "coverage/registry.edn " code " :coverage/wave-1-sources drifted from "
                   "data/seed/legal-sources.edn wave/w1 rows for that jurisdiction — "
                   "expected=" expected " got=" claimed))))

  (println {:status :ok :jurisdictions (count reg-codes) :wave-1-consistent true}))

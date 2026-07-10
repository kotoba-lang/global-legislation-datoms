(require '[clojure.edn :as edn]
         '[datascript.core :as d]
         '[adapters.read-only :as read-only])

(let [schema (edn/read-string (slurp "schema/legislation.edn"))
      tx (edn/read-string (slurp "data/datascript-tx.edn"))
      quality (edn/read-string (slurp "data/quality-report.edn"))
      queries (:queries (edn/read-string (slurp "queries/examples.edn")))
      db (d/db-with (d/empty-db schema) tx)
      run #(d/q (:query (get queries %)) db)]
  (assert (>= (:quality/legal-sources quality) 25) "legal-source catalog unexpectedly shrank")
  (assert (>= (:quality/wave-1 quality) 5) "wave-1 anchor sources missing")
  (assert (zero? (:quality/missing-required-provenance-doc quality)) "a legal-source row is missing provenance-doc")
  (assert (zero? (:quality/ingested-full-text quality))
          "scope-disclosure violated: this repo must never claim ingested full text")
  (assert (>= (count (run :wave-1-sources)) 5) "wave-1 query coverage missing")
  (assert (>= (count (run :tier-a-sources)) 8) "Tier-A coverage missing")
  (assert (>= (count (run :prohibited-sources)) 4) "prohibited-vendor catalog incomplete")
  (assert (= 1 (count (run :cn-scrutiny-flagged))) "CN non-substitution scrutiny flag missing")
  (assert (>= (count (run :legislature-for-source-jurisdiction)) 20)
          "legal-source <-> legislature jurisdiction join missing")
  (let [[national international] (run :hanrei-coverage-snapshot)]
    (assert (= 75 national) "hanrei national-jurisdiction count drifted from CLAUDE.md")
    (assert (= 8 international) "hanrei international-court count drifted from CLAUDE.md"))
  (let [consumer (first (:connections/consumers (edn/read-string (slurp "connections/actors.edn"))))]
    (read-only/require-provenance! consumer)
    (assert (seq (read-only/query db {:queries queries} :wave-1-sources)) "read-only consumer adapter missing"))
  (println {:status :ok :entities (count tx) :queries (count queries)}))

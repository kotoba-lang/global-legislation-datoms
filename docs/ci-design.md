# CI design

This repo has no upstream raw dataset to fetch (see `maturity/scorecard.edn`
`:raw-source-integrity`), so its CI contract differs from a sibling
`-datoms` repo that projects a DataLad source dataset: there is nothing to
check out at a locked revision, because the "source" is a fixed set of
already-reviewed docs living in this same monorepo (an ADR, a README, a
CLAUDE.md coverage table). The workflow instead validates that the
committed projection is a faithful, honestly-scoped rebuild of
`data/seed/*.edn`:

1. Verify every `data/seed/legal-sources.edn` row cites an allow-listed
   monorepo doc and does not name a prohibited vendor
   (`bin/verify-seed-provenance.cljs`).
2. Rebuild the Datascript transaction EDN and Kotoba EAVT export solely
   from `data/seed/*.edn`.
3. Fail if generated files differ from committed output.
4. Rebuild the quality report and assert `:quality/ingested-full-text` is
   pinned at `0` — the one invariant this repo must never silently violate,
   since it is what distinguishes a source *catalog* from a text *corpus*.
5. Materialize the Datascript database and run the query/coverage
   invariants in `test/query_contract.clj`. Snapshot rebuild and projection
   use NBB; the Datascript compatibility test uses the canonical Clojure
   Datascript library (same split as `global-energy-datoms`).

Growing this catalog (adding a jurisdiction, correcting a URL, adding a
family) is a normal PR that edits `data/seed/*.edn` and lets CI rebuild +
verify the derived files. Landing actual ingested legal text is explicitly
**not** in this repo's scope — that is the separate raw-bytes/CID layer
ADR-2605262800 describes and has not yet shipped (R0 only).

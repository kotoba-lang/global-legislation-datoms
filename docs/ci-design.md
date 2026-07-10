# CI design

This repo has two input classes with different CI treatment (see
`maturity/scorecard.edn`):

- **`data/seed/{legal-sources,prohibited-sources,hanrei-coverage}.edn`** —
  no upstream raw dataset to fetch. There is nothing to check out at a
  locked revision for this class, because the "source" is a fixed set of
  already-reviewed docs living in this same monorepo (an ADR, a README, a
  CLAUDE.md coverage table, an actor manifest).
- **`:legislature` / `:court` / `:jurisdiction`** — mechanically parsed from
  the `etzhayyim/com-etzhayyim-ooyake` sibling repo, exactly like a normal
  `-datoms` repo projects a DataLad source dataset. This class DOES have a
  locked revision (`sources.lock.edn`), the same pattern
  `global-energy-datoms` uses for its four statistical sources.

The workflow validates that the committed projection is a faithful,
honestly-scoped rebuild of both classes together:

1. Verify every `data/seed/legal-sources.edn` row cites an allow-listed
   monorepo doc, does not name a prohibited vendor, and — for any row
   graded from general legal knowledge rather than an in-repo doc — carries
   its required `:legal-source/license-basis`
   (`bin/verify-seed-provenance.cljs`).
2. Verify `sources.lock.edn`'s pinned `etzhayyim/com-etzhayyim-ooyake`
   revision matches the `ref:` the workflow actually checks out
   (`bin/verify-ci-lock.cljs`).
3. Check out `etzhayyim/com-etzhayyim-ooyake` at that locked revision into
   `.sources/com-etzhayyim-ooyake`.
4. Rebuild the Datascript transaction EDN and Kotoba EAVT export from
   `data/seed/*.edn` plus the locked ooyake checkout
   (`bin/build.cljs .sources`).
5. Fail if generated files differ from committed output.
6. Rebuild the quality report and assert `:quality/ingested-full-text` is
   pinned at `0` — the one invariant this repo must never silently violate,
   since it is what distinguishes a source *catalog* from a text *corpus* —
   and that `:quality/tier-unspecified` is `0` (every legal-source row has
   a real license grading, not a placeholder).
7. Materialize the Datascript database and run the query/coverage
   invariants in `test/query_contract.clj`, including that all 8 of
   hanrei's named international courts are present. Snapshot rebuild and
   projection use NBB; the Datascript compatibility test uses the canonical
   Clojure Datascript library (same split as `global-energy-datoms`).
8. Verify `coverage/registry.edn` — a hand-maintained overview, NOT
   part of the `bin/build.cljs` pipeline — has not silently drifted from
   `data/seed/legal-sources.edn`: its jurisdiction-code set must match
   exactly, and every jurisdiction's `:coverage/wave-1-sources` must be
   exactly that jurisdiction's `wave/w1` rows, no more and no less
   (`test/coverage_registry_contract.clj`).

Growing the `legal-source` catalog (adding a jurisdiction, correcting a
URL, adding a family, grading a license) is a normal PR that edits
`data/seed/legal-sources.edn` and lets CI rebuild + verify the derived
files. Growing `:legislature`/`:court`/`:jurisdiction` coverage requires no
edit here at all — it tracks whatever `etzhayyim/com-etzhayyim-ooyake`
registers, gated only by advancing `sources.lock.edn`'s pinned revision.
Landing actual ingested legal *text* is explicitly **not** in this repo's
scope — that is the separate raw-bytes/CID layer ADR-2605262800 describes
and has not yet shipped (R0 only).

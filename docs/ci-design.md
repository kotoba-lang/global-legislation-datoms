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
6. Rebuild the quality report and assert `:quality/text-bytes-held-here` is
   pinned at `0` — the one invariant this repo must never silently violate,
   since it is what keeps a *projection* of a corpus from turning into a
   second copy of one — and that `:quality/tier-unspecified` is `0` (every
   legal-source row has a real license grading, not a placeholder).

   (Through 2026-07-10 this gate was named `:quality/ingested-full-text`.
   That one number asserted two different things — "this repo holds no
   text" and "no text has been ingested anywhere" — and the second stopped
   being true when ADR-2608041800 landed four full-text datasets. The first
   is still the invariant and is now named for what it actually checks.)
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

## The corpus layer, and the CI gap it currently has (ADR-2608041800)

Landing actual ingested legal *text* was explicitly **not** in this repo's
scope until 2026-08-04. It now is — but the text still lives elsewhere.
`data/corpus/**` is built by `bin/build-corpus.cljs` from the `index/` layer
of four revision-locked DataLad datasets (`sources.lock.edn`), and each
`:law` entity addresses its full text by sha256 rather than embedding it.

**These four steps are missing from `.github/workflows/contract.yml` and
should be added.** The agent that landed the corpus layer could not push
them: its OAuth token lacks GitHub's `workflow` scope, which blocks a
`git push` touching a workflow file *and* the Contents API (which answers
404, not 403). `bin/verify-ci-lock.cljs` reports the four unverified
sources loudly on every run so this does not fade into the background.

Until it is applied, CI reproduces and gates the **catalog** only;
`data/corpus/**` is committed output that CI does not independently rebuild,
and `test/corpus_contract.clj` does not run there (it does pass locally —
run it before landing any corpus change).

Insert after the existing `com-etzhayyim-ooyake` checkout step:

```yaml
      # Corpus source datasets. Only index/ is read; raw/ is git-annex
      # content in B2 and a plain checkout leaves it as dangling symlinks,
      # which is correct — rebuilding this projection must never require
      # downloading 3.43 GB of statutory text.
      - uses: actions/checkout@v4
        with:
          repository: etzhayyim/jp.go.e-gov.elaws
          ref: 2e601624979d0692c32bde83ca824b7b86380e3c
          path: .sources/jp.go.e-gov.elaws
      - uses: actions/checkout@v4
        with:
          repository: etzhayyim/eu.europa.eur-lex
          ref: eed2847ef56d2088e1421ec590c3d67706c3dd15
          path: .sources/eu.europa.eur-lex
      - uses: actions/checkout@v4
        with:
          repository: etzhayyim/uk.gov.legislation
          ref: c212340d56878bbacc5c12db34b405ad003fb94e
          path: .sources/uk.gov.legislation
      - uses: actions/checkout@v4
        with:
          repository: etzhayyim/gov.govinfo.bulkdata
          ref: 49da746ef00539b0e4e1622731779afb786ed7b1
          path: .sources/gov.govinfo.bulkdata
```

and these two run steps — the first before `Build coverage-quality report`
(so the freshness diff covers `data/corpus`), the second after the existing
query contract:

```yaml
      - name: Rebuild corpus projection from locked source-dataset indexes
        run: npx nbb bin/build-corpus.cljs .sources
      - name: Exercise corpus + dependency-graph query contract
        run: clojure -M test/corpus_contract.clj
```

Then flip `:source/ci-checkout` to `true` (or drop the key) on the four
corpus rows in `sources.lock.edn`; `bin/verify-ci-lock.cljs` will start
asserting their revisions and stop warning.

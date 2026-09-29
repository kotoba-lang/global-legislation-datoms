# CI design

**CI for this repository is the murakumo mac-mini fleet, not GitHub Actions.**

GitHub Actions is **disabled** here (2026-08-05, owner instruction
"github は使わない"). The workspace made this call in ADR-2607300900 after every
workflow in three orgs stopped starting jobs — the runs were not failing, they
were not running at all, so the repositories looked green while nothing was
checked. `.github/workflows/contract.yml` is still on disk and is **inert**;
deleting it needs GitHub's `workflow` OAuth scope, which this workspace's token
does not have.

## Where the checks live now

| layer | what runs it | what it checks |
|---|---|---|
| **fleet gate** | `scripts/fleet-ci/gates/legislation-corpus-check.cljs` in `com-junkawasaki/root`, registered in `scripts/fleet-ci/gates.edn` | the **committed projection**, on every tip change |
| **local contract** | `kbb -M test/query_contract.cljk` (chains `test/corpus_contract.cljk`) | the projection **loaded into a real Datascript db** |
| **local rebuild** | `bin/verify-seed-provenance.cljk` → `bin/verify-ci-lock.cljk` → `bin/build.cljk` → `bin/build-corpus.cljk` → `bin/build-quality-report.cljk` → `git diff --exit-code -- data` | that the committed output is what the generators produce |

Run all three before landing a change. The fleet runs the first one for you.

## The fleet gate

Registered in `scripts/fleet-ci/gates.edn` as
`{:name "global-legislation-datoms" :gate :nbb-script :cd true}`. On every tip
change `tick.cljs` ships an `.edn`-only tarball of the repo to a fleet node,
runs the gate there, signs a receipt into the append-only
`manifest/fleet-ci.edn`, and — because `:cd true` — advances this repo's west
pin when green.

It checks:

1. **Every declared shard's sha256 and byte count match `data/corpus/manifest.edn`.**
   This is the point of the gate. It catches a shard edited after it was
   generated — including one edited *consistently*, which no amount of reading
   the committed values can detect.
2. Every shard's entity count matches the manifest.
3. `data/quality-report.edn` floors: corpus source/law/relation counts, and
   `:quality/text-bytes-held-here` = 0 (this repo projects a corpus, it must
   never store one).
4. The manifest's per-source totals agree with the quality report's.
5. On the JP shard (the one source whose text layer is complete as a class):
   `:law/key` and `:law.rel/id` uniqueness, well-formed text addresses, exactly
   13 laws without text (the ids e-Gov itself 404s), and **edge direction** —
   the Constitution of Japan has never been amended, so an incoming `amends`
   edge means `from`/`to` got swapped, a bug no row count can see.
6. The corpus→catalog join: every `:law/source-id` resolves to a
   `:legal-source/id` that is `:status/ingested` and names a dataset. Without
   this, "under what licence may I use this text?" silently returns nothing.
7. `schema/legislation.edn` declares the corpus attributes.

**Why it is an `:nbb-script` gate and not `:jvm-test`.** The original reason
given here was wrong and is worth recording rather than quietly replacing: it
said fleet nodes reach only the tailnet, so the maven-hosted Datascript
dependency could never resolve. That came from fleet-ci's own README, which
recorded **one measurement of one node** (zebulun, 2026-07-26). On 2026-08-05
all ten reachable nodes were measured directly and `repo1.maven.org` returns
**200** on every one of them. `:jvm-test` is therefore possible.

The gate stays as it is for a different, and better, reason: the sha256
comparison, the shard counts and the edge-direction check need no Datalog
engine, run faster than `kbb -M:test`, and catch a hand-edited shard, which
is the one thing the Actions design structurally could not. The assertions that
genuinely need Datascript — that the schema and transaction data load into a
real db, and that the read-only adapter rejects bad input — stay in the local
contract, where they belong.

**What the gate does not check**, stated rather than left to be inferred: the
EU/UK/US shards are verified by sha256 and entity count only, not parsed for
semantics (646,468 edges take minutes to read with edamame, and sha256 already
detects tampering). And nothing **re-derives** `data/corpus/**` from the locked
source datasets — that needs their `index/` trees on a node.

## The three input classes

- **`data/seed/{legal-sources,prohibited-sources,hanrei-coverage}.edn`** — no
  upstream raw dataset to fetch. The "source" is a fixed set of already-reviewed
  docs in this same monorepo (an ADR, a README, a AGENTS.md coverage table, an
  actor manifest). `bin/verify-seed-provenance.cljk` checks that every row cites
  an allow-listed one of them, names no prohibited vendor, and — for a row
  graded from general legal knowledge rather than an in-repo doc — carries its
  `:legal-source/license-basis`.
- **`:legislature` / `:court` / `:jurisdiction`** — mechanically parsed from the
  `etzhayyim/com-etzhayyim-ooyake` sibling repo at a locked revision
  (`sources.lock.edn`), the same pattern `global-energy-datoms` uses for its
  four statistical sources.
- **corpus (`:law` / `:law.rel`)** — projected from the `index/` layer of four
  locked DataLad datasets (ADR-2608041800). Each `:law` addresses its full text
  by sha256; the bytes live in git-annex/B2, never here.

`:quality/text-bytes-held-here` was called `:quality/ingested-full-text` through
2026-07-10. That one number asserted two different things — "this repo holds no
text" and "no text has been ingested anywhere" — and the second stopped being
true when ADR-2608041800 landed four full-text datasets. The first is still the
invariant and is now named for what it actually checks.

## The local contract

`kbb -M test/query_contract.cljk` runs the catalog contract and then chains
`test/corpus_contract.cljk`. That covers what the fleet gate structurally cannot:
the schema and transaction data are loaded into a real Datascript db, published
queries are executed through `adapters/read_only.cljk`, and the read-only
boundary is asserted to *reject* malformed consumers and unpublished query ids.
`test/coverage_registry_contract.cljk` checks that the hand-maintained
`coverage/registry.edn` has not drifted from `data/seed/legal-sources.edn`.

## Growing coverage

Growing the `legal-source` catalog (adding a jurisdiction, correcting a URL,
adding a family, grading a licence) is a normal PR that edits
`data/seed/legal-sources.edn` and lets the generators rebuild the derived files.
Growing `:legislature`/`:court`/`:jurisdiction` needs no edit here at all — it
tracks whatever `com-etzhayyim-ooyake` registers, gated only by advancing the
pinned revision. Adding a corpus jurisdiction is one row in
`bin/build-corpus.cljk`'s `datasets` plus one DataLad dataset.

## If someone re-enables GitHub Actions

Don't, without a reason — ADR-2607300900 is the standing decision. If it happens
anyway, `bin/verify-ci-lock.cljk` will already have been reporting any revision
in the inert `contract.yml` that disagrees with `sources.lock.edn`, so the trap
is visible before it fires.

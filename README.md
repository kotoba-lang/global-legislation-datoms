# global-legislation-datoms

世界の法律・法案の「一次ソース所在地」——どの法域にどんな法律・判例・条約のソースがあり、どのライセンスで、どの sensor family が対応するか——を query 可能な共通 EDN に投影する公開契約です。**条文・判決の全文は保持しません**（スコープの詳細は後述）。

- `schema/legislation.edn` は Datascript の schema。
- `data/datascript-tx.edn` は Datascript に直接 `db-with` できる entity map 群。
- `data/world-legislation.kotoba.edn` は Kotoba 互換の `[e a v tx :add]` EAVT。
- `data/provenance.edn` は各エントリの根拠 doc・件数。
- `data/quality-report.edn` は wave 別・ライセンス層別 coverage の決定的レポート。
- `coverage/registry.edn` は法域ごとの family coverage と安全な結合キー。
- `connections/actors.edn` は actor／他リポジトリごとの read-only 接続契約。
- `adapters/read_only.clj` は公開済み query だけを実行する Datascript consumer adapter。

## スコープ(重要)

この repo は `global-energy-datoms` と同じ「-datoms」パターン(schema + tx + kotoba EAVT + provenance + quality-report + coverage + connections + read-only adapter)を踏襲しますが、**入力データの性質が異なります**:

- `global-energy-datoms` は、別リポジトリとして既に存在する DataLad raw dataset(World Bank WDI・Our World in Data・UN SDG 等)を機械的にパースして投影します。
- この repo が対象とする「世界の法律・法案の全文コーパス」は、`ADR-2605262800`(`etzhayyim/root`)が R0(ADR + sensor scaffold のみ)の状態で、実際に ingest された raw dataset がまだ monorepo 上に存在しません。

そのため `data/seed/*.edn` は、既にレビュー済みの monorepo 内ドキュメントから手作業で編纂した catalog です。各行が根拠ドキュメントを明示的に引用します:

- `data/seed/legal-sources.edn` — `ADR-2605262800`(法域別ソース一覧 + Tier-A/B/C ライセンス格付け)、`kotoba-lang/kotodama-py` の `sensors/legal/README.md`(wave-1 5-anchor 表)、`etzhayyim/root` `60-apps/etzhayyim-project-hanrei/CLAUDE.md`(e-Gov・官報・courts.go.jp の実URL)。
- `data/seed/legislatures.edn` — `etzhayyim/com-etzhayyim-ooyake` の `registry/gov-units.world-legislatures.edn`(`:maintainer-verified` 行のみ)。
- `data/seed/prohibited-sources.edn` — 同じ2ドキュメントが明記する ingestion 禁止ベンダー(Westlaw / LexisNexis / Bloomberg Law / Wolters Kluwer)。
- `data/seed/hanrei-coverage.edn` — hanrei 自身が報告する集計 coverage(法域ごとの legal-system 内訳はここには複製しない。集計値のみ on-record)。

`data/quality-report.edn` の `:quality/ingested-full-text` は常に `0` に固定されます。これは「catalog(登録層)」と「corpus(全文層)」を混同しないための不変条件です。

## Rebuild

```bash
npx nbb bin/verify-seed-provenance.cljs   # 各行が許可済みdocを引用しているか + 禁止ベンダーでないか
npx nbb bin/build.cljs                    # data/seed/*.edn → datascript-tx.edn / world-legislation.kotoba.edn / provenance.edn
npx nbb bin/build-quality-report.cljs     # data/quality-report.edn
clojure -M test/query_contract.clj        # Datascript query/coverage 不変条件
```

新しい法域・ソースを追加する通常の変更は `data/seed/*.edn` を編集する PR で、`:legal-source/provenance-doc` に実在する monorepo ドキュメントを引く。CI (`.github/workflows/contract.yml`) がそれを機械的に強制する。

## 詳細

- `docs/ci-design.md` — CI が何を検証し、何を検証しない(=raw source pin が無い理由)か。
- `maturity/scorecard.edn` — R1(catalog layer)としての達成/未達基準。全文コーパス化は明示的に対象外(`:full-text-corpus :status :explicitly-out-of-scope`)。

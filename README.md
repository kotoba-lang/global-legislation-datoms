# global-legislation-datoms

世界の法律・法案の「一次ソース所在地」——どの法域にどんな法律・判例・条約のソースがあり、どのライセンスで、どの sensor family が対応するか——を query 可能な共通 EDN に投影する公開契約です。**条文・判決の全文は保持しません**（スコープの詳細は後述）。

- `schema/legislation.edn` は Datascript の schema。
- `data/datascript-tx.edn` は Datascript に直接 `db-with` できる entity map 群(567 entities)。
- `data/world-legislation.kotoba.edn` は Kotoba 互換の `[e a v tx :add]` EAVT。
- `data/provenance.edn` は各エントリの根拠 doc・件数。
- `data/quality-report.edn` は wave 別・ライセンス層別・法域別 coverage の決定的レポート。
- `coverage/registry.edn` は法域ごとの family coverage と安全な結合キー。
- `connections/actors.edn` は actor／他リポジトリごとの read-only 接続契約。
- `adapters/read_only.clj` は公開済み query だけを実行する Datascript consumer adapter。

## 現在の coverage(2026-07-10)

| entity | 件数 | 備考 |
|---|---|---|
| `:legal-source` | 34 | ADR-2605262800 + hanrei が名指しした法域のソースのカタログ。wave-1(sensor実装対象) 5件 |
| `:legislature` | 186 | ooyake `world-legislatures.edn` から機械的にミラー |
| `:court` | 144 | ooyake `world-courts.edn` から機械的にミラー(最高裁/憲法裁) |
| `:jurisdiction` | 198 | 上記3種の union、国名は ooyake の country registry から解決 |
| ライセンス | Tier-A 21 / Tier-B 13 / **unspecified 0** | 15件の unspecified を実地の著作権法根拠で全格付け済み(下記) |
| hanrei の8国際裁判所 | **8/8** | ICJ/ICC/ECHR/CJEU/IACHR/ACHPR/ITLOS/WTO AB 全件 `:legal-source/hanrei-court-code` でタグ済み |

## スコープ(重要)

この repo は `global-energy-datoms` と同じ「-datoms」パターン(schema + tx + kotoba EAVT + provenance + quality-report + coverage + connections + read-only adapter)を踏襲しますが、**入力データの性質が2階層あります**:

1. **`data/seed/{legal-sources,prohibited-sources,hanrei-coverage}.edn`** — 手作業で編纂した catalog。`global-energy-datoms` と違い、この「世界の法律・法案の全文コーパス」対象領域には、機械的にパースできる既存の DataLad raw dataset がまだ存在しません(`ADR-2605262800` は R0 = ADR + sensor scaffold のみ)。各行は根拠ドキュメントを明示的に引用します:
   - `data/seed/legal-sources.edn` — `:doc/adr-2605262800`(法域別ソース一覧 + Tier-A/B/C ライセンス格付け)、`:doc/kotodama-py-legal-sensors-readme`(`kotoba-lang/kotodama-py` の wave-1 5-anchor 表)、`:doc/hanrei-claude-md`(e-Gov・官報・courts.go.jp の実URL)、`:doc/hanrei-actor-manifest`(hanrei の「8 international courts」列挙)。
   - `data/seed/prohibited-sources.edn` — 同上ドキュメントが明記する ingestion 禁止ベンダー(Westlaw / LexisNexis / Bloomberg Law / Wolters Kluwer)。
   - `data/seed/hanrei-coverage.edn` — hanrei 自身が報告する集計 coverage(法域ごとの legal-system 内訳はここには複製しない。集計値のみ on-record)。
2. **`:legislature` / `:court` / `:jurisdiction`** — `bin/build.cljs` が `etzhayyim/com-etzhayyim-ooyake`(sibling repo, `sources.lock.edn` で revision 固定)の `world-legislatures.edn` / `world-courts.edn` / `world-countries.edn`+`seed.edn`+`g20.edn` を直接パースして生成。**手打ちの行は一つもありません** — `global-energy-datoms` が World Bank/OWID/UN SDG を機械的に投影するのと同じ sibling-repo パターンです。legal-source(34件)より遥かに広い198法域分の「実在する議会・最高裁」を、legal-source を将来拡張する際の候補台帳として提供します。**legislature/court の存在は統治機構の実在確認であって、その法域の法律・判例テキストの一次ソースURL・ライセンスが判明していることを意味しません**(そこは今も legal-source 34件のみ)。

### ライセンス格付けの2段階の確信度

`:legal-source/license-provenance` は `:legal-source/url-provenance` と同じ区別を持ちます:

- `:doc-literal` — monorepo 内のドキュメントにライセンス文字列がそのまま書かれている(wave-1 5件 + fr-legifrance の "Étalab")。
- `:general-legal-knowledge` — 各国の著作権法上の「国の公式著作物は著作権の対象外」条項(日本著作権法13条・独UrhG§5・伊法633/1941第5条・韓国著作権法7条・伯法9.610/98第8条・米17 U.S.C.§105・中国著作権法第5条)や、国際機関の著作権ポリシー(UN/WIPO/HCCH/CoE/ICC — reuse-permitted-with-attribution が通例で、無条件 public domain ではない)から判定。**`:doc-literal` より確信度が低い主張であり、必ず `:legal-source/license-basis` に具体的な法的根拠を書く**(`bin/verify-seed-provenance.cljs` が両フィールドの対応を強制)。

`data/quality-report.edn` の `:quality/ingested-full-text` は常に `0` に固定されます。これは「catalog(登録層)」と「corpus(全文層)」を混同しないための不変条件です。

## Rebuild

```bash
npx nbb bin/verify-seed-provenance.cljs   # legal-source各行が許可済みdocを引用 + license-basis対応 + 禁止ベンダーでないか
npx nbb bin/verify-ci-lock.cljs           # sources.lock.edn と CI workflow の revision 一致
npx nbb bin/build.cljs [source-root]      # data/seed/*.edn + ooyake(sibling) → datascript-tx.edn / world-legislation.kotoba.edn / provenance.edn
npx nbb bin/build-quality-report.cljs     # data/quality-report.edn
clojure -M test/query_contract.clj        # Datascript query/coverage 不変条件
```

`source-root` 省略時は `..`(= このリポジトリが `orgs/etzhayyim/global-legislation-datoms` として checkout されている west 環境で、sibling の `orgs/etzhayyim/com-etzhayyim-ooyake` を直接参照)。CI は `sources.lock.edn` の revision で ooyake を `.sources/com-etzhayyim-ooyake` に checkout し、`bin/build.cljs .sources` で走らせる。

新しい法域・ソースを追加する通常の変更は `data/seed/*.edn` を編集する PR で、`:legal-source/provenance-doc` に実在する monorepo ドキュメントを引く。CI (`.github/workflows/contract.yml`) がそれを機械的に強制する。ooyake 側の登録が増えれば `:legislature`/`:court`/`:jurisdiction` は次回 rebuild で自動的に追従する(手動の追記は不要)。

## 詳細

- `docs/ci-design.md` — CI が何を検証するか(seed provenance / CI lock / ビルド差分 / query contract)。
- `maturity/scorecard.edn` — R2 としての達成/未達基準。全文コーパス化は明示的に対象外(`:full-text-corpus :status :explicitly-out-of-scope`)。

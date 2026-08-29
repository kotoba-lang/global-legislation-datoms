(ns adapters.datalog-runtime
  "In-memory Datalog query layer for global-legislation-datoms.

  Replaces JVM `datascript.core` with `kotoba-lang/datalog`. Keyword attributes
  and keyword values in entity maps are stored as bare strings so published
  queries in queries/examples.edn keep working unchanged.

  Extends the global-accounts-datoms adapter with DataScript-shaped `pull`,
  `:rules` / `%` inputs, tuple `:find`, and incremental `db-with`."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datalog.core :as dl]
            [datalog.index :as index]))

(defn- attr-name [k]
  (if (keyword? k)
    (if-let [ns* (namespace k)]
      (str ns* "/" (name k))
      (name k))
    (str k)))

(defn- keywordize-attr [s]
  (if (str/includes? s "/")
    (keyword (subs s 0 (str/index-of s "/")) (subs s (inc (str/index-of s "/"))))
    (keyword s)))

(defn- attr-value [v]
  (cond
    (keyword? v) (attr-name v)
    (map? v) (pr-str v)
    (or (vector? v) (seq? v) (set? v)) (pr-str v)
    (nil? v) ""
    :else v))

(defn- aggregate-form? [x]
  (and (seq? x) (#{'count 'sum 'avg 'min 'max 'count-distinct} (first x))))

(defn- parse-vector-query [query]
  (let [qvec (if (string? query) (edn/read-string query) query)
        idx-in (first (keep-indexed #(when (= %2 :in) %1) qvec))
        idx-where (or (first (keep-indexed #(when (= %2 :where) %1) qvec)) -1)
        find-end (or idx-in idx-where)
        find-part (vec (remove #{'$ '.} (subvec qvec 1 find-end)))
        scalar-dot? (some #{'.} (subvec qvec 1 find-end))
        pull-spec (when (= 1 (count find-part))
                    (let [x (first find-part)]
                      (when (and (seq? x) (= 'pull (first x)))
                        {:var (second x) :attrs (nth x 2)})))
        tuple-find? (and (= 1 (count find-part))
                         (vector? (first find-part))
                         (every? symbol? (first find-part)))
        agg-forms (vec (filter aggregate-form? find-part))
        plain-syms (vec (filter symbol? find-part))
        dl-find (cond
                  pull-spec [(pull-spec :var)]
                  tuple-find? (vec (first find-part))
                  (seq agg-forms) (into plain-syms agg-forms)
                  :else find-part)
        in-raw (when idx-in (vec (subvec qvec (inc idx-in) idx-where)))
        rules-input? (some #{'%} in-raw)
        in-syms (vec (remove #{'$ '%} in-raw))
        where-clauses (when (pos? idx-where) (vec (subvec qvec (inc idx-where))))]
    {:find dl-find
     :pull-spec pull-spec
     :tuple-find? tuple-find?
     :in in-syms
     :rules-input? rules-input?
     :where where-clauses
     :scalar-dot? scalar-dot?}))

(defn- norm-ground [x]
  (cond
    (symbol? x) x
    (keyword? x) (attr-name x)
    :else x))

(defn- norm-clause [clause]
  (cond
    (and (seq? clause) (= 'not (first clause)) (vector? (second clause)))
    (list 'not (mapv norm-ground (second clause)))

    (and (seq? clause)
         (symbol? (first clause))
         (not (#{'not 'or 'or-join} (first clause)))
         (not (vector? clause)))
    (apply list (cons (first clause) (map norm-ground (rest clause))))

    (vector? clause)
    (mapv (fn [x]
            (if (seq? x)
              (apply list (map norm-ground x))
              (norm-ground x)))
          clause)

    :else clause))

(defn- norm-rules [rules]
  (mapv (fn [rule]
          (let [[head & body] rule]
            (into [head] (map norm-clause body))))
        rules))

(defn- denorm-value [v]
  (if (and (string? v)
           (re-matches #"^[^/]+/[^/]+$" v))
    (keywordize-attr v)
    v))

(defn- denorm-row [row]
  (mapv denorm-value row))

(defn- empty-store []
  {:index (index/empty-db) :entities {} :counter 0})

(defn- add-record [store record]
  (let [subject (str "e" (:counter store))
        db' (reduce (fn [acc [k v]]
                      (if (= k :db/id)
                        acc
                        (index/assert-quad acc
                                           {:s subject
                                            :p (attr-name k)
                                            :o (attr-value v)}
                                           (constantly false))))
                    (:index store)
                    record)]
    {:index db'
     :entities (assoc (:entities store) subject record)
     :counter (inc (:counter store))}))

(defn db
  "Build a store from a seq of entity maps (one map per entity)."
  [records]
  (reduce add-record (empty-store) records))

(defn db-with
  "Add entity maps from `tx` to an existing store (DataScript `db-with` shape)."
  [store tx]
  (reduce add-record store tx))

(defn pull
  "DataScript-shaped pull over a store. `pattern` is a vector of attribute
  keywords, or `[*]` for the full entity map."
  [store eid pattern]
  (let [entity (get (:entities store) (str eid))]
    (when entity
      (if (= pattern '[*])
        entity
        (select-keys entity (map keywordize-attr pattern))))))

(defn- project-rows [store {:keys [pull-spec tuple-find? scalar-dot?]} rows]
  (let [pull-pattern (when pull-spec (if (= '[*] (:attrs pull-spec)) '[*] (:attrs pull-spec)))
        rows (if pull-spec
               (for [[eid] rows]
                 [(pull store eid pull-pattern)])
               rows)]
    (cond
      (and scalar-dot? (= 1 (count rows)))
      (ffirst rows)

      tuple-find?
      (if (= 1 (count rows))
        (vec (first rows))
        (set (map vec rows)))

      :else
      (set rows))))

(defn q
  "Run a DataScript-shaped vector query over `store`. When the query's `:in`
  includes `%`, the first argument after `store` must be the rules vector;
  remaining args follow `:in` after `$` and `%`.

  Argument order matches DataScript: `(q query store & inputs)`."
  [query store & inputs]
  (let [parsed (parse-vector-query query)
        {:keys [find in where rules-input?]} parsed
        [rules & rest-inputs]
        (if rules-input?
          inputs
          (cons nil inputs))
        _ (when (not= (count in) (count rest-inputs))
            (throw (ex-info "datalog-runtime: :in arity mismatch"
                            {:in in :inputs rest-inputs :rules-input? rules-input?})))
        dl-query (cond-> {:find find
                          :in (when (seq in) in)
                          :where (mapv norm-clause where)}
                   (seq rules) (assoc :rules (norm-rules rules)))
        rows (mapv denorm-row
                 (vec (dl/q (:index store) dl-query (constantly true) (vec rest-inputs))))]
    (project-rows store parsed rows)))

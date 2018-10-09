(ns metabase.mbql.util
  "Utilitiy functions for working with MBQL queries."
  (:refer-clojure :exclude [replace])
  (:require [clojure.core.match :as match]
            [clojure
             [string :as str]
             [walk :as walk]]
            [metabase.mbql.schema :as mbql.s]
            [metabase.mbql.util.match :as mbql.match]
            [metabase.util :as u]
            [metabase.util
             [date :as du]
             [i18n :refer [tru]]
             [schema :as su]]
            [schema.core :as s]
            [medley.core :as m]))

(s/defn normalize-token :- s/Keyword
  "Convert a string or keyword in various cases (`lisp-case`, `snake_case`, or `SCREAMING_SNAKE_CASE`) to a lisp-cased
  keyword."
  [token :- su/KeywordOrString]
  (-> (u/keyword->qualified-name token)
      str/lower-case
      (str/replace #"_" "-")
      keyword))

(defn mbql-clause?
  "True if `x` is an MBQL clause (a sequence with a keyword as its first arg). (Since this is used by the code in
  `normalize` this handles pre-normalized clauses as well.)"
  [x]
  (and (sequential? x)
       (keyword? (first x))))

(defn is-clause?
  "If `x` an MBQL clause, and an instance of clauses defined by keyword(s) `k-or-ks`?

    (is-clause? :count [:count 10])        ; -> true
    (is-clause? #{:+ :- :* :/} [:+ 10 20]) ; -> true"
  [k-or-ks x]
  (and
   (mbql-clause? x)
   (if (coll? k-or-ks)
     ((set k-or-ks) (first x))
     (= k-or-ks (first x)))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                Match & Replace                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

;; Actual implementation of these macros is in `mbql.util.match`. They're in a seperate namespace because they have
;; lots of other functions and macros they use for their implementation that we would like to discourage you from
;; using directly. implementation of

(defmacro match
  "Return a sequence of things that match a `pattern` or `patterns` inside `x`, presumably a query, returning `nil` if
  there are no matches. Recurses through maps and sequences. `pattern` can be one of several things:

  *  Keyword name of an MBQL clause
  *  Set of keyword names of MBQL clauses. Matches any clauses with those names
  *  A `core.match` pattern
  *  A symbol naming a class.
  *  A symbol naming a predicate function

  Examples:

    ;; keyword pattern
    (match {:fields [[:field-id 10]]} :field-id) ; -> [[:field-id 10]]

    ;; set of keywords
    (match some-query #{:field-id :fk->}) ; -> [[:field-id 10], [:fk-> [:field-id 10] [:field-id 20]], ...]

    ;; `core.match` pattern
    (match some-query [:field-id (_ :guard #(> % 100))]) ; -> [[:field-id 200], ...]

    ;; symbol naming a Class
    (match some-query java.util.Date) ; -> [[#inst \"2018-10-08\", ...]

    ;; symbol naming a predicate function
    (match some-query even?) ; -> [2 4 6 8]

  ### Using `core.match` patterns

  See [`core.match` documentation](`https://github.com/clojure/core.match/wiki/Overview`) for more details.

  ### Returing something other than the exact match with result body

  By default, `match` returns whatever matches the pattern you pass in. But what if you only want to return part of
  the match? You can, using `core.match` binding facilities. Bind relevant things in your pattern and pass in the
  optional result body. Whatever result body returns will be returned by `match`:

     ;; just return the IDs of Field ID clauses
     (match some-query [:field-id id] id) ; -> [1 2 3]

  You can also use result body to results, and any `nil` values will be skipped:

    (match some-query [:field-id id]
      (when (even? id)
        id))
    ;; -> [2 4 6 8]

  Of course, it's probably more efficient to let `core.match` compile an efficient matching function, so prefer using
  patterns with `:guard` where possible.

  One more thing to know about result bodies: you can call `recur` inside them, and use the same matching logic
  against a different value.

  ### `&match` and `&parents` anaphors

  For more advanced matches, like finding `:field-id` clauses nested anywhere inside `:datetime-field` clauses,
  `match` binds a pair of anaphors inside the result body for your convenience. `&match` is bound to the entire
  match, regardless of how you may have destructured it; `&parents` is bound to a sequence of keywords naming the
  parent top-level keys and clauses of the match.

    (mbql.u/match {:fields [[:datetime-field [:fk-> [:field-id 1] [:field-id 2]] :day]]} :field-id
      ;; &parents will be [:fields :datetime-field :fk->]
      (when (contains? (set &parents) :datetime-field)
        &match))
    ;; -> [[:field-id 1] [:field-id 2]]"
  {:style/indent 1}
  [x & patterns-and-results]
  `(mbql.match/match ~x ~patterns-and-results))

(defmacro match-one
  "Like `match` but returns a single match rather than a sequence of matches."
  {:style/indent 1}
  [x & patterns-and-results]
  `(first (mbql.match/match ~x ~patterns-and-results)))


(defmacro replace
  "Like `match`, but replace matches in `x` with the results of result body. The same pattern options are supported,
  and `&parents` and `&match` anaphors are available in the same way. (`&match` is particularly useful here if you
  want to use keywords or sets of keywords as patterns.)"
  {:style/indent 1}
  [x & patterns-and-results]
  `(mbql.match/replace ~x ~patterns-and-results))

(defmacro replace-in
  "Like `replace`, but only replaces things in the part of `x` noted by `ks`."
  {:style/indent 2}
  [x ks & patterns-and-results]
  `(update-in ~x ~ks #(mbql.match/replace % ~patterns-and-results)))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                       Functions for manipulating queries                                       |
;;; +----------------------------------------------------------------------------------------------------------------+

;; TODO - I think we actually should move this stuff into a `mbql.helpers` namespace so we can use the util functions
;; above in the `schema.helpers` namespace instead of duplicating them

(s/defn simplify-compound-filter :- mbql.s/Filter
  "Simplify compound `:and`, `:or`, and `:not` compound filters, combining or eliminating them where possible. This
  also fixes theoretically disallowed compound filters like `:and` with only a single subclause."
  [[filter-name & args :as filter-clause]]
  (cond
    ;; for `and` or `not` compound filters with only one subclase, just unnest the subclause
    (and (#{:and :or} filter-name)
         (= (count args) 1))
    (recur (first args))

    ;; for `and` and `not` compound filters with subclauses of the same type pull up any compounds of the same type
    ;; e.g. [:and :a [:and b c]] ; -> [:and a b c]
    (and (#{:and :or} filter-name)
         (some (partial is-clause? filter-name) args))
    (recur
     (vec (cons filter-name (mapcat (fn [subclause]
                                      (if (is-clause? filter-name subclause)
                                        (rest subclause)
                                        [subclause]))
                                    args))))

    ;; for `and` or `or` clauses with duplicate args, remove the duplicates and recur
    (and (#{:and :or} filter-name)
         (not= (count args) (count (distinct args))))
    (recur (vec (cons filter-name (distinct args))))

    ;; for `not` that wraps another `not`, eliminate both
    (and (= :not filter-name)
         (is-clause? :not (first args)))
    (recur (second (first args)))

    :else
    filter-clause))

;; TODO - we should validate the query against the Query schema and the output as well. Flip that on once the schema
;; is locked-in 100%

(s/defn combine-filter-clauses :- mbql.s/Filter
  "Combine two filter clauses into a single clause in a way that minimizes slapping a bunch of `:and`s together if
  possible."
  [filter-clause & more-filter-clauses]
  (simplify-compound-filter (vec (cons :and (filter identity (cons filter-clause more-filter-clauses))))))

(s/defn add-filter-clause :- mbql.s/Query
  "Add an additional filter clause to an `outer-query`. If `new-clause` is `nil` this is a no-op."
  [outer-query :- mbql.s/Query, new-clause :- (s/maybe mbql.s/Filter)]
  (if-not new-clause
    outer-query
    (update-in outer-query [:query :filter] combine-filter-clauses new-clause)))


(defn query->source-table-id
  "Return the source Table ID associated with `query`, if applicable; handles nested queries as well."
  {:argslists '([outer-query])}
  [{{source-table-id :source-table, source-query :source-query} :query, query-type :type, :as query}]
  (cond
    ;; for native queries, there's no source table to resolve
    (not= query-type :query)
    nil

    ;; for MBQL queries with a *native* source query, it's the same story
    (and (nil? source-table-id) source-query (:native source-query))
    nil

    ;; for MBQL queries with an MBQL source query, recurse on the source query and try again
    (and (nil? source-table-id) source-query)
    (recur (assoc query :query source-query))

    ;; otherwise resolve the source Table
    :else
    source-table-id))

(s/defn unwrap-field-clause :- (s/if (partial is-clause? :field-id)
                                 mbql.s/field-id
                                 mbql.s/field-literal)
  "Un-wrap a `Field` clause and return the lowest-level clause it wraps, either a `:field-id` or `:field-literal`."
  [[clause-name x y, :as clause] :- mbql.s/Field]
  (case clause-name
    :field-id         clause
    :fk->             (recur y)
    :field-literal    clause
    :datetime-field   (recur x)
    :binning-strategy (recur x)))

(defn maybe-unwrap-field-clause
  "Unwrap a Field `clause`, if it's something that can be unwrapped (i.e. something that is, or wraps, a `:field-id` or
  `:field-literal`). Otherwise return `clause` as-is."
  [clause]
  (if (is-clause? #{:field-id :fk-> :field-literal :datetime-field :binning-strategy} clause)
    (unwrap-field-clause clause)
    clause))

(s/defn field-clause->id-or-literal :- (s/cond-pre su/IntGreaterThanZero su/NonBlankString)
  "Get the actual Field ID or literal name this clause is referring to. Useful for seeing if two Field clauses are
  referring to the same thing, e.g.

    (field-clause->id-or-literal [:datetime-field [:field-id 100] ...]) ; -> 100
    (field-clause->id-or-literal [:field-id 100])                       ; -> 100

  For expressions (or any other clauses) this returns the clause as-is, so as to facilitate the primary use case of
  comparing Field clauses."
  [clause :- mbql.s/Field]
  (second (unwrap-field-clause clause)))

(s/defn add-order-by-clause :- mbql.s/Query
  "Add a new `:order-by` clause to an MBQL query. If the new order-by clause references a Field that is already being
  used in another order-by clause, this function does nothing."
  [outer-query :- mbql.s/Query, [_ field, :as order-by-clause] :- mbql.s/OrderBy]
  (let [existing-fields (set (for [[_ existing-field] (-> outer-query :query :order-by)]
                               (maybe-unwrap-field-clause existing-field)))]
    (if (existing-fields (maybe-unwrap-field-clause field))
      ;; Field already referenced, nothing to do
      outer-query
      ;; otherwise add new clause at the end
      (update-in outer-query [:query :order-by] (comp vec conj) order-by-clause))))


(s/defn add-datetime-units :- mbql.s/DateTimeValue
  "Return a `relative-datetime` clause with `n` units added to it."
  [absolute-or-relative-datetime :- mbql.s/DateTimeValue
   n                             :- s/Num]
  (if (is-clause? :relative-datetime absolute-or-relative-datetime)
    (let [[_ original-n unit] absolute-or-relative-datetime]
      [:relative-datetime (+ n original-n) unit])
    (let [[_ timestamp unit] absolute-or-relative-datetime]
      (du/relative-date unit n timestamp))))


(defn dispatch-by-clause-name-or-class
  "Dispatch function perfect for use with multimethods that dispatch off elements of an MBQL query. If `x` is an MBQL
  clause, dispatches off the clause name; otherwise dispatches off `x`'s class."
  [x]
  (if (mbql-clause? x)
    (first x)
    (class x)))


(s/defn fk-clause->join-info :- (s/maybe mbql.s/JoinInfo)
  "Return the matching info about the JOINed for the 'destination' Field in an `fk->` clause.

     (fk-clause->join-alias [:fk-> [:field-id 1] [:field-id 2]])
     ;; -> \"orders__via__order_id\""
  [query :- mbql.s/Query, [_ source-field-clause] :- mbql.s/fk->]
  (let [source-field-id (field-clause->id-or-literal source-field-clause)]
    (some (fn [{:keys [fk-field-id], :as info}]
            (when (= fk-field-id source-field-id)
              info))
          (-> query :query :join-tables))))


(s/defn expression-with-name :- mbql.s/ExpressionDef
  "Return the `Expression` referenced by a given `expression-name`."
  [query :- mbql.s/Query, expression-name :- su/NonBlankString]
  (or (get-in query, [:query :expressions (keyword expression-name)])
      (throw (Exception. (str (tru "No expression named ''{0}''" (name expression-name)))))))


(s/defn aggregation-at-index :- mbql.s/Aggregation
  "Fetch the aggregation at index. This is intended to power aggregate field references (e.g. [:aggregation 0]).
   This also handles nested queries, which could be potentially ambiguous if multiple levels had aggregations. To
   support nested queries, you'll need to keep tract of how many `:source-query`s deep you've traveled; pass in this
   number to as optional arg `nesting-level` to make sure you reference aggregations at the right level of nesting."
  ([query index]
   (aggregation-at-index query index 0))
  ([query :- mbql.s/Query, index :- su/NonNegativeInt, nesting-level :- su/NonNegativeInt]
   (if (zero? nesting-level)
     (or (nth (get-in query [:query :aggregation]) index)
         (throw (Exception. (str (tru "No aggregation at index: {0} (nesting level: {1})" index nesting-level)))))
     ;; keep recursing deeper into the query until we get to the same level the aggregation reference was defined at
     (recur (get-in query [:query :source-query]) index (dec nesting-level)))))

(defn ga-id?
  "Is this ID (presumably of a Metric or Segment) a GA one?"
  [id]
  (boolean
   (when ((some-fn string? keyword?) id)
     (re-find #"^ga(id)?:" (name id)))))

(defn ga-metric-or-segment?
  "Is this metric or segment clause not a Metabase Metric or Segment, but rather a GA one? E.g. something like `[:metric
  ga:users]`. We want to ignore those because they're not the same thing at all as MB Metrics/Segments and don't
  correspond to objects in our application DB."
  [[_ id]]
  (ga-id? id))

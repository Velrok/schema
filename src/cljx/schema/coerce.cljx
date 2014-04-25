(ns schema.coerce
  "Experimental extension of schema for input coercion (coercing an input to match a schema)"
  (:require
   #+cljs [cljs.reader :as reader]
   #+clj [clojure.edn :as edn]
   #+clj [schema.macros :as macros]
   [schema.core :as s]
   [schema.utils :as utils])
  #+cljs (:require-macros [schema.macros :as macros]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Generic input coercion

(def Schema
  "A Schema for Schemas"
  #+clj (s/protocol s/Schema)
  #+cljs (macros/protocol s/Schema))

(def CoercionMatcher
  "A function from schema to coercion function, or nil if no special coercion is needed.
   The returned function is applied to the corresponding data before validation (or walking/
   coercion of its sub-schemas, if applicable)"
  (macros/=> (s/maybe (macros/=> s/Any s/Any)) Schema))

(macros/defn coercer
  "Produce a function that simultaneously coerces and validates a datum."
  [schema coercion-matcher :- CoercionMatcher]
  (s/start-walker
   (fn [s]
     (let [walker (s/walker s)]
       (if-let [coercer (coercion-matcher s)]
         (fn [x]
           (macros/try-catchall
            (let [v (coercer x)]
              (if (utils/error? v)
                v
                (walker v)))
            (catch t (macros/validation-error s x t))))
         walker)))
   schema))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Coercion helpers

(macros/defn first-matcher :- CoercionMatcher
  "A matcher that takes the first match from matchers."
  [matchers :- [CoercionMatcher]]
  (fn [schema] (first (keep #(% schema) matchers))))

(defn safe
  "Take a single-arg function f, and return a single-arg function that acts as identity
   if f throws an exception, and like f otherwise.  Useful because coercers are not explicitly
   guarded for exceptions, and failing to coerce will generally produce a more useful error
   in this case."
  [f]
  (fn [x] (macros/try-catchall (f x) (catch e x))))

#+clj (def safe-long-cast
        "Coerce x to a long if this can be done without losing precision, otherwise return x."
        (safe
         (fn [x]
           (let [l (long x)]
             (if (== l x)
               l
               x)))))

(def edn-read-string #+clj edn/read-string #+cljs reader/read-string)

(defn string->keyword [s]
  (if (string? s) (keyword s) s))

(def string->num (safe edn-read-string))
(def string->int (safe edn-read-string))
#+clj (def string->int-jvm (safe #(safe-long-cast (edn-read-string %))))
#+clj (def string->long-jvm (safe #(safe-long-cast (edn-read-string %))))
#+clj (def string->double-jvm (safe #(Double/parseDouble %)))

(defn pred-enum-matcher
  [schema pred conversion-fn]
  (when (and (instance? #+clj schema.core.EnumSchema #+cljs s/EnumSchema schema)
             (every? pred (.-vs ^schema.core.EnumSchema schema)))
    conversion-fn))

(defn keyword-enum-matcher [schema]
  (pred-enum-matcher schema keyword? string->keyword))

(defn int-enum-matcher [schema]
  (pred-enum-matcher schema integer? #+cljs string->int #+clj string->int-jvm))

(defn set-matcher [schema]
  (if (instance? #+clj clojure.lang.APersistentSet #+cljs cljs.core.PersistentHashSet schema)
    (fn [x] (if (sequential? x) (set x) x))))

(def json-coercion-matcher-coercions
  (merge {s/Keyword string->keyword}
         #+clj {clojure.lang.Keyword string->keyword
                s/Int safe-long-cast
                Long safe-long-cast
                Double double}))

(defn json-coercion-matcher
    "A matcher that coerces keywords and keyword enums from strings, and longs and doubles
     from numbers on the JVM (without losing precision)"
    [schema]
    (or (json-coercion-matcher-coercions schema)
        (keyword-enum-matcher schema)
        (set-matcher schema)))

(def string-coercion-matcher-coercions
  (merge {s/Keyword string->keyword
          s/Num string->num
          s/Int string->int}
         #+clj {clojure.lang.Keyword string->keyword
                s/Int string->int-jvm
                Long string->long-jvm
                Double string->double-jvm}))

(defn string-coercion-matcher
    "A matcher that coerces keywords, keyword enums, s/Num and s/Int,
     and long and doubles (JVM only) from strings."
    [schema]
    (or (string-coercion-matcher-coercions schema)
        (keyword-enum-matcher schema)))

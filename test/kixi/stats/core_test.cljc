(ns kixi.stats.core-test
  (:require [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [kixi.stats.core :as kixi]
            [kixi.stats.utils :refer [sq sqrt]]
            #?@(:cljs
                [[clojure.test.check.clojure-test :refer-macros [defspec]]
                 [clojure.test.check.properties :refer-macros [for-all]]
                 [cljs.test :refer-macros [is deftest]]]
                :clj
                [[clojure.test.check.clojure-test :refer [defspec]]
                 [clojure.test.check.properties :refer [for-all]]
                 [clojure.test :refer [is deftest]]])))

(def test-opts {:num-tests 100
                :par       4})

(defn approx=
  "Equal to within err fraction, or if one is zero, to within err absolute."
  ([err x y]
   (or (= x y)
       (if (or (zero? x) (zero? y))
         (< (- err) (- x y) err)
         (< (- 1 err) (/ x y) (+ 1 err)))))
  ([err x y & more]
   (->> more
        (cons y)
        (every? (partial approx= err x)))))

(def =ish
  "Almost equal"
  (partial approx= 0.0000001))

(defn mean'
  [coll]
  (let [c (count coll)]
    (when (pos? c)
      (/ (reduce + coll) c))))

(defn variance'
  [coll]
  (let [c (count coll)]
    (when (pos? c)
      (let [c' (dec c)]
        (if (pos? c')
          (/ (->> coll
                  (map #(sq (- % (mean' coll))))
                  (reduce +))
             c')
          0)))))

(defn pvariance'
  [coll]
  (let [c (count coll)]
    (when (pos? c)
      (/ (->> coll
              (map #(sq (- % (mean' coll))))
              (reduce +))
         (count coll)))))

(defn covariance'
  [fx fy coll]
  (let [coll' (filter fx (filter fy coll))]
    (if (empty? coll')
      nil
      (let [mean-x (mean' (map fx coll'))
            mean-y (mean' (map fy coll'))]
        (/ (reduce + (map #(* (- (fx %) mean-x)
                              (- (fy %) mean-y))
                          coll'))
           (count coll'))))))

(defn correlation'
  [fx fy coll]
  "http://mathworld.wolfram.com/CorrelationCoefficient.html"
  (let [coll' (filter fx (filter fy coll))]
    (when-not (empty? coll')
      (let [xs (map fx coll')
            ys (map fy coll')
            mx (mean' xs)
            my (mean' ys)
            mxs (map #(- % mx) xs)
            mys (map #(- % my) ys)]
        (let [d (sqrt (* (reduce + (map * mxs mxs))
                         (reduce + (map * mys mys))))]
          (when-not (zero? d)
            (/ (reduce + (map * mxs mys)) d)))))))

(defn finite?
  [x]
  #?(:clj  (Double/isFinite x)
     :cljs (js/isFinite x)))

(def numeric
  (gen/such-that finite? (gen/one-of [gen/int gen/double])))

(defspec count-spec
  test-opts
  (for-all [xs (gen/vector numeric)]
           (is (= (transduce identity kixi/count xs)
                  (count xs)))))

(deftest count-test
  (is (zero? (transduce identity kixi/count []))))

(defspec mean-spec
  test-opts
  (for-all [xs (gen/vector numeric)]
           (is (=ish (transduce identity kixi/mean xs)
                     (mean' xs)))))

(deftest mean-test
  (is (nil? (transduce identity kixi/mean []))))

(defspec variance-spec
  test-opts
  (for-all [xs (gen/vector numeric)]
           (is (=ish (transduce identity kixi/variance xs)
                     (variance' xs)))))

(deftest variance-test
  (is (nil?  (transduce identity kixi/variance [])))
  (is (zero? (transduce identity kixi/variance [1])))
  (is (= 2   (transduce identity kixi/variance [1 3])))
  (is (= 4   (transduce identity kixi/variance [1 3 5]))))

(defspec pvariance-spec
  test-opts
  (for-all [xs (gen/vector numeric)]
           (is (=ish (transduce identity kixi/pvariance xs)
                     (pvariance' xs)))))

(deftest pvariance-test
  (is (nil?  (transduce identity kixi/pvariance [])))
  (is (zero? (transduce identity kixi/pvariance [1])))
  (is (= 4   (transduce identity kixi/pvariance [1 5]))))

(defspec standard-deviation-spec
  test-opts
  (for-all [xs (gen/vector numeric)]
           (is (=ish (transduce identity kixi/standard-deviation xs)
                     (some-> (variance' xs) sqrt)))))

(deftest standard-deviation-test
  (is (nil?  (transduce identity kixi/standard-deviation [])))
  (is (zero? (transduce identity kixi/standard-deviation [1])))
  (is (== 2  (transduce identity kixi/standard-deviation [1 3 5]))))

(defspec pstandard-deviation-spec
  test-opts
  (for-all [xs (gen/vector numeric)]
           (is (=ish (transduce identity kixi/pstandard-deviation xs)
                     (some-> (pvariance' xs) sqrt)))))

(deftest pstandard-deviation-test
  (is (nil?  (transduce identity kixi/pstandard-deviation [])))
  (is (zero? (transduce identity kixi/pstandard-deviation [1])))
  (is (== 2  (transduce identity kixi/pstandard-deviation [1 5]))))

(defspec covariance-spec
  test-opts
  ;; Take maps like {}, {:x 1}, {:x 2 :y 3} and compute covariance
  (for-all [coll (gen/vector (gen/map (gen/elements [:x :y]) gen/int))]
           (is (=ish (transduce identity (kixi/covariance :x :y) coll)
                     (covariance' :x :y coll)))))

(deftest covariance-test
  (is (nil?  (transduce identity (kixi/covariance :x :y) [])))
  (is (zero? (transduce identity (kixi/covariance :x :y) [{:x 1 :y 2}]))))

(defspec correlation-spec
  test-opts
  ;; Take maps like {}, {:x 1}, {:x 2 :y 3} and compute correlation
  (for-all [coll (gen/vector (gen/map (gen/elements [:x :y]) gen/int))]
           (is (=ish (transduce identity (kixi/correlation :x :y) coll)
                     (correlation' :x :y coll)))))

(deftest correlation-test
  (is (nil? (transduce identity (kixi/correlation :x :y) [])))
  (is (nil? (transduce identity (kixi/correlation :x :y) [{:x 1 :y 2}]))))

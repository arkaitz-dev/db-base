(ns demo-ledger.money
  "Amounts as a person types them and as the database keeps them: a string such as
  \"12.50\" or \"12,50\" on the way in, whole cents on the way out and back.")

(def ^:private amount-pattern
  "Up to eight digits of units — ninety-nine million — and at most two of cents,
  with a point or a comma between them. Nothing else: no sign, no thousands
  separator, no exponent."
  #"(\d{1,8})(?:[.,](\d{1,2}))?")

(defn parse-cents
  "The whole cents in `s`, or nil when `s` is not an amount this form accepts or is
  zero. \"12.5\" is 1250 cents, not 1205."
  [s]
  (when-let [[_ units cents] (and (string? s) (re-matches amount-pattern (.trim ^String s)))]
    (let [total (+ (* 100 (parse-long units))
                   (if cents (parse-long (if (= 1 (count cents)) (str cents "0") cents)) 0))]
      (when (pos? total) total))))

(defn format-cents
  "Cents as units and two decimals, with a sign when negative: -1250 is \"-12.50\"."
  [cents]
  (let [sign (if (neg? cents) "-" "")
        n    (abs cents)]
    (format "%s%d.%02d" sign (quot n 100) (rem n 100))))

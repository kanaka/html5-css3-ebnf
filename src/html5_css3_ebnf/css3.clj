(ns html5-css3-ebnf.css3
  (:require [clojure.data.json :as json]
            [clojure.string :as string]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.cli :refer [parse-opts]]

            [instacheck.core :as instacheck]
            [instaparse.core :as instaparse]))

;; TODO: global properties (inherit, initial, unset, revert)

;; https://developer.mozilla.org/en-US/docs/Web/CSS/Value_definition_syntax
;; https://www.smashingmagazine.com/2016/05/understanding-the-css-property-value-syntax/
;; https://www.w3.org/TR/CSS21/grammar.html
;; https://github.com/mdn/data/tree/master/css
;; https://github.com/csstree/csstree/
;; https://csstree.github.io/docs/syntax.html

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn filter-css-definitions
  "Filter properties we want (also removes '--*')"
  [defs status-set]
  (into {} (filter (fn [[name attrs]]
		     (and
		      (re-find #"^[@a-z-]+$" name)
		      (status-set (get attrs "status"))))
		   defs)))

(defn mangle-css-syntaxes
  "Fixup syntax definitions."
  [syntaxes]
  (into {} (for [[k {:strs [syntax] :as v}] syntaxes]
             (cond
               ;; TODO: file bug against github.com/mdn/data
               (= syntax "<custom-ident>: <integer>+;")
               [k {"syntax" "<custom-ident> : <integer>+ ;"}]

               ;; TODO: file bug against github.com/mdn/data
               (= syntax "rect(<top>, <right>, <bottom>, <left>)")
               [k {"syntax" "rect( <top>, <right>, <bottom>, <left> ) | rect( <top> <right> <bottom> <left> )"}]

               ;; <media-condition>, <media-condition-without-or>, and
               ;; <supports-condition> are currently mutually
               ;; recursive definitions (unsupported).  Rewrite them
               ;; to pull in child syntax in order to make them
               ;; directly recursive (supported).

               ;; Original <media-condition> syntax:
               ;;     "<media-not> | <media-and> | <media-or> | <media-in-parens>"
               (= k "media-condition")
               [k {"syntax" "[ not? [ ( <media-condition> ) | <media-feature> | <general-enclosed> ] ] | [ [ ( <media-condition> ) | <media-feature> | <general-enclosed> ] [ [ and | or ] [ ( <media-condition> ) | <media-feature> | <general-enclosed> ] ]+ ]"}]

               ;; Original <media-condition-without-or> syntax:
               ;;     "<media-not> | <media-and> | <media-in-parens>"
               (= k "media-condition")
               [k {"syntax" "[ not? [ ( <media-condition> ) | <media-feature> | <general-enclosed> ] ] | [ [ ( <media-condition> ) | <media-feature> | <general-enclosed> ] [ and [ ( <media-condition> ) | <media-feature> | <general-enclosed> ] ]+ ]"}]

               ;; Original <supports-condition> syntax:
               ;;     "not <supports-in-parens> | <supports-in-parens> [ and <supports-in-parens> ]* | <supports-in-parens> [ or <supports-in-parens> ]*"
               (= k "supports-condition")
               [k {"syntax" "not [ ( <supports-condition> ) | <supports-feature> | <general-enclosed> ] | [ ( <supports-condition> ) | <supports-feature> | <general-enclosed> ] [ and [ ( <supports-condition> ) | <supports-feature> | <general-enclosed> ] ]* | [ ( <supports-condition> ) | <supports-feature> | <general-enclosed> ] [ or [ ( <supports-condition> ) | <supports-feature> | <general-enclosed> ] ]*"}]

               ;; Drop unused syntaxes that also have recursion
               (= k "page-body")
               nil

               ;; VDS has a comma combining syntax that is difficult
               ;; to parse but only applies to rbg and rgba currently
               ;; so fix them up to be easier to parse. A bug related
               ;; to this: https://github.com/mdn/data/issues/341
               ;; Original:
               ;;     "rgb( <percentage>{3} [ / <alpha-value> ]? ) | rgb( <number>{3} [ / <alpha-value> ]? ) | rgb( <percentage>#{3} , <alpha-value>? ) | rgb( <number>#{3} , <alpha-value>? )"
               (= k "rgb()")
               [k {"syntax" "rgb( <percentage>{3} [ / <alpha-value> ]? ) | rgb( <number>{3} [ / <alpha-value> ]? ) | rgb( <percentage>#{3} [ , <alpha-value> ]? ) | rgb( <number>#{3} [ , <alpha-value> ]? )"}]

               ;; Original:
               ;;     "rgba( <percentage>{3} [ / <alpha-value> ]? ) | rgba( <number>{3} [ / <alpha-value> ]? ) | rgba( <percentage>#{3} , <alpha-value>? ) | rgba( <number>#{3} , <alpha-value>? )"
               (= k "rgba()")
               [k {"syntax" "rgba( <percentage>{3} [ / <alpha-value> ]? ) | rgba( <number>{3} [ / <alpha-value> ]? ) | rgba( <percentage>#{3} [ , <alpha-value> ]? ) | rgba( <number>#{3} [ , <alpha-value> ]? )" }]

               :else
               [k v]))))

(defn mangle-css-at-rules
  "Fixup the at-rules definitions."
  [at-rules]
  (into {} (for [[k {:strs [syntax] :as v}] at-rules]
             (cond
               ;; TODO: MDN data doesn't have a way of representing
               ;; non-standard descriptors in the parent syntax. Add
               ;; non-standard font-display to parent.
               (= k "@font-face")
               [k (assoc v "syntax" (string/replace syntax #"\n*}$" " ||\n  [ font-display: <font-display>; ]\n}"))]
               :else
               [k v]))))


;; TODO: these from descriptors syntaxes are the only unique ones
;; actually referenced by at-rules: 'src', 'unicode-range',
;; 'font-variation-settings', 'suffix', 'speak-as', 'range', 'prefix',
;; 'additive-symbols'. 'font-display' isn't referenced in the parent
;; but we add it above in the mangler.

(def include-descriptors #{"src" "font-display"})

(defn css-vds-combined [properties syntaxes at-rules]
  (let [syns (mangle-css-syntaxes syntaxes)
        arules (mangle-css-at-rules at-rules)
        ps (for [[prop {:strs [syntax]}] (sort properties)]
             (if (= prop "all")
               (str "<'" prop "'> = " syntax "\n")
               (str "<'" prop "'> = <'all'> | " syntax "\n")))
        ss (for [[syn {:strs [syntax]}] (sort syns)]
             (str "<" syn "> = " syntax "\n"))
        as (for [[rule-name {:strs [syntax]}] (sort arules)]
             (str "<'" rule-name "'> = " syntax "\n"))
        ads (for [[_ {:strs [descriptors]}] (sort arules)
                  [rule-name {:strs [syntax]}] (sort descriptors)
                  :when (get include-descriptors rule-name)]
              (str "<" rule-name "> = " syntax "\n"))]
    (apply str (concat ps
                       ["\n\n"]
                       ss
                       ["\n\n"]
                       as
                       ["\n\n"]
                       ads))))

(comment
  (spit "data/css3.vds" (css-vds-combined
                          (filter-css-definitions
                            (json/read-str (slurp "./mdn_data/css/properties.json"))
                            #{"standard"})
                          (json/read-str (slurp "./mdn_data/css/syntaxes.json"))))
)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn parsed-tree->items [tree]
  (assert (= :assignments (first tree))
          (str "Parse tree started with "
               (first tree) " rather than :assignments"))
  (assert (every? #(and (vector? %) (= :assignment (first %)))
                  (drop 1 tree))
          "Parse tree contained invalid property")
  (for [prop (drop 1 tree)]
    (let [ptype (first (second prop))
          name (second (second prop))
          data (nth prop 2)]
      (condp = ptype
        :property     [(str "'" name "'") data]
        :non-property [name data]))))

(defn parsed-tree->map [tree]
  (let [items (parsed-tree->items tree)
        repeats (filter #(> (val %) 1) (frequencies (map first items)))]
    (assert ;;(= (count items) (count (set (map first items))))
            (empty? repeats)
            (str "Repeated properties:" (vector repeats)))
  (into {} items)))

(comment
  (def css3-syntax-parser (instaparse/parser "resources/css-vds.ebnf"))
  ;; Takes 4 seconds
  (def parse-tree (css3-syntax-parser (slurp "data/css3.vds")))
  (def parse-map (parsed-tree->map parse-tree))
)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare juxtapose-ebnf double-amp-ebnf double-bar-ebnf component-ebnf
         component-single-ebnf component-multiplied-ebnf brackets-ebnf
         block-ebnf func-ebnf braces-ebnf)

;; Operator precendence summary:
;;  mult, juxt, &&, ||, |
;;
;; Notes:
;; - juxtaposition has precedence over the double ampersand, meaning
;;   that
;;     bold thin && <length> is equivalent to
;;     [ bold thin ] && <length>
;; - the double ampersand has precedence over the double bar, meaning
;;   that
;;     bold || thin && <length> is equivalent to
;;     bold || [ thin && <length> ]
;; - the double bar has precedence over the single bar, meaning that
;;     bold | thin || <length> is equivalent to
;;     bold | [ thin || <length> ]
;; - multipliers cannot be added and have all precedence over
;; combinators.
;;

(defn name-ebnf
  [[kind k]]
  (-> (cond
        (= :property kind)
        (str "prop-" k)

        (re-find #"\(\)$" k)
        (str "func-" (string/replace k #"\(\)$" ""))

        (= :non-property kind)
        (str "nonprop-" k))
      (string/replace #"@" "AT-")))

(defn single-bar-ebnf
  "One of the values must occur."
  [tree indent]
  ;;(prn :** :single-bar-ebnf (count tree) tree :indent indent)
  (let [pre (apply str (repeat indent "  "))]
    (if (= 1 (count tree))
      (double-bar-ebnf (drop 1 (first tree)) indent)
      (str pre "(\n"
           (string/join
             (str " |\n")
             (for [t tree]
               (double-bar-ebnf (drop 1 t) (+ 1 indent))))
           "\n"
           pre ")"))))

;; TODO: for EBNF we just treat this like a single-bar with '+'
;; appended. This means that we may get more than one of each
;; element.
(defn double-bar-ebnf
  "One or more of the values must occur in any order."
  [tree indent]
  ;;(prn :** :double-bar-ebnf (count tree) tree :indent indent)
  (let [pre (apply str (repeat indent "  "))]
    (if (= 1 (count tree))
      (double-amp-ebnf (drop 1 (first tree)) indent)
      (str pre "( (\n"
           (string/join
             (str " |\n")
             (for [t tree]
               (double-amp-ebnf (drop 1 t) (+ 1 indent))))
           "\n"
           pre ")+ )"))))

;; TODO: for EBNF we just treat this as juxtapose we means we use the
;; original order it is defined with. Fix this to allow any order.
(defn double-amp-ebnf
  "All values must occur in any order."
  [tree indent]
  ;;(prn :** :double-amp-ebnf (count tree) tree :indent indent)
  (let [pre (apply str (repeat indent "  "))]
    (if (= 1 (count tree))
      (juxtapose-ebnf (drop 1 (first tree)) indent)
      (str pre "(\n"
           (string/join
             "\n"
             (for [t tree]
               (if (= :comma (first t))
                 (str pre "  ',' S")
                 (juxtapose-ebnf (drop 1 t) (+ 1 indent)))))
           "\n"
           pre ")"))))

(defn juxtapose-ebnf
  "Each value must occur."
  [tree indent]
  ;;(prn :** :juxtapose-ebnf (count tree) tree :indent indent)
  (let [pre (apply str (repeat indent "  "))]
    (if (= 1 (count tree))
      (component-ebnf (second (first tree)) indent)
      (str pre "(\n"
           (string/join
             "\n"
             (for [t tree]
               (if (= :comma (first t))
                 (str pre "  ',' S")
                 (component-ebnf (second t) (+ 1 indent)))))
           "\n"
           pre ")"))))

(defn component-ebnf [tree indent]
  ;;(prn :** :component-ebnf (count tree) tree :indent indent)
  (str
    (condp = (first tree)
      :component-single     (component-single-ebnf (second tree) indent)
      :component-multiplied (component-multiplied-ebnf (drop 1 tree) indent))))

(defn component-single-ebnf [tree indent]
  ;;(prn :** :component-single-ebnf (count tree) tree :indent indent)
  (let [pre (apply str (repeat indent "  "))]
    (condp = (first tree)
      :literal       (str pre "('" (second tree) "' S)")
      :keyword-value (str pre "('" (second tree) "' S)")
      :non-property  (str pre (name-ebnf tree))
      :property      (str pre (name-ebnf tree))
      :brackets      (brackets-ebnf (drop 1 tree) indent)
      :block         (block-ebnf    (drop 1 tree) indent)
      :func          (func-ebnf     (drop 1 tree) indent)
      )))

(defn component-multiplied-ebnf [[tree multiplier] indent]
  ;;(prn :** :component-multiplied-ebnf (count tree) tree :indent indent)
  (let [pre (apply str (repeat indent "  "))
        single (partial component-single-ebnf (second tree))]
    (condp = (first (second multiplier))
      :question    (str (single indent) "?")
      :asterisk    (str (single indent) "*")
      :plus        (str (single indent) "+")
      :hash        (str (single indent) " (\n"
                        pre "  ',' S\n"
                        (single (+ 1 indent)) "\n"
                        pre ")*")
      :braces      (braces-ebnf (second (second multiplier))
                                false (single (+ 2 indent)) indent)
      :hash-braces (braces-ebnf (second (second (second multiplier)))
                                true (single (+ 2 indent)) indent)
    )))

(defn brackets-ebnf [tree indent]
  ;;(prn :** :brackets-ebnf tree indent)
  ;; TODO: deal with bang?
  (single-bar-ebnf (drop 1 (first tree)) indent))

(defn func-ebnf [tree indent]
  ;;(prn :** :func-ebnf tree indent)
  (let [pre (apply str (repeat indent "  "))]
    (str pre "'" (first tree) "(' S\n"
         (single-bar-ebnf (drop 1 (second tree)) (+ 1 indent)) "\n"
         pre "')' S")))

(defn block-ebnf [tree indent]
  ;;(prn :** :block-ebnf tree indent)
  (let [pre (apply str (repeat indent "  "))]
    (condp = (first (first tree))
      \{         (str pre "'{' S\n"
                      (single-bar-ebnf (drop 1 (second tree)) (+ 1 indent)) "\n"
                      pre "'}' S")
      :single-bar (single-bar-ebnf (drop 1 (first tree)) indent))))

(defn braces-ebnf [kind hash? single indent]
  ;;(prn :** :braces-ebnf kind hash? single indent)
  (let [pre (apply str (repeat indent "  "))
        bmin (Integer. (second (second kind)))
        bmax (Integer. (second (nth kind 2 [:digit bmin])))]
    ;;(assert (= :bracesA-B (first kind))
    ;;        (str "Unsupported curly braces repeat form:" kind))
    (assert (and (>= bmin 0)
                 (>= bmax bmin)
                 (<= bmax 20))
            (str "Unsupported curly braces range: " bmin "-" bmax))
    (str pre "(\n"
         pre "  (\n"
         (string/join
           " |\n"
           (for [i (range bmin (+ 1 bmax))]
             (string/join
               (if hash? " ',' S\n" " rS\n")
               (if (= i 0)
                 [(str pre "    ''")]
                 (repeat i single)))))
         "\n"
         pre "  )\n"
         pre ")")))
;;


(defn lhs-ebnf [k]
  (-> (cond
        (= \' (first k))
        (str "prop-" (string/replace k #"'" ""))

        (re-find #"\(\)$" k)
        (str "func-" (string/replace k #"\(\)$" ""))

        :else
        (str "nonprop-" k))
      (string/replace #"@" "AT-")))

(defn value-ebnf [k v]
  ;;(prn :value-ebnf :k k :v v)
  (str (lhs-ebnf k) " =\n"
       (single-bar-ebnf (drop 1 v) 1)))

(defn map->ebnf [m]
  (string/join
    " ;\n\n"
    (for [[k v] m] (value-ebnf k v))))

(comment
  (def css3-syntax-parser (instaparse/parser "resources/css-vds.ebnf"))
  ;; Takes 4 seconds
  (def css-tree (css3-syntax-parser (slurp "data/css3.vds")))
  (def css-map (parsed-tree->map css-tree))

  ;; The following takes 25 seconds
  (def css-ebnf (map->ebnf css-map))
)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn pr-err
  [& args]
  (binding [*out* *err*]
    (apply println args)))

(defn opt-errors [opts]
  (when (:errors opts)
    (doall (map pr-err (:errors opts)))
    (System/exit 2))
  opts)

(def cli-options
  [[nil "--vds-grammar EBNF-OUTPUT"
    "Path to generic VDS grammar definition."
    :default "./resources/css-vds.ebnf"]

   [nil "--css-vds-output CSS-VDS-OUTPUT"
    "Write full CSS VDS syntax to file"
    :default "./data/css3.vds"]
   [nil "--ebnf-output EBNF-OUTPUT"
    "Write intermediate EBNF to file"
    :default "./data/css3.ebnf"]
   [nil "--grammar-output GRAMMAR-OUTPUT"
    "Write cached EDN grammar tree to path."
    :default "./data/css3.grammar"]

   [nil "--css-properties CSS-PROPERTIES"
    "Path to CSS properties JSON file."
    :default "./mdn_data/css/properties.json"]
   [nil "--css-syntaxes CSS-SYNTAXES"
    "Path to CSS syntaxes JSON file."
    :default "mdn_data/css/syntaxes.json"]
   [nil "--css-at-rules CSS-AT-RULES"
    "Path to CSS at-rules JSON file."
    :default "mdn_data/css/at-rules.json"]

   [nil "--status-list STATUS-LIST"
    "Comma separated list of status values to include"
    :default "standard"]
   [nil "--ebnf-prefix EBNF-PREFIX"
    "Path to prefix file to include in EBNF output"
    :default "./resources/css3-prefix.ebnf"]
   [nil "--ebnf-base EBNF-BASE"
    "Path to base grammar file to include in EBNF output"
    :default "./resources/css3-base.ebnf"]
   [nil "--ebnf-common EBNF-COMMON"
    "Path to common rules to include in EBNF output"
    :default "./resources/common.ebnf"]])

(defn css-known-ebnf [suffix props]
  (str "css-known-" suffix " =\n"
       "  (\n"
       (string/join
         " |\n"
         (for [p props]
           (str "    \"" p "\" S \":\" S prop-" p "")))
       "\n"
       "  ) ;"))

(defn ebnf-combined-str [css-map properties opts]
  (let [cfilt (fn [status xs]
                (keys (filter #(= status (get (val %) "status")) xs)))
        ;;standard (map first (cfilt #(= "standard" %) properties))
        ;;nonstandard (map first (cfilt #(not= "standard" %) properties))
        ]
    (string/join
      "\n\n"
      ["(* Generated by mend.w3c.css3 *)"
       (slurp (:ebnf-prefix opts))
       (string/join
         "\n\n"
         (for [status (string/split (:status-list opts) #",")]
           (css-known-ebnf status (sort (cfilt status properties)))))
       ;;(css-known-ebnf "standard" (sort standard))
       ;;(css-known-ebnf "nonstandard" (sort nonstandard))
       (map->ebnf (sort-by key css-map))
       (slurp (:ebnf-base opts))
       (slurp (:ebnf-common opts))])))


(defn -main [& args]
  "Generate a CSS 3 EBNF grammar based on specification data from
  Mozilla Developer Network (MDN).

  This takes about 30 seconds to run."
  (let [opts (:options (opt-errors (parse-opts args cli-options)))

        _ (pr-err "Creating VDS grammar parser from:" (:vds-grammar opts))
        css3-syntax-parser (instaparse/parser (:vds-grammar opts))

        properties-file (:css-properties opts)
        syntaxes-file (:css-syntaxes opts)
        at-rules-file (:css-at-rules opts)
        _ (pr-err "Generating full CSS VDS grammar based on:"
                  properties-file syntaxes-file at-rules-file)
        properties (filter-css-definitions
                     (json/read-str (slurp properties-file))
                     (set (string/split (:status-list opts) #",")))
        syntaxes (json/read-str (slurp syntaxes-file))
        at-rules (filter-css-definitions
                   (json/read-str (slurp at-rules-file))
                   (set (string/split (:status-list opts) #",")))
        vds-text (css-vds-combined properties syntaxes at-rules)

        _ (when-let [pfile (:css-vds-output opts)]
            (pr-err "Saving full CSS VDS grammar file to:" pfile)
            (spit pfile vds-text))

        _ (pr-err "Parsing CSS VDS grammar")
        css-tree (instacheck/parse css3-syntax-parser vds-text)
        css-map (parsed-tree->map css-tree)

        _ (pr-err "Converting CSS VDS grammar to EBNF")
        css3-ebnf-str (ebnf-combined-str css-map properties opts)

        _ (println "Saving EBNF to" (:ebnf-output opts))
        _ (spit (:ebnf-output opts) css3-ebnf-str)

        _ (pr-err "Checking EBNF and converting to Parser")
        css3-parser (instacheck/load-parser css3-ebnf-str)
        _ (pr-err "Converting Parser to Cached Grammar")
        css3-grammar (instacheck/parser->grammar css3-parser)]

    (println "Saving cached parser grammar EDN to" (:grammar-output opts))
    (spit (:grammar-output opts) (with-out-str (pprint css3-grammar)))))


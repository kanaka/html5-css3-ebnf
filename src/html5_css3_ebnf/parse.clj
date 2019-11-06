(ns html5-css3-ebnf.parse
  (:require [clojure.tools.cli :refer [parse-opts]]
            [clojure.java.io :as io]
            [clojure.string :refer [ends-with?]]
            [clojure.pprint :refer [pprint]]

            [instacheck.core :as icore]
            [instacheck.grammar :as igrammar]
            [instacheck.reduce :as ireduce]
            [html5-css3-ebnf.html-mangle :refer [extract-html
                                                 extract-css-map]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn pr-err
  [& args]
  (binding [*out* *err*]
    (apply println args)
    (flush)))

(defn load-parser-from-grammar [file start]
  (let [gfile (io/resource file)
        grammar (read-string (slurp gfile))]
    (icore/grammar->parser grammar start)))


(defn filter-parse-data
  "Filter out the zero weights from the :parts :wtrek and :full-wtrek
  data."
  [data]
  {:parts (for [p (:parts data)]
            (assoc p :wtrek
                   (into {} (filter #(not= 0 (val %))
                                    (:wtrek p)))))
   :full-wtrek (into {} (filter #(not= 0 (val %))
                                (:full-wtrek data)))})

(defn parse-files
  [html-parser css-parser files]
  (loop [data {:parts []
               :full {}}
         files files]
    (let [[file & rest-files] files
          _ (pr-err (str "Processing: '" file "'"))
          fdir (.getParent (io/file file))
          text (slurp file)
          html (extract-html text)
          css-map (extract-css-map text #(slurp (io/file fdir %)))
          _ (pr-err "  - parsing HTML")
          html-data-all (icore/parse-wtreks
                          html-parser [[html file]])
          html-data (filter-parse-data html-data-all)
          _ (pr-err (str "  - HTML weights: "
                         (count (:full-wtrek html-data)) "/"
                         (count (:full-wtrek html-data-all))))
          _ (pr-err "  - parsing CSS")
          css-data-all (icore/parse-wtreks
                         css-parser (for [[k v] css-map] [v k]))
          css-data (filter-parse-data css-data-all)
          _ (pr-err (str "  - CSS weights: "
                         (count (:full-wtrek css-data)) "/"
                         (count (:full-wtrek css-data-all))))
          new-data {:parts (vec (concat (:parts data)
                                        (:parts html-data)
                                        (:parts css-data)))
                    :full-wtrek (merge-with +
                                            (:full-wtrek data)
                                            (:full-wtrek html-data)
                                            (:full-wtrek css-data))}]
      (if (seq rest-files)
        (recur new-data rest-files)
        new-data))))

;; The minimal set that can be removed to prevent mutually recursive
;; cycles. Will be added back in at the beginning of the sorted rules.
(def CYCLE-SET #{:stylesheet})

(defn parser-wtrek->ebnf
  [parser wtrek]
  (let [grammar (igrammar/parser->grammar parser)]
    (ireduce/prune-grammar->sorted-ebnf grammar {:wtrek wtrek
                                                 :cycle-set CYCLE-SET})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Command line usage of wend

(def cli-options
  [[nil "--debug" "Add debug comments to generated code"]
   [nil "--verbose" "Verbose output during execution"]
   [nil "--weights-output WEIGHTS-OUTPUT" "Write all resulting frequency weights to WEIGHTS-OUTPUT"]
   [nil "--parse-output PARSE-OUTPUT" "Write resulting parse data to PARSE-OUTPUT"]
   [nil "--html-ebnf-output HTML-EBNF-OUTPUT" "Write pruned HTML EBNF grammar to HTML-EBNF-OUTPUT"]
   [nil "--css-ebnf-output CSS-EBNF-OUTPUT" "Write pruned CSS EBNF grammar to CSS-EBNF-OUTPUT"]])

(defn opt-errors [opts]
  (when (:errors opts)
    (doall (map pr-err (:errors opts)))
    (System/exit 2))
  opts)

(defn usage []
  (pr-err "[OPTS] <FILE>...")
  (System/exit 2))

(defn -main
  [& args]
  (let [opts (opt-errors (parse-opts args cli-options))
        {:keys [parse-output weights-output
                html-ebnf-output css-ebnf-output]} (:options opts)
        [& files] (:arguments opts)
        _ (pr-err "Loading HTML parser")
        html-parser (load-parser-from-grammar "html5.grammar" :html)
        _ (pr-err "Loading CSS parser")
        css-parser (load-parser-from-grammar "css3.grammar" :stylesheet)
        parse-data (parse-files html-parser css-parser files)
        full-wtrek (:full-wtrek parse-data)]
    (pr-err (str "Weight count: " (count full-wtrek)))
    (when parse-output
      (pr-err (str "Saving parse data to: '" parse-output "'"))
      (spit parse-output parse-data))
    (when html-ebnf-output
      (pr-err (str "Generating pruned HTML EBNF"))
      (let [ebnf (parser-wtrek->ebnf html-parser full-wtrek)]
        (pr-err (str "Saving pruned HTML EBNF to: '" html-ebnf-output "'"))
        (spit html-ebnf-output ebnf)))
    (when css-ebnf-output
      (pr-err (str "Generating pruned CSS EBNF"))
      (let [ebnf (parser-wtrek->ebnf css-parser full-wtrek)]
        (pr-err (str "Saving pruned CSS EBNF to: '" css-ebnf-output "'"))
        (spit css-ebnf-output ebnf)))
    (when weights-output
      (pr-err (str "Saving weights to: '" weights-output "'"))
      (icore/save-weights weights-output full-wtrek))))


(comment

(defn time-test
  [hp cp file]
  (let [text (slurp (str "test/html/" file))
        _ (println "Base file bytes:" (count text))
        html    (time (extract-html text))
        _ (println "HTML bytes:" (count html))
        css-map (time (extract-css-map text #(slurp (io/file "test/html" %))))
        _ (println "CSS bytes:" (apply + (map count (vals css-map))))
        hw (time (icore/parse-wtrek hp html))
        _ (println "HTML weight count:" (count hw))
        cw (time (icore/parse-wtreks
                   cp (zipmap (vals css-map) (keys css-map))))
        _ (println "CSS weight count:" (count cw))]
    ;(pprint html)
    ;(pprint css-map)
    true))

(def hp (load-parser-from-grammar "html5.grammar" :html))
(def cp (load-parser-from-grammar "css3.grammar" :stylesheet))


;; HTML: success, CSS: success
(time-test hp cp "basics.html")
;; HTML: success, CSS: success
(time-test hp cp "example.com-20190422.html")
;; HTML: success, CSS: success
(time-test hp cp "smedberg-20190701-2.html")
;; HTML: success, CSS: @-webkit-keyframes
(time-test hp cp "apple.com-20190422-2.html")
;; HTML: '[' in attr val, CSS: url with no quoting
(time-test hp cp "github.com-20190422.html")
;; HTML: success, CSS: success (18 seconds)
(time-test hp cp "mozilla.com-20190506.html")
;; HTML: itemscope, CSS: -webkit...rgba(
(time-test hp cp "google.com-20190422.html")
;; HTML: success, CSS: url with no quoting
(time-test hp cp "cnn.com-20190422.html")

(time-test hp cp "ssllabs.com-20190816.html")


(pprint (filter #(> (second %) 0) (:wtrek hw)))
(pprint (filter #(> (second %) 0) (:full-wtrek cw)))

(print (parser-wtrek->ebnf hp (:wtrek hw)))
(print (parser-wtrek->ebnf cp (:full-wtrek cw)))

)

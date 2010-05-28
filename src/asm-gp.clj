
;; [[file:~/research/genprog/asm/asm-gp.org::*Namespace%20and%20included%20libraries][block-2]]
(ns asm-gp
    (:require (clojure.contrib
               (duck-streams :as f)
               (shell-out :as s))
              (clojure.contrib.generic (math-functions :as m)))
    (:import (java.io BufferedReader InputStreamReader File)
             (java.util ArrayList Collections)))
;; block-2 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*macros][block-3]]
(defmacro while-let
  "Like while, but uses when-let instead of when."
  [test & body]
  `(loop []
     (when-let ~test
       ~@body
       (recur))))
;; block-3 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*macros][block-4]]
(defn message
  [fmt & args]
  (println (apply format (cons fmt args))))
;; block-4 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*macros][block-5]]
(defmacro with-timeout [ms & body]
  `(let [f# (future ~@body)]
     (.get f# ~ms java.util.concurrent.TimeUnit/MILLISECONDS)))
;; block-5 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*serialization][block-6]]
(defn write-obj
  "Write a clojure object to a file" [f obj]
  (f/spit f (pr-str obj)))
;; block-6 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*serialization][block-7]]
(defn read-obj
  "Read a clojure object from a file" [f]
  (with-in-str (slurp f) (read)))
;; block-7 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*random%20weighted%20list%20access][block-8]]
(defn shuffle
  "Shuffles coll using a Java ArrayList." [coll]
  (let [l (ArrayList. coll)] (Collections/shuffle l) (seq l)))

(defn place
  "Pick a random location from a sequence"
  [lst]
  (rand-int (.size lst)))

(defn pick
  "Pick and return a random element from a sequence."
  [lst] (nth lst (place lst)))

(defn weighted-place
  "Pick a random location in an asm individual weighted by the
   associated bad-path."
  ([asm] (weighted-place asm :bad-weight))
  ([asm weight-key]
     (pick
      ((fn [index asm assoc] ;; expand each place by it's weight
         (if (empty? asm)
           assoc
           (recur
            (inc index)
            (rest asm)
            (concat (repeat (m/ceil (or (weight-key (first asm)) 0)) index) assoc))))
       0 asm (range (.size asm))))))

(defn weighted-pick
  "Return a random element in an asm individual weighted by the
   associated bad-path"
  ([asm]
     (nth asm (weighted-place asm)))
  ([asm weight-key]
     (nth asm (weighted-place asm weight-key))))
;; block-8 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*reading%20and%20writing%20assembly][block-9]]
(defn read-asm
  "Read in an assembly file as list and parse cmd lines."
  [path]
  {:representation
   (map (fn [el]
          {:line (if-let [part (re-matches #"\t(.*)\t(.*)" el)]
                   (rest part)
                   el)})
        (f/read-lines path))
   :compile nil :fitness nil :trials nil :operations nil})

(defn write-asm
  [f asm]
  (f/write-lines
   (f/file-str f)
   (map #(let [line (:line %)]
           (if (not (string? line))
             (apply str "\t" (interpose "\t" line)) line))
        (:representation asm))))
;; block-9 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::#parameters][block-10]]
(def target-fitness 10)
(def max-generations 10)
(def population-size 40)
(def tournament-size 3)
(def use-tournament false)
(def max-section-size 1)
(def crossover-rate 0.1)
(def fitness-cache-path
     (.getPath (f/file-str "~/research/code/data/fitness-cache.clj")))
(def good-mult 1)
(def bad-mult 5)
(def compiler "gcc")
(def compiler-flags nil) ;; (list "-pthread")
(def test-dir nil)  ;; "~/research/code/gcd/"
(def test-timeout 2000)
(def test-good nil) ;; "./test-good.sh"
(def test-bad nil)  ;; "./test-bad.sh"
(def java-class-nest nil)
;; block-10 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*read%20a%20path][block-11]]
(defn read-path
  "Read the given path giving the raw sum of the value for each
  instruction."  [path-to-path]
  (reduce
   (fn [a f] (assoc a f (inc (get a f 0)))) {}
   (map (fn [arg] (Integer/parseInt arg))
        (f/read-lines path-to-path))))
;; block-11 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*smooth%20path][block-12]]
(defn smooth-path
  "Smooth the given path by blurring with a 1-D Gaussian, then taking
  the log of all values -- with a min value of 1 for each
  instruction."  [path]
  (let [kernel {-3 0.006, -2 0.061, -1 0.242, 0 0.383, 1 0.242, 2 0.061, 3 0.006}]
    ;; log of the blurred weights
    (reduce
     (fn [accum el] (assoc accum (first el) (m/log (inc (second el))))) {}
     ;; 1D Gaussian Smoothing of weights
     (reduce
        (fn [accum el]
          (reduce
           (fn [a f]
             (let [place (+ (first el) (first f))]
               (assoc a place
                      (+ (get a place 0)
                         (* (second f) (second el))))))
           accum kernel)) {}
           path))))
;; block-12 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*difference%20between%20paths][block-13]]
(defn path-
  "Subtract one path from another." [left right]
  (reduce (fn [l r] (dissoc l (first r))) left right))
;; block-13 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*apply%20path][block-14]]
(defn apply-path
  "Apply the weights in a path to a GP individual"
  [asm key path]
  (assoc asm
    :representation
    (reduce #(let [place (first %2) weight (second %2)]
               (if (< place (.size %1))
                 (concat
                  (take place %1)
                  (list (assoc (nth %1 place) key weight))
                  (drop (inc place) %1))
                 %1)) (:representation asm) path)))
;; block-14 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::#gp-operations][block-15]]
(defn section-length
  "Limit the size of sections of ASM used for GP operations."
  [single length]
  (if single
    (if (number? single) (min single length) 1)
    (inc (rand-int (min max-section-size length)))))
;; block-15 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*swap%20asm][block-16]]
(defn swap-asm
  "Swap two lines or sections of the asm."
  ([asm] (swap-asm asm nil))
  ([asm single]
     (assoc asm
       :representation
       (let [asm (:representation asm)
             first (weighted-place asm)
             second (weighted-place asm)]
         (if (= first second)
           asm
           (let [left (min first second)
                 right (max first second)
                 left-length
                 (section-length single
                                 (.size (take (- right left) (drop left asm))))
                 right-length (section-length single (.size (drop right asm)))]
             (concat
              (take left asm)
              (take right-length (drop right asm))
              (take (- right (+ left left-length))
                    (drop (+ left left-length) asm))
              (take left-length (drop left asm))
              (drop (+ right right-length) asm)))))
       :operations (cons :swap (:operations asm)))))
;; block-16 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*delete%20asm][block-17]]
(defn delete-asm
  "Delete a line or section from the asm.  Optional second argument
will force single line deletion rather than deleting an entire
section."
  ([asm] (delete-asm asm nil))
  ([asm single]
     (assoc asm
       :representation
       (let [asm (:representation asm)
             start (weighted-place asm)
             length (section-length single (.size (drop start asm)))]
         (concat (take start asm) (drop (+ start length) asm)))
       :operations (cons :delete (:operations asm)))))
;; block-17 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*append%20asm][block-18]]
(defn append-asm
  "Inject a line from the asm into a random location in the asm.
  Optional third argument will force single line injection rather than
  injecting an entire section."
  ([asm] (append-asm asm nil))
  ([asm single]
     (assoc asm
       :representation
       (let [asm (:representation asm)
             start (weighted-place asm :good-weight)
             length (section-length single (.size (drop start asm)))
             point (weighted-place asm)]
         (concat (take point asm) (take length (drop start asm))
                 (drop point asm)))
       :operations (cons :append (:operations asm)))))
;; block-18 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*mutate%20asm][block-19]]
(defn mutate-asm
  "Mutate the asm with either delete-asm, append-asm, or swap-asm.
  For now we're forcing all changes to operate by line rather than
  section." [asm]
  (let [choice (rand-int 3)]
    (cond
     (= choice 0) (delete-asm asm)
     (= choice 1) (append-asm asm)
     (= choice 2) (swap-asm asm))))
;; block-19 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*crossover%20asm][block-20]]
(defn crossover-asm
  "Takes two individuals and returns the result of performing single
  point crossover between then."  [left right]
  {:representation
   (let [left (:representation left) right (:representation right)]
     (concat (take (weighted-place left) left) (drop (weighted-place right) right)))
   :operations (list :crossover (list (:operations left) (:operations right)))
   :compile nil :fitness nil :trials nil})
;; block-20 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*compile%20asm][block-21]]
(defn compile-asm
  "Compile the asm, set it's :compile field to the path to the
  compiled binary if successful or to nil if unsuccessful."  [asm]
  (let [asm-source (.getPath (File/createTempFile "variant" ".S"))
        asm-bin (.getPath (File/createTempFile "variant" "bin"))]
    (write-asm asm-source asm)
    (assoc asm
      :compile
      (when (= 0 (:exit
                  (apply
                   s/sh
                   (concat
                    (apply list compiler compiler-flags)
                    (list "-o" asm-bin asm-source :return-map true)))))
        (s/sh "chmod" "+x" asm-bin)
        asm-bin))))
;; block-21 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*Fitness%20Evaluation][block-22]]
(def fitness-cache (ref {}))
;; block-22 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*Fitness%20Evaluation][block-23]]
(def fitness-count (ref 0))
;; block-23 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*Fitness%20Evaluation][block-24]]
(defn evaluate-asm
  "Take an individual, evaluate it and pack it's score into
  it's :fitness field."  [asm]
  ;; increment our global fitness counter
  (dosync (alter fitness-count inc))
  (assoc
      ;; evaluate the fitness of the individual
      (if (@fitness-cache (.hashCode (:representation asm)))
        (assoc asm ;; cache hit
          :fitness (@fitness-cache (.hashCode (:representation asm)))
          :compile true)
        (let [asm (compile-asm asm) ;; cache miss
              test-good (.getPath (f/file-str test-dir test-good))
              test-bad (.getPath (f/file-str test-dir test-bad))
              bin (:compile asm)
              run-test (fn [test mult]
                         (* mult
                            (try
                             (let [out-file (.getPath (File/createTempFile "variant" ".out"))]
                               (with-timeout test-timeout (s/sh test bin out-file))
                               (.size (f/read-lines out-file)))
                             (catch java.util.concurrent.TimeoutException e 0))))]
          (assoc asm
            :fitness ((dosync (alter fitness-cache assoc (.hashCode
                                                          (:representation asm))
                                     (if bin ;; new fitness
                                       (+ (run-test test-good good-mult)
                                          (run-test test-bad bad-mult))
                                       0)))
                      (.hashCode (:representation asm))))))
    :trials @fitness-count))
;; block-24 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*populate][block-25]]
(defn populate
  "Return a population starting with a baseline individual.
  Pass :group true as optional arguments to populate from a group of
  multiple baseline individuals."
  [asm & opts]
  ;; this doesn't work as list? will return true no matter what, we
  ;; must use an optional keyword argument...
  (let [asm (if (get (apply hash-map opts) :group false)
              asm (list asm))]
    ;; calculate their fitness
    (pmap #(evaluate-asm %)
          ;; include the originals
          (concat asm
                  ;; create random mutants
                  (take (- population-size (.size asm))
                        (repeatedly #(mutate-asm (pick asm))))))))
;; block-25 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*selection%20tournament%20and%20sus][block-26]]
(defn tournament
  "Select an individual from the population via tournament selection."
  [population n]
  (take n
        (repeatedly
         (fn []
           (last
            (sort-by :fitness
                     (take tournament-size
                           (repeatedly #(pick population)))))))))
;; block-26 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*selection%20tournament%20and%20sus][block-27]]
(defn stochastic-universal-sample
  "Stochastic universal sampling"
  [population n]
  (let [total-fit (reduce #(+ %1 (:fitness %2)) 0 population)
        step-size (/ total-fit n)]
    (loop [pop (reverse (sort-by :fitness (shuffle population)))
           accum 0 marker 0
           result '()]
      (if (> n (.size result))
        (if (> marker (+ accum (:fitness (first pop))))
          (recur (rest pop) (+ accum (:fitness (first pop))) marker result)
          (recur pop accum (+ marker step-size) (cons (first pop) result)))
        result))))
;; block-27 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*selection%20tournament%20and%20sus][block-28]]
(defn select-asm [population n]
  (if use-tournament
    (tournament population n)
    (stochastic-universal-sample population n)))
;; block-28 ends here

;; [[file:~/research/genprog/asm/asm-gp.org::*evolve][block-29]]
(defn evolve
  "Build a population from a baseline individual and evolve until a
solution is found or the maximum number of generations is reached.
Return the best individual present when evolution terminates."
  [asm]
  (loop [population (populate asm)
         generation 0]
    (let [best (last (sort-by :fitness population))
          mean (/ (float (reduce + 0 (map :fitness population))) (.size population))]
      ;; write out the best so far
      (message "generation %d mean-score %S best{:fitness %S, :trials %d}"
               generation mean (:fitness best) (:trials best))
      (write-obj (format "variant.gen.%d.best.%S.clj" generation (:fitness best))
                 best)
      (if (>= (:fitness best) target-fitness)
        (do ;; write out the winner to a file and return
          (message "success after %d generations and %d fitness evaluations"
                   generation @fitness-count)
          (write-obj "best.clj" best) best)
        (if (>= generation max-generations)
          (do ;; print out failure message and return the best we found
            (message "failed after %d generations and %d fitness evaluations"
                     generation @fitness-count) best)
          (recur
           (select-asm
            (concat
             (dorun
              (pmap #(evaluate-asm %)
                    (concat
                     (take (Math/round (* crossover-rate population-size))
                           (repeatedly
                            (fn [] (apply crossover-asm (select-asm population 2)))))
                     (pmap #(mutate-asm %)
                          (select-asm population
                                      (Math/round (* (- 1 crossover-rate) population-size)))))))
             population)
            population-size)
           (+ generation 1)))))))
;; block-29 ends here
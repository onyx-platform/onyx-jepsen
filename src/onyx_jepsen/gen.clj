(ns onyx-jepsen.gen
  (:require [jepsen [client :as client]
             [generator :as gen]]))

;; TODO, only emit after n secs has passed since first invocation
;; afterwards, wait for n secs again
; (defn delay-nonblock
;   "Every operation from the underlying generator takes (f) seconds longer."
;   [f gen]
;   (reify Generator
;     (op [_ test process]
;       (Thread/sleep (* 1000 (f)))
;       (op gen test process))))

(defn filter-new
  "Takes a generator and yields a generator which emits only operations
  satisfying `(f op)`."
  [f gen]
  (reify gen/Generator
    (op [_ test process]
      (loop []
        (if-let [op' (gen/op gen test process)]
          (if (f op')
            op'
            (recur))
          (recur))))))

;; Appropriated from https://github.com/krukow/ants-demo/blob/master/src/ants/defs.clj
(defn wrand 
  "given a vector of slice sizes, returns the index of a slice given a
  random spin of a roulette wheel with compartments proportional to
  slices."
  [slices]
  (let [total (reduce + slices)
        r (rand total)]
    (loop [i 0 sum 0]
      (if (< r (+ (slices i) sum))
        i
        (recur (inc i) (+ (slices i) sum))))))

(defn frequency
  "A mixture of operations based on provided likelihoods"
  [gens probabilities]
  (let [gens (vec gens)
        probabilities (vec probabilities)]
    (assert (= (count gens) (count probabilities)))
    (reify gen/Generator
      (op [_ test process]
        (let [selection (wrand probabilities)]
          (gen/op (gens selection) test process))))))

(defn adds
  "Generator that emits :add operations for sequential integers."
  []
  (->> (range)
       (map (fn [x] {:type :invoke, :f :add, :value x}))
       gen/seq))

(defn gc-peer-logs
  "Generator that emits :add operations for sequential integers."
  []
  (->> (range)
       (map (fn [x] {:type :invoke, :f :gc-peer-log}))
       gen/seq))

(defn read-ledgers-gen
  [task-name]
  (gen/clients (gen/once {:type :invoke :f :read-ledgers :value task-name})))

(defn read-peer-log-gen
  []
  (gen/clients (gen/once {:type :invoke :f :read-peer-log})))

(defn close-await-completion-gen
  []
  (gen/clients (gen/once {:type :invoke :f :close-ledgers-await-completion})))

(defn submit-job-gen [n-jobs job-params]
  (->> (range n-jobs)
       (map (fn [n] 
              {:type :invoke 
               :f :submit-job 
               :job-num n
               :n-jobs n-jobs
               :params job-params}))
       gen/seq))

(defn start-stop-nemesis-seq [awake-mean stopped-mean]
  (gen/seq 
    (mapcat (fn [_] 
              [(gen/sleep (rand-int (* 2 stopped-mean)))
               {:type :info :f :start}
               (gen/sleep (rand-int (* 2 awake-mean)))
               {:type :info :f :stop}]) 
            (range))))

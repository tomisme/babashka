(ns babashka.executor-test
  (:require [babashka.test-utils :as tu]
            [clojure.test :refer [deftest is]]))

(set! *warn-on-reflection* true)



(deftest executor-test
  (let [program
        (str '(do (import '(java.util.concurrent Executors))

                  (defn run [nthreads niters]
                    (let [a (atom 0)
                          pool  (Executors/newFixedThreadPool nthreads)
                          tasks (map (fn [_]
                                       (fn []
                                         (dotimes [_ niters]
                                           (swap! a inc))))
                                     (range nthreads))]
                      (doseq [future (.invokeAll pool tasks)]
                        (.get future))
                      (.shutdown pool)
                      @a))
                  (run 5 10)))]
    (is (= "50\n" (tu/bb nil program)))))

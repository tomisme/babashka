(ns pod
  (:refer-clojure :exclude [read read-string])
  (:require [babashka.pods :as pods]
            [cheshire.core :as cheshire]
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:gen-class))

(defn debug [& args]
  (binding [*out* (io/writer "/tmp/log.txt" :append true)]
    (apply prn args)
    (flush)))

(defn run-pod [cli-args]
  (let [format (if (contains? cli-args "--json")
                 :json
                 :edn)
        read-fn (case format
                  :edn (let [reader *in*]
                         #(do
                            (debug "reading")
                            (let [v (edn/read {:eof ::EOF} reader)]
                              (debug "read" v)
                              v)))
                  :json (let [messages (cheshire/parsed-seq *in* true)
                              messages (volatile! messages)]
                          (fn []
                            (debug "reading next JSON")
                            (if-let [ms (seq @messages)]
                              (do (vreset! messages (rest ms))
                                  (first ms))
                              ::EOF))))
        write-fn (case format
                   :edn (fn [v] (locking *out*
                                  (prn v)
                                  (flush)))
                   :json (fn [v]
                           (debug "writing JSON" v)
                           (locking *out*
                             (println (cheshire/generate-string v true))
                             (flush))))]
    (debug "writing headers")
    (println "format: " (name format))
    (println)
    (loop []
      (let [message (read-fn)]
        (debug "got message" message)
        (when-not (identical? ::EOF message)
          (let [op (:op message)
                op (keyword op)]
            (case op
              :describe (do
                          (debug "describe")
                          (write-fn '{:vars [{:namespace pod.test-pod
                                              :name add-sync}
                                             {:namespace pod.test-pod
                                              :name range-stream
                                              :async true}
                                             {:namespace pod.test-pod
                                              :name assoc}]})
                          (recur))
              :invoke (let [var (-> (:var message)
                                    symbol)
                            id (:id message)
                            args (:args message)]
                        (case var
                          pod.test-pod/add-sync
                          (do (debug "invoking var")
                              (write-fn
                               {:value (apply + args)
                                :id id
                                :status ["done"]}))
                          pod.test-pod/range-stream
                          (let [rng (apply range args)]
                            (doseq [v rng]
                              (write-fn
                               {:value v
                                :id id})
                              (Thread/sleep 100))
                            (write-fn
                             {:status ["done"]
                              :id id}))
                          pod.test-pod/assoc
                          (write-fn
                           {:value (apply assoc args)
                            :status ["done"]
                            :id id}))
                        (recur)))))))))

(let [cli-args (set *command-line-args*)]
  (if (contains? cli-args "--run-as-pod")
    (run-pod cli-args)
    (let [native? (contains? cli-args "--native")]
      (pods/load-pod (if native?
                       (into ["./bb" "test-resources/pod.clj" "--run-as-pod"] cli-args)
                       (into ["lein" "bb" "test-resources/pod.clj" "--run-as-pod"] cli-args)))
      (require '[pod.test-pod])
      (if (contains? cli-args "--json")
        (prn ((resolve 'pod.test-pod/assoc) {:a 1} :b 2))
        (do
          (prn ((resolve 'pod.test-pod/add-sync) 1 2 3))
          (let [chan ((resolve 'pod.test-pod/range-stream) 1 10)]
            (loop []
              (when-let [x (async/<!! chan)]
                (prn x)
                (recur)))))))))

(ns babashka.impl.pods
  {:no-doc true}
  (:refer-clojure :exclude [read])
  (:require [cheshire.core :as cheshire]
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io PushbackReader]))

(set! *warn-on-reflection* true)

(defn add-shutdown-hook! [^Runnable f]
  (-> (Runtime/getRuntime)
      (.addShutdownHook (Thread. f))))

(defn write [pod v]
  (let [write-fn (:write-fn pod)]
    (binding [*out* (io/writer (:stdin pod))]
      (println (write-fn v))
      (flush))))

(defn read [pod]
  (let [read-fn (:read-fn pod)
        v (read-fn)]
    v))

(defn processor [pod]
  (let [chans (:chans pod)]
    (try
      (loop []
        (let [reply (read pod)
              id    (get reply :id)
              value (get reply :value)
              status (get reply :status)
              status (set (map keyword status))
              done? (contains? status :done)
              chan (get @chans id)]
          (when value (async/>!! chan value))
          (when done? (async/close! chan)))
        (recur))
      (catch Exception e (prn e)))))

(defn invoke [pod pod-var args async?]
  (let [chans (:chans pod)
        id (str (java.util.UUID/randomUUID))
        chan (async/chan)
        _ (swap! chans assoc id chan)
        _ (write pod {:id id
                      :op "invoke"
                      :var pod-var
                      :args args})]
    (if async? chan
        (async/<!! chan))))

(defn read-headers
  [^java.io.InputStream stdout]
  (let [^java.io.BufferedReader reader (io/reader stdout)]
    (loop [headers {}]
      (if-let [line (.readLine reader)]
        (if (str/blank? line)
          headers
          (let [[k v] (str/split line #":" 2)
                k (keyword k)
                v (when v (str/trim v))
                headers (assoc headers k v)]
            (recur headers)))
        headers))))

(defn load-pod
  ([ctx pod-spec] (load-pod ctx pod-spec nil))
  ([ctx pod-spec _opts]
   (let [pod-spec (if (string? pod-spec) [pod-spec] pod-spec)
         pb (ProcessBuilder. ^java.util.List pod-spec)
         _ (.redirectErrorStream pb true)
         p (.start pb)
         stdin (.getOutputStream p)
         stdout (.getInputStream p)
         _ (add-shutdown-hook! #(.destroy p))
         headers (read-headers stdout)
         format (-> (:format headers) keyword)
         write-fn (case format
                    :edn pr-str
                    :json #(cheshire/generate-string % true))
         read-fn (case format
                   :json (let [messages (cheshire/parsed-seq (io/reader stdout) true)
                               messages (volatile! messages)]
                           (fn []
                             (if-let [ms (seq @messages)]
                               (do (vreset! messages (rest ms))
                                   (first ms))
                               ::EOF)))
                   :edn (let [reader (PushbackReader. (io/reader stdout))]
                          #(do
                             (edn/read {:eof ::EOF} reader))))
         pod {:process p
              :pod-spec pod-spec
              :stdin stdin
              :write-fn write-fn
              :read-fn read-fn
              :stdout stdout
              :chans (atom {})
              :format format}
         _ (write pod {:op "describe"})
         reply ((:read-fn pod))
         vars (:vars reply)
         env (:env ctx)]
     (swap! env
            (fn [env]
              (let [namespaces (:namespaces env)
                    namespaces (reduce (fn [acc v]
                                         (let [ns (:namespace v)
                                               name (:name v)
                                               [ns name]
                                               (if (identical? format :json)
                                                 [(symbol ns) (symbol name)] [ns name])
                                               sym (symbol (str ns) (str name))
                                               async? (:async v)
                                               f (fn [& args]
                                                   (let [res (invoke pod sym args async?)]
                                                     res))]
                                           (assoc-in acc [ns name] f)))
                                       namespaces
                                       vars)]
                (assoc env :namespaces namespaces))))
     (future (processor pod))
     vars)))

(def pods-namespace
  {'load-pod (with-meta load-pod
               {:sci.impl/op :needs-ctx})})

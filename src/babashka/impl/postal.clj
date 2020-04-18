(ns babashka.impl.postal
  {:no-doc true}
  (:require [postal.core :as postal]
            [sci.impl.vars :as vars]))

(def pns (vars/->SciNamespace 'postal.core nil))

(defn new-var [sym val meta]
  (vars/->SciVar val sym (assoc meta
                                :ns pns
                                :sci.impl/built-in true)))

(def postal-namespace
  {'send-message (new-var 'send-message
                          (fn [& args]
                            (System/setProperty "postal.version" "2.0.3")
                            (apply postal/send-message args))
                          (meta #'postal/send-message))})

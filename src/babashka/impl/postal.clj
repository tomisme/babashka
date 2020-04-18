(ns babashka.impl.postal
  {:no-doc true}
  (:require [postal.core :as postal]
            [sci.impl.namespaces :refer [copy-var]]
            [sci.impl.vars :as vars]))

(def pns (vars/->SciNamespace 'postal.core nil))

(def postal-namespace
  {'send-message (copy-var postal/send-message pns)})



(ns babashka.impl.hato
  {:no-doc true}
  (:require [hato.client :as hc]))

(def hato-client-namespace
  {'get hc/get
   'request hc/request})

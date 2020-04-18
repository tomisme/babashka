(ns babashka.postal-test
  (:require  [babashka.test-utils :as tu]
             [babashka.wait :as wait]
             [clojure.string :as str]
             [clojure.test :refer [deftest is]])
  (:import [com.icegreen.greenmail.util ServerSetupTest GreenMail DummySSLSocketFactory]
           [java.security Security]))

(deftest test-smtp
  (when (= "true" (System/getenv "BABASHKA_POSTAL_TEST"))
    (Thread/setDefaultUncaughtExceptionHandler
     (reify Thread$UncaughtExceptionHandler
       (uncaughtException [_ thread ex]
         (when-not (instance? java.net.SocketException (.getCause ex))
           (.printStackTrace ex)))))
    (Security/setProperty "ssl.SocketFactory.provider" (.getName DummySSLSocketFactory));
    (doseq [port [3025 (when-not tu/native?
                         ;; the DummySSLSocketFactory isn't available within bb
                         3465)]
            :when port]
      (let [mail-setup ServerSetupTest/ALL
            green-mail (GreenMail. mail-setup)]
        (.start green-mail)
        (wait/wait-for-port "localhost" port)
        (tu/bb nil (format "
(require '[postal.core :as mail])
(mail/send-message
   {:host \"localhost\"
    :port %s
    :ssl %s}
   {:from \"michielborkent@gmail.com\"
    :to \"michielborkent+test@gmail.com\"
    :subject \"Hello from babashka\"
    :body \"Cool. This is the body.\"})
" port (= port 3465)))
        (let [msgs (.getReceivedMessages green-mail)]
          (is (= 1 (count msgs)))
          (doseq [m msgs]
            (is (str/includes? (.getSubject m) "babashka"))))
        (.stop green-mail)))))



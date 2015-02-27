;;Copyright 2014 Istvan Szukacs

;;Licensed under the Apache License, Version 2.0 (the "License");
;;you may not use this file except in compliance with the License.
;;You may obtain a copy of the License at

;;    http://www.apache.org/licenses/LICENSE-2.0

;;Unless required by applicable law or agreed to in writing, software
;;distributed under the License is distributed on an "AS IS" BASIS,
;;WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;;See the License for the specific language governing permissions and
;;limitations under the License
(ns shovel.core
  (:require
    ;internal
    [shovel.consumer        :as     sh-consumer                         ]
    [shovel.producer        :as     sh-producer                         ]
    [shovel.helpers         :refer   :all                               ]
    ;external
    [clojure.tools.logging  :as     log                                 ]
    [metrics.meters         :refer  [defmeter mark! rates]              ]
    [metrics.histograms     :refer  [defhistogram update! percentiles]  ]
    [metrics.counters       :refer  [defcounter inc! value]             ]
    [metrics.core           :refer  [new-registry]                      ]
    [clojure.core.async     :as     async         ]
    [clojure.tools.cli      :refer  [parse-opts]  ])
  (:import 
    [java.io                File                                ]
    [java.util              ArrayList                           ]
    [java.util.concurrent   ThreadPoolExecutor$DiscardPolicy    ]
    [kafka.consumer         ConsumerConfig Consumer KafkaStream ]
    [kafka.javaapi.consumer ConsumerConnector                   ]
    [kafka.message          MessageAndMetadata                  ])
  (:gen-class))

;; metrics 
(def reg (new-registry))
(defmeter     reg messages-read)
(defmeter     reg messages-written)
(defcounter   reg bytes-read)
(defcounter   reg bytes-written)


;; Helpers

(def handler (proxy [Thread$UncaughtExceptionHandler] [] 
  (uncaughtException [thread exception]
    (log/error thread exception))))

(log/info (Thread/setDefaultUncaughtExceptionHandler handler))



  ;;this isn't really helpful to measure how many bytes we are sending 
  ;;but probably worth to try to correlate with other better measurements
  (defn total-memory [obj]
    (let [baos (java.io.ByteArrayOutputStream.)]
      (with-open [oos (java.io.ObjectOutputStream. baos)]
        (.writeObject oos obj))
      (count (.toByteArray baos))))
;;
;; OPS

(defnx safe-producer-connector [config] 
  (do 
    (let [return {:ok (sh-producer/producer-connector config)}]
      (log/debug "fn:safe-producer-connector return: " return)
      return)))

(defnx safe-producer-produce [producer-connection message]
  (do
    (let [return {:ok (sh-producer/produce producer-connection  message)}]
      (log/debug "fn:safe-producer-produce return: " return)
      return)))

(defn test-producer
  [config topic]
  (log/debug "fn: test-producer params: " config topic)
  (let [producer-connection-hm (safe-producer-connector config) counter (atom 0)]
    (cond 
      (contains? producer-connection-hm :ok) 
        (def producer-connection (:ok producer-connection-hm)) 
    :else 
      (do 
        (log/error "Cannot connect....")
        (exit 1)))

    (log/info producer-connection)
    (doseq [n (range 1000000)]
      (do
        (log/debug "n: " n)
        ;send the message
        (let [message (sh-producer/message topic "asd" (str "{this is my message : " n "}"))] 
          (inc! bytes-written (total-memory message))
          (log/debug 
            "fn:safe-producer-produce : " producer-connection 
            "message: " message 
            "produce:" (safe-producer-produce producer-connection message)))
        ;mark the meter
        (mark! messages-written)
        ;if the counter is 10000 reset the counter and log the metrics
        (cond 
          (= @counter 10000) ;if
          (do 
            (reset! counter 0) ;not sure if thread safe
            (log/info (rates messages-written) (value bytes-written))) 
        :else 
          (do 
            (log/debug @counter) 
            (swap! counter inc)));end cond
        )))
  {:ok :ok})

(defn test-consumer 
  [config] 
  (log/info "fn: new-consumer-messages params: " config)
  (let [stat-chan (async/chan 8)]
    (dotimes [i 1]
    (async/thread
      (let [  consumer-config     (get-in config [:ok :consumer-config]) 
              consumer-topic      (get-in config [:ok :common :consumer-topic])
              consumer-connector  (sh-consumer/consumer-connector consumer-config)
              message-streams     (sh-consumer/message-streams consumer-connector consumer-topic (int 1))
              messages            (sh-consumer/messages message-streams)
              counter             (atom 0)
              message-counter     (atom 0)        ]

        (loop [[message & stream-rest] messages] 
          (do 
            (swap! message-counter inc)
            (log/debug "message counter: " @message-counter)
            (mark! messages-read)
            (inc! bytes-read (total-memory message))
            (cond (= @counter 10000) 
              (do 
                (reset! counter 0) 
                (log/debug (rates messages-read))
                (async/>!! stat-chan {:rates (rates messages-read) :percentiles (value bytes-read) } )) 
            :else 
              (do 
                (log/debug @counter) 
                (swap! counter inc)))
            (log/debug message @counter stat-chan))
        (recur stream-rest)))))
    
    (while true 
      (async/<!!
        (async/go
          (let [[result source] (async/alts! [stat-chan (async/timeout 10000)])]
            (if (= source stat-chan)
              (log/info "main-loop: " result)
                ;else - timeout 
                (do 
                  (log/info "Channel timed out. Stopping...") 
                  (exit 0)))))))))
            
;; CLI

(def cli-options
  [
    ["-f" "--config-file FILE" "Configuration file" :default "conf/app.edn"]
    ["-c" "--connect" "Initiate connections" :default false ]
    ["-h" "--help" "This application is helpless"]
  ])

(defn -main [& args]
  (log/info "-main starts")
  (let [  {:keys [options arguments errors summary]} (parse-opts args cli-options)
          config (read-config (:config-file options))                               ]
    ;; INIT
    (log/info "init :: start")
    (log/info "checking config...")
    (cond 
      (contains? config :ok)
        (config-ok config)
      :else
        ;; exit 1 here
        (config-err config))


    ; Execute program with options
    (case (first arguments)
      "print-config"
        (println config)
      "consumer-test"
        (test-consumer config)
      "producer-test"
      (do
        (log/info "producer-test")
        (log/info (test-producer (get-in config [:ok :producer-config]) (get-in config [:ok :common :producer-topic]))))
      ;default
        (do
          (log/error "Missing arugments")
          (exit 1))))
        )


;; END

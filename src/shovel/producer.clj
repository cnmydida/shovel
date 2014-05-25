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
(ns 
  ^{:doc "
    https://cwiki.apache.org/confluence/display/KAFKA/0.8.0+Producer+Example
  "}
  ;ns
  shovel.producer
  (:require
    ;internal
    ;external
    [clojure.walk   :refer [stringify-keys]]
    [clojure.pprint :as pprint])
  (:import
    [kafka.javaapi.producer Producer                    ]
    [kafka.producer         KeyedMessage ProducerConfig ]
    [java.util              Properties                  ])
  (:gen-class))

; internal 
; move this to shovel.helpers
(defn hashmap-to-properties
  [h]
  (doto (Properties.) 
    (.putAll (stringify-keys h))))

; external 

(defn producer-connector
  [h]
  (let [config (ProducerConfig. (hashmap-to-properties h))]
    (Producer. config)))

(defn message
  [topic key value] 
  (println topic key value)
  (KeyedMessage. topic key value))

(defn produce
  [^Producer producer ^KeyedMessage message]
  (println producer message)
  (.send producer message))

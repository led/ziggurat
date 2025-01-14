(ns ziggurat.streams-test
  (:require [clojure.test :refer :all]
            [flatland.protobuf.core :as proto]
            [ziggurat.streams :refer [start-streams stop-streams]]
            [ziggurat.fixtures :as fix]
            [ziggurat.config :refer [ziggurat-config]])
  (:import [flatland.protobuf.test Example$Photo]
           [java.util Properties]
           [kafka.utils MockTime]
           [org.apache.kafka.clients.producer ProducerConfig]
           [org.apache.kafka.streams KeyValue]
           [org.apache.kafka.streams.integration.utils IntegrationTestUtils]))

(use-fixtures :once fix/mount-only-config)

(defn props []
  (doto (Properties.)
    (.put ProducerConfig/BOOTSTRAP_SERVERS_CONFIG (get-in (ziggurat-config) [:stream-router :default :bootstrap-servers]))
    (.put ProducerConfig/ACKS_CONFIG "all")
    (.put ProducerConfig/RETRIES_CONFIG (int 0))
    (.put ProducerConfig/KEY_SERIALIZER_CLASS_CONFIG "org.apache.kafka.common.serialization.ByteArraySerializer")
    (.put ProducerConfig/VALUE_SERIALIZER_CLASS_CONFIG "org.apache.kafka.common.serialization.ByteArraySerializer")))

(def message {:id   7
              :path "/photos/h2k3j4h9h23"})

(defn create-photo []
  (proto/protobuf-dump (proto/protodef Example$Photo) message))

(def message-key-value (KeyValue/pair (create-photo) (create-photo)))

(defn mapped-fn [_]
  :success)

(defn rand-application-id []
  (str "test" "-" (rand-int 999999999)))

(deftest start-streams-with-since-test
  (let [message-received-count (atom 0)]
    (with-redefs [mapped-fn (fn [message-from-kafka]
                              (when (= message message-from-kafka)
                                (swap! message-received-count inc))
                              :success)]
      (let [times                         6
            oldest-processed-message-in-s 10
            changelog-topic-replication-factor 1
            kvs                           (repeat times message-key-value)
            streams                       (start-streams {:default {:handler-fn mapped-fn}}
                                                         (-> (ziggurat-config)
                                                             (assoc-in [:stream-router :default :application-id] (rand-application-id))
                                                             (assoc-in [:stream-router :default :oldest-processed-message-in-s] oldest-processed-message-in-s)
                                                             (assoc-in [:stream-router :default :changelog-topic-replication-factor] changelog-topic-replication-factor)))]
        (Thread/sleep 10000)                                ;;waiting for streams to start
        (IntegrationTestUtils/produceKeyValuesSynchronously (get-in (ziggurat-config) [:stream-router :default :origin-topic])
                                                            kvs
                                                            (props)
                                                            (MockTime. (- (System/currentTimeMillis) (* 1000 oldest-processed-message-in-s)) (System/nanoTime)))
        (Thread/sleep 5000)                                 ;;wating for streams to consume messages
        (stop-streams streams)
        (is (= 0 @message-received-count))))))

(deftest start-streams-test
  (let [message-received-count (atom 0)]
    (with-redefs [mapped-fn (fn [message-from-kafka]
                              (when (= message message-from-kafka)
                                (swap! message-received-count inc))
                              :success)]
      (let [times   6
            changelog-topic-replication-factor 1
            kvs     (repeat times message-key-value)
            streams (start-streams {:default {:handler-fn mapped-fn}}
                                   (-> (ziggurat-config)
                                       (assoc-in [:stream-router :default :application-id] (rand-application-id))
                                       (assoc-in [:stream-router :default :changelog-topic-replication-factor] changelog-topic-replication-factor)))]
        (Thread/sleep 10000)                                ;;waiting for streams to start
        (IntegrationTestUtils/produceKeyValuesSynchronously (get-in (ziggurat-config) [:stream-router :default :origin-topic])
                                                            kvs
                                                            (props)
                                                            (MockTime.))
        (Thread/sleep 5000)                                 ;;wating for streams to consume messages
        (stop-streams streams)
        (is (= times @message-received-count))))))

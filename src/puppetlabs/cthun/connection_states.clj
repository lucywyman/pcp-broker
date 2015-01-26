(ns puppetlabs.cthun.connection-states
  (:require [clojure.core.async :as async]
            [clojure.core.incubator :refer [dissoc-in]]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [puppetlabs.cthun.activemq :as activemq]
            [puppetlabs.cthun.message :as message]
            [puppetlabs.cthun.validation :as validation]
            [puppetlabs.cthun.metrics :as metrics]
            [puppetlabs.kitchensink.core :as ks]
            [schema.core :as s]
            [clj-time.core :as time]
            [clj-time.coerce :as time-coerce]
            [metrics.counters :refer [inc!]]
            [metrics.meters :refer [mark!]]
            [metrics.timers :refer [time!]]
            [ring.adapter.jetty9 :as jetty-adapter]))

(def ConnectionState
  "The state of a websocket in the connection-map"
  {:client s/Str
   :type s/Str
   :status s/Str
   (s/optional-key :endpoint) validation/Endpoint
   :created-at validation/ISO8601})

(def connection-map (atom {})) ;; Map of ws -> ConnectionState

(def endpoint-map (atom {})) ;; { endpoint => ws }

(def inventory (atom {}))

(def delivery-executor (atom {})) ;; will be replaced with an ExecutorService

(defn set-delivery-executor
  "Sets the delivery executor"
  [executor]
  (reset! delivery-executor executor))

(defn- make-endpoint-string
  "Make a new endpoint string for a host and type"
  [host type]
  (str "cth://" host "/" type))

(defn websocket-of-endpoint
  "Return the websocket an endpoint is connected to, false if not connected"
  [endpoint]
  (get @endpoint-map endpoint))

(s/defn ^:always-validate
  new-socket :- ConnectionState
  "Return a new, unconfigured connection map"
  [client]
  {:client client
   :type "undefined"
   :status "connected"
   :created-at (ks/timestamp)})

(defn- logged-in?
  "Determine if a websocket combination is logged in"
  [host ws]
  (= (get-in @connection-map [ws :status]) "ready"))

(defn- handle-delivery-failure
  [message reason]
  "If the message is not expired schedule for a future redelivery by adding to the redeliver queue"
  (log/info "Failed to deliver message" message reason)
  (let [expires (time-coerce/to-date-time (:expires message))
        now     (time/now)]
    (if (time/after? expires now)
      (let [difference     (time/in-seconds (time/interval now expires))
            sleep-duration (if (<= (/ difference 2) 1) 1 (float (/ difference 2)))
            message        (message/add-hop message "redelivery")]
        (log/info "Moving message to the redeliver queue in " sleep-duration " seconds")
        ;; TODO(richardc): we should queue for future delivery, not sleep
        (Thread/sleep (* sleep-duration 1000))
        (activemq/queue-message "redeliver" message)))
    (log/warn "Message " message " has expired. Dropping message")))

(defn deliver-message
  [message]
  "Delivers a message to the websocket indicated by the :_destination field"
  (if-let [websocket (websocket-of-endpoint (:_destination message))]
    (try
      ; Lock on the websocket object allowing us to do one write at a time
      ; down each of the websockets
      (locking websocket
                 (inc! metrics/total-messages-out)
                 (mark! metrics/rate-messages-out)
                 (let [message (message/add-hop message "deliver")]
                   (jetty-adapter/send! websocket (byte-array (map byte (message/encode message))))))
      (catch Exception e
        (.start (Thread. (fn [] (handle-delivery-failure message e))))))
    (.start (Thread. (fn [] (handle-delivery-failure message "not connected"))))))

(defn messages-to-destinations
  "Returns a sequence of messages, each with a single endpoint in
  its :_destination field.  All wildcards are expanded here by the
  InventoryService."
  [message]
  (map (fn [endpoint]
         (assoc message :_destination endpoint))
       ((:find-clients @inventory) (:endpoints message))))

(defn make-delivery-fn
  "Returns a Runnable that delivers a message to a :_destination"
  [message]
  (fn []
    (log/info "delivering message from executor to websocket" message)
    (let [message (message/add-hop message "deliver-message")]
      (deliver-message message))))

(defn deliver-from-accept-queue
  [message]
  "Message consumer.  Accepts a message from the accept queue, expands
  destinations, and attempts delivery."
  (doall (map (fn [message]
                (.execute @delivery-executor (make-delivery-fn message)))
              (messages-to-destinations message))))

(defn deliver-from-redelivery-queue
  [message]
  "Message consumer.  Accepts a message from the redeliver queue, and
  attempts delivery."
  (.execute @delivery-executor (make-delivery-fn message)))

(defn subscribe-to-topics
  [accept-threads redeliver-threads]
  (activemq/subscribe-to-topic "accept" deliver-from-accept-queue accept-threads)
  (activemq/subscribe-to-topic "redeliver" deliver-from-redelivery-queue redeliver-threads))

(defn use-this-inventory
  "Specify which inventory to use"
  [new-inventory]
  (reset! inventory new-inventory))

(defn- process-client-message
  "Process a message directed at a connected client(s)"
  [host ws message]
  (message (message/add-hop message "accept-to-queue"))
  (time! metrics/time-in-message-queueing
         (activemq/queue-message "accept" message)))

(defn- login-message?
  "Return true if message is a login type message"
  [message]
  (and (= (first (:endpoints message)) "cth://server")
       (= (:data_schema message) "http://puppetlabs.com/loginschema")))

(defn add-connection
  "Add a connection to the connection state map"
  [host ws]
  (swap! connection-map assoc ws (new-socket host)))

(defn remove-connection
  "Remove a connection from the connection state map"
  [host ws]
  (if-let [endpoint (get-in @connection-map [ws :endpoint])]
    (swap! endpoint-map dissoc endpoint))
  (swap! connection-map dissoc ws))

(defn- process-login-message
  "Process a login message from a client"
  [host ws message-body]
  (log/info "Processing login message")
  (when (validation/validate-login-data (:data message-body))
    (log/info "Valid login message received")
    (if (logged-in? host ws)
      (throw (Exception. (str "Received login attempt for '" host "/" (get-in message-body [:data :type]) "' on socket '"
                         ws "'.  Socket was already logged in as " host / (get-in @connection-map [ws :type])
                         " connected since " (get-in @connection-map [ws :created-at])
                         ". Ignoring")))
      (let [data (:data message-body)
            type (:type data)
            endpoint (make-endpoint-string host type)]
        (if-let [old-ws (websocket-of-endpoint endpoint)]
          (do
            (log/error "endpoint " endpoint " already logged in on " old-ws  " Closing new connection")
            (jetty-adapter/close! ws))
          (do
            (swap! connection-map update-in [ws] merge {:type type
                                                        :status "ready"
                                                        :endpoint endpoint})
            (swap! endpoint-map assoc endpoint ws)
            ((:record-client @inventory) endpoint)
            (log/info "Successfully logged in " host "/" type " on websocket: " ws)))))))

(defn- process-inventory-message
  "Process a request for inventory data"
  [host ws message-body]
  (log/info "Processing inventory message")
  (when (validation/validate-inventory-data (:data message-body))
    (log/info "Valid inventory message received")
    (let [data (:data message-body)]
      (process-client-message host ws  {:version 1
                                        :id 12345
                                        :endpoints [(get-in @connection-map [ws :endpoint])]
                                        :data_schema "http://puppetlabs.com/inventoryresponseschema"
                                        :sender "cth://server"
                                        :expires ""
                                        :hops []
                                        :data {:response
                                               {:endpoints ((:find-clients @inventory) (:query data))}}}))))

(defn- process-server-message
  "Process a message directed at the middleware"
  [host ws message-body]
  (log/info "Procesesing server message")
  ; We've only got two message types at the moment - login and inventory
  ; More will be added as we add server functionality
  ; To define a new message type add a schema to
  ; puppetlabs.cthun.validation, check for it here and process it.
  (let [data-schema (:data_schema message-body)]
    (case data-schema
      "http://puppetlabs.com/loginschema" (process-login-message host ws message-body)
      "http://puppetlabs.com/inventoryschema" (process-inventory-message host ws message-body)
      (log/warn "Invalid server message type received: " data-schema))))

(defn message-expired?
  "Check whether a message has expired or not"
  [message]
  (let [expires (:expires message)]
    (time/after? (time/now) (time-coerce/to-date-time expires))))

(defn process-message
  "Process an incoming message from a websocket"
  [host ws message-body]
  (log/info "processing incoming message")
  ; check if message has expired
  (if (message-expired? message-body)
    (log/warn "Expired message with id '" (:id message-body) "' received from '" (:sender message-body) "'. Dropping.")
  ; Check if socket has been logged into
    (if (logged-in? host ws)
  ; check if this is a message directed at the middleware
      (if (= (get (:endpoints message-body) 0) "cth://server")
        (process-server-message host ws message-body)
        (process-client-message host ws message-body))
      (if (login-message? message-body)
        (process-server-message host ws message-body)
        (log/warn "Connection cannot accept messages until login message has been "
                  "processed. Dropping message.")))))

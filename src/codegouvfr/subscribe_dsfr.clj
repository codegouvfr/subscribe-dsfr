#!/usr/bin/env bb

;; Copyright (c) DINUM, Bastien Guerry
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: EPL-2.0.txt

;; This script runs a web app to let users subscribe to a Mailgun
;; mailing list with double opt-in. You need a Mailgun API key.
;;
;; You need to set these environment variables:
;;
;; export MAILGUN_API_KEY="xxxxxxxxxxxxxxxx-xxxxxxxx-xxxxxxxx"
;; export MAILGUN_API_ENDPOINT="https://api.eu.mailgun.net/v3"
;; export MAILGUN_LIST_ID="my@list.com"
;; export MAILGUN_LIST_NAME="My Newsletter"
;;
;; For double opt-in email sending:
;;
;; export SUBSCRIBE_SMTP_HOST="smtp.example.com"
;; export SUBSCRIBE_SMTP_PORT="587"
;; export SUBSCRIBE_SMTP_USER="user@example.com"
;; export SUBSCRIBE_SMTP_PASS="yourpassword"
;; export SUBSCRIBE_SMTP_FROM="newsletter@example.com"
;; export SUBSCRIBE_BASE_URL="http://localhost"
;;
;; The application runs on http://localhost:8080. You can try:
;;
;; ~$ subscribe
;;
;; You can also set a base path to deploy the application on a
;; subdirectory (e.g. "http://localhost:8080/newsletter"):
;;
;; export SUBSCRIBE_BASE_PATH="/newsletter"
;;
;; You can use a EDN configuration file for setting options:
;;
;; ~$ subscribe --config config.edn
;;
;; This config file can let you set/override these variables:
;;
;; - ui-strings
;; - mailgun-api-endpoint
;; - mailgun-api-key
;; - mailgun-list-id
;; - mailgun-list-name
;; - base-url
;; - base-path
;; - subscribe-smtp-host
;; - subscribe-smtp-port
;; - subscribe-smtp-user
;; - subscribe-smtp-pass
;; - subscribe-smtp-from
;;
;; ~$ subscribe -h # Show more information

(ns codegouvfr.subscribe-dsfr
  (:require [org.httpkit.server :as server]
            [babashka.http-client :as http]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [clojure.edn :as edn]
            [babashka.cli :as cli]
            [babashka.pods :as pods]
            [selmer.parser :as selmer]
            [selmer.filters :as filters]
            [clojure.spec.alpha :as s]
            [babashka.deps :as deps]))

(def version "0.5.4")

(deps/add-deps '{:deps {org.clojars.askonomm/ruuter {:mvn/version "1.3.4"}}})
(pods/load-pod 'tzzh/mail "0.0.3")
(require '[ruuter.core :as ruuter]
         '[pod.tzzh.mail :as mail])

(defn print-version []
  (println (format "subscribe %s" version))
  (System/exit 0))

(def cli-options
  {:help      {:alias :h :desc "Display help"}
   :version   {:alias :v :desc "Describe version"}
   :port      {:alias :p :desc "Port number" :default 8080 :coerce :int}
   :base-path {:alias :b :desc "Base path" :coerce :string}
   :base-url  {:alias :u :desc "Base URL for confirmation links (no port)" :coerce :string}
   :log-level {:alias :l :desc "Log level (debug, info, warn, error)" :default "info" :coerce :string}
   :config    {:alias :C :desc "Config file path" :coerce :string}
   :log-file  {:alias :L :desc "Log file" :coerce :string}})

(defn print-usage []
  (println "Usage: subscribe [options]")
  (println "\nOptions:")
  (doseq [[k v] cli-options]
    (println (format "  --%s\t-%s\t%s"
                     (name k)
                     (name (:alias v))
                     (:desc v))))
  (println "\nEnvironment variables:")
  (println "  MAILGUN_LIST_ID             Mailgun list identifier")
  (println "  MAILGUN_API_ENDPOINT        Mailgun API endpoint")
  (println "  MAILGUN_API_KEY             Mailgun API key")
  (println "  SUBSCRIBE_BASE_PATH         Base path for deployments in subdirectories")
  (println "  SUBSCRIBE_SMTP_HOST         SMTP server hostname")
  (println "  SUBSCRIBE_SMTP_PORT         SMTP server port")
  (println "  SUBSCRIBE_SMTP_USER         SMTP username")
  (println "  SUBSCRIBE_SMTP_PASS         SMTP password")
  (println "  SUBSCRIBE_SMTP_FROM         From email address for confirmation emails")
  (println "  SUBSCRIBE_BASE_URL          Base URL for confirmation links")
  (println "\nExamples:")
  (println "  subscribe                   # Run on default port 8080")
  (println "  subscribe -p 8888           # Run on port 4444")
  (println "  subscribe -l debug          # Specify log level as \"debug\"")
  (println "  subscribe -L log.txt        # Specify a log file name")
  (println "  subscribe -b /app           # Set base path to /app")
  (println "  subscribe -u https://z.org  # Set confirmation URL")
  (println "  subscribe -c config.edn     # Load configuration from file")
  (System/exit 0))

;; Setting defaults
(def rate-limit-window (* 60 60 1000)) ;; 1 hour in milliseconds
(def max-requests-per-window 10) ;; Maximum 10 requests per IP per hour
(def ip-request-log (atom {}))
(def last-pruned-time (atom (System/currentTimeMillis)))
(def token-store (atom {}))

;; Data validation
(s/def ::email
  (s/and string?
         #(<= (count %) 254)
         #(re-matches #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$" %)
         #(not (re-find #"\.{2,}|@{2,}|\_{2,}|\-{2,}" %))))

(s/def ::ui-strings map?)
(s/def ::mailgun-list-name string?)
(s/def ::mailgun-list-id (s/and string? #(not (re-find #"\s" %))))
(s/def ::mailgun-api-endpoint (s/and string? #(not (re-find #"\s" %))))
(s/def ::base-path string?)
(s/def ::smtp-config
  (s/keys :req [::subscribe-smtp-host ::subscribe-smtp-port
                ::subscribe-smtp-user ::subscribe-smtp-pass
                ::subscribe-smtp-from]))

(s/def ::config
  (s/keys :opt-un [::ui-strings
                   ::mailgun-list-id
                   ::mailgun-list-name
                   ::mailgun-api-endpoint
                   ::base-path
                   ::base-url]
          :opt [::smtp-config]))

(s/def ::form-email ::email)
(s/def ::form-action #{"subscribe" "unsubscribe"})
(s/def ::csrf_token string?)
(s/def ::website (s/nilable string?))
(s/def ::subscription-form
  (s/keys :req-un [::email ::csrf_token]
          :opt-un [::form-action ::website]))

(defn validate-config [config-data]
  (if (s/valid? ::config config-data)
    true
    (do
      (log/error "Invalid configuration:" (s/explain-str ::config config-data))
      false)))

;; Token management
(def token-expirations
  {:csrf        (* 8 60 60 1000) ;; 8 hours for CSRF tokens
   :subscribe   (* 24 60 60 1000) ;; 24 hours for subscription confirmations
   :unsubscribe (* 24 60 60 1000)}) ;; 24 hours for unsubscription confirmations

(defn generate-token-key []
  (let [random-bytes (byte-array 32)]
    (.nextBytes (java.security.SecureRandom.) random-bytes)
    (.encodeToString (java.util.Base64/getUrlEncoder) random-bytes)))

(defn create-token [token-type data]
  (let [token-key  (generate-token-key)
        now        (System/currentTimeMillis)
        expiration (get token-expirations token-type (* 24 60 60 1000))
        token-data {:type       token-type
                    :data       data
                    :created-at now
                    :expires-at (+ now expiration)}]
    (swap! token-store assoc token-key token-data)
    (log/debug "Created token:" token-key "of type" token-type)
    token-key))

(defn validate-token [token-key & {:keys [token-type consume]
                                   :or   {consume false}}]
  (when (string? token-key)
    (let [token-data (get @token-store token-key)
          now        (System/currentTimeMillis)]
      (when (and token-data
                 (< now (:expires-at token-data))
                 (or (nil? token-type) (= token-type (:type token-data))))
        ;; If consume flag is true, remove the token after validation
        (when consume
          (swap! token-store dissoc token-key))
        token-data))))

(defn consume-token [token-key & {:keys [token-type] :or {token-type nil}}]
  (when-let [token-data (validate-token token-key :token-type token-type :consume true)]
    (:data token-data)))

(defn create-subscription-token [email]
  (create-token :subscribe {:email email}))

(defn create-unsubscription-token [email]
  (create-token :unsubscribe {:email email}))

(defn get-or-create-csrf-token [ip]
  (let [existing-tokens (filter (fn [[_ data]]
                                  (and (= (:type data) :csrf)
                                       (= (get-in data [:data :ip]) ip)))
                                @token-store)]
    (if (seq existing-tokens)
      ;; Return the first valid token for this IP
      (first (keys existing-tokens))
      ;; Create a new token
      (create-token :csrf {:ip ip}))))

(defn validate-csrf-token [token-key ip]
  (when-let [token-data (validate-token token-key :token-type :csrf)]
    (= (get-in token-data [:data :ip]) ip)))

(defn cleanup-expired-tokens []
  (let [now (System/currentTimeMillis)]
    (swap! token-store
           (fn [tokens]
             (into {} (filter (fn [[_ data]]
                                (< now (:expires-at data)))
                              tokens))))
    (log/debug "Cleaned up expired tokens")))

(def cleanup-interval (* 60 60 1000)) ;; 1 hour in milliseconds
(def last-cleanup-time (atom (System/currentTimeMillis)))

(defn schedule-cleanup-if-needed []
  (let [now (System/currentTimeMillis)]
    (when (> (- now @last-cleanup-time) cleanup-interval)
      (cleanup-expired-tokens)
      (reset! last-cleanup-time now))))

;; Path and URL normalization functions
(defn normalize-path [path]
  (if (str/blank? path)
    ""
    (-> path
        str/trim
        ;; Remove duplicate slashes
        (str/replace #"/{2,}" "/")
        ;; Handle leading slash
        (as-> p (if (str/starts-with? p "/") p (str "/" p)))
        ;; Handle trailing slash
        (str/replace #"/$" ""))))

(defn normalize-url
  "Normalize a URL by ensuring consistent format."
  [url & {:keys [trailing-slash] :or {trailing-slash false}}]
  (when-not (str/blank? url)
    (let [url-with-protocol (if (re-find #"^[a-z]+://" url)
                              url
                              (str "http://" url))]
      (cond-> url-with-protocol
        (not trailing-slash) (str/replace #"/$" "")
        trailing-slash       (as-> u (if (str/ends-with? u "/") u (str u "/")))))))

(defn join-paths
  "Join multiple path segments together with proper handling of slashes."
  [& segments]
  (let [filtered-segments   (remove str/blank? segments)
        normalized-segments (map normalize-path filtered-segments)]
    (if (empty? normalized-segments)
      "/"
      (str/join "/" normalized-segments))))

(defn join-url [base-url & path-segments]
  (when-not (str/blank? base-url)
    (let [normalized-base (normalize-url base-url)
          path-part       (apply join-paths path-segments)]
      (str normalized-base path-part))))

(defn create-url-with-query [base-url path query-params]
  (let [url          (join-url base-url path)
        query-string (when (seq query-params)
                       (str/join "&"
                                 (map (fn [[k v]]
                                        ;; Special handling for token to avoid double-encoding
                                        (if (= k :token)
                                          (str (name k) "=" v)
                                          (str (name k) "="
                                               (java.net.URLEncoder/encode (str v) "UTF-8"))))
                                      query-params)))]
    (if (seq query-string)
      (str url "?" query-string)
      url)))

(defn generate-default-base-url [port]
  (let [port-str     (str port)
        default-base (or (System/getenv "SUBSCRIBE_BASE_URL")
                         (str "http://localhost:" port-str))]
    (normalize-url default-base :trailing-slash true)))

;; Setup UI strings with internationalization support
(def ui-strings-data
  {:en
   {:page
    {:title      "Mailing list subscription"
     :heading    "Subscribe to our mailing list"
     :subheading "Join our mailing list to receive updates and news"}
    :form
    {:email-placeholder  "you@example.com"
     :website-label      "Website (leave this empty)"
     :subscribe-button   "Subscribe"
     :unsubscribe-button "Unsubscribe"}
    :messages
    {:back-to-subscription                        "Back to subscription"
     :thank-you                                   "Thank you!"
     :success-subscribe                           "Your email <strong>%s</strong> has been successfully subscribed."
     :already-subscribed                          "Already subscribed"
     :already-subscribed-message                  "The email <strong>%s</strong> is already subscribed."
     :not-subscribed                              "Warning: not subscribed"
     :not-subscribed-message                      "The email <strong>%s</strong> is not currently subscribed. No action was taken."
     :operation-failed                            "Operation failed"
     :no-email                                    "No email address provided. Please try again."
     :rate-limit                                  "Rate limit exceeded"
     :rate-limit-message                          "Too many subscription attempts from your IP address. Please try again later."
     :invalid-email                               "Invalid email format"
     :invalid-email-message                       "The email <strong>%s</strong> appears to be invalid. Please check the format and try again."
     :spam-detected                               "Submission rejected"
     :spam-detected-message                       "Your submission has been identified as potential spam and has been rejected."
     :csrf-invalid                                "Security validation failed"
     :csrf-invalid-message                        "Security token validation failed. This could happen if you used an old form or if your session expired."
     :unknown-action                              "Unknown action requested. Please try again."
     :server-error                                "An unexpected error occurred. Please try again later."
     :confirmation-sent                           "Confirmation email sent"
     :confirmation-sent-message                   "A confirmation email has been sent to <strong>%s</strong>. Please check your inbox and click the confirmation link to complete your subscription."
     :confirmation-success                        "Subscription confirmed"
     :confirmation-success-message                "Your subscription has been confirmed. Thank you for subscribing!"
     :confirmation-error                          "Confirmation error"
     :confirmation-error-message                  "The confirmation link is invalid or has expired. Please try subscribing again."
     :confirmation-email-failed                   "Confirmation email could not be sent"
     :confirmation-email-failed-message           "We couldn't send a confirmation email to <strong>%s</strong>. Please try again later."
     :tokens                                      "Available confirmation tokens"
     :tokens-message                              "Currently there are %s pending confirmation tokens."
     :subscription-confirmation-success           "Subscription confirmed"
     :subscription-confirmation-success-message   "Thank you! Your email <strong>%s</strong> has been successfully added to our mailing list."
     :unsubscription-confirmation-success         "Unsubscription confirmed"
     :unsubscription-confirmation-success-message "Your unsubscription request for <strong>%s</strong> has been processed."}
    :emails
    {:subscription-confirm-subject   "[%s] Please confirm your subscription"
     :subscription-confirm-body-text "Thank you for subscribing to our mailing list with your email address: %s.\n\nPlease confirm your subscription by clicking on this link:\n\n%s\n\nIf you did not request this subscription, you can ignore this email."
     :subscription-confirm-body-html "<html><body><p>Thank you for subscribing to our mailing list with your email address: <strong>%s</strong>.</p><p>Please confirm your subscription by clicking on the following link:</p><p><a href=\"%s\">Confirm your subscription</a></p><p>If you did not request this subscription, you can ignore this email.</p></body></html>"
     :unsubscribe-confirm-subject    "[%s] Please confirm your unsubscription"
     :unsubscribe-confirm-body-text  "You have requested to unsubscribe from our mailing list with the email address: %s.\n\nPlease confirm your unsubscription by clicking on the following link:\n\n%s\n\nIf you did not request this unsubscription, you can ignore this email."
     :unsubscribe-confirm-body-html  "<html><body><p>You have requested to unsubscribe from our mailing list with the email address: <strong>%s</strong>.</p><p>Please confirm your unsubscription by clicking on the following link:</p><p><a href=\"%s\">Confirm your unsubscription</a></p><p>If you did not request this unsubscription, you can ignore this email.</p></body></html>"}}
   :fr
   {:page
    {:title      "Abonnement par e-mail"
     :heading    "Abonnement à notre liste de diffusion"
     :subheading "Rejoignez notre liste pour recevoir des nouvelles"}
    :form
    {:email-placeholder  "vous@exemple.com"
     :website-label      "Site web (laissez ce champ vide)"
     :subscribe-button   "Abonnement"
     :unsubscribe-button "Désabonnement"}
    :messages
    {:back-to-subscription                        "Retour à l'accueil"
     :thank-you                                   "Merci !"
     :success-subscribe                           "Votre adresse e-mail <strong>%s</strong> a été abonnée avec succès."
     :already-subscribed                          "Déjà abonné"
     :already-subscribed-message                  "L'adresse e-mail <strong>%s</strong> est déjà abonnée."
     :not-subscribed                              "Attention : non abonné"
     :not-subscribed-message                      "L'adresse e-mail <strong>%s</strong> n'est pas actuellement abonnée. Aucune action n'a été effectuée."
     :operation-failed                            "Échec de l'opération"
     :no-email                                    "Aucune adresse e-mail fournie. Veuillez réessayer."
     :rate-limit                                  "Limite de taux dépassée"
     :rate-limit-message                          "Trop de tentatives d'abonnement depuis votre adresse IP. Veuillez réessayer plus tard."
     :invalid-email                               "Format d'e-mail invalide"
     :invalid-email-message                       "L'adresse e-mail <strong>%s</strong> semble être invalide. Veuillez vérifier le format et réessayer."
     :spam-detected                               "Soumission rejetée"
     :spam-detected-message                       "Votre soumission a été identifiée comme spam potentiel et a été rejetée."
     :csrf-invalid                                "Échec de validation de sécurité"
     :csrf-invalid-message                        "La validation du jeton de sécurité a échoué. Cela peut se produire si vous avez utilisé un ancien formulaire ou si votre session a expiré."
     :unknown-action                              "Action inconnue demandée. Veuillez réessayer."
     :server-error                                "Une erreur inattendue s'est produite. Veuillez réessayer plus tard."
     :confirmation-sent                           "Email de confirmation envoyé"
     :confirmation-sent-message                   "Un email de confirmation a été envoyé à <strong>%s</strong>.<br/>Veuillez vérifier votre boîte de réception et cliquer sur le lien de confirmation pour finaliser votre abonnement."
     :confirmation-success                        "Abonnement confirmé"
     :confirmation-success-message                "Votre abonnement a été confirmé. Merci de vous être abonné !"
     :confirmation-error                          "Erreur de confirmation"
     :confirmation-error-message                  "Le lien de confirmation n'est pas valide ou a expiré.<br/>Veuillez essayer de vous abonner à nouveau."
     :confirmation-email-failed                   "L'email de confirmation n'a pas pu être envoyé."
     :confirmation-email-failed-message           "Nous n'avons pas pu envoyer un email de confirmation à <strong>%s</strong>.<br/>Veuillez réessayer plus tard."
     :tokens                                      "Jetons de confirmation disponibles"
     :tokens-message                              "Il y a actuellement %s jetons de confirmation en attente."
     :subscription-confirmation-success           "Abonnement confirmé"
     :subscription-confirmation-success-message
     "Merci ! Votre adresse e-mail <strong>%s</strong> a été ajoutée à notre liste de diffusion."
     :unsubscription-confirmation-success         "Désabonnement confirmé"
     :unsubscription-confirmation-success-message "Votre demande de désabonnement pour <strong>%s</strong> a été traitée."}
    :emails
    {:subscription-confirm-subject   "[%s] Veuillez confirmer votre abonnement"
     :subscription-confirm-body-text "Merci de vous être abonné à notre liste de diffusion avec votre adresse e-mail : %s.\n\nVeuillez confirmer votre abonnement en cliquant sur le lien suivant :\n\n%s\n\nSi vous n'avez pas demandé cet abonnement, vous pouvez ignorer cet e-mail."
     :subscription-confirm-body-html "<html><body><p>Merci de vous être abonné à notre liste de diffusion avec votre adresse e-mail : <strong>%s</strong>.</p><p>Veuillez confirmer votre abonnement en cliquant sur le lien suivant :</p><p><a href=\"%s\">Confirmer votre abonnement</a></p><p>Si vous n'avez pas demandé cet abonnement, vous pouvez ignorer cet e-mail.</p></body></html>"
     :unsubscribe-confirm-subject    "[%s] Veuillez confirmer votre désabonnement"
     :unsubscribe-confirm-body-text  "Vous avez demandé à vous désabonner de notre liste de diffusion avec l'adresse e-mail : %s.\n\nVeuillez confirmer votre désabonnement en cliquant sur le lien suivant :\n\n%s\n\nSi vous n'avez pas demandé ce désabonnement, vous pouvez ignorer cet e-mail."
     :unsubscribe-confirm-body-html  "<html><body><p>Vous avez demandé à vous désabonner de notre liste de diffusion avec l'adresse e-mail : <strong>%s</strong>.</p><p>Veuillez confirmer votre désabonnement en cliquant sur le lien suivant :</p><p><a href=\"%s\">Confirmer votre désabonnement</a></p><p>Si vous n'avez pas demandé ce désabonnement, vous pouvez ignorer cet e-mail.</p></body></html>"}}})

(def app-config
  (atom {:mailgun-list-id      (System/getenv "MAILGUN_LIST_ID")
         :mailgun-list-name    (System/getenv "MAILGUN_LIST_NAME")
         :mailgun-api-endpoint (or (System/getenv "MAILGUN_API_ENDPOINT")
                                   "https://api.mailgun.net/v3")
         :mailgun-api-key      (System/getenv "MAILGUN_API_KEY")
         :base-path            (normalize-path (or (System/getenv "SUBSCRIBE_BASE_PATH") ""))
         :base-url             (generate-default-base-url 8080)
         :subscribe-smtp-host  (System/getenv "SUBSCRIBE_SMTP_HOST")
         :subscribe-smtp-port  (System/getenv "SUBSCRIBE_SMTP_PORT")
         :subscribe-smtp-user  (System/getenv "SUBSCRIBE_SMTP_USER")
         :subscribe-smtp-pass  (System/getenv "SUBSCRIBE_SMTP_PASS")
         :subscribe-smtp-from  (System/getenv "SUBSCRIBE_SMTP_FROM")
         :ui-strings           ui-strings-data}))

(defn config [& ks]
  (get-in @app-config ks))

(defn get-ui-strings
  ([lang] (get (config :ui-strings) lang))
  ([] (get-ui-strings :en)))

(defn with-base-path [& path-segments]
  (let [base-path       (config :base-path)
        normalized-base (normalize-path (or base-path ""))
        path-part       (if (seq path-segments)
                          (apply join-paths path-segments)
                          "/")]
    (if (str/blank? normalized-base)
      path-part
      (if (= path-part "/")
        normalized-base
        (join-paths normalized-base path-part)))))

(defn make-path [& segments]
  (apply with-base-path segments))

(defn create-confirmation-url [token]
  (let [base-url  (config :base-url)
        base-path (config :base-path)
        endpoint  "/confirm"]
    (create-url-with-query (join-url base-url (normalize-path base-path))
                           endpoint
                           {:token token})))

;; Returns Authorization header value for Mailgun API requests
(def get-mailgun-auth-header
  (memoize
   (fn []
     (let [auth-string  (str "api:" (config :mailgun-api-key))
           auth-bytes   (.getBytes auth-string)
           encoder      (java.util.Base64/getEncoder)
           encoded-auth (.encodeToString encoder auth-bytes)]
       (str "Basic " encoded-auth)))))

(defn get-mailgun-member-url [email]
  (format "%s/lists/%s/members/%s"
          (config :mailgun-api-endpoint)
          (config :mailgun-list-id)
          (java.net.URLEncoder/encode email "UTF-8")))

(defn get-mailgun-members-url []
  (format "%s/lists/%s/members"
          (config :mailgun-api-endpoint)
          (config :mailgun-list-id)))

(defn make-mailgun-request [method url body-params]
  (let [auth-header  (get-mailgun-auth-header)
        request-opts (cond-> {:headers {"Authorization" auth-header} :throw false}
                       body-params
                       (assoc :headers {"Authorization" auth-header
                                        "Content-Type"  "application/x-www-form-urlencoded"}
                              :body body-params))
        http-fn      (get {:get http/get :post http/post :delete http/delete} method)]
    (try
      (http-fn url request-opts)
      (catch Exception e
        (log/error e (str "Mailgun " (name method) " error: " url))
        {:error       true
         :exception   (.getMessage e)
         :stack-trace (with-out-str (.printStackTrace e))}))))

(def subscription-count (atom 0))
(defn warn-new-subscription! []
  (let [new-count (swap! subscription-count inc)]
    (when (zero? (mod new-count 10))
      (log/info (format "%d new subscriptions" new-count)))))

(defn subscribe-to-mailgun [email]
  (log/info "Subscribing email to Mailgun:" email)
  (let [url         (get-mailgun-members-url)
        body-params (format "address=%s&subscribed=yes&upsert=yes"
                            (java.net.URLEncoder/encode email "UTF-8"))
        _           (log/debug "Making request to Mailgun API:" url)
        _           (log/debug "Body:" body-params)
        response    (make-mailgun-request :post url body-params)]
    (log/debug "Mailgun API response status:" (:status response))
    (log/debug "Mailgun API response body:" (:body response))
    (cond
      (:error response)
      {:success false
       :message "Connection error. Please try again later."
       :debug   response}
      (< (:status response) 300)
      (do (warn-new-subscription!)
          (log/info "Successfully subscribed email:" email)
          {:success true})
      :else
      (do
        (log/error "Failed to subscribe email:" email "- Status:" (:status response))
        (log/error "Error response:" (:body response))
        {:success false
         :message "Failed to subscribe. Please try again later."
         :debug   {:status (:status response)
                   :body   (:body response)}}))))

(defn unsubscribe-from-mailgun [email]
  (log/info "Attempting to unsubscribe email:" email)
  (let [url      (get-mailgun-member-url email)
        _        (log/debug "Making DELETE request to Mailgun API:" url)
        response (make-mailgun-request :delete url nil)]
    (log/debug "Mailgun API unsubscribe response status:" (:status response))
    (log/debug "Mailgun API unsubscribe response body:" (:body response))
    (cond
      (:error response)
      {:success false
       :message "Connection error. Please try again later."
       :debug   response}
      (< (:status response) 300)
      (do
        (log/info "Successfully unsubscribed email:" email)
        {:success true})
      (= (:status response) 404)
      (do
        (log/info "Email not found for unsubscription:" email)
        {:success   false
         :not_found true
         :message   "Email address not found in subscription list."})
      :else
      (do
        (log/error "Failed to unsubscribe email:" email "- Status:" (:status response))
        (log/error "Error response:" (:body response))
        {:success false
         :message "Failed to unsubscribe. Please try again later."
         :debug   {:status (:status response)
                   :body   (:body response)}}))))

(defn process-confirmation-token [token-key]
  (if-let [token-data (validate-token token-key)]
    (let [token-type (:type token-data)
          email      (get-in token-data [:data :email])]
      (case token-type
        :subscribe
        (let [result (subscribe-to-mailgun email)]
          (when (:success result)
            (consume-token token-key))
          (assoc result
                 :email email
                 :action :subscribe
                 :confirm-type :subscription-confirmation-success))
        :unsubscribe
        (let [result (unsubscribe-from-mailgun email)]
          (when (:success result)
            (consume-token token-key))
          (assoc result
                 :email email
                 :action :unsubscribe
                 :confirm-type :unsubscription-confirmation-success))
        ;; Unknown token type
        {:success       false
         :invalid_token true
         :message       "Unknown token type"}))
    ;; Invalid or expired token
    {:success       false
     :invalid_token true
     :message       "Invalid or expired token"}))

;; Configure Selmer HTML escape handling
(filters/add-filter! :safe-str (fn [x] [:safe x]))

;; Updated Selmer Templates with DSFR styling
(def index-template
  "<!DOCTYPE html>
<html lang=\"{{lang}}\" data-fr-theme>
<head>
  <meta charset=\"UTF-8\">
  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
  <title>{{page.title}}{% if list-name|not-empty %} - {{list-name}}{% endif %}</title>

  <!-- DSFR resources -->
  <link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/@gouvfr/dsfr@1.12.1/dist/dsfr/dsfr.min.css\">
  <link rel=\"apple-touch-icon\" href=\"https://cdn.jsdelivr.net/npm/@gouvfr/dsfr@1.12.1/dist/favicon/apple-touch-icon.png\">
  <link rel=\"icon\" href=\"https://cdn.jsdelivr.net/npm/@gouvfr/dsfr@1.12.1/dist/favicon/favicon.svg\" type=\"image/svg+xml\">
  <link rel=\"shortcut icon\" href=\"https://cdn.jsdelivr.net/npm/@gouvfr/dsfr@1.12.1/dist/favicon/favicon.ico\" type=\"image/x-icon\">

  <!-- HTMX for form interactions -->
  <script src=\"https://unpkg.com/htmx.org@2.0.0\"></script>

  <style>
    .success {
      border-left: 5px solid var(--success-425-625);
      padding: 1rem;
      margin-bottom: 1rem;
      background-color: var(--success-950-100);
    }

    .error {
      border-left: 5px solid var(--error-425-625);
      padding: 1rem;
      margin-bottom: 1rem;
      background-color: var(--error-950-100);
    }

    .warning {
      border-left: 5px solid var(--warning-425-625);
      padding: 1rem;
      margin-bottom: 1rem;
      background-color: var(--warning-950-100);
    }

    .info {
      border-left: 5px solid var(--info-425-625);
      padding: 1rem;
      margin-bottom: 1rem;
      background-color: var(--info-950-100);
    }

    .debug {
      margin-top: 1rem;
      padding: 1rem;
      background-color: var(--grey-950-100);
      border-radius: 0.25rem;
      font-family: monospace;
      white-space: pre-wrap;
      display: none;
      font-size: 0.85rem;
    }

    .htmx-indicator {
      opacity: 0;
      transition: opacity 200ms ease-in;
    }

    .htmx-request .htmx-indicator {
      opacity: 1;
    }

    .htmx-request.htmx-indicator {
      opacity: 1;
    }

    /* Honeypot field - hidden from users but visible to bots */
    .visually-hidden {
      position: absolute;
      left: -9999px;
      height: 1px;
      width: 1px;
      overflow: hidden;
    }

    .fr-subscribe-form {
      padding: 2rem 0;
    }
  </style>
</head>
<body>
  <!-- Header -->
  <header role=\"banner\" class=\"fr-header\">
    <div class=\"fr-header__body\">
      <div class=\"fr-container\">
        <div class=\"fr-header__body-row\">
          <div class=\"fr-header__brand fr-enlarge-link\">
            <div class=\"fr-header__brand-top\">
              <div class=\"fr-header__logo\">
                <p class=\"fr-logo\">
                  République<br>
                  Française
                </p>
              </div>
              <div class=\"fr-header__operator\">
                <img src=\"https://cdn.jsdelivr.net/npm/@gouvfr/dsfr@1.12.1/dist/artwork/pictograms/digital/mail.svg\" class=\"fr-responsive-img\" style=\"width:3rem;\" alt=\"\">
              </div>
            </div>
            <div class=\"fr-header__service\">
              <p class=\"fr-header__service-title\">{{page.title}}{% if list-name|not-empty %} - {{list-name}}{% endif %}</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  </header>

  <main role=\"main\" id=\"content\">
    <div class=\"fr-container fr-my-6w\">
      <div class=\"fr-grid-row fr-grid-row--gutters\">
        <div class=\"fr-col-12 fr-col-md-8 fr-col-offset-md-2\">
          <article class=\"fr-subscribe-form\">
            <div class=\"fr-card fr-card--grey\">
              <div class=\"fr-card__body\">
                <div class=\"fr-card__content\">
                  <h2>{% firstof list-name page.heading %}</h2>
                  <p>{{page.subheading}}</p>

                  <form hx-post=\"{{subscribe_path}}\" hx-target=\"#result\" hx-swap=\"outerHTML\" hx-indicator=\"#loading\" class=\"fr-form\">
                    <div class=\"fr-input-group\">
                      <label class=\"fr-label\" for=\"email\">E-mail</label>
                      <input class=\"fr-input\" type=\"email\" id=\"email\" name=\"email\" placeholder=\"{{form.email-placeholder}}\" required>
                    </div>

                    <!-- CSRF Protection -->
                    <input type=\"hidden\" name=\"csrf_token\" value=\"{{csrf_token}}\">

                    <!-- Honeypot field - bots will fill this out, humans won't see it -->
                    <div class=\"visually-hidden\">
                      <label for=\"website\">{{form.website-label}}</label>
                      <input type=\"text\" id=\"website\" name=\"website\" autocomplete=\"off\">
                    </div>

                    <div class=\"fr-btns-group fr-btns-group--inline fr-mt-3w\">
                      <button type=\"submit\" name=\"action\" value=\"subscribe\" class=\"fr-btn\">{{form.subscribe-button}}</button>
                      <button type=\"submit\" name=\"action\" value=\"unsubscribe\" class=\"fr-btn fr-btn--secondary\">{{form.unsubscribe-button}}</button>
                    </div>
                    <div class=\"fr-mt-2w\">
                      <progress id=\"loading\" class=\"fr-progress htmx-indicator\"></progress>
                    </div>
                  </form>
                </div>
              </div>
            </div>
          </article>

          <div id=\"result\"></div>
        </div>
      </div>
    </div>
  </main>


  <!-- Footer -->
  <footer class=\"fr-footer\" role=\"contentinfo\">
    <div class=\"fr-container\">
      <div class=\"fr-footer__body\">
        <div class=\"fr-footer__brand fr-enlarge-link\">
          <p class=\"fr-logo\">
            République<br>
            Française
          </p>
        </div>
        <div class=\"fr-footer__content\">
          <p class=\"fr-footer__content-desc\">
            Inscription à la gazette BlueHats 🧢
          </p>
          <ul class=\"fr-footer__content-list\">
            <li class=\"fr-footer__content-item\">
              <a class=\"fr-footer__content-link\" target=\"new\" href=\"https://info.gouv.fr\">info.gouv.fr</a>
            </li>
            <li class=\"fr-footer__content-item\">
              <a class=\"fr-footer__content-link\" target=\"new\" href=\"https://service-public.fr\">service-public.fr</a>
            </li>
            <li class=\"fr-footer__content-item\">
              <a class=\"fr-footer__content-link\" target=\"new\" href=\"https://legifrance.gouv.fr\">legifrance.gouv.fr</a>
            </li>
            <li class=\"fr-footer__content-item\">
              <a class=\"fr-footer__content-link\" target=\"new\" href=\"https://data.gouv.fr\">data.gouv.fr</a>
            </li>
          </ul>
        </div>
      </div>
      <div class=\"fr-footer__bottom\">
        <ul class=\"fr-footer__bottom-list\">
          <li class=\"fr-footer__bottom-item\">
            <a class=\"fr-footer__bottom-link\" href=\"https://code.gouv.fr\">Retour à code.gouv.fr</a>
          </li>
          <li class=\"fr-footer__bottom-item\">
            <a class=\"fr-footer__bottom-link\" href=\"https://code.gouv.fr/fr/bluehats/tags/gazette/\">Lire la gazette BlueHats</a>
          </li>
          <li class=\"fr-footer__bottom-item\">
            <a class=\"fr-footer__bottom-link\" href=\"https://bluehats.global\">Le mouvement BlueHats</a>
          </li>
        </ul>
      </div>
    </div>
  </footer>

  <!-- DSFR JavaScript -->
  <script src=\"https://cdn.jsdelivr.net/npm/@gouvfr/dsfr@1.12.1/dist/dsfr/dsfr.module.min.js\" type=\"module\"></script>
  <script src=\"https://cdn.jsdelivr.net/npm/@gouvfr/dsfr@1.12.1/dist/dsfr/dsfr.nomodule.min.js\" nomodule></script>
</body>
</html>")

(def confirmation-template
  "<!DOCTYPE html>
<html lang=\"{{lang}}\" data-fr-theme>
<head>
  <meta charset=\"UTF-8\">
  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
  <title>{{page-title}}{% if list-name|not-empty %} - {{list-name}}{% endif %}</title>

  <!-- DSFR resources -->
  <link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/@gouvfr/dsfr@1.12.1/dist/dsfr/dsfr.min.css\">
  <link rel=\"apple-touch-icon\" href=\"https://cdn.jsdelivr.net/npm/@gouvfr/dsfr@1.12.1/dist/favicon/apple-touch-icon.png\">
  <link rel=\"icon\" href=\"https://cdn.jsdelivr.net/npm/@gouvfr/dsfr@1.12.1/dist/favicon/favicon.svg\" type=\"image/svg+xml\">
  <link rel=\"shortcut icon\" href=\"https://cdn.jsdelivr.net/npm/@gouvfr/dsfr@1.12.1/dist/favicon/favicon.ico\" type=\"image/x-icon\">

  <style>
    .success {
      border-left: 5px solid var(--success-425-625);
      padding: 1rem;
      margin-bottom: 1rem;
      background-color: var(--success-950-100);
    }

    .error {
      border-left: 5px solid var(--error-425-625);
      padding: 1rem;
      margin-bottom: 1rem;
      background-color: var(--error-950-100);
    }

    .warning {
      border-left: 5px solid var(--warning-425-625);
      padding: 1rem;
      margin-bottom: 1rem;
      background-color: var(--warning-950-100);
    }

    .info {
      border-left: 5px solid var(--info-425-625);
      padding: 1rem;
      margin-bottom: 1rem;
      background-color: var(--info-950-100);
    }

    .debug {
      margin-top: 1rem;
      padding: 1rem;
      background-color: var(--grey-950-100);
      border-radius: 0.25rem;
      font-family: monospace;
      white-space: pre-wrap;
      display: none;
      font-size: 0.85rem;
    }
  </style>
</head>
<body>
  <!-- Header -->
  <header role=\"banner\" class=\"fr-header\">
    <div class=\"fr-header__body\">
      <div class=\"fr-container\">
        <div class=\"fr-header__body-row\">
          <div class=\"fr-header__brand fr-enlarge-link\">
            <div class=\"fr-header__brand-top\">
              <div class=\"fr-header__logo\">
                <p class=\"fr-logo\">
                  République<br>
                  Française
                </p>
              </div>
              <div class=\"fr-header__operator\">
                <img src=\"https://cdn.jsdelivr.net/npm/@gouvfr/dsfr@1.12.1/dist/artwork/pictograms/digital/mail.svg\" class=\"fr-responsive-img\" style=\"width:3rem;\" alt=\"\">
              </div>
            </div>
            <div class=\"fr-header__service\">
              <p class=\"fr-header__service-title\">{{page-title}}{% if list-name|not-empty %} - {{list-name}}{% endif %}</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  </header>

  <main role=\"main\" id=\"content\">
    <div class=\"fr-container fr-my-6w\">
      <div class=\"fr-grid-row fr-grid-row--gutters\">
        <div class=\"fr-col-12 fr-col-md-8 fr-col-offset-md-2\">
          <div id=\"result\">
            <div class=\"fr-alert fr-alert--{{message-type}}\">
              <h3 class=\"fr-alert__title\">{{heading}}</h3>
              <p>{{message|safe-str}}</p>
              {% if debug-info %}
              <div class=\"debug\">
                {{debug-info}}
              </div>
              {% endif %}
            </div>

            {% if show-back-link %}
            <div class=\"fr-mt-4w\">
              <a href=\"{{base-path}}\" class=\"fr-btn fr-btn--secondary\">{{back}}</a>
            </div>
            {% endif %}
          </div>
        </div>
      </div>
    </div>
  </main>


  <!-- Footer -->
  <footer class=\"fr-footer\" role=\"contentinfo\">
    <div class=\"fr-container\">
      <div class=\"fr-footer__body\">
        <div class=\"fr-footer__brand fr-enlarge-link\">
          <p class=\"fr-logo\">
            République<br>
            Française
          </p>
        </div>
        <div class=\"fr-footer__content\">
          <p class=\"fr-footer__content-desc\">
            Inscription à la gazette BlueHats 🧢
          </p>
          <ul class=\"fr-footer__content-list\">
            <li class=\"fr-footer__content-item\">
              <a class=\"fr-footer__content-link\" target=\"new\" href=\"https://info.gouv.fr\">info.gouv.fr</a>
            </li>
            <li class=\"fr-footer__content-item\">
              <a class=\"fr-footer__content-link\" target=\"new\" href=\"https://service-public.fr\">service-public.fr</a>
            </li>
            <li class=\"fr-footer__content-item\">
              <a class=\"fr-footer__content-link\" target=\"new\" href=\"https://legifrance.gouv.fr\">legifrance.gouv.fr</a>
            </li>
            <li class=\"fr-footer__content-item\">
              <a class=\"fr-footer__content-link\" target=\"new\" href=\"https://data.gouv.fr\">data.gouv.fr</a>
            </li>
          </ul>
        </div>
      </div>
      <div class=\"fr-footer__bottom\">
        <ul class=\"fr-footer__bottom-list\">
          <li class=\"fr-footer__bottom-item\">
            <a class=\"fr-footer__bottom-link\" href=\"https://code.gouv.fr\">Retour à code.gouv.fr</a>
          </li>
          <li class=\"fr-footer__bottom-item\">
            <a class=\"fr-footer__bottom-link\" href=\"https://code.gouv.fr/fr/bluehats/tags/gazette/\">Lire la gazette BlueHats</a>
          </li>
          <li class=\"fr-footer__bottom-item\">
            <a class=\"fr-footer__bottom-link\" href=\"https://bluehats.global\">Le mouvement BlueHats</a>
          </li>
        </ul>
      </div>
    </div>
  </footer>

  <!-- DSFR JavaScript -->
  <script src=\"https://cdn.jsdelivr.net/npm/@gouvfr/dsfr@1.12.1/dist/dsfr/dsfr.module.min.js\" type=\"module\"></script>
  <script src=\"https://cdn.jsdelivr.net/npm/@gouvfr/dsfr@1.12.1/dist/dsfr/dsfr.nomodule.min.js\" nomodule></script>
</body>
</html>")

(defn send-confirmation-email [{:keys [email token lang action]
                                :or   {action :subscribe lang :en}}]
  (let [strings     (get-ui-strings lang)
        confirm-url (create-confirmation-url token)
        from        (or (config :subscribe-smtp-from) "noreply@example.com")
        ;; Email templates based on action
        email-templates
        {:subscribe
         {:subject   (get-in strings [:emails :subscription-confirm-subject])
          :body-text (get-in strings [:emails :subscription-confirm-body-text])
          :body-html (get-in strings [:emails :subscription-confirm-body-html])}
         :unsubscribe
         {:subject   (get-in strings [:emails :unsubscribe-confirm-subject])
          :body-text (get-in strings [:emails :unsubscribe-confirm-body-text])
          :body-html (get-in strings [:emails :unsubscribe-confirm-body-html])}}
        ;; Select template based on action
        {:keys [subject body-text body-html]}
        (get email-templates action
             ;; Fallback to subscribe template if not found
             (get email-templates :subscribe))]
    (log/info (str "Sending " (name action) " confirmation email to:") email)
    (log/info "Confirmation URL:" confirm-url)
    (try
      (let [smtp-host (config :subscribe-smtp-host)
            smtp-port (config :subscribe-smtp-port)
            smtp-user (config :subscribe-smtp-user)
            smtp-pass (config :subscribe-smtp-pass)]
        (if (and smtp-host smtp-port smtp-user smtp-pass)
          (let [list-description (or (not-empty (config :mailgun-list-name))
                                     (config :mailgun-list-id))
                result           (mail/send-mail
                                  {:from     from
                                   :to       [email]
                                   :subject  (format subject list-description)
                                   :text     (format body-text email confirm-url)
                                   :html     (format body-html email confirm-url)
                                   :host     smtp-host
                                   :port     (Integer/parseInt (or smtp-port "587"))
                                   :username smtp-user
                                   :password smtp-pass})]
            (log/debug "Sending email result:" result)
            (log/info (str (name action) " confirmation email sent to") email)
            {:success true})
          (do
            (log/error (str "SMTP configuration missing. Cannot send "
                            (name action) " confirmation email."))
            {:success false
             :message "Email configuration missing"})))
      (catch Exception e
        (log/error (str "Failed to send " (name action)
                        " confirmation email:") (.getMessage e))
        {:success false
         :message (.getMessage e)}))))

(defn read-config-file [file-path]
  (try
    (if (.exists (java.io.File. file-path))
      (let [config-content (slurp file-path)]
        (log/info "Reading configuration from:" file-path)
        (edn/read-string {:readers {}} config-content))
      (log/warn "Configuration file not found:" file-path))
    (catch Exception e
      (log/error "Error reading configuration file:" (.getMessage e)))))

(defn merge-ui-strings! [config-data]
  (when-let [config-ui-strings (:ui-strings config-data)]
    (swap! app-config update :ui-strings
           (fn [original]
             (merge-with (fn [orig new] (merge-with merge orig new))
                         original
                         config-ui-strings)))
    (log/info "Merged UI strings from configuration file")))

(defn update-config-from-file! [file-path]
  (when file-path
    (log/info "Using configuration file:" file-path)
    (when-let [config-data (read-config-file file-path)]
      (if-not (validate-config config-data)
        (System/exit 0)
        (do ;; Merge UI strings first to handle the nested structure
          (merge-ui-strings! config-data)
          ;; Handle path normalization for specific fields
          (let [processed-config
                (cond-> (dissoc config-data :ui-strings)
                  ;; Process base-path if present
                  (:base-path config-data)
                  (update :base-path normalize-path)
                  ;; Process base-url if present
                  (:base-url config-data)
                  (update :base-url normalize-url))]
            ;; Log what we're updating
            (doseq [k (keys processed-config)]
              (log/info "Updating config:" k))
            ;; Update the config with the processed values
            (swap! app-config merge processed-config)))))))

(defn determine-language [req]
  (let [accept-language (get-in req [:headers "accept-language"] "")]
    (cond
      ;; Check Accept-Language header for supported languages
      (str/includes? accept-language "fr") :fr
      :else                                :en)))

(defn get-client-ip [req]
  (or (get-in req [:headers "x-forwarded-for"])
      (get-in req [:headers "x-real-ip"])
      (:remote-addr req)
      "unknown-ip"))

(defn rate-limited? [ip]
  (let [now             (System/currentTimeMillis)
        window-start    (- now rate-limit-window)
        requests        (get @ip-request-log ip [])
        recent-requests (filter #(>= % window-start) requests)]
    ;; Prune old entries periodically
    (when (or (> (- now @last-pruned-time) rate-limit-window)
              (> (count @ip-request-log) 1000))
      (swap! ip-request-log
             (fn [log-map]
               (reduce-kv (fn [m k v] (assoc m k (filter #(>= % window-start) v)))
                          {} log-map)))
      (reset! last-pruned-time now))
    ;; Update the request log with the current timestamp
    (swap! ip-request-log update ip #(conj (or % []) now))
    ;; Prune old entries every 1000 IP requests
    (when (> (count @ip-request-log) 1000)
      (swap! ip-request-log
             (fn [log-map]
               (reduce-kv (fn [m k v]
                            (assoc m k (filter #(>= % window-start) v)))
                          {}
                          log-map))))
    (> (count recent-requests) max-requests-per-window)))

(defn honeypot-filled? [form-data]
  (not (str/blank? (str (:website form-data)))))

(defn escape-html [^String s]
  (when (not-empty s)
    (-> s
        (str/replace "&" "&amp;")
        (str/replace "<" "&lt;")
        (str/replace ">" "&gt;")
        (str/replace "\"" "&quot;")
        (str/replace "'" "&#39;"))))

;; Render HTML template using Selmer
(defn render-index-html [strings lang csrf-token]
  (selmer/render
   index-template
   {:lang           lang
    :list-name      (config :mailgun-list-name)
    :page           (:page strings)
    :form           (:form strings)
    :subscribe_path (make-path "subscribe")
    :csrf_token     csrf-token}))

(defn render-confirmation-html [strings lang message-type heading message & [debug-info]]
  (let [show-back-link
        (or (= heading (get-in strings [:messages :subscription-confirmation-success]))
            (= heading (get-in strings [:messages :unsubscription-confirmation-success])))]
    (selmer/render
     confirmation-template
     {:lang           lang
      :page-title     (:title (:page strings))
      :list-name      (config :mailgun-list-name)
      :message-type   message-type
      :heading        heading
      :message        message
      :debug-info     debug-info
      :back           (get-in strings [:messages :back-to-subscription])
      :base-path      (make-path "")
      :show-back-link show-back-link})))

(defn result-template [strings type message-key & args]
  (let [lang     (keyword (or (first (filter keyword? args)) :en))
        heading  (get-in strings [:messages message-key])
        message  (get-in strings [:messages (keyword (str (name message-key) "-message"))])
        msg-args (filter string? args)]
    (render-confirmation-html
     strings
     lang
     type
     heading
     (if (seq msg-args)
       (apply format message (map escape-html msg-args))
       message))))

(defn debug-result-template [strings type message & debug-info]
  (let [lang      (or (first (filter keyword? debug-info)) :en)
        debug-str (when (seq debug-info)
                    (str "\n\nDebug Info:\n" (escape-html (str (remove keyword? debug-info)))))]
    (render-confirmation-html
     strings
     lang
     type
     message
     (escape-html message)
     debug-str)))

(def base-security-headers
  {"X-Content-Type-Options" "nosniff"
   "X-Frame-Options"        "DENY"})

(def security-headers
  (merge base-security-headers
         {"Content-Security-Policy" "default-src 'self'; script-src 'self' 'unsafe-inline' https://unpkg.com; style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; img-src 'self' data:;"}))

(def security-headers-self
  (merge base-security-headers
         {"Content-Security-Policy" "default-src 'self';"}))

(defn make-response [status type strings heading-key message-key & args]
  {:status  status
   :headers (merge {"Content-Type" "text/html; charset=UTF-8"} security-headers)
   :body    (apply result-template strings type heading-key message-key args)})

(defn handle-error [req e debug-info]
  (log/error "Error:" (str e))
  (log/error "Stack trace:" (with-out-str (.printStackTrace e)))
  (let [lang    (determine-language req)
        strings (get-ui-strings lang)]
    {:status  500
     :headers (merge {"Content-Type" "text/html; charset=UTF-8"} security-headers)
     :body    (debug-result-template
               strings
               "error"
               :operation-failed
               (get-in strings [:messages :server-error])
               (str "Exception: " (.getMessage e) "\n\n"
                    "Debug info:\n" debug-info))}))

(defn check-if-subscribed [email]
  (log/info "Checking if email is already subscribed:" email)
  (let [url      (get-mailgun-member-url email)
        _        (log/debug "Making request to check subscription status:" url)
        response (make-mailgun-request :get url nil)]
    (log/debug "Mailgun API check response status:" (:status response))
    (log/debug "Mailgun API check response body:" (:body response))
    (and (not (:error response))
         (= 200 (:status response)))))

(defn handle-subscription-request [email lang]
  (log/info "Handling subscription request for:" email)
  (if (check-if-subscribed email)
    ;; Already subscribed
    {:already_subscribed true}
    ;; Not subscribed, create confirmation token and send email
    (let [token  (create-subscription-token email)
          result (send-confirmation-email
                  {:email  email
                   :token  token
                   :lang   lang
                   :action :subscribe})]
      (if (:success result)
        {:confirmation_sent true}
        {:confirmation_failed true
         :message             (:message result)}))))

(defn handle-unsubscribe-request [email lang]
  (log/info "Handling unsubscribe request for:" email)
  (if-not (check-if-subscribed email)
    ;; Not currently subscribed
    {:not_subscribed true}
    ;; Subscribed, create confirmation token and send email
    (let [token  (create-unsubscription-token email)
          result (send-confirmation-email
                  {:email  email
                   :token  token
                   :lang   lang
                   :action :unsubscribe})]
      (if (:success result)
        {:confirmation_sent true}
        {:confirmation_failed true
         :message             (:message result)}))))

(defn parse-form-data [request]
  (let [content-type (get-in request [:headers "content-type"] "")
        is-form      (str/includes? content-type "application/x-www-form-urlencoded")]
    (if-not (and (:body request) is-form)
      (do (log/debug "Not a form submission or no body") {})
      (try
        (letfn [(decode-value [s]
                  (try (java.net.URLDecoder/decode s "UTF-8")
                       (catch Exception _ "")))
                (parse-pair [pair]
                  (let [[k v] (str/split pair #"=" 2)
                        key   (keyword (decode-value k))
                        value (if v (decode-value v) "")]
                    [key value]))]
          (let [body   (slurp (:body request))
                pairs  (str/split body #"&")
                pairs  (filter #(not (str/blank? %)) pairs)
                result (into {} (map parse-pair pairs))]
            (log/debug "Parsed form data:" (pr-str result))
            result))
        (catch Exception e
          (log/error "Form parsing error:" (.getMessage e)))))))

(defn parse-query-params-0 [uri]
  (try
    (when (and (not-empty uri) (str/includes? uri "?"))
      (when-let [query-string (second (str/split uri #"\?"))]
        (log/debug "Query string:" query-string)
        (reduce (fn [acc pair]
                  (if (str/blank? pair)
                    acc
                    (try
                      (let [parts (str/split pair #"=" 2)
                            k     (first parts)
                            v     (if (> (count parts) 1) (second parts) "")]
                        (when (not (str/blank? k))
                          (let [decoded-k (java.net.URLDecoder/decode k "UTF-8")
                                decoded-v (if (not (str/blank? v))
                                            (java.net.URLDecoder/decode v "UTF-8")
                                            "")]
                            (log/debug "Decoded param:" decoded-k "=" decoded-v)
                            (assoc acc (keyword decoded-k) decoded-v))))
                      (catch Throwable t
                        (log/error "Error parsing param pair:" pair ":" (str t))
                        acc))))
                {}
                (str/split query-string #"&"))))
    (catch Throwable t
      (log/error "Error parsing query params:" t)
      (log/error "URI that caused error:" uri))))

(defn parse-query-params [req]
  (try
    (let [existing-params (:query-params req)
          uri             (get req :uri "")
          uri-params      (parse-query-params-0 uri)]
      (log/debug "Existing params in request:" (pr-str existing-params))
      (log/debug "URI-parsed params:" (pr-str uri-params))
      (cond
        ;; Use existing params if available
        (and (map? existing-params) (not-empty existing-params))
        existing-params
        ;; Next try URI params
        (and (map? uri-params) (not-empty uri-params))
        uri-params
        ;; Finally, try to extract from query-string directly
        :else
        (when-let [query-string (not-empty (get req :query-string))]
          (parse-query-params-0 (str "?" query-string)))))
    (catch Throwable t
      (log/error "Error in enhanced param parsing:" t)
      (log/error "Request that caused error:"
                 (pr-str (select-keys req [:uri :query-string]))))))

(defn process-subscription-action [strings lang action email]
  (case action
    "subscribe"
    (let [result (handle-subscription-request email lang)]
      (cond
        (:already_subscribed result)
        (make-response 200 "success" strings :already-subscribed :already-subscribed-message email)
        (:confirmation_sent result)
        (make-response 200 "info" strings :confirmation-sent :confirmation-sent-message email)
        (:confirmation_failed result)
        {:status  400
         :headers (merge {"Content-Type" "text/html; charset=UTF-8"} security-headers)
         :body    (debug-result-template
                   strings
                   "error"
                   :confirmation-email-failed
                   (format (get-in strings [:messages :confirmation-email-failed-message]) email)
                   (str "Debug info:\n" (pr-str result)))}
        :else
        {:status  400
         :headers (merge {"Content-Type" "text/html; charset=UTF-8"} security-headers)
         :body    (debug-result-template
                   strings
                   "error"
                   :operation-failed
                   (or (:message result) (get-in strings [:messages :server-error]))
                   (str "Debug info:\n" (pr-str result)))}))
    "unsubscribe"
    (let [result (handle-unsubscribe-request email lang)]
      (cond
        (:not_subscribed result)
        (make-response 200 "warning" strings :not-subscribed :not-subscribed-message email)
        (:confirmation_sent result)
        (make-response 200 "info" strings :confirmation-sent :confirmation-sent-message email)
        (:confirmation_failed result)
        {:status  400
         :headers (merge {"Content-Type" "text/html; charset=UTF-8"} security-headers)
         :body    (debug-result-template
                   strings
                   "error"
                   :confirmation-email-failed
                   (format (get-in strings [:messages :confirmation-email-failed-message]) email)
                   (str "Debug info:\n" (pr-str result)))}
        :else
        {:status  400
         :headers (merge {"Content-Type" "text/html; charset=UTF-8"} security-headers)
         :body    (debug-result-template
                   strings
                   "error"
                   :operation-failed
                   (or (:message result) (get-in strings [:messages :server-error]))
                   (str "Debug info:\n" (pr-str result)))}))
    ;; Default case for unknown action
    (make-response 400 "error" strings :unknown-action :unknown-action)))

(defn handle-index [req]
  (let [lang       (determine-language req)
        strings    (get-ui-strings lang)
        client-ip  (get-client-ip req)
        csrf-token (get-or-create-csrf-token client-ip)]
    (log/debug "Using CSRF token for IP" client-ip ":" csrf-token)
    {:status  200
     :headers (merge {"Content-Type" "text/html; charset=UTF-8"} security-headers)
     :body    (render-index-html strings lang csrf-token)}))

(defn handle-subscribe [req]
  (log/debug "Request method:" (:request-method req))
  (log/debug "Headers:" (pr-str (:headers req)))
  (try
    (let [form-data       (parse-form-data req)
          email           (-> (:email form-data) str/trim str/lower-case)
          action          (or (:action form-data) "subscribe")
          client-ip       (get-client-ip req)
          lang            (determine-language req)
          strings         (get-ui-strings lang)
          form-csrf-token (:csrf_token form-data)]
      (log/debug "Parsed form data:" (pr-str form-data))
      (log/debug "Email from form:" email)
      (log/debug "Action from form:" action)
      (log/debug "CSRF token from form:" form-csrf-token)
      ;; CSRF Protection check
      (if-not (validate-csrf-token form-csrf-token client-ip)
        (do
          (log/warn "CSRF token validation failed")
          (make-response 403 "error" strings :csrf-invalid :csrf-invalid-message))
        ;; Anti-spam: rate limiting
        (if (rate-limited? client-ip)
          (do
            (log/warn "Rate limit exceeded for IP:" client-ip)
            (make-response 429 "error" strings :rate-limit :rate-limit-message))
          ;; Anti-spam: honeypot check
          (if (honeypot-filled? form-data)
            (do
              (log/warn "Spam detected: honeypot field filled from IP:" client-ip)
              (make-response 400 "error" strings :spam-detected :spam-detected-message))
            ;; Email validation
            (if (s/valid? ::subscription-form form-data)
              ;; Process valid form
              (process-subscription-action strings lang action email)
              ;; Handle invalid form
              (let [explain (s/explain-str ::subscription-form form-data)]
                (log/error "Invalid form submission:" explain)
                (make-response 400 "error" strings :invalid-email :invalid-email-message email)))))))
    (catch Throwable e
      (handle-error req e (str "Request method: " (name (:request-method req)) "\n"
                               "Headers: " (pr-str (:headers req)))))))

(defn handle-confirmation [req]
  (try
    (let [query-params (:query-params req)
          token        (:token query-params)
          lang         (determine-language req)
          strings      (get-ui-strings lang)]
      (log/info "Handling confirmation with token:" token)
      (log/info "Query params:" (pr-str query-params))
      ;; Check for token cleanup if needed
      (schedule-cleanup-if-needed)
      (if (str/blank? token)
        (do
          (log/warn "Missing token in confirmation request")
          (make-response 400 "error" strings :confirmation-error :confirmation-error-message))
        (let [result (process-confirmation-token token)]
          (log/debug "Confirmation result:" (pr-str result))
          (cond
            (:success result)
            (make-response
             200
             "success"
             strings
             (or (:confirm-type result) :confirmation-success)
             (or (:confirm-type result) :confirmation-success-message)
             (:email result))
            (:invalid_token result)
            (make-response 400 "error" strings :confirmation-error :confirmation-error-message)
            :else
            {:status  400
             :headers (merge {"Content-Type" "text/html; charset=UTF-8"} security-headers)
             :body    (debug-result-template
                       strings
                       "error"
                       :operation-failed
                       (or (:message result) (get-in strings [:messages :server-error]))
                       (str "Debug info:\n" (pr-str result)))}))))
    (catch Throwable e
      (handle-error req e (str "URI: " (:uri req))))))

(defn handle-tokens [req]
  (let [lang      (determine-language req)
        strings   (get-ui-strings lang)
        count-str (str (count @token-store))]
    {:status  200
     :headers (merge {"Content-Type" "text/html; charset=UTF-8"} security-headers)
     :body    (result-template strings "info" :tokens :tokens-message count-str)}))

(defn handle-robots-txt []
  {:status  200
   :headers {"Content-Type" "text/plain; charset=UTF-8"}
   :body    "User-agent: *\nDisallow: /"})

(def routes
  [{:path "/" :method :get :response handle-index}
   {:path "/subscribe" :method :post :response handle-subscribe}
   {:path "/confirm" :method :get :response handle-confirmation}
   {:path "/tokens" :method :get :response handle-tokens}
   {:path "/robots.txt" :method :get :response handle-robots-txt}])

(defn app [req]
  (let [uri             (:uri req)
        normalized-uri  (normalize-url uri)
        query-params    (parse-query-params req)
        req-with-params (assoc req :query-params query-params)]
    (try
      (log/debug "Processing request:" (:request-method req) uri)
      (log/debug "Normalized path:" normalized-uri)
      (log/debug "Query params:" (pr-str query-params))
      (let [response (ruuter/route routes req-with-params)]
        (or response
            (do
              (log/debug "Not found:" (:request-method req) uri)
              {:status  404
               :headers (merge {"Content-Type" "text/html; charset=UTF-8"}
                               security-headers-self)
               :body    (format "<h1>%s</h1><p>%s: %s %s</p>"
                                "Not Found"
                                "Resource not found"
                                (name (:request-method req))
                                uri)})))
      (catch Throwable e
        (handle-error req e (str "URI: " uri))))))

(defn -main [& args]
  (let [opts (cli/parse-opts args {:spec cli-options})
        port (get opts :port 8080)]
    ;; Handle help and version option
    (when (:help opts) (print-usage))
    (when (:version opts) (print-version))
    ;; Update base-url with specified port when provided
    (when-let [specified-port (get opts :port)]
      (swap! app-config assoc :base-url (generate-default-base-url specified-port))
      (log/info "Setting base-url with specified port:" specified-port))
    ;; Set base-url from command line if provided
    (when-let [conf-url (:base-url opts)]
      (swap! app-config assoc :base-url conf-url)
      (log/info "Setting base-url from command line:" conf-url))
    ;; Set base-path from command line if provided
    (when-let [path (:base-path opts)]
      (swap! app-config assoc :base-path (normalize-path path))
      (log/info "Setting base-path from command line:" path))
    ;; Process configuration file if provided
    (when-let [config-path (:config opts)]
      (update-config-from-file! config-path))
    ;; Log configuration state after all updates
    (log/info "MAILGUN_LIST_ID=" (config :mailgun-list-id))
    (log/info "MAILGUN_LIST_NAME=" (config :mailgun-list-name))
    (log/info "MAILGUN_API_ENDPOINT=" (config :mailgun-api-endpoint))
    (when (not-empty (config :base-path))
      (log/info "SUBSCRIBE_BASE_PATH=" (config :base-path)))
    (if (not-empty (config :mailgun-api-key))
      (log/info "MAILGUN_API_KEY=****")
      (do
        (log/error "MAILGUN_API_KEY not set")
        (System/exit 0)))
    ;; Log SMTP configuration
    (if (and (config :subscribe-smtp-host)
             (config :subscribe-smtp-port)
             (config :subscribe-smtp-user)
             (config :subscribe-smtp-pass)
             (config :subscribe-smtp-from))
      (do
        (log/info "SUBSCRIBE_SMTP_HOST=" (config :subscribe-smtp-host))
        (log/info "SUBSCRIBE_SMTP_PORT=" (config :subscribe-smtp-port))
        (log/info "SUBSCRIBE_SMTP_USER=" (config :subscribe-smtp-user))
        (log/info "SUBSCRIBE_SMTP_PASS=****")
        (log/info "SUBSCRIBE_SMTP_FROM=" (config :subscribe-smtp-from)))
      (log/error "SMTP configuration incomplete."))
    (if (config :base-url)
      (log/info "SUBSCRIBE_BASE_URL=" (config :base-url))
      (log/warn "SUBSCRIBE_BASE_URL not set, use http://localhost"))
    ;; Configure Timbre logging
    (let [appenders (merge {:println (log/println-appender {:stream :auto})}
                           (when-let [f (get opts :log-file)]
                             {:spit (log/spit-appender {:fname f})}))]
      (log/merge-config!
       {:min-level (keyword (get opts :log-level)) :appenders appenders}))
    ;; Start the server
    (log/info (str "Starting server on http://localhost:" port))
    (log/info (str "Base path: " (if (str/blank? (config :base-path)) "[root]" (config :base-path))))
    (server/run-server app {:port port})
    ;; Keep the server running
    @(promise)))

;; Main entry point
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

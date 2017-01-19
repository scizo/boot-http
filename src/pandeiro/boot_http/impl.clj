(ns pandeiro.boot-http.impl
  (:import  [java.net URLDecoder])
  (:require [clojure.java.io :as io]
            [clojure.string  :as s]
            [ring.util.response :refer [resource-response content-type]]
            [ring.middleware
             [file :refer [wrap-file]]
             [resource :refer [wrap-resource]]
             [content-type :refer [wrap-content-type]]
             [not-modified :refer [wrap-not-modified]]
             [reload :refer [wrap-reload]]]
            [pandeiro.boot-http.util :as u]
            [boot.util :as util]))

;;
;; Directory serving
;;
(def index-files #{"index.html" "index.htm"})

(defn index-file-exists? [files]
  (first (filter #(index-files (s/lower-case (.getName %))) files)))

(defn path-diff [root-path path]
  (s/replace path (re-pattern (str "^" root-path)) ""))

(defn filepath-from-uri [root-path uri]
  (str root-path (URLDecoder/decode uri "UTF-8")))

(defn list-item [root-path]
  (fn [file]
    (format "<li><a href=\"%s\">%s</a></li>"
            (path-diff root-path (.getPath file))
            (.getName file))))

(defn index-for [dir]
  (let [root-path (or (.getPath (io/file dir)) "")]
    (fn [{:keys [uri] :as req}]
      (let [directory (io/file (filepath-from-uri root-path uri))]
        (when (.isDirectory directory)
          (let [files (sort (.listFiles directory))]
            {:status  200
             :headers {"Content-Type" "text/html"}
             :body    (if-let [index-file (index-file-exists? files)]
                        (io/file index-file)
                        (format (str "<!doctype html><meta charset=\"utf-8\">"
                                     "<body><h1>Directory listing</h1><hr>"
                                     "<ul>%s</ul></body>")
                                (apply str (map (list-item root-path) files))))}))))))

(defn wrap-index [handler dir]
  (fn [req]
    (or ((index-for dir) req)
        (handler req))))

;;
;; Handlers
;;

(defn wrap-handler [{:keys [handler reload env-dirs]}]
  (when handler
    (if reload
      (wrap-reload (u/resolve-sym handler) {:dirs (or env-dirs ["src"])})
      (u/resolve-sym handler))))

(defn- maybe-create-dir! [dir]
  (let [dir-file (io/file dir)]
    (when-not (.exists dir-file)
      (util/warn "Directory '%s' was not found. Creating it..." dir)
      (.mkdirs dir-file))))

(defn not-found-handler [not-found]
  (if not-found
    (u/resolve-sym not-found)
    (fn [_] {:status  404
             :headers {"Content-Type" "text/plain; charset=utf-8"}
             :body    "Not found"})))

(defn dir-handler [{:keys [dir resource-root not-found]
                    :or {resource-root ""}}]
  (when dir
    (maybe-create-dir! dir)
    (-> (not-found-handler not-found)
      (wrap-resource resource-root)
      (wrap-file dir {:index-files? false})
      (wrap-index dir))))

(defn index-resource-handler [resource-root]
  (fn [{:keys [request-method uri]}]
    (if (and (= request-method :get))
      ; Remove start slash and add end slash
      (let [uri (if (.startsWith uri "/") (.substring uri 1) uri)
            uri (if (.endsWith uri "/") uri (str uri "/"))]
        (some-> (resource-response (str uri "index.html") {:root resource-root})
                (content-type "text/html"))))))

(defn wrap-index-resource [handler resource-root]
  (fn [req]
    (or ((index-resource-handler resource-root) req)
        (handler req))))

(defn resources-handler [{:keys [resource-root not-found]
                          :or   {resource-root ""}}]
  (-> (not-found-handler not-found)
      (wrap-index-resource resource-root)
      (wrap-resource resource-root)))

(defn ring-handler [opts]
  (or (wrap-handler opts)
      (dir-handler opts)
      (resources-handler opts)))

;;
;; Jetty / HTTP Kit
;;

(defn- start-httpkit [handler opts]
  (require 'org.httpkit.server)
  (let [stop-server ((resolve 'org.httpkit.server/run-server) handler opts)]
    (merge (meta stop-server)
           {:stop-server stop-server
            :human-name "HTTP Kit"})))

(defn- start-jetty [handler opts]
  (require 'ring.adapter.jetty)
  (let [server ((resolve 'ring.adapter.jetty/run-jetty) handler opts)]
    {:server server
     :human-name "Jetty"
     :local-port (-> server .getConnectors first .getLocalPort)
     :stop-server #(.stop server)}))

(defn server [{:keys [port httpkit ssl-props] :as opts}]
  ((if httpkit start-httpkit start-jetty)
   (-> (ring-handler opts)
       wrap-content-type
       wrap-not-modified)
    (cond-> {:port  port
             :join? false}
            (seq ssl-props)
            (merge {:http?        false
                    :ssl?         (boolean (seq ssl-props))
                    :ssl-port     (:port ssl-props)
                    :keystore     (:keystore ssl-props)
                    :key-password (:key-password ssl-props)}))))

;;
;; nREPL
;;

(defn nrepl-server [{:keys [nrepl]}]
  (require 'clojure.tools.nrepl.server)
  (let [start-server      (resolve 'clojure.tools.nrepl.server/start-server)
        default-handler   (resolve 'clojure.tools.nrepl.server/default-handler)
        handler           (when-let [mw (:middleware nrepl)]
                            (apply default-handler mw))
        opts              (->> (-> (assoc nrepl :handler handler)
                                   (update :bind #(or % "127.0.0.1")))
                               (reduce-kv #(if %3 (assoc %1 %2 %3) %1) {}))
        {port :port
         :as repl-server} (apply start-server (mapcat identity opts))]
    (doto (io/file ".nrepl-port") .deleteOnExit (spit port))
    (util/info "Started boot-http nREPL on nrepl://%s:%d\n" (:bind opts) port)
    repl-server))

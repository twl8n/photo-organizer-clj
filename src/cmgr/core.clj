(ns cmgr.core
  (:require [cmgr.state]
            [machine.core]
            [machine.util :refer :all]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [ring.adapter.jetty :as ringa]
            [ring.util.response :as ringu]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]])
  (:gen-class))
;; Workaround for the namespace changing to "user" after compile and before -main is invoked
(def true-ns (ns-name *ns*))

;; This would be simpler (?), more secure, more reliable if it only accepted certain keys.
;; export-path
;; db-path
(defn read-config
  "Read .cmgr in the user's home dir. Strip comments and blank lines."
  []
  (into {} conj
        (map (fn [xx] (let [[kstr vstr] (str/split xx #"\s+")] {(keyword kstr) vstr}))
             (remove #(re-matches #"^\s*;;.*|^\s*$" %)
                     (re-seq #"(?m)^.*$"
                             (slurp (str(System/getenv "HOME") "/.cmgr")))))))


;; Be specific that we only do dynamic requests to the /cmgr endpoint.
;; Anything else is a 404 here, and wrap-file will try to load static content aka a file.
;; Frankly, it would have been easier to use slurp to load static content rather than ring's wrap-file.
(defn handler
  [request]
  (if (not (some? (re-matches #".*/cmgr[/]*" (:uri request))))
    ;; calling code in ring.middleware.file expects a status 404 when the handler doesn't have an answer.
    (let [err-return {:status 404 :body (format "Unknown request %.40s ..." (:uri request))}]
      ;; (print (format "uri: %s\n" (:uri request))) (flush)
      err-return)
    (let [temp-params (as-> request yy
                        (:form-params yy) ;; We only support POST requests now.
                        (reduce-kv #(assoc %1 (keyword %2) (clojure.string/trim %3))  {} yy)
                        (assoc yy
                               :d_state (keyword (:d_state yy))))]
      (cmgr.state/set-params temp-params)
      (machine.util/reset-state)
      (machine.util/reset-history)
      (run! #(machine.util/add-state %) (keys temp-params))
      (let [res (machine.util/traverse (or (:d_state temp-params) :page_search) cmgr.state/table)]
        (when res (prn res)))

      ;; Can we change the uri during the response? Yes, I think putting a Location header in here forces the
      ;; uri back to what we want, clearing any anchor/id values, and any other cruft.
      ;; NOTE: "./cmgr" just creates a mess, post-pending "/cmgr" on the uri. 
      {:status 200
       :headers {"Location" "/cmgr" "Content-Type" "text/html"}
       :body @cmgr.state/html-out})))

(comment
  ;; This might not be what we want to fix a uri.
  (-> (ringu/redirect requri)
      (assoc :session new-session)
      (assoc :headers {"Content-Type" "text/html"}))
  )

(defn get-conf [ckey]
  (ckey @cmgr.state/config))

;; Ignore favicon.ico
;; Name prefix local- to distinguish it from ring.middleware
;; (local-wrap-ignore-favicon [handler]
;;
(defn local-wrap-ignore-favicon [handler]
  (fn [request]
    (if (= (:uri request) "/favicon.ico")
      {:status 404}
      (handler request))))


;; Note: wrap-file is not quite cooked. For the URL "http://host/foo" it returns the
;; file "some-path/foo/index.html", the URI is not ".../" or ".../index.html" and all the relative links in
;; the resulting page will be broken. Happily, we have very little need for static content. Just beware. When
;; stuff can't load, it throws errors:

;; 2021-02-19 20:21:44.408:WARN:oejs.HttpChannel:qtp1417854124-16: /images/piaa/IMG_2501_s.jpg
;; java.lang.NullPointerException: Response map is nil

;; Deep inside ring.middleware.file, if file-request can't find a file, it returns a nil response map which
;; results in a 500 error. I really think it should return a 404, since a missing file isn't generally
;; considered a hard fail.

;; The handler is basically a callback that the wrappers may choose to run. Assuming the wrappers call the
;; handler, the wrappers can modify the request before passing it to the handler, and/or modify the response
;; from the handler.

;; We need to dynamically discover the export path at run time, NOT compile time, therefor we must
;; use defn and not def (as you will see in every other ring server example). 
(defn make-app [& args]
  (-> handler
      (local-wrap-ignore-favicon)
      (wrap-file (get-conf :export-path) {:allow-symlinks? true
                                          :prefer-handler? true})
      (wrap-multipart-params)
      (wrap-params)))


;; Unclear how defonce and lein ring server headless will play together.
(defn ds []
  (defonce server (ringa/run-jetty (make-app) {:port 8080 :join? false})))

(defn -main
  "Parse the states.dat file."
  [& args]
  ;; Workaround for the namespace changing to "user" after compile and before -main is invoked
  (in-ns true-ns)
  (cmgr.state/set-config (read-config))
  (print (format "%s\n" @cmgr.state/config))
  (ds)
  (prn "server: " server)
  (.start server))

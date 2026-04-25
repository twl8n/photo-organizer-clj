(ns porg.core
  (:require [porg.state :as state]
            [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.edn :as edn]
            [machine.core]
            [machine.util :refer :all]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [ring.adapter.jetty :as ringa]
            [ring.util.response :as ringu]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [clojure.java.shell :as shell])
  (:gen-class))

;; commonly 8080 or 8081
;; ports 27161 thru 27169 are directtv and might be open on most home routers.
(def rport 8081) 

;; 2025-04-11 migrate read-config to sqlite using default db path and name.
;; This would be simpler (?), more secure, more reliable if it only accepted certain keys.
(defn read-config
  "Read .cmgr in the user's home dir. Strip comments and blank lines."
  []
  {:db-path (str (System/getenv "HOME") "/porg.db")})

;; A CGI param from multiple checkboxes will be a vector of strings.
;; A CGI param from input or single checkbox is a string.
;; This is a helper function to deal with it this aspect of CGI params.

(def deb (atom ""))

(defn trim-vec-or-string
  "Trim trailing whitespace from CGI params. If vector, trim the strings in the vector. If just a string, trim the string"
  [orig]
  (cond (instance? clojure.lang.PersistentVector orig) (map #(clojure.string/trim %) orig)
        (string? orig) (clojure.string/trim orig)
        :else orig))

(def xrequest (atom {}))

(defn keywordify [rmap]
  (reduce-kv #(assoc %1 (keyword %2) (trim-vec-or-string %3)) {} rmap))


;; (:remote-addr request) :remote-addr address unchanged
;; (get (:headers request) "user-agent") confirm user-agent unchanged
;; (get (:headers request) "origin") origin unchanged

;; Use the /porg endpoint.
;; wrap-file will try to load static content aka a file.

(defn handler
  [request]
  (reset! xrequest request) ;; keep this for future debugging?
  ;; Don't check (:uri request) since 404 doesn't have clear meaning here.
  ;; 404 loads the login page.
  (let [html-out (atom "")
        session-data (str (:remote-addr request)
                          (get (:headers request) "user-agent")
                          (get (:headers request) "origin"))
        temp-params (as-> request yy
                      (:form-params yy) ;; :form-params are POST requests
                      (reduce-kv #(assoc %1 (keyword %2) (trim-vec-or-string %3)) {} yy)
                      (assoc yy :html-out html-out)
                      (assoc yy :phdata (keywordify (state/wdecode (:phdata yy))))
                      (assoc yy (keyword (:curr_page (:phdata yy))) true)
                      (assoc yy :session-data session-data)
                      (atom yy))]
    (binding [state/params temp-params]
      (machine.util/reset-state)
      (machine.util/reset-history)
      (machine.util/set-app-state @temp-params)
      (let [res (machine.util/traverse (state/default-state) state/table machine.util/if-arg)]
        (when res (prn res))))

    ;; Can we change the uri during the response? Yes, I think putting a Location header in here forces the
    ;; uri back to what we want, clearing any anchor/id values, and any other cruft.
    ;; NOTE: "./porg" just creates a mess, post-pending "/porg" on the uri. 
    {:status 200
     :headers {"Location" "/porg" "Content-Type" "text/html"}
     :body @html-out}))

(defn get-conf [ckey]
  (ckey @state/config))

;; Ignore favicon.ico
;; Name prefix local- to distinguish it from ring.middleware
;; (local-wrap-ignore-favicon [handler]

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

;; symbolic links work with wrap-file.
;; For the file: /Volumes/external/my-family/2023-08-29/DSC_0001.JPG
;; create a symbolic link: ln -s /Volumes/external/my-family/ image
;; call the middleware: (wrap-file "image")

;; 2026-04-20 These URLs work:
;; http://localhost:8081/2023-08-29/DSC_0001.JPG
;; http://localhost:8081/person.css
;; (assuming port is set to 8081)

;; ipv6 supported out of the box. No changes were necessary.

;; Unclear why order matters, and why it works when html precedes image.
;; (wrap-file "html") must come before (wrap-file "image")

(def app
  (-> handler
      (local-wrap-ignore-favicon)
      (wrap-multipart-params)
      (wrap-file "html")
      (wrap-file "image")
      (wrap-params)))

(defonce server (atom nil))

(defn start-server []
  (when-not @server
    (reset! server (ringa/run-jetty #'app {:port rport :join? false}))
    (printf "Server started on %s\n" rport)))

(defn stop-server []
  (when @server
    (.stop @server)
    (reset! server nil)
    (printf "Server stopped\n")))

(defn restart-server []
  (stop-server)
  (refresh :after 'porg.core/start-server))

(defn -main
  "Launch the server, then open the default web page."
  [& args]
  (state/set-config (read-config))
  (print (format "%s\n" @state/config))
  (print (state/test-config-data))
  (printf "Remember that @porg.core/xrequest has the entire http request.\n")
  (start-server)
  (shell/sh "/Applications/Firefox.app/Contents/MacOS/firefox"
            "--private-window"
            (format "http://localhost:%s/porg" rport)))


(ns porg.state
  (:require [clojure.string :as str]
            [machine.core]
            [machine.util]
            [clojure.set]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.java.jdbc :as jdbc]
            [clostache.parser :as clostache]
            [clojure.pprint :as pp]
            [hugsql.core :as hugsql]))

(def dbspec-sqlite
  {:classname   "org.sqlite.JDBC"
   :subprotocol "sqlite"
   :subname     (str (System/getenv "HOME") "/porg.db")})

;; Hugsql macros must be outside defn and come before any mention of functions that they will create at
;; compile time (or is it run time?). Two functions will be created for each :name in full.sql.

(hugsql/def-db-fns (clojure.java.io/as-file "full.sql"))

(hugsql/def-sqlvec-fns (clojure.java.io/as-file "full.sql"))

(defn test-config
  "Simply return the test key from the config table in the db."
  []
  (let [conn {:connection (jdbc/get-connection dbspec-sqlite)}]
    (sql-config conn)))

;; Work around a feature (or bug) in clostache that turns $ into \$ and \ into \\
;; but only in data fields.
(defn my-render [template data]
  (str/replace
   (clostache/render template data)
   #"[\\]{1}(.)" "$1"))

;; System wide config, with some defaults.
(def config 
  (atom {:export-path (System/getenv "HOME")
         :db-path (System/getenv "HOME")}))

(defn set-config
  "Arg is a map. We need :export-path, :db-path. Override compile time defaults by calling read-config at runtime."
  [new-config]
  (reset! config new-config))

;; This won't work until porg.core has finished compiling, thus we use `resolve`.
;; todo: explain how this works and why.
(when (resolve 'porg.core/init-config)
  (set-config ((eval (resolve 'porg.core/init-config)))))

(def html-out (atom ""))

;; I think db is a "connection"
(def db {:dbtype "sqlite" :dbname (format "%s/porg.db" (:db-path @config))})

;; 2021-01-31 We could use rs/as-unqualified-lower-maps, but since we're using lowercase in our schema,
;; and since we're used to sql drivers returning the case shown in the schema (or uppercase) we don't have to
;; force everything to lower at this time.

(defn msg [arg] (printf "%s\n" arg))

(def params (atom {}))

(defn set-params [xx]
  (reset! params xx))
  

(defn test_config []
  (let [ready-data {:vals (test-config)}
        html-result (my-render (slurp "html/test_config.html") ready-data)]
    (reset! html-out html-result)))



(defn newline-to-br [some-text]
  (str/replace some-text #"[\n\r]{2}" "<br>"))

(defn clear_continue []
 (swap! params #(dissoc % :continue)))

(defn edit_new_page []
  (let [html-result (my-render (slurp "html/edit_page.html")
                                      {:d_state "edit_new_page"
                                       :menu ""
                                       :page_title ""
                                       :body_title ""
                                       :page_name ""
                                       :search_string ""
                                       :image_dir ""
                                       :site_name (:site_name @params "") ;; Add new page from existing site or brand new.
                                       :site_path ""
                                       :page_order 0.0
                                       :valid_page 1
                                       :external_url 0
                                       :ext_zero "selected"
                                       :ext_one ""})]
    (reset! html-out html-result)))

(defn delete_page []
  (println "fn delete_page does nothing yet."))

(defn get_wh [full_name]
  (let [[_ width height] (re-matches  #"(?s)(\d+)\s+(\d+).*"
                                      (:out (shell/sh "sh" "-c" (format "jpegtopnm < %s| pnmfile -size" full_name))))]
    [(Integer. width) (Integer. height)]))

(comment
  (machine.util/verify-table table)
  (machine.util/check-table table)
  (machine.util/check-infinite :page_search table)
  )

;; Quick description of v5 state transition table format.
(comment
  ;; This sort of describes the map of lists of lists that is the state table.
  {:starting-default-state
   [[:some-key some-fn-symbol :other-state]
    [:true fn-symbol nil]
    [:some-other-key other-fn-symbol nil]
    [:true fn-symbol nil]]
   :other-state
   [[:true side-effect-fn-b nil]
    [:true render-html-change-state nil]]}
  )

;; Default is page_search. (How do you know that? Hard coded in core.clj?)
(def table
  {:test_config
   [[:true test_config nil]]
   })


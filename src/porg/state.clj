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

;; Hugsql macros must be outside defn and come before any mention of functions that they will create at
;; compile time (or is it run time?). Two functions will be created for each :name in full.sql.
;; 2026-03-15 todo Need to document/fix path to full.sql hard coded here!
(hugsql/def-db-fns (clojure.java.io/as-file "full.sql"))
(hugsql/def-sqlvec-fns (clojure.java.io/as-file "full.sql"))

;; System wide config, with some defaults.
(def config 
  (atom {:export-path (System/getenv "HOME")
         :db-path (System/getenv "HOME")}))

;; I think db is a "connection"
(def db {:dbtype "sqlite" :dbname (format "%s/porg.db" (:db-path @config))})

(defn set-config
  "Arg is a map. We need :export-path, :db-path. Override compile time defaults by calling read-config at runtime."
  [new-config]
  (reset! config new-config))

;; This won't work until porg.core has finished compiling, thus we use `resolve`.
;; todo: explain how this works and why.
(when (resolve 'porg.core/init-config)
  (set-config ((eval (resolve 'porg.core/init-config)))))

(def html-out (atom ""))

;; 2021-01-31 We could use rs/as-unqualified-lower-maps, but since we're using lowercase in our schema,
;; and since we're used to sql drivers returning the case shown in the schema (or uppercase) we don't have to
;; force everything to lower at this time.

(defn msg [arg] (printf "%s\n" arg))

(def params (atom {}))

(defn set-params [xx]
  (reset! params xx))

;; convert {:name "test", :value "12345"} to  {:test "12345"}
(defn config-data
  "Return hashmap keyed by :name. Records are name,value pairs from the config table in the db. See sql-config in full.sql."
  []
    (into {} (map (fn [aa] [(keyword (:name aa)) (:value aa)]) (sql-config db))))

(defn test-config-data
  "Return all the name,value pairs from the config table in the db."
  []
    (sql-config db))

;; Work around a feature (or bug) in clostache that turns $ into \$ and \ into \\
;; but only in data fields.
(defn my-render [template data]
  (str/replace
   (clostache/render template data)
   #"[\\]{1}(.)" "$1"))

;; 2026-03-16 user code below this line.

(defn get_wh [full_name]
  (let [[_ width height] (re-matches  #"(?s)(\d+)\s+(\d+).*"
                                      (:out (shell/sh "sh" "-c" (format "jpegtopnm < %s| pnmfile -size" full_name))))]
    [(Integer. width) (Integer. height)]))

;; Remove leading part of the full path, creating a "path" that is relavtive to the "image" symlink.
;; The symlink is hard coded, created manually. Should be in config, created by this app.
;; image-path-base The directory path is hard coded here, and should be in config.

(defn parse-file-list
  "Read a list of files, run a regex to keep image file names, remove the rest. Assumes some other code has created the file of names."
  [filename]
  (with-open [rdr (clojure.java.io/reader filename)]
    (reduce conj [] (->> (line-seq rdr)
                         (map #(second (re-matches #"/Volumes/.*family/(.*):\s+image.*" %)))
                         (remove nil?)))))

(defn populate-db [filename-list]
  "Given a list of filenames, call function sql-insert-filename created by hugsql. See full.sql."
  (doseq [pfn filename-list]
    (sql-insert-filename db {:pathfile_name pfn})))

;; sqlite> insert into config values ("pfl-file", "/Users/zeus/files.txt");
(defn populate-db-wrapper []
  (populate-db (parse-file-list (:pfl-file (config-data)))))

(defn person-checkbox-helper
  "Get the list of all person, but transform elements to include :checked 'checked' for persons related to photo_pk"
  [photo_pk]
  (let [pp (map #(assoc % :checked true) (sql-select-photo-person db {:photo_fk photo_pk}))
        pxp (apply merge (flatten (map (fn [xx] {(:person_fk xx) true}) pp)))
        ap (sql-select-all-person db)]
    (map (fn [xx] (if (contains? pxp (:person_pk xx)) (assoc xx :checked "checked") xx)) ap)))

(defn pk-helper
  "Return an integer or nil. Don't throw error."
  [pk]
  (try (Integer. pk) (catch Exception e nil)))

(defn draw-photo-page []
  (let [photo_pk (or (pk-helper (:photo_pk @params)) (:photo_pk (sql-firstnon db)))
        person-seq (person-checkbox-helper photo_pk)
        pic_root_path (:pic_root_path (config-data))
        page_name "html/photo_page.html"
        photo-rec (sql-select-photo db {:photo_pk photo_pk})
        html-result (my-render
                     (slurp page_name)
                     (merge {:d_state "s_photo_page"
                             :photo_pk photo_pk
                             :page_name page_name
                             :pic_root_path pic_root_path
                             :person_display (sql-select-photo-person db {:photo_fk photo_pk})
                             :person_list person-seq}
                            photo-rec))]
    (reset! html-out html-result)))

;; 2026-03-20 Single/multi params problem: better if always list/vector, even when single? Maybe include a
;; hidden checkbox element that is always true, but nil value? Ugh. Php relies on a trailing sigil-like
;; convention foo[] indicated array-ish. We could have a naming convention for checkbox params. Can html say
;; that an input is always a list/vector, even when single value? (No) Or could our middleware do that? Or an
;; HTML trick like always having a empty value?

(defn save-photo-person
  "Save persons linked to a photo. This set is assumed to be complete, so delete the old values and insert new."
  []
  (sql-delete-photo-person db {:photo_fk (:photo_pk @params)}) ;; be explicit about using a foreign key
  (let [photo_fk (:photo_pk @params) ;; photo_pk become photo_fk foreign key in table photo_person
        praw (:person_pk @params) ;; raw params might be a single value or list
        pvec (if (seq? praw) (into [] praw) (into [] (list praw)))] ;; make a vector
    (doseq [person_fk pvec]
      (sql-insert-photo-person db {:photo_fk photo_fk :person_fk person_fk}))))

(defn save-photo []
  (sql-save-photo db @params)
  (save-photo-person))

(defn test_config []
  (let [ready-data {:vals (test-config-data) 
                    :d_state (:d_state @params) 
                    :s_one (:s_one @params) 
                    :s_two (:s_two @params)
                    :whichfn "test_config"}
        html-result (my-render (slurp "html/test_config.html") ready-data)]
    (reset! html-out html-result)))

(defn alt []
  (let [ready-data {:vals (test-config-data) 
                    :d_state (:d_state @params) 
                    :s_one (:s_one @params)
                    :s_two (:s_two @params)
                    :whichfn "alt"}
        html-result (my-render (slurp "html/test_config.html") ready-data)]
    (reset! html-out html-result)))

(defn newline-to-br [some-text]
  (str/replace some-text #"[\n\r]{2}" "<br>"))

(defn clear_continue []
  (swap! params #(dissoc % :continue)))

;; Make sure we have a full photo record. Use the first non-updated record as a default
(defn draw-start-page []
  (let [photo-map (if (number? (pk-helper (:photo_pk @params))) (sql-select-photo db @params) (sql-firstnon db))
        pic_root_path (:pic_root_path (config-data))
        web-params (merge {:d_state "s_start_page" :pic_root_path pic_root_path}
                          @params
                          photo-map
                          (sql-records-found db))
        html-result (my-render
                     (slurp "html/start_page.html")
                     web-params)]
    (reset! html-out html-result)))

;; Use and 'or' so that this will work if @params is uninitialized.
(defn next-photo []
  (set-params (merge @params
                     (or (sql-next-photo db {:photo_pk (:photo_pk @params)})
                         (sql-firstnon db)))))

(defn previous-photo
  "select the previous photo and wrap when at min fk. Return the first photo when something goes wrong."
  []
  (set-params (merge @params
                     (or (sql-previous-photo db {:photo_pk (:photo_pk @params)})
                         (sql-firstnon db)))))

(defn jump-to []
  (let [jump_pk (:jump_pk @params)]
    (if (number? (pk-helper jump_pk))
      (set-params (merge @params {:photo_pk jump_pk}))
      nil)))

;; This could take several minutes to run. We won't have any feedback on the progress.
;; Probably better to run it manually outside the app.
(defn all-pic-files []
  (shell/sh "./pic-list.sh"))

(defn noop [] true)

(defn draw-person-page []
  (println "draw-person-page")
  (let [choose-person-bool (if (:s_choose_person @params) true false)
        person-seq (person-checkbox-helper (:photo_pk @params))
        old-person-seq (sql-select-all-person db)
        page_name "html/person_page.html"
        html-result (my-render
                     (slurp page_name)
                     {:d_state "s_person_page"
                      :choose_person choose-person-bool
                      :photo_pk (:photo_pk @params)
                      :page_name page_name
                      :person_list person-seq})]
    (reset! html-out html-result)))

;; :place_pk from the database is a number.
;; :place_fk from the html is a string.
;; Convert the db value wih (str) before comparing. 
(defn place-checkbox-helper
  "Get the list of all person, but transform elements to include :checked 'checked' for persons related to photo_pk"
  [photo_pk]
  (let [ap (sql-select-all-place db)]
    (map (fn [xx] (if (= (str (:place_pk xx)) (:place_fk @params)) (assoc xx :checked "checked") xx)) ap)))

;; We can come here from start page or photo page, so we might have place_pk or place_fk.
(defn draw-place-page []
  (let [chose_place (if (:s_choose_place @params) {:choose_place true} nil)
        d_state "s_place_page"
        place-seq (place-checkbox-helper (:photo_pk @params));; (sql-select-all-place db)
        page_name "html/place_page.html"
        web-params (merge
                    chose_place
                    {:d_state d_state
                     :photo_pk (:photo_pk @params)
                     :place_pk (or (:place_fk @params) (:place_pk @params))
                     :page_name page_name
                     :place_list place-seq})
        html-result (my-render (slurp page_name) web-params)]
    (reset! html-out html-result)))

(defn draw-edit-place []
  (let [d_state "s_edit_place"
        page_name "html/new_place.html"
        place-seq (sql-select-place db @params)
        html-result (my-render
                     (slurp page_name)
                     (merge
                      {:d_state d_state :page_name page_name}
                      place-seq))]
    (reset! html-out html-result)))

(defn draw-new-place []
  (let [d_state "s_new_place"
        page_name "html/new_place.html"
        html-result (my-render
                     (slurp page_name)
                     (merge
                      {:d_state d_state :page_name page_name}))]
    (reset! html-out html-result)))

(defn save-place []
  (sql-insert-place db @params))

(defn update-place []
  (sql-update-place db @params))

(defn save-place-choice []
  (let [working-params {:photo_pk (:photo_pk @params) :place_fk (:place_pk @params)}]
    (sql-update-pplace db working-params)))

;; CGI params might be single value, or might be vector. Deal with it.
;; Some cases expect multiple values, but here we need a single primary key, so just take the first.
;; CGI multivalue starts off as vector, but somewhere I accidentally changed it to list. Use `seq?` instead of
;; `instance? clojure.lang.PersistentVector`

(defn draw-edit-person
  ;; Might be merged with new-person, not getting any record from the db.
  []
  (let [page_name "html/new_person.html"
        tpk (:person_pk @params)
        single-pk (if (seq? tpk)
                    (first tpk)
                    tpk)
        html-result (my-render
                     (slurp page_name)
                     (merge {:d_state "s_edit_person" :page_name page_name}
                            (sql-select-person db {:person_pk single-pk})))]
    (reset! html-out html-result)))

(defn new-person
  ;; Might be merged with edit-person, sending nil person_pk
  []
  (let [page_name "html/new_person.html"
        html-result (my-render
                     (slurp page_name)
                     {:d_state "s_new_person" :page_name page_name})]
    (reset! html-out html-result)))

(defn save-person []
  (sql-insert-person db @params))

(defn update-person []
  (sql-update-person db @params))

;; There should be a formal way to use a param to change the state.
(defn back-state []
  (set-params (assoc @params :d_state (:prev_state @params))))

;; Quick description of v5 state transition table format.
;; Hashmap of state names
;; Values in the hashmap are lists (of lists) of transitions for that state.
;; Transition list is: state-key function-symbol next-state
;; If state-key exists in data known to the state machine then true and perform the action.
;; some-fn-symbol is a function that performs the action.
;; If the function returns true, then transitionn to :other-state.
;; If action returns false, iterate through transitions.
;; State-key true is always true.
;; If the :other-state (next state) is nil, continue to iterate over transitions.

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

(comment
  ;; functions from machine.util to sanity check the table
  (machine.util/verify-table table)
  (machine.util/check-table table)
  (machine.util/check-infinite :test_config table)
  )

;; 2026-03-20 We needed this last week, but now we don't. What changed??
;; (declare draw-start-page)

;; Change the return value to be your default starting state. Was :test_conf
(defn default-state [] :s_start_page)

;; 2026-03-18 check boolean state function and do things depending on true/false, like don't change to new state?
;; Combine that with explicit exit or error cases?

;; next state must be a keyword, probably also needs to exist in the table.
;; nil is ok as a fn-symbol
;; Don't confuse or conflate d_state with the current state.
(def table
  {:s_start_page
   [ ;; [:s_populate_db populate-db-wrapper nil]
    [:s_previous previous-photo nil]
    [:s_jump jump-to nil]
    [:s_edit nil :s_photo_page]
    [:s_new_person nil :s_new_person]
    [:s_new_place nil :s_new_place]
    [:s_places nil :s_place_page]
    [:s_choose_person nil :s_person_page]
    [:s_next next-photo nil]
    [:true draw-start-page nil]]

   :s_edit_person
   [[:s_save update-person nil]
    [:s_cancel draw-person-page :exit]
    [:true draw-edit-person nil]]
   :s_person_page
   [[:s_new nil :s_new_person]
    [:s_cancel nil :s_start_page]
    [:s_edit nil :s_edit_person]
    [:s_save_choice save-photo-person :s_photo_page]
    [:true draw-person-page nil]]
   :s_new_person
   [[:s_save save-person :s_person_page]
    [:s_cancel nil :s_person_page]
    [:true new-person nil]]

   :s_photo_page
   [[:s_cancel nil :s_start_page]
    [:s_choose_person save-photo :s_person_page]
    [:s_next save-photo nil]
    [:s_next next-photo nil]
    [:s_save save-photo nil]
    [:s_choose_place save-photo :s_choose_place]
    [:true draw-photo-page nil]]

   :s_choose_place
   [[:s_cancel nil nil]
    [:true draw-place-page nil]]
   :s_edit_place
   [[:s_save update-place nil]
    [:s_save draw-place-page :exit]
    [:s_cancel draw-place-page :exit]
    [:true draw-edit-place nil]]
   :s_place_page
   [[:s_save_choice save-place-choice :s_photo_page]
    [:s_new nil :s_new_place]
    [:s_cancel nil :s_start_page]
    [:s_edit nil :s_edit_place]
    [:true draw-place-page nil]]
   :s_new_place
   [[:s_save save-place :s_place_page]
    [:s_cancel nil :s_place_page]
    [:true draw-new-place nil]]

   :test_config
   [[:true test_config nil]]
   :alt
   [[:s_one alt :exit]
    [:true nil :test_config]]
   :exit
   [[:true nil nil]]
   })


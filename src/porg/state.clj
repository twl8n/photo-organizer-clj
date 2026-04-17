(ns porg.state
  (:require [clojure.string :as str]
            [clojure.data.codec.base64 :as base64]
            [clojure.edn :as edn]
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

(def sysmsg (atom ""))
(defn addmsg [msg] (swap! sysmsg #(str % " " msg)))
(defn clrmsg [] (reset! sysmsg ""))

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

(defn wencode
  "base64 encode for web html"
  [enc-me]
  (String. (base64/encode (.getBytes (prn-str enc-me)))))

(defn wdecode
  "base64 decode and return encoded data"
  [dec-me]
  (if (or (nil? dec-me) (empty? dec-me))
    nil
    (edn/read-string (String. (base64/decode (.getBytes dec-me))))))

;; 2021-01-31 We could use rs/as-unqualified-lower-maps, but since we're using lowercase in our schema,
;; and since we're used to sql drivers returning the case shown in the schema (or uppercase) we don't have to
;; force everything to lower at this time.

(defn msg [arg] (printf "%s\n" arg))

(def ^:dynamic params (atom {}))
(def ^:dynamic phdata nil)

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

;; (str/trim (:out (shell/sh "uuidgen")))

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



;; Create a truly local phdata. Assume that porg.state/phdata has been bound to another var in porg.core/handler.
;; Try to update the photo_pk in phdata to be up-to-date.
(defn phd-fn
  ([cparams]
   (phd-fn cparams {}))
  ([cparams add-map]
   (let [userid (or (not-empty (:userid (:phdata cparams))) (not-empty (:userid cparams)))
         new_ppk (:photo_pk @params)
         phd_ppk (if (number? (pk-helper (:photo_pk phdata))) (:photo_pk phdata) (:photo_pk (sql-firstnon db)))
         photo_pk (if (and (some? new_ppk) (sql-select-photo db {:photo_pk new_ppk})) new_ppk phd_ppk)
         phdata (merge {:userid userid
                        :d_state :do_check_auth
                        :photo_pk photo_pk}
                       add-map)
         phdata-enc (wencode phdata)]
     (assoc phdata :phdata-enc phdata-enc))))

(defn get-helper [cparams kw]
  (or
   (not-empty (str (get (:phdata cparams) kw)))
   (not-empty (str (get cparams kw)))))

;; :place_pk from the database is a number.
;; :place_fk from the html is a string.
;; Convert the db value wih (str) before comparing. 
(defn place-checkbox-helper
  "Get the list of all place, but transform elements to include :checked 'checked' for place related to photo_pk"
  ([photo_pk]
   (place-checkbox-helper photo_pk (or (:place_pk @params) (:place_fk @params))))
  ([photo_pk place_fk]
   (let [ap (sql-select-all-place db)]
     (map (fn [xx] (if (= (str (:place_pk xx)) (str place_fk)) (assoc xx :checked "checked") xx)) ap))))

;; place_pk is the value in the html. In a photo context, the SQL is photo.place_fk.
(defn draw-photo-page []
  (let [pic_root_path (:pic_root_path (config-data))
        page_name "html/photo_page.html"
        curr_page "s_photo_page"
        photo_pk (or (pk-helper (:photo_pk @params)) (:photo_pk (sql-firstnon db)))
        phdata (phd-fn @params {:curr_page curr_page})
        ;; userid (or (:userid (:phdata @params)) (:userid @params))
        userid (:userid phdata)
        ;; phdata (wencode {:userid userid
        ;;                  :curr_page curr_page
        ;;                  :photo_pk photo_pk})
        person-seq (person-checkbox-helper photo_pk)
        photo-rec (sql-select-photo db {:photo_pk photo_pk})
        all-place (place-checkbox-helper photo_pk (:place_fk photo-rec))
        html-result (my-render
                     (slurp page_name)
                     (merge {:curr_page_state curr_page
                             :userid userid
                             :photo_pk photo_pk
                             :page_name page_name
                             :pic_root_path pic_root_path
                             :place_list all-place
                             :person_display (sql-select-photo-person db {:photo_fk photo_pk})
                             :person_list person-seq
                             :debug (format "\nphoto_pk %s\nnew phdata %s\nold phdata %s\nuserid %s\nparams %s\n"
                                            photo_pk
                                            (str phdata)
                                            (str (:phdata @params))
                                            userid
                                            (with-out-str (pp/pprint @params)))
                             :phdata (:phdata-enc phdata)}
                            photo-rec))]
    (reset! (:html-out @params) html-result)))

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

;; Do What I Mean check anything for empty
(defn dwim-empty? [thing]
  (cond
    (empty? (str thing)) true
    (keyword? thing) false
    (coll? thing) (some? (seq thing))
    (number? thing) (= 0 thing)
    :else false)
  )

;; In a photo page context, photo_pk is the param key. Copy it to :place_fk for the SQL.
(defn save-photo []
  (let [place_fk (:place_pk @params)
        working-params (assoc @params :place_fk place_fk)]
        (sql-save-photo db working-params)
        (save-photo-person)))

(defn test_config []
  (let [ready-data {:vals (test-config-data) 
                    :s_one (:s_one @params) 
                    :s_two (:s_two @params)
                    :whichfn "test_config"}
        html-result (my-render (slurp "html/test_config.html") ready-data)]
    (reset! (:html-out @params) html-result)))

(defn alt []
  (let [ready-data {:vals (test-config-data) 
                    :s_one (:s_one @params)
                    :s_two (:s_two @params)
                    :whichfn "alt"}
        html-result (my-render (slurp "html/test_config.html") ready-data)]
    (reset! (:html-out @params) html-result)))

(defn newline-to-br [some-text]
  (str/replace some-text #"[\n\r]{2}" "<br>"))

;; I'm pretty sure this can't work due to "state" being held internally in machine.util/traverse.
(defn clear_continue []
  (swap! params #(dissoc % :continue)))

;; Make sure we have a full photo record. Use the first non-updated record as a default
(defn draw-start-page []
  (let [page_name "html/start_page.html"
        pic_root_path (:pic_root_path (config-data))
        phdata (phd-fn @params {:curr_page "s_start_page"})
        photo_pk (or (get-helper @params :photo_pk) (str (:photo_pk (sql-firstnon db))))
        userid (:userid phdata)
        photo-map (sql-select-photo db {:photo_pk photo_pk})
        all-place (place-checkbox-helper photo_pk (:place_fk photo-map))
        person-seq (person-checkbox-helper photo_pk)
        web-params (merge {:curr_page_state (:curr_page phdata)
                           :page_name page_name
                           ;; :userid userid
                           :pic_root_path pic_root_path
                           :place_list all-place
                           :debug (format "\nnew phdata %s\nold phdata %s\nuserid %s\nparams %s\n"
                                          (str phdata)
                                          (str (:phdata @params))
                                          userid
                                          (with-out-str (pp/pprint @params)))
                           :person_display (sql-select-photo-person db {:photo_fk photo_pk})
                           :phdata (:phdata-enc phdata)}
                          ;; (dissoc @params :s_want_place :phdata)
                          photo-map
                          (sql-records-found db))
        html-result (my-render
                     (slurp page_name)
                     web-params)]
    (reset! (:html-out @params) html-result)))

;; Use and 'or' so that this will work if @params is uninitialized.
;; Assume that the current photo_pk is in phdata, not params.
(defn next-photo []
  (let [photo_pk (:photo_pk (:phdata @params))
        next_pk (:photo_pk (or (sql-next-photo db {:photo_pk photo_pk})
                               (sql-firstnon db)))]
    (set-params (assoc (assoc-in @params [:phdata :photo_pk] next_pk) :photo_pk next_pk))))

(defn previous-photo
  "select the previous photo and wrap when at min fk. Return the first photo when something goes wrong."
  []
  (let [photo_pk (:photo_pk (:phdata @params))
        prev_pk (:photo_pk (or (sql-previous-photo db {:photo_pk photo_pk})
                               (sql-firstnon db)))]
    (set-params (assoc (assoc-in @params [:phdata :photo_pk] prev_pk) :photo_pk prev_pk))))

;; jump_pk only comes from the http request, so look in @params
(defn jump-to []
  (let [jump_pk (:jump_pk @params)]
    (if (number? (pk-helper jump_pk))
      (set-params (assoc (assoc-in @params [:phdata :photo_pk] jump_pk) :photo_pk jump_pk))
      ;;(set-params (merge @params {:photo_pk jump_pk}))
      nil)))

;; This could take several minutes to run. We won't have any feedback on the progress.
;; Probably better to run it manually outside the app.
(defn all-pic-files []
  (shell/sh "./pic-list.sh"))

(defn noop [] true)

(defn draw-person-page []
  (let [choose-person-bool (if (or (:s_choose_person @params)
                                   (:choose_button @params))true false)
        person-seq (person-checkbox-helper (:photo_pk @params))
        old-person-seq (sql-select-all-person db)
        page_name "html/person_page.html"
        curr_page "s_show_person"
        ;; photo_pk (if (number? (pk-helper (:photo_pk @params))) (:photo_pk @params) (:photo_pk (sql-firstnon db)))
        photo_pk (or (pk-helper (:photo_pk @params)) (:photo_pk (sql-firstnon db)))
        ;; userid (or (:userid (:phdata @params)) (:userid @params))
        phdata (phd-fn @params {:curr_page "s_start_page" :choose_person choose-person-bool})
        userid (:userid phdata)
        ;; phdata (wencode {:userid userid
        ;;                  :curr_page curr_page
        ;;                  :photo_pk photo_pk
        ;;                  :choose_person choose-person-bool})
        html-result (my-render
                     (slurp page_name)
                     {:curr_page_state curr_page
                      :userid userid
                      :choose_person choose-person-bool
                      :photo_pk (:photo_pk @params)
                      :page_name page_name
                      :phdata (:phdata-enc phdata)
                      :debug (format "\nraw phdata %s\nold phdata %s\nuserid %s\nparams %s\n"
                                     (str phdata)
                                     (str (:phdata @params))
                                     userid
                                     (with-out-str (pp/pprint @params)))
                      :person_list person-seq})]
    (reset! (:html-out @params) html-result)))


;; 2026-03-22 Modified machine.util/traverse to support *not* traversing when the state-fn returns explicit false.
;; Unclear if this will break anything. Only stopping on false means legacy functions that return nil are still ok.
;; Might be best to bite the bullet and upgrade all state-fns to have explicit boolean returns.

;; 2026-03-23 Modified machine.util/if-arg to support functions that return a boolean, in addition to keyword
;; testing. Works with the state table here, and have-place-pk?
(defn have-place-pk? []
  (contains? @params :place_pk))

;; (if (not (empty?  (:userid phdata))) true false )
(defn logged-in? []
  (if (not (empty? (or (not-empty (:userid @params)) (not-empty (:userid (:phdata @params))))))
    true
    false))

(defn nil_person_pk? []
  (if (not (empty?  (:person_pk @params)))
    false
    true))

(defn draw-login []
  (let [page_name "html/login.html"
        phdata (phd-fn @params {:curr_page "s_login_page"})
        userid (:userid phdata)
        html-result (my-render (slurp page_name)
                               {:do_check_auth 1
                                :debug (format "\nphdata %s\nold phdata %s\nuserid %s\n"
                                               (str phdata)
                                               (str (:phdata @params))
                                               userid)
                                :phdata (:phdata-enc phdata)})]
    (reset! (:html-out @params) html-result)))

(defn logout []
  (set-params (dissoc @params :userid))
  true)

;; We can come here from start page or photo page, so we might have place_pk or place_fk.
(defn draw-place-page []
  (let [want_place (if (or (not-empty (:s_want_place @params)) (:place_button @params))
                     {:want_place_val "s_want_place" :want_place_bool true}
                     nil)
        place_pk (or (:place_pk @params) (:place_fk @params))
        place-seq (place-checkbox-helper (:photo_pk @params) place_pk);; (sql-select-all-place db)
        page_name "html/place_page.html"
        userid (or (:userid (:phdata @params)) (:userid @params))
        curr_page "s_place_page"
        photo_pk (or (pk-helper (:photo_pk @params)) (:photo_pk (sql-firstnon db)))
        phdata (phd-fn @params (merge {:curr_page curr_page} want_place))
        web-params (merge
                    want_place
                    {:curr_page_state curr_page
                     :userid userid
                     :photo_pk photo_pk
                     :place_pk place_pk
                     :page_name page_name
                     :place_list place-seq
                     :debug (format "\nphdata %s\nold phdata %s\nuserid %s\n"
                                    (str phdata)
                                    (str (:phdata @params))
                                    userid)
                     :phdata (:phdata-enc phdata)})
        html-result (my-render (slurp page_name) web-params)]
    (reset! (:html-out @params) html-result)))

(defn draw-edit-place []
  (let [want_place (if (not-empty (:s_want_place @params))
                     {:want_place_val "place_button" :want_place_bool true}
                     nil)
        page_name "html/new_place.html"
        userid (or (:userid (:phdata @params)) (:userid @params))
        curr_page "s_edit_place"
        photo_pk (or (pk-helper (:photo_pk @params)) (:photo_pk (sql-firstnon db)))
        phdata (wencode {:userid userid
                         :curr_page curr_page
                         :photo_pk photo_pk})
        place-seq (sql-select-place db @params)
        html-result (my-render
                     (slurp page_name)
                     (merge
                      want_place
                      {:curr_page_state curr_page
                       :userid userid
                       :photo_pk photo_pk
                       :page_name page_name
                       :debug (format "\nphdata %s\nold phdata %s\nuserid %s\n"
                                      (str phdata)
                                      (str (:phdata @params))
                                      userid)
                       :phdata phdata}
                      place-seq))]
    (reset! (:html-out @params) html-result)))

(defn draw-new-place []
  (let [want_place (if (not-empty (:s_want_place @params))
                     {:want_place_val  "place_button" :want_place_bool true}
                     nil)
        page_name "html/new_place.html"
        ;;userid (or (:userid (:phdata @params)) (:userid @params))
        curr_page "s_new_place"
        photo_pk (or (pk-helper (:photo_pk @params)) (:photo_pk (sql-firstnon db)))
        phdata (phd-fn @params (merge {:curr_page curr_page} want_place))
        userid (:userid phdata)
        ;; phdata (wencode {:userid userid
        ;;                  :curr_page curr_page
        ;;                  :photo_pk photo_pk})
        html-result (my-render
                     (slurp page_name)
                     (merge
                      want_place
                      {:curr_page_state curr_page
                       :userid userid
                       :photo_pk photo_pk
                       :s_want_place (:s_want_place @params)
                       :page_name page_name
                       :debug (format "\nraw phdata %s\nold phdata %s\nuserid %s\n"
                                      (str phdata)
                                      (str (:phdata @params))
                                      userid)
                       :phdata (:phdata-enc phdata)}))]
    (reset! (:html-out @params) html-result)))

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
        userid (or (:userid (:phdata @params)) (:userid @params))
        curr_page "s_edit_person"
        photo_pk (or (pk-helper (:photo_pk @params)) (:photo_pk (sql-firstnon db)))
        phdata (wencode {:userid userid
                         :curr_page curr_page
                         :photo_pk photo_pk})
        choose-person-bool (if (or (:s_choose_person @params)
                                   (:choose_button @params))true false)
        tpk (:person_pk @params)
        single-pk (if (seq? tpk)
                    (first tpk)
                    tpk)
        html-result (my-render
                     (slurp page_name)
                     (merge {:curr_page_state curr_page
                             :choose_person choose-person-bool
                             :userid userid
                             :page_name page_name
                             :photo_pk photo_pk
                             :related (sql-related-person db {:related_pk single-pk})
                             :debug (format "\nphdata %s\nold phdata %s\nuserid %s\n"
                                            (str phdata)
                                            (str (:phdata @params))
                                            userid)
                             :phdata phdata}
                            (sql-select-person db {:person_pk single-pk})))]
    (reset! (:html-out @params) html-result)))

(defn draw-new-person
  []
  (let [page_name "html/new_person.html"
        ;; userid (or (:userid (:phdata @params)) (:userid @params))
        curr_page "s_new_person"
        photo_pk (or (pk-helper (:photo_pk @params)) (:photo_pk (sql-firstnon db)))
        ;; phdata (wencode {:userid userid
        ;;                  :curr_page curr_page
        ;;                  :photo_pk photo_pk})
        choose-person-bool (if (or (:s_choose_person @params)
                                   (:choose_button @params))true false)
        phdata (phd-fn @params {:curr_page curr_page :choose_person choose-person-bool})
        userid (:userid phdata)
        html-result (my-render
                     (slurp page_name)
                     {:curr_page_state curr_page
                      :choose_person choose-person-bool
                      :userid userid
                      :photo_pk photo_pk
                      :page_name page_name
                      :debug (format "\nraw phdata %s\nold phdata %s\nuserid %s\nparams %s\n"
                                     (str phdata)
                                     (str (:phdata @params))
                                     userid
                                     (with-out-str (pp/pprint @params)))
                      :phdata (:phdata-env phdata)})]
    (reset! (:html-out @params) html-result)))

(defn save-person []
  (sql-insert-person db @params))

(defn update-person []
  (sql-update-person db @params))

(defn edit-nn
  "If we have valid user entered photo pk use it else use the normal photo pk from params"
  []
  (let [nn_photo_pk (pk-helper (:nn_photo_pk @params))
        local_photo_pk (if (number? nn_photo_pk) nn_photo_pk (:photo_pk @params))]
    (set-params (assoc @params :photo_pk local_photo_pk))))

;; 2026-04-16 Quick description of v5 state transition table format. The outer hashmap is state tables. Values
;; in the hashmap is a single element vector of vectors which are transitions of the table. A transition vector
;; is:
;; - state-key or boolean function
;; - function-symbol
;; - other-table

;; If state-key exists in data known to the state machine then true call the function function-symbol. The fn
;; function-symbol performs an action with side effects. Its return value is tested for false, but true and
;; nil are both true (to preserve legacy functions)

;; If the function-symbol returns true or nil, then transition to the state table :other-table. If action
;; returns false, continue to iterate through transitions. State-key :true is always true. If
;; the :other-table is nil, continue to iterate over transitions.

(comment
  ;; This sort of describes the map of vector of vectors that are the state tables.
  ;; While docs here often call this "the table", it is a map of state tables.
  {:state-table-name
   [[:key-or-fn some-fn-symbol-or-nil :state-table-or-nil]
    [:true fn-symbol-a nil]
    [:some-other-key other-fn-symbol nil]
    [:true fn-symbol-b nil]]
   :other-table-name
   [[:true side-effect-fn nil]
    [:true render-html-change-state nil]]}
  )

(comment
  ;; functions from machine.util to sanity check the table
  (machine.util/verify-table table)
  (machine.util/check-table table)
  (machine.util/check-infinite :test_config table)
  )

;; Change the return value to be your default starting state. Was :test_conf
(defn default-state [] :do_check_auth)

;; two letter prefix/suffix to make every state test/keyword unique
;; :do_check_auth	ca
;; :do_dispatch		di
;; :do_start_page	sp
;; :do_edit_person	es perSon
;; :do_person_page	se perSon
;; :do_new_person	ns perSon
;; :do_photo_page	pp
;; :do_choose_place	cc plaCe
;; :do_edit_place	ec plaCe
;; :do_place_page	pc plaCe
;; :do_new_place	nc plaCe

(def table
  {:do_check_auth
   [[logged-in? nil :do_dispatch]
    [:true draw-login nil]]

   :do_dispatch
   [[:s_start_page nil :do_start_page]
    [:s_edit_person nil :do_edit_person]
    [:s_show_person nil :do_person_page]
    [:s_new_person nil :do_new_person]
    [:s_photo_page nil :do_photo_page]
    [:s_place_page nil :do_place_page] ;; confusion around do_place_page and do_choose_place
    [:s_want_place nil :do_choose_place] ;; s_want_place state test vs do_choose_place dispatch
    [:s_edit_place nil  :do_edit_place]
    [:s_new_place nil :do_new_place]
    [logged-in? draw-start-page :exit]
    [:true draw-login nil]]

   :do_start_page
   [[:s_jump jump-to nil]
    [:s_edit draw-photo-page :exit]
    [:s_edit_nn edit-nn nil]
    [:s_edit_nn draw-photo-page :exit]
    [:s_new_person draw-new-person :exit]
    [:s_new_place draw-new-place :exit]
    [:s_places draw-place-page :exit]
    [:clicked_person draw-person-page :exit]
    [:s_previous previous-photo nil]
    [:s_next next-photo nil]
    [:true draw-start-page :exit]]

   :do_edit_person
   [[nil_person_pk? draw-person-page :exit]
    [:s_save update-person nil]
    [:s_save draw-person-page :exit]
    [:s_cancel draw-person-page :exit]
    [:true draw-edit-person nil]]

   :do_person_page
   [[:s_new nil :do_new_person]
    [:s_cancel draw-start-page :exit]
    [:s_edit draw-edit-person :exit]
    [:s_save_choice save-photo-person nil]
    [:s_save_choice draw-photo-page :exit]
    [:s_edit_photo draw-photo-page :exit]
    [:true draw-person-page nil]]

   :do_new_person
   [[:s_save save-person :do_person_page]
    [:s_cancel draw-person-page :exit]
    [:true draw-new-person nil]]

   :do_photo_page
   [[:s_cancel draw-start-page :exit]
    [:s_choose_person save-photo :do_person_page]
    [:s_choose_person draw-person-page :exit]
    [:s_previous save-photo nil]
    [:s_previous previous-photo nil]
    [:s_next save-photo nil]
    [:s_next next-photo nil]
    [:s_next draw-photo-page :exit]
    [:s_save save-photo nil]
    [:s_want_place save-photo nil]
    [:s_want_place draw-place-page :exit]
    [:true draw-photo-page :exit]]

   :do_choose_place
   [[:s_cancel nil nil]
    [:true draw-place-page nil]]

   :do_edit_place
   [[:s_save update-place nil]
    [:s_save draw-place-page :exit]
    [:s_cancel draw-place-page :exit]
    [have-place-pk? draw-edit-place :exit]
    [:true draw-place-page nil]]

   :do_place_page
   [[:s_save_choice save-place-choice nil]
    [:s_save_choice draw-photo-page :exit]
    [:s_new draw-new-place :exit]
    [:s_cancel draw-start-page :exit]
    [:s_edit nil :do_edit_place] ;; change table, handle have-place-pk?
    [:s_edit_photo draw-photo-page :exit]
    [:true draw-place-page nil]]

   :do_new_place
   [[:s_save save-place nil]
    [:s_save draw-place-page :exit]
    [:s_cancel draw-place-page :exit]
    [:true draw-new-place nil]]

   :test_config
   [[:true test_config nil]]
   :alt
   [[:s_one alt :exit]
    [:true nil :test_config]]
   :exit
   [[:true nil nil]]
   })


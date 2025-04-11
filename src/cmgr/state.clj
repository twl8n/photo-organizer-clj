(ns cmgr.state
  (:require [clojure.string :as str]
            [machine.core]
            [machine.util]
            [clojure.set]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [next.jdbc.result-set :as rs]
            [clostache.parser :as clostache]
            [clojure.pprint :as pp]))

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

;; This won't work until cmgr.core has finished compiling, thus we use `resolve`.
;; todo: explain how this works and why.
(when (resolve 'cmgr.core/init-config)
  (set-config ((eval (resolve 'cmgr.core/init-config)))))

(def html-out (atom ""))

;; I think db is a "connection"
(def db {:dbtype "sqlite" :dbname (format "%s/cmgr.db" (:db-path @config))})

;; 2021-01-31 We could use rs/as-unqualified-lower-maps, but since we're using lowercase in our schema,
;; and since we're used to sql drivers returning the case shown in the schema (or uppercase) we don't have to
;; force everything to lower at this time.

;; with-options works on db or ds
;; I guess ds-opts is a "datasource with options" in next.jdbc parlance.
(def ds-opts (jdbc/with-options db {:return-keys true :builder-fn rs/as-unqualified-maps}))

(defn msg [arg] (printf "%s\n" arg))

(def params (atom {}))

(defn set-params [xx]
  (reset! params xx))
  
;; siv does the data side of scrollIntoView() javascript at the bottom of page_search.html
;; It is a context sensitive way to scroll page_search to whatever page we're working on most recently.
(defn page_search []
  (let [result-set (jdbc/execute! ds-opts ["select * from page where valid_page=1 order by site_name,page_order"])
        db-data {:dcc_site (mapv (fn [xx] {:site_name (key xx)
                                           :siv (if (= (key xx) (:from_page @params nil)) true false)
                                           :dcc_page (val xx)}) (group-by :site_name result-set))}
        recf (count (:dcc_site db-data))
        ready-data (merge @params
                          {:recordsfound recf
                           :d_state "page_search"
                           :s (if (> recf 1) "s" "")}
                          db-data
                          )
        html-result (my-render (slurp "html/page_search.html") ready-data)]
    (reset! html-out html-result)))

(defn edit_page []
  (let [result-set (jdbc/execute-one! ds-opts ["select * from page where page_pk=?" (:page_pk @params)])
        [ext_one ext_zero] (if (= 1 (:external_url @params)) ["selected" ""] ["" "selected"])
        ready-data (merge result-set
                          {:d_state "edit_page"
                           :ext_one ext_one
                           :ext_zero ext_zero}
                          )
        html-result (my-render (slurp "html/edit_page.html") ready-data)]
    (reset! html-out html-result)))

(defn save_page []
  (let [result-set (sql/update! ds-opts
                                :page
                                (select-keys @params
                                             [:menu :page_title :body_title
                                              :page_name :search_string :image_dir
                                              :site_name :site_path :page_order
                                              :valid_page :external_url :page_pk])
                                {:page_pk (:page_pk @params)})]
    true))

;; item = photo+text
;; page = one or more items
;; item_search is a sort-of preview-ish list of all the items for a given page.
;; It has a button on each item preview to edit that item.
(defn item_search []
  (let [raw-set (jdbc/execute!
                    ds-opts
                    ["select content.*, page.site_name, page.image_dir, page.site_path 
			from content,page where page_fk=? and page_pk=page_fk order by item_order"
                     (:page_pk @params)])
        ;; Need to html-encode :description THEN regex the \n and \r
        ;; I see a \$ in con_pk 2799
        result-set (mapv #(assoc % :desc-html (str/replace (or (:description % "") "") #"([\000-\037]+)" "<br><br>")) raw-set)
        ready-data (merge
                    @params
                    {:site_name (:site_name (first raw-set))}
                    {:content result-set :d_state "item_search"})
        html-result (my-render (slurp "html/item_search.html") ready-data)]
    (reset! html-out html-result)))


;; I think we aren't saving the other fields because they were fixed when the item was created, and cannot
;; be changed.
(defn save_item []
  (let [result-set (sql/update! ds-opts
                                :content
                                (select-keys @params
                                             [:description :alt_text :item_order :valid_content])
                                {:page_fk (:page_fk @params)
                                 :con_pk (:con_pk @params)})]
    true))


;; 2021-02-08 This will re-edit the same item if there is no next, and that's a tiny bit confusing.
(defn next_item []
  (let [old_con_pk (:con_pk @params)
        result-set (jdbc/execute-one!
                    ds-opts
                    ["select con_pk from content 
			where page_fk=? and 
			valid_content=1 and item_order>? 
			order by item_order,con_pk limit 1"
                     (:page_pk @params) (:item_order @params)])]
    (swap! params #(assoc % :con_pk (:con_pk result-set old_con_pk)))))


;; The old Perl code that attempted to resize the textarea based on the amount of text.
;; $drows = p_count($description); # number of <p> or \n in $description
;; $drows += (length($description)/60);
;; $drows += ($drows*0.3);
;; $drows = sprintf("%d", $drows);
(defn edit_item []
  (let [result-set (jdbc/execute-one!
                    ds-opts
                    ["select page_fk,
		      	image_name, image_width, image_height,
		      	description, alt_text, valid_content,
		      	item_order, s_name, s_width, s_height,
		      	menu, page_title, body_title,
		      	page_name, search_string, site_name, site_path,
		      	page_order, valid_page, image_dir, external_url
		      	from content,page where con_pk=? and page_fk=page_pk"
                     (:con_pk @params)])
        ready-data (merge @params
                          result-set
                          {:drows 20
                           :page_pk (:page_fk result-set)
                           :d_state "edit_item"})
        html-result (my-render (slurp "html/edit_item.html") ready-data)]
    (reset! html-out html-result)))

(comment
  (def site_name "baja_bug")
  (def result-set (jdbc/execute!
                    ds-opts
                    ["select page_pk as menu_page_pk,page_order,page_name,menu,site_path from page where
			site_name=? and valid_page<>0 order by page_order" site_name]))
  (def ordinal-set (map-indexed #(assoc %2 :ordinal  %1) result-set))
  (def desired-columns 5.0M)
        ;; See Note 1 above.
  ;; only works if desired-columns is 5M (and not 5.0)
  (with-precision 1 :rounding CEILING (/ (max desired-columns (count ordinal-set)) desired-columns))

  ;; Seems to work all the time.
  (Math/ceil (/ (max desired-columns (count ordinal-set)) desired-columns))
  (Math/ceil (/ (max desired-columns 6) desired-columns))
  (Math/ceil (/ (max desired-columns 5) desired-columns))
  (Math/ceil (/ (max desired-columns 4) desired-columns))
  )


;; Note 1: (see Note 1 below) Use quot to get an integer number of lines from a rounded-up float number of lines.
;; Require at least desired-columns as a min that yields 1 line.
(defn menu_gen
  "Given a site name, build the top menu for that site, returning an HTML fragment."
  [site_name]
  (let [result-set (jdbc/execute!
                    ds-opts
                    ["select page_pk as menu_page_pk,page_order,page_name,menu,site_path from page where
			site_name=? and valid_page<>0 order by page_order" site_name])
        ordinal-set (map-indexed #(assoc %2 :ordinal  %1) result-set)
        desired-columns 5.0
        ;; See Note 1 above.
        number-of-lines (Math/ceil (/ (max desired-columns (count ordinal-set)) desired-columns))
        pb-set (mapv #(assoc % :page_break (mod (:ordinal %) number-of-lines)) ordinal-set)
        ready-data (assoc {} :menu_line (mapv (fn [xx] {:first_col (val xx)}) (group-by :page_break pb-set)))]
    (my-render (slurp "html/menu_template.html") ready-data)))


;; These are item pages, aka image pages. Ideally, we'd be consistent about the name.
(defn gen_image_pages [ready-data]
  (doseq [page-data (:content-ordinal ready-data)] ;; :description \n changed to <br> in gen_single_page.
    (let [page_pk (:page_pk ready-data)
          item_order (:item_order page-data)
          page_stem (:page_stem ready-data)
          site_path (:site_path ready-data)
          max_ordinal (:max_ordinal (jdbc/execute-one!
                                     ds-opts
                                     ["select count(*) as max_ordinal from content where valid_content=1 and page_fk=?" page_pk]))
          prev (:prev (jdbc/execute-one!
                       ds-opts
                       ["select count(*) as prev from content 
			where valid_content=1 and page_fk=? and item_order<?" page_pk item_order]))
          prev_flag (< 0 prev)
          ordinal (+ 1 prev)
          next (+ 2 prev)
          next_flag (<= next max_ordinal)
          prev-name (format "%s_%s_i.html" page_stem prev)
          next-name (format "%s_%s_i.html" page_stem next)
          ;; full-site-path (format "%s/%s" (:export-path @config) (:site_path page-rec))       
          full_page_name (format "%s/%s/%s_%s_i.html" (:export-path @config) site_path page_stem ordinal)
          html-fragment (my-render (slurp "html/image_t.html")
                                          (merge page-data
                                                 ready-data
                                                 {:ordinal ordinal
                                                  :next next_flag
                                                  :next-name next-name
                                                  :prev prev_flag
                                                  :prev-name prev-name}))]
      (spit full_page_name html-fragment))))


;; This is especially for :description, but might be necessary for other text that has HTML line breaks.
;; Eventually, we might need to support other embedded HTML (or not).
;; Any field that gets this treatment needs to be HTML enabled in clostache with & as in: {{&foo}}
(defn newline-to-br [some-text]
  (str/replace some-text #"[\n\r]{2}" "<br>"))


(defn gen_single_page [page_pk menu_text]
  (let [nav (slurp (format "%s/nav.html" (:export-path @config)))
        page-rec (jdbc/execute-one!
                  ds-opts
                  ["select
			page_pk, menu, page_title, body_title, page_name,
			search_string, image_dir, site_name, site_path,
			page_order, valid_page,external_url
			from page where page_pk=?" page_pk])
        page_stem (second (re-matches #"(.*)\..*" (:page_name page-rec)))
        content-raw (jdbc/execute!
                      ds-opts
                      ["select con_pk,image_height,description,alt_text,valid_content,s_name,s_width,s_height,
			image_name,image_width,item_order
			from content where page_fk=? and valid_content=1 order by item_order, con_pk" page_pk])
        ;; Changing content-raw here effects all downstream code, like gen_image_pages.
        content-recs (mapv #(assoc %1 :description (newline-to-br (:description %1))) content-raw)
        ;; For :flag I'm pretty sure we want the first record which is ordinal (index) zero.
        content-ordinal (map-indexed #(assoc %2 :ordinal  (inc %1)
                                             :flag (= (inc %1) 1)
                                             :pf_name (format "%s_%s_i.html" page_stem (inc %1))) content-recs)
        start_inner (filter :flag content-ordinal)
        remainder (vec (remove :flag content-ordinal))

        full-site-path (format "%s/%s" (:export-path @config) (:site_path page-rec))
        full_page_name (format "%s/%s" full-site-path (:page_name page-rec))
        ready-data (assoc page-rec
                          :nav nav
                          :page_stem page_stem
                          :menu_text menu_text
                          :full_page_name full_page_name
                          :content-ordinal content-ordinal
                          :start_inner start_inner
                          :remainder remainder)
        html-fragment (my-render (slurp "html/main_t.html")
                                        ready-data)]
    (spit full_page_name html-fragment)
    (gen_image_pages ready-data)))


(defn site_gen []
  (let [site_name (:site_name @params)
        result-set (jdbc/execute!
                    ds-opts
                    ["select page_pk from page where
	 	     site_name=? and valid_page<>0 and external_url=0" site_name])
        menu-html (menu_gen site_name)]
    ;; Create export html file for each page.
    (doseq [single-page result-set]
      (gen_single_page (:page_pk single-page) menu-html))))


(defn insert_page []
  (let [site_path (:site_path @params)
        image_dir (:image_dir @params)
        export-path (:export-path @config)]
    (.mkdirs (io/file (format "%s/%s/images/%s" export-path site_path image_dir)))
    (sql/insert! ds-opts :page
                 (select-keys
                  @params
                  [:menu :page_title :body_title :page_name
                   :search_string :image_dir :site_name :site_path
                   :page_order :valid_page :external_url]))))


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

(defn page_gen []
  (gen_single_page (:page_pk @params) (menu_gen (:site_name @params))))

(defn delete_page []
  (println "fn delete_page does nothing yet."))

(defn get_wh [full_name]
  (let [[_ width height] (re-matches  #"(?s)(\d+)\s+(\d+).*"
                                      (:out (shell/sh "sh" "-c" (format "jpegtopnm < %s| pnmfile -size" full_name))))]
    [(Integer. width) (Integer. height)]))


(defn gen-core
  "Do side effects, return (inc item_order)"
  [pdata short_name item_order]
  (let [{:keys [site_path image_dir page_pk]} pdata
        full_name (format "%s/%s/images/%s/%s" (:export-path @config) site_path image_dir short_name)
        full_s_name (str/replace full_name #"(?i)\.jp.*g" "_s.jpg")
        image_name (str/replace full_name #".*\/(.*)" "$1")
        xsize 320
        cmd (format "jpegtopnm < %s | pnmscale -xsize=%s | pnmtojpeg > %s 2>&1" full_name xsize full_s_name)
        conv_results (shell/sh "sh" "-c" cmd)
        [image_width image_height] (get_wh full_name)
        [s_width s_height] (get_wh full_s_name)
        {:keys [exists]} (jdbc/execute-one!
                          ds-opts
                          ["select (count(*)>0) as 'exists' from content where image_name=? and page_fk=?"
                           image_name page_pk])
        s_name (str/replace full_s_name #".*\/(.*)" "$1")
        insert-data {:page_fk page_pk
                     :image_name image_name
                     :image_width image_width
                     :image_height image_height
                     :valid_content 1
                     :description "enter a description"
                     :item_order item_order
                     :s_name s_name
                     :s_width s_width
                     :s_height s_height}]
    (when (= 0 exists)
      (sql/insert! ds-opts :content insert-data)))
  (inc item_order))

;; 2024-11-07 auto_gen images. Use any image in the directory. Sequence is based on string sorting.
(comment 
  (pprint (.list (io/file "/Users/twl/Sites/content-manager-pages/1990_f250/images/camperv3/")))
  (def xx
    (let [filter-fn (fn [xx] (re-find #"(?i).*\.jp.*g" xx))
          remove-fn (fn [xx] (re-find #"(?i).*_s\.jp.*g" xx))]
      (->> (.list (io/file (format "%s/%s/images/%s" "/Users/twl/Sites/content-manager-pages" "1990_f250" "camperv3")))
           (filter filter-fn)
           (remove remove-fn)
           (sort))
      ))
)

;; Any non-thumbnail jpeg is ok. Get all jpeg, then remove *_s.jpg small thunbnail files.
;; Need to check exif orientation, and use jpegtran to rotate, before creating thumbnails.
;; Maybe separate out file munging from database record insert.

(defn auto_gen []
  (let [page_pk (:page_pk @params)
        {:keys [site_path image_dir]} (jdbc/execute-one!
                                       ds-opts
                                       ["select site_path, image_dir from page where page_pk=?" page_pk])
        filter-fn (fn [xx] (re-find #"(?i).*\.jp.*g" xx))
        remove-fn (fn [xx] (re-find #"(?i).*_s\.jp.*g" xx))
        file-list (->> (.list (io/file (format "%s/%s/images/%s" (:export-path @config) site_path image_dir)))
                       (filter filter-fn)
                       (remove remove-fn)
                       (sort))
        page-data {:page_pk page_pk :site_path site_path :image_dir image_dir}]
    ;; Rotate images

    ;; Using reduce this way is like run! with an (ordinal) index
    (reduce #(gen-core page-data %2 %1) 1 file-list)))


(defn auto_gen_1 []
  (let [page_pk (:page_pk @params)
        {:keys [site_path image_dir]} (jdbc/execute-one!
                                       ds-opts
                                       ["select site_path, image_dir from page where page_pk=?" page_pk])
        sort-fn (fn [xx]
                  (Integer. (nth (re-matches #"(?i)(?:.*\/)*.*_(\d+)\.jp.*g" xx) 1 "0")))
        filter-fn (fn [xx] (re-find #"(?i).*_\d+\.jp.*g" xx))
        file-list (->> (.list (io/file (format "%s/%s/images/%s" (:export-path @config) site_path image_dir)))
                       (filter filter-fn)
                       (sort-by sort-fn))
        page-data {:page_pk page_pk :site_path site_path :image_dir image_dir}]
    ;; Using reduce this way is like run! with an (ordinal) index
    (reduce #(gen-core page-data %2 %1) 1 file-list)))

;; 2024-11-07 auto_gen_1 has issues. Image file names must be in a certain format, and must contain a number.
;; The number is used to order the files in sequence. That has often gone wrong, and ends up requiring manual
;; correction by setting the page order. Therefore, create a new auto_gen, above.

;; Create a file-list that only contains good jpeg files, in order of their numeric suffix. Send that list to
;; gen-core to resize images, gather necessary bookkeeping data, and insert a content item into the db for each
;; image.

(defn auto_gen_1 []
  (let [page_pk (:page_pk @params)
        {:keys [site_path image_dir]} (jdbc/execute-one!
                                       ds-opts
                                       ["select site_path, image_dir from page where page_pk=?" page_pk])
        sort-fn (fn [xx]
                  (Integer. (nth (re-matches #"(?i)(?:.*\/)*.*_(\d+)\.jp.*g" xx) 1 "0")))
        filter-fn (fn [xx] (re-find #"(?i).*_\d+\.jp.*g" xx))
        file-list (->> (.list (io/file (format "%s/%s/images/%s" (:export-path @config) site_path image_dir)))
                       (filter filter-fn)
                       (sort-by sort-fn))
        page-data {:page_pk page_pk :site_path site_path :image_dir image_dir}]
    ;; Using reduce this way is like run! with an (ordinal) index
    (reduce #(gen-core page-data %2 %1) 1 file-list)))

;; Quick description of v5 state transition table format.
(comment
  ;; This sort of describes the map of lists of lists that is the state table.
  {:starting-default-state
   [[:some-key some-fn-symbol :other-state]
    [:true fn-symbol nil]
    [:some-other-key other-fn-symbol nil]
    [:true fn-symbol nil]]
   :other-state
   [[:true site-effect-fn-b nil]
    [:true render-html-change-state nil]]}
  )

(comment
  (machine.util/verify-table table)
  (machine.util/check-table table)
  (machine.util/check-infinite :page_search table)
  )

;; Default is page_search. (How do you know that? Hard coded in core.clj?)
(def table
  {:page_search
   [[:edit nil :edit_page]
    [:delete nil :ask_delete_page]
    [:insert nil :edit_new_page]
    [:item nil :item_search]
    [:site_gen nil :site_gen]
    [:true page_search nil]]

   :site_gen
   [[:true site_gen nil]
    [:true page_search nil]]

   :edit_page
   [[:save (fn [] (save_page) (page_search)) nil]
    [:continue (fn [] (save_page) (edit_page)) nil]
    [:edit edit_page nil]]

   :item_search
   [[:edit nil :edit_item]
    [:page_gen page_gen nil]
    [:auto_gen auto_gen nil]
    [:true item_search nil]]

   :edit_item
   [[:save nil :save_item]
    [:continue nil :save_item_continue]
    [:next nil :edit_next]
    [:true edit_item nil]]

   :edit_next
   [[:true save_item nil]
    [:true next_item nil]
    [:true edit_item nil]] ;; Assumes we have a good con_pk

   :save_item
   [[:true save_item nil]
    [:true item_search nil]]

   :save_item_continue
   [[:true save_item nil]
    [:true edit_item nil]]

   :edit_new_page
   [[:save (fn [] (insert_page) (page_search)) nil]
    [:continue (fn [] (insert_page) (edit_page)) nil]
    [:insert edit_new_page nil]]
   
   :ask_delete_page
   [[:confirm nil :delete_page]
    [:true page_search nil]]

   :delete_page
   [[:true delete_page nil]
    [:true page_search nil]]
   })


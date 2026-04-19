(ns build
  (:require [clojure.tools.build.api :as bb]))

;; The namespace below could be net.clojars.userid where userid is your unique userid?
;; Or just make up something local.

;; namespace/libname creates ./target/libname-0.1.0-standalone.jar
;; e.g. ./target/porg-0.1.0-standalone.jar 

(def lib 'twl/porg) 
(def version "0.1.0") ;; What? "or source from file, etc"
(def class-dir "target/classes")
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))

;; delay to defer side effects (artifact downloads)
(def basis (delay (bb/create-basis {:project "deps.edn"})))

(defn clean [_]
  (bb/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (bb/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (bb/compile-clj {:basis @basis
                  :ns-compile '[porg.core]
                  :class-dir class-dir})
  (bb/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'porg.core}))

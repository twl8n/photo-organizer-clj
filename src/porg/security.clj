(ns porg.security
  (:import (java.security MessageDigest)))

(defn gensalt
  "generate random salt n characters"
  [n] 
  (let [charseq (map char (concat
			   (range 48 58)     ; 0-9
			   (range 97 123)))] ; 0-z
    (apply str
	   (take n
		 (repeatedly #(rand-nth charseq))))))

;;core digesting algorithm. Hash salt+password then iterate on result
(defn digester
  "Create digest by hasing salt and password, then interate."
  [hasher salt pw-clear iter]
  (letfn [(hashme [hv] ;;iterate to increase work factor
		  (letfn [(oneround [hv]
				    (do (.reset hasher)
					(.digest hasher hv)))]
		    (nth (iterate oneround hv) iter)))]
      (.reset hasher)
      (.update hasher (.getBytes salt))
      (.update hasher (.getBytes pw-clear))
      (hashme (.digest hasher))))

(defn b36
  "Convert bytes to base36 characters."
  [hbytes] (.toString (BigInteger. 1 hbytes) 36))

(defn pw-digest
  [hashalg saltlen iterations]
  (let [jhash (MessageDigest/getInstance hashalg)]
    (fn [pw-clear]
	       (let [salt (gensalt saltlen)
		     hashout (digester jhash salt pw-clear iterations)]
	         (str salt (b36 hashout))))))

(defn pw-verify 
  [hashalg saltlen iterations]
  (let [jhash (MessageDigest/getInstance hashalg)]
    (fn [pw-clear pw-protected]
      (let [salt (apply str (take saltlen pw-protected))
	    hashout (digester jhash salt pw-clear iterations)]
	(= pw-protected
	   (str salt (b36 hashout)))))))

(comment
  (def xxd ((pw-digest "SHA-256" 16 10000) "orig"))
  ((pw-verify "SHA-256" 16 10000) "orig" xxd)
  ((pw-verify "SHA-256" 16 10000) "fake" xxd)
  
  (.toString (java.util.UUID/randomUUID))
  (str/trim (:out (shell/sh "uuidgen")))

  )

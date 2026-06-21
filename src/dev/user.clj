(ns user
  (:require [ncbi-api-client.core :as ncbi]
            [ncbi-api-client.datafy :as d]
            [ncbi-api-client.package :as pkg]
            [clojure.datafy :refer [datafy nav]]
            [clojure.string :as str]
            [martian.core :as martian]))

(defn find-gene-in-annotations
  "Find a gene by symbol in an assembly's annotations."
  [client accession symbol]
  (->> (ncbi/annotations client accession)
       (filter #(= symbol (:symbol %)))
       first))

(defn get-gene-fasta
  "Download gene FASTA sequences by gene ID."
  [client gene-id]
  (let [pkg (ncbi/download-gene-package client gene-id
                                        {:include-annotations [:fasta-gene]})]
    (nav (datafy pkg) :ncbi.nav/gene-fasta :deferred)))

(defn compare-sequences
  "Compare two nucleotide sequences, returning identity and differences."
  [seq1 seq2]
  (let [s1 (:sequence seq1)
        s2 (:sequence seq2)
        len1 (count s1)
        len2 (count s2)
        min-len (min len1 len2)
        diffs (keep-indexed
               (fn [i [a b]] (when (not= a b) {:pos i :a a :b b}))
               (map vector (take min-len s1) (take min-len s2)))]
    {:acc-a (:acc seq1)
     :acc-b (:acc seq2)
     :len-a len1
     :len-b len2
     :same-length? (= len1 len2)
     :mismatches (count diffs)
     :identity (format "%.1f%%" (* 100.0 (/ (- min-len (count diffs)) min-len)))
     :differences (vec diffs)}))

(comment
  ;; === Setup ===
  (def client (ncbi/connect))

  ;; === Taxonomy -> Assemblies ===
  (def ecoli (first (ncbi/taxonomy client ["562"])))
  {:tax_id (:tax_id ecoli)
   :name   (get-in ecoli [:current_scientific_name :name])
   :rank   (:rank ecoli)}

  (def ecoli-assemblies (nav (datafy ecoli) :ncbi.nav/assemblies :deferred))
  (mapv #(hash-map :accession (:accession %)
                   :name (get-in % [:assembly_info :assembly_name])
                   :level (get-in % [:assembly_info :assembly_level]))
        (take 5 ecoli-assemblies))

  ;; === Find recA across assemblies ===
  ;; K-12 (MG1655) and O157:H7 Sakai both have recA in their annotations
  (def k12-reca (find-gene-in-annotations client "GCF_000005845.2" "recA"))
  ;; => {:gene_id "947170", :symbol "recA", ...}

  (def o157-reca (find-gene-in-annotations client "GCF_000008865.2" "recA"))
  ;; => {:gene_id "914722", :symbol "recA", ...}

  ;; === Download FASTA via package nav ===
  ;; Use vary-meta to request annotation types, then nav to :ncbi.nav/package
  (def reca-gene (first (ncbi/gene client ["947170"])))
  (def reca-gene+ (vary-meta reca-gene assoc
                             :ncbi/include-annotations [:fasta-gene]))
  (def reca-pkg (nav (datafy reca-gene+) :ncbi.nav/package :deferred))
  (keys (datafy reca-pkg))
  ;; => (:catalog :ncbi.nav/gene-fasta :ncbi.nav/data-report)

  (def reca-seqs (nav (datafy reca-pkg) :ncbi.nav/gene-fasta :deferred))
  ;; => [{:acc "NC_000913.3:c2823769-2822708", :sequence "ATGGC...", ...}]

  ;; === Or use the direct helper ===
  (def k12-fasta (first (get-gene-fasta client "947170")))
  (def o157-fasta (first (get-gene-fasta client "914722")))

  ;; === Compare recA across strains ===
  (compare-sequences k12-fasta o157-fasta)
  ;; => {:same-length? true, :identity "99.7%", :mismatches 3,
  ;;     :differences [{:pos 231, :a \C, :b \T}
  ;;                   {:pos 242, :a \C, :b \T}
  ;;                   {:pos 917, :a \G, :b \A}]}

  )

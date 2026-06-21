(ns user
  (:require [ncbi-api-client.core :as ncbi]
            [ncbi-api-client.datafy :as d]
            [ncbi-api-client.package :as pkg]
            [clojure.datafy :refer [datafy nav]]
            [clojure.java.shell :refer [sh]]
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

(defn seqs->fasta
  "Convert a sequence of maps with :acc, :description, :sequence to FASTA format."
  [seqs]
  (str/join "\n" (mapcat (fn [s] [(str ">" (:acc s) " " (:description s))
                                  (:sequence s)])
                         seqs)))

(def ^:private conda-dir
  (str (System/getProperty "user.home") "/miniforge3"))

(def ^:private default-conda-env "biotools")

(defn- conda-sh
  "Run a command inside a conda environment. Returns the sh result map."
  [conda-env & args]
  (sh "bash" "-c"
      (str "eval \"$(" conda-dir "/bin/conda shell.bash hook)\" && "
           "conda activate " conda-env " && "
           (str/join " " args))))

(defn run-mafft
  "Align sequences using MAFFT. Takes a collection of sequence maps,
   returns the aligned FASTA as a string."
  [seqs & [{:keys [conda-env] :or {conda-env "biotools"}}]]
  (let [input-file (java.io.File/createTempFile "mafft-in" ".fasta")]
    (try
      (spit input-file (seqs->fasta seqs))
      (let [result (conda-sh conda-env "mafft" "--auto" (.getAbsolutePath input-file))]
        (if (zero? (:exit result))
          (:out result)
          (throw (ex-info "MAFFT failed" {:stderr (:err result)}))))
      (finally
        (.delete input-file)))))

(defn get-assembly-fasta
  "Download genome FASTA for an assembly accession. Returns sequence maps."
  [client accession]
  (let [pkg (ncbi/download-assembly-package client accession
                                            {:include-annotations [:genome-fasta]})]
    (nav (datafy pkg) :ncbi.nav/gene-fasta :deferred)))

(defn run-mash-dist
  "Screen a FASTA file against a Mash sketch database.
   Returns a sorted vector of hit maps."
  [fasta-path sketch-path & [{:keys [max-dist conda-env]
                               :or {max-dist 0.05
                                    conda-env "biotools"}}]]
  (let [result (conda-sh conda-env
                         "mash" "dist"
                         sketch-path fasta-path
                         "-v" (str max-dist))]
    (if (zero? (:exit result))
      (->> (str/split-lines (:out result))
           (remove str/blank?)
           (mapv (fn [line]
                   (let [[ref query dist p-val hashes] (str/split line #"\t")]
                     {:reference ref
                      :distance (Double/parseDouble dist)
                      :p-value (Double/parseDouble p-val)
                      :matching-hashes hashes})))
           (sort-by :distance))
      (throw (ex-info "Mash failed" {:stderr (:err result)})))))

(defn screen-assembly
  "Download an assembly and screen it against a Mash sketch database.
   Returns sorted hits."
  [client accession sketch-path & [opts]]
  (let [seqs (get-assembly-fasta client accession)
        fasta-file (java.io.File/createTempFile "mash-query" ".fasta")]
    (try
      (spit fasta-file (seqs->fasta seqs))
      (run-mash-dist (.getAbsolutePath fasta-file) sketch-path opts)
      (finally
        (.delete fasta-file)))))

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

  ;; === Align with MAFFT ===
  (def aligned (run-mafft [k12-fasta o157-fasta]))
  (println aligned)

  ;; === Full pipeline: fetch + align ===
  (let [gene-ids ["947170" "914722"]
        seqs (mapcat #(do (Thread/sleep 1500)
                          (get-gene-fasta client %))
                     gene-ids)]
    (spit "/tmp/recA-aligned.fasta" (run-mafft seqs))
    (println "Aligned" (count seqs) "sequences"))

  ;; === Mash: screen assembly against RefSeq ===
  (def refseq-sketch "data/RefSeqSketches_227.msh")

  ;; Download assembly, write FASTA, screen against RefSeq — all in one call
  (def o157-hits (screen-assembly client "GCF_000008865.2" refseq-sketch))
  (take 10 o157-hits)
  ;; => [{:reference "Escherichia_coli_GCF_000008865.2.fasta", :distance 0.0, ...}
  ;;     {:reference "Shigella_dysenteriae_GCF_022354085.1.fasta", :distance 0.017, ...}
  ;;     {:reference "Escherichia_coli_GCF_000005845.2.fasta", :distance 0.023, ...}
  ;;     ...]

  ;; Or step by step:
  (def asm-seqs (get-assembly-fasta client "GCF_000008865.2"))
  (mapv #(hash-map :acc (:acc %) :length (count (:sequence %))) asm-seqs)
  ;; => [{:acc "NC_002695.2", :length 5498578}   ; chromosome
  ;;     {:acc "NC_002127.1", :length 3306}       ; pO157 plasmid
  ;;     {:acc "NC_002128.1", :length 92721}]     ; pOSAK1 plasmid

  (spit "/tmp/o157-sakai.fasta" (seqs->fasta asm-seqs))
  (def hits (run-mash-dist "/tmp/o157-sakai.fasta" refseq-sketch))
  (take 5 hits)

  )

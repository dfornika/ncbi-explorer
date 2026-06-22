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

  ;; === E-utilities: search -> bridge -> Datasets ===
  ;; ncbi/search wraps esearch+esummary and tags results with datafy/nav metadata.
  ;; datafy on a result lazily discovers available cross-database links.
  ;; nav :ncbi.nav/datasets-entity bridges from eutils into the Datasets entity graph.

  ;; List available Entrez databases
  (ncbi/einfo client)
  ;; => ["pubmed" "protein" "nuccore" "gene" "assembly" "taxonomy" ...]

  ;; Search for the SARS-CoV-2 spike gene using Entrez field-tagged query
  (def spike-results (ncbi/search client "gene" "S[gene] AND txid2697049[orgn]" {:retmax 5}))
  (meta spike-results)
  ;; => {:ncbi/total-count 48, :ncbi/retmax 5, ...}

  (mapv (fn [r] {:uid (:uid r)
                 :name (:name r)
                 :description (:description r)
                 :organism (get-in r [:organism :scientificname])})
        spike-results)
  ;; => [{:uid "43740568", :name "S", :description "surface glycoprotein",
  ;;      :organism "Severe acute respiratory syndrome coronavirus 2"} ...]

  ;; Discover available links via datafy
  (def spike-hit (first spike-results))
  (filterv #(or (= (namespace %) "ncbi.elink") (= (namespace %) "ncbi.nav"))
           (keys (datafy spike-hit)))
  ;; => [:ncbi.elink/gene_pubmed :ncbi.elink/gene_protein_refseq
  ;;     :ncbi.elink/gene_taxonomy :ncbi.elink/gene_nuccore
  ;;     :ncbi.nav/datasets-entity ...]

  ;; Bridge into Datasets: get the full gene entity
  (def spike-gene (nav (datafy spike-hit) :ncbi.nav/datasets-entity :deferred))
  {:gene_id (:gene_id spike-gene)
   :symbol (:symbol spike-gene)
   :description (:description spike-gene)
   :type (:type spike-gene)}
  ;; => {:gene_id "43740568", :symbol "S", :description "surface glycoprotein",
  ;;     :type "PROTEIN_CODING"}

  ;; Follow cross-database links: gene -> PubMed articles
  (def spike-pubs (nav (datafy spike-hit) :ncbi.elink/gene_pubmed :deferred))
  {:total (get (meta spike-pubs) :ncbi.elink/total-count)
   :returned (count spike-pubs)}
  ;; => {:total 1372, :returned 200}

  (mapv (fn [p] {:title (:title p) :source (:source p) :pubdate (:pubdate p)})
        (take 3 spike-pubs))
  ;; => [{:title "SARS-CoV-2 variant spike and accessory gene mutations alter pathogenesis.",
  ;;      :source "Proc Natl Acad Sci U S A", :pubdate "2022 Sep 13"} ...]

  ;; Follow cross-database links: gene -> RefSeq proteins
  (def spike-proteins (nav (datafy spike-hit) :ncbi.elink/gene_protein_refseq :deferred))
  (mapv (fn [p] {:accession (:accessionversion p) :title (:title p)}) spike-proteins)
  ;; => [{:accession "YP_009724390.1",
  ;;      :title "surface glycoprotein [Severe acute respiratory syndrome coronavirus 2]"}]

  ;; === Full pipeline: text search -> bridge -> FASTA download ===
  (let [results (ncbi/search client "gene" "S[gene] AND txid2697049[orgn]" {:retmax 1})
        hit (first results)
        gene-entity (nav (datafy hit) :ncbi.nav/datasets-entity :deferred)
        fasta (get-gene-fasta client (:gene_id gene-entity))]
    {:search-uid (:uid hit)
     :gene-id (:gene_id gene-entity)
     :symbol (:symbol gene-entity)
     :fasta-count (count fasta)
     :fasta-length (count (:sequence (first fasta)))
     :fasta-acc (:acc (first fasta))})
  ;; => {:search-uid "43740568", :gene-id "43740568", :symbol "S",
  ;;     :fasta-count 1, :fasta-length 3822, :fasta-acc "NC_045512.2:21563-25384"}

  ;; === Virus genome exploration ===
  ;; Taxonomy structure differs for viruses: :classification instead of :lineage/:rank

  (def sars2 (first (ncbi/taxonomy client ["2697049"])))
  {:name (get-in sars2 [:current_scientific_name :name])
   :group (:group_name sars2)
   :moltype (:genomic_moltype sars2)
   :family (get-in sars2 [:classification :family :name])
   :genus (get-in sars2 [:classification :genus :name])}
  ;; => {:name "Severe acute respiratory syndrome coronavirus 2",
  ;;     :group "viruses", :moltype "ssRNA(+)",
  ;;     :family "Coronaviridae", :genus "Betacoronavirus"}

  ;; All SARS-CoV-2 genes from the reference genome
  (def sars2-annots (ncbi/annotations client "GCF_009858895.2"))
  (mapv (fn [a] {:symbol (:symbol a) :gene_id (:gene_id a)}) sars2-annots)
  ;; => [{:symbol "ORF1ab", :gene_id "43740578"}
  ;;     {:symbol "S", :gene_id "43740568"}
  ;;     {:symbol "E", :gene_id "43740570"}
  ;;     {:symbol "M", :gene_id "43740571"}
  ;;     {:symbol "N", :gene_id "43740575"} ...]

  ;; === Three-way coronavirus spike gene comparison ===
  ;; SARS-CoV-2, SARS-CoV-1, and MERS-CoV

  (def sars2-spike (first (get-gene-fasta client "43740568")))  ; 3822 bp
  (def sars1-spike (first (get-gene-fasta client "1489668")))   ; 3768 bp
  (def mers-spike  (first (get-gene-fasta client "14254594")))  ; 4062 bp

  (def spike-alignment (run-mafft [sars2-spike sars1-spike mers-spike]))
  (spit "/tmp/coronavirus-spike-aligned.fasta" spike-alignment)

  ;; Pairwise identity from three-way alignment:
  ;; SARS-CoV-2 vs SARS-CoV-1: 64.8%
  ;; SARS-CoV-2 vs MERS-CoV:   42.5%
  ;; SARS-CoV-1 vs MERS-CoV:   42.1%

  )

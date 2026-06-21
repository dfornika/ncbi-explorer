(ns user
  (:require [ncbi-api-client.core :as ncbi]
            [ncbi-api-client.datafy :as d]
            [clojure.datafy :refer [datafy nav]]
            [martian.core :as martian]
            [hato.client :as hc]))

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

  ;; === Annotations: find recA on K-12 ===
  (def k12-anns (ncbi/annotations client "GCF_000005845.2"))
  (->> k12-anns
       (filter #(= "recA" (:symbol %)))
       first
       (#(select-keys % [:gene_id :symbol :name :gene_type])))
  ;; => {:gene_id "947170", :symbol "recA", :name "DNA recombination/repair protein RecA", :gene_type "protein-coding"}

  ;; === Gene -> Products -> Protein accession ===
  (def reca-gene (first (ncbi/gene client ["947170"])))
  (def reca-products (nav (datafy reca-gene) :ncbi.nav/products :deferred))
  (let [p (first reca-products)]
    (mapv #(select-keys % [:accession_version :name :type :length :protein])
          (:transcripts p)))
  ;; => [{:protein {:accession_version "NP_417179.1", ...}}]

  ;; === Download gene FASTA via martian (broken: returns zip as string) ===
  (def dl-result (martian/response-for client :download-gene-package
                                       {:gene-ids [947170]
                                        :include-annotation-type ["FASTA_GENE"]}))
  {:status (:status dl-result) :content-type (:content-type dl-result)}
  ;; => {:status 200, :content-type :application/zip}
  ;; Body is a String — binary zip data gets corrupted.

  ;; === Workaround: download with hato directly, :as :byte-array ===
  (let [resp (hc/get "https://api.ncbi.nlm.nih.gov/datasets/v2/gene/id/947170/download"
                     {:as :byte-array
                      :query-params {"include_annotation_type" "FASTA_GENE"}})]
    (with-open [os (java.io.FileOutputStream. "/tmp/reca-gene.zip")]
      (.write os ^bytes (:body resp)))
    {:status (:status resp) :size (count (:body resp))})
  ;; => {:status 200, :size 4061}
  ;; unzip -l shows: ncbi_dataset/data/gene.fna ✓

  ;; === Also tried: :download-prokaryote-gene-package (by protein accession) ===
  ;; This endpoint returned a zip with catalog but NO actual FASTA files.
  ;; :download-gene-package (by gene ID) is the one that works.

  )

# ncbi-api-client-clj: feedback from ncbi-explorer

## What we're building

An exploratory app ("ncbi-explorer") that uses the ncbi-api-client-clj library
to grab the same gene (e.g. *recA*) from multiple *E. coli* assemblies and
align the sequences. The goal is to use datafy/nav to traverse from taxonomy
through assemblies, annotations, and genes all the way down to FASTA sequence
data.

## The workflow we tested

1. `ncbi/taxonomy` → fetch *E. coli* (taxon 562)
2. `nav` via `:ncbi.nav/assemblies` → get complete genome assemblies
3. `ncbi/annotations` on an assembly → find *recA* by symbol → get gene ID
4. `ncbi/gene` → fetch full gene record → `nav` to `:ncbi.nav/products` → get
   protein accession
5. Download gene FASTA via the `:download-gene-package` API endpoint (by gene
   ID, with `include-annotation-type` = `FASTA_GENE`)
6. Unzip → extract `ncbi_dataset/data/gene.fna`

Steps 1–4 work great via the library. Steps 5–6 are where we had to drop down
to raw hato calls and manual zip handling.

## Issues found

### 1. Binary download responses are corrupted

The martian/hato pipeline returns zip response bodies as `String`, which
corrupts the binary data. The download endpoints
(`:download-gene-package`, `:download-prokaryote-gene-package`, etc.) all
return `application/zip`.

**Workaround:** We bypassed martian and called hato directly with
`:as :byte-array`:

```clojure
(hc/get "https://api.ncbi.nlm.nih.gov/datasets/v2/gene/id/947170/download"
        {:as :byte-array
         :query-params {"include_annotation_type" "FASTA_GENE"}})
```

**Suggestion:** The library could detect `application/zip` (or any binary
content-type) in the response and use `:as :byte-array` automatically, or
provide a download function that handles this.

### 2. No datafy/nav support for download endpoints

The current nav graph ends at gene/assembly/annotation metadata. There's no
way to navigate from a gene or assembly entity into downloaded sequence data.

**Suggestion:** Extend the nav graph so that entities with downloadable data
expose nav keys like `:ncbi.nav/fasta` or `:ncbi.nav/download`. The nav
implementation could:

- Call the appropriate download endpoint with `:as :byte-array`
- Unzip the response in memory (via `java.util.zip.ZipInputStream`)
- Parse the FASTA content into a Clojure data structure
- Return entities that are themselves datafy/nav-able (e.g., a sequence
  entity you could navigate back to its assembly)

This would let the full workflow stay in the datafy/nav world:

```clojure
(def ecoli (first (ncbi/taxonomy client ["562"])))
(def assemblies (nav (datafy ecoli) :ncbi.nav/assemblies :deferred))
(def k12 (first assemblies))
(def anns (nav (datafy k12) :ncbi.nav/annotations :deferred))
(def reca (->> anns (filter #(= "recA" (:symbol %))) first))
(def reca-gene (nav (datafy reca) :ncbi.nav/gene :deferred))
;; This is the missing step — currently requires manual hato + unzip:
(def fasta (nav (datafy reca-gene) :ncbi.nav/fasta :deferred))
```

### 3. `:download-prokaryote-gene-package` returned empty results

When we used the prokaryote-specific endpoint with a RefSeq protein accession
(`NP_417179.1`), the zip contained a catalog that *listed* `gene.fna` but the
file wasn't actually included. The general `:download-gene-package` endpoint
(by gene ID) worked correctly.

This may be an NCBI API issue rather than a library issue, but worth noting
if the library ever wraps these download endpoints.

### 4. Zip file handling as a reusable concern

Since many NCBI download endpoints return zip archives with a predictable
internal structure (`ncbi_dataset/data/...`), the library could provide a
utility for working with these:

```clojure
;; Possible API sketch
(ncbi/download-gene client gene-id {:include [:fasta-gene]})
;; => {:gene.fna "ATGGCTATC..." :data-report {...} :catalog {...}}
```

Or, if keeping it lower-level:

```clojure
(ncbi/download-zip client :download-gene-package
                   {:gene-ids [947170]
                    :include-annotation-type ["FASTA_GENE"]})
;; => ZipFile-like object that's datafy-able
```

## Environment

- ncbi-api-client-clj SHA: `640393537926442a675a6634d8dcffb5a3633318`
- Clojure 1.12.5, JDK 21, hato 1.0.0, martian 0.2.3

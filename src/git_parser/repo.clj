(ns git-parser.repo
  (:require [clj-jgit.porcelain :as git]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv]
            [clj-jgit.querying :as query]
            [clj-jgit.util :as util]
            [clj-jgit.internal :refer :all])
  (:import
   [org.eclipse.jgit.diff RawTextComparator DiffFormatter DiffEntry Edit]
   [org.eclipse.jgit.patch FileHeader]
   [org.eclipse.jgit.api Git]
   [org.eclipse.jgit.util.io DisabledOutputStream]
   [java.util HashMap Date]
   [java.text SimpleDateFormat]
   [org.eclipse.jgit.revwalk RevCommit])
  (:gen-class))

(defn- diff-formatter-for-changes
  [^Git repo]
  (doto
   (DiffFormatter. DisabledOutputStream/INSTANCE)
    (.setRepository (.getRepository repo))
    (.setRepository (.getRepository repo))
    (.setDiffComparator RawTextComparator/DEFAULT)
    (.setDetectRenames false)))

(defn- change-kind
  [^DiffEntry entry]
  (let [change (.. entry getChangeType name)]
    (cond
      (= change "ADD") :add
      (= change "MODIFY") :edit
      (= change "DELETE") :delete
      (= change "COPY") :copy)))

(defn- line-changes
  [^Edit edit]
  (let [change (.. edit getType name)
        lengthB (.getLengthB edit)
        lengthA (.getLengthA edit)]
    (cond
      (= change "INSERT") {:deleted 0 :inserted lengthB}
      (= change "DELETE") {:deleted lengthA :inserted 0}
      (= change "REPLACE") {:deleted lengthA :inserted lengthB}
      (= change "EMPTY") {:deleted 0 :inserted 0})))

(defn- parse-diff-entry
  [^DiffEntry entry]
  (let [old-path (util/normalize-path (.getOldPath entry))
        new-path (util/normalize-path (.getNewPath entry))
        change-kind (change-kind entry)]
    (cond
      (= old-path new-path)   [change-kind new-path]
      (= old-path "dev/null") [change-kind new-path]
      (= new-path "dev/null") [change-kind old-path]
      :else [change-kind new-path])))

(defn calc-lines-changed
  [^FileHeader fh]
  (let [num-changes (map line-changes (.toEditList fh))]
    (if (<= (count num-changes) 1)
      (first num-changes)
      (reduce (fn [map1 map2]
                {:deleted (+ (:deleted map1) (:deleted map2))
                 :inserted (+ (:inserted map1) (:inserted map2))})
              {:deleted 0 :inserted 0} num-changes))))

(defn lines-changed
  [^DiffFormatter formatter ^DiffEntry entry change-type loc]
  (let [fh (.toFileHeader formatter entry)]
    (cond
      (= change-type :add) {:deleted 0 :inserted loc}
      (= change-type :delete) {:deleted loc :inserted 0}
      :else (calc-lines-changed fh))))

(defn lines-of-code
  [^Git repo
   directory-prefix
   file-name
   rev-commit]
  (let [full-file-name (str directory-prefix "/" file-name)]
    (git/git-checkout repo rev-commit) (if (.exists (io/as-file full-file-name))
                                         (with-open [rdr (io/reader full-file-name)]
                                           (count (line-seq rdr)))
                                         0)))

(defn changed-files-between-commits-with-stats
  "List of files changed between two RevCommit objects"
  [^Git repo
   directory-prefix
   ^RevCommit old-rev-commit
   ^RevCommit new-rev-commit]
  (let [df ^DiffFormatter (diff-formatter-for-changes repo)
        entries (.scan df old-rev-commit new-rev-commit)]
    (map (fn [entry]
           (let [parsed-entry (parse-diff-entry entry)
                 changed-file (last parsed-entry)
                 change-type (first parsed-entry)
                 loc (lines-of-code repo directory-prefix changed-file (.getName new-rev-commit))]
             {:file-name changed-file :loc loc :lines-changed (lines-changed df entry change-type loc)})) entries)))

(defn changed-files-with-stats
  "List of files changed in RevCommit object"
  [^Git repo
   directory-prefix
   ^RevCommit rev-commit]
  (let [parent (first (.getParents rev-commit))]
    (changed-files-between-commits-with-stats repo directory-prefix parent rev-commit)))

(def date-formatter (new SimpleDateFormat "yyyy-MM-dd'T'HH:mm:ss"))

(defn commit-info-without-branches-with-stats
  [^Git repo
   directory-prefix
   ^RevCommit rev-commit]
  (let [ident (.getAuthorIdent rev-commit)
        time (-> (.getCommitTime rev-commit) (* 1000) Date.)
        message (-> (.getFullMessage rev-commit) str string/trim)
        changed-files (changed-files-with-stats repo directory-prefix rev-commit)]
    (println (.format date-formatter time))
    {:id (.getName rev-commit)
     :author (.getName ident)
     :email (.getEmailAddress ident)
     :time (.format date-formatter time)
     :message message
     :changed_files changed-files
     :raw rev-commit}))

(defn to-csv-rows
  [commit-stats]
  (let [files (:changed_files commit-stats)
        id (:id commit-stats)
        author (:author commit-stats)
        time (:time commit-stats)
        message (:message commit-stats)
        rev-commit (:raw commit-stats)]
    (println id)
    (map (fn [file]
           [author (:file-name file) id time message (:loc file) (:deleted (:lines-changed file)) (:inserted (:lines-changed file))]) files)))

(defn grab-rev-list
  [^Git my-repo
   last-rev]
  (let [rev-commits (query/rev-list my-repo)]
    (if last-rev
      (take-while #(not (string/starts-with? (.getName %) last-rev)) rev-commits)
      rev-commits)))

(def headers ['("author" "file" "commit" "time" "message" "loc" "churn" "add")])
(def csv-file-name "out.csv")

(defn all-commit-info-with-stats
  [^Git my-repo
   directory-prefix
   last-rev]
  (let [rev-commits (grab-rev-list my-repo last-rev)
        commit-stats (map #(commit-info-without-branches-with-stats my-repo directory-prefix %) rev-commits)
        to-csv (lazy-seq (apply concat (map #(to-csv-rows %) commit-stats)))]
    (with-open [writer (io/writer csv-file-name)]
      (csv/write-csv writer headers)
      (->> to-csv
           (csv/write-csv writer)))))

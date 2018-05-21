(ns git-parser.core
  (:require [clj-jgit.porcelain :as git]
            [clojure.string :as string]
            [git-parser.repo :as repo]
            [clojure.tools.cli :as cli]
            [clojure.java.io :as io])
  (:gen-class))

(defn clone-repo [repo-url ssh]
  (let [sub-dir (last (string/split repo-url #"/"))
        sub-dir-file (and sub-dir (.exists (io/as-file sub-dir)) sub-dir)]
    (if sub-dir-file
      (git/load-repo sub-dir-file)
      (:repo (if ssh
               (git/with-identity {:name ssh}
                 (git/git-clone-full repo-url))
               (git/git-clone-full repo-url))))))

(def cli-options
  [["-r" "--repo REPO" "Git Repository URL"]
   ["-d" "--directory REPO" "Already cloned repository directory"]
   ["-c" "--commit-rev COMMIT" "Which revision at which to end, defaults to first commit. Rev parsing starts at the latest revision"]
   ["-s" "--ssh SSH" "SSH Identity path if necessary for clone"]
   ["-h" "--help"]])

(defn- usage [options-summary]
  (->> ["This is git-parse for parsing git revision data into a analyazable format"
        "Version: 1.0-SNAPSHOT"
        ""
        "Usage: program-name -l log-file [options]"
        ""
        "Options:"
        options-summary
        "Please refer to the manual page for more information."]
       (string/join \newline)))

(defn- error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn- exit [status msg]
  (println msg)
  (System/exit status))

(defn -main
  [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)]
    (cond
      (:help options) (exit 0 (usage summary))
      errors (exit 1 (error-msg errors)))
    :else
    (let [directory (:directory options)
          repo-url (:repo options)
          last-rev (:commit-rev options)
          sub-dir (and repo-url (last (string/split repo-url #"/")))
          my-repo (if directory
                    (git/load-repo directory)
                    (clone-repo repo-url (:ssh options)))]

      (repo/all-commit-info-with-stats my-repo (or directory sub-dir) last-rev))))

(defproject git-parser "0.1.0-SNAPSHOT"
  :description "A simple parser for transforming git commit logs into usable data"
  :url ""
  :plugins [[lein-cljfmt "0.5.7"]]
  :license {:name "MIT "
            :url "MIT License URL"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/tools.cli "0.3.7"]
                 [clj-jgit "0.8.10"]
                 [org.eclipse.jgit/org.eclipse.jgit "4.11.0.201803080745-r" :exclusions [com.jcraft/jsch]]
                 [org.clojure/data.csv "0.1.3"]]
  :main git-parser.core
  :aot [git-parser.core]
  :jvm-opts ["-Xmx5g" "-Djava.awt.headless=true" "-Xss512M"])

(ns shadow.build.npm
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [shadow.build.json-order-preserving :as json-order]
            [cljs.compiler :as cljs-comp]
            [shadow.jvm-log :as log]
            [shadow.build.resource :as rc]
            [shadow.build.log :as cljs-log]
            [shadow.cljs.util :as util :refer (reduce-> reduce-kv->)]
            [shadow.build.data :as data]
            [clojure.edn :as edn]
            [shadow.debug :as dbg])
  (:import (java.io File)
           (com.google.javascript.jscomp SourceFile CompilerOptions CompilerOptions$LanguageMode)
           (com.google.javascript.jscomp.deps ModuleNames)
           (shadow.build.closure JsInspector)
           [java.nio.file Path]))

(set! *warn-on-reflection* true)

;; used in every resource :cache-key to make sure it invalidates when shadow-cljs updates
(def NPM-CACHE-KEY
  (data/sha1-url (io/resource "shadow/build/npm.clj")))

(def CLOSURE-CACHE-KEY
  (data/sha1-url (io/resource "shadow/build/closure.clj")))

(defn service? [x]
  (and (map? x) (::service x)))

(defn maybe-kw-as-string [x]
  (cond
    (string? x)
    x

    (keyword? x)
    (name x)

    (symbol? x)
    (name x)

    :else
    nil))

(defn collect-npm-deps-from-classpath []
  (->> (-> (Thread/currentThread)
           (.getContextClassLoader)
           (.getResources "deps.cljs")
           (enumeration-seq))
       (map slurp)
       (map edn/read-string)
       (mapcat #(-> % (get :npm-deps) (keys)))
       (map maybe-kw-as-string)
       (remove nil?)
       (set)
       ))

(comment
  (collect-npm-deps-from-classpath))

(defn absolute-file
  ".getCanonicalFile resolves links but we just want to replace . and .."
  ^File [^File x]
  (-> x
      (.toPath)
      (.toAbsolutePath)
      (.normalize)
      (.toFile)))

;; not allowed to mix conditions with paths, so just checking first is enough
;; to figure out if this is a condition map or paths
(defn path-map? [m]
  (str/starts-with? (first (keys m)) "."))

(defn merge-package-exports* [package exports]
  (cond
    (or (string? exports) (vector? exports))
    (assoc-in package [:exports-exact "."] exports)

    (or (not (map? exports))
        (empty? exports))
    (do (log/warn ::invalid-exports {:package-dir (:package-dir package)})
        package)

    (path-map? exports)
    (reduce-kv
      (fn [package path match]
        (if (str/ends-with? path "/")
          (update package :exports-prefix util/vec-conj
            {:prefix path
             :match match})

          ;; wildcards are allowed to appear at the end and with additional suffix
          ;; https://nodejs.org/api/packages.html#subpath-patterns
          ;; although webpack makes it appear its only allowed at the end
          ;; https://webpack.js.org/guides/package-exports/
          ;; leave :suffix nil if at the end, so later we know if that needs to be checked
          (if-some [star-idx (str/index-of path "*")]
            (update package :exports-wildcard util/vec-conj
              ;; strip * here, so we don't have to do it again later
              {:prefix (subs path 0 star-idx)
               :suffix (when (not= star-idx (dec (count path)))
                         (subs path (inc star-idx)))
               :match match})

            ;; just-a-path
            (assoc-in package [:exports-exact path] match))))
      package
      exports)

    :condition-map-at-root
    (assoc-in package [:exports-exact "."] exports)
    ))

(defn sort-by-prefix-length [coll]
  (sort-by #(- 0 (count (:prefix %))) coll))

(defn merge-package-exports [p1 exports]
  (let [p2 (merge-package-exports* p1 exports)]
    (if (identical? p1 p2)
      p1
      ;; trying to avoid duplicating some code in the above fn
      (-> p2
          ;; store a single key, as a shortcut, so we don't have to check for 3 keys later
          (assoc :exports true)
          ;; want the longest matches first later, so just sorting here
          (update :exports-prefix sort-by-prefix-length)
          (update :exports-wildcard sort-by-prefix-length)
          ))))

(defn read-package-json
  "this caches the contents package.json files since we may access them quite often when resolving deps"
  [{:keys [index-ref] :as state} ^File file]
  (let [last-modified (.lastModified file)

        cached
        (get-in @index-ref [:package-json-cache file])]

    (if (and cached (= last-modified (:last-modified cached)))
      (:content cached)
      (let [{:strs [dependencies name version browser exports] :as package-json}
            (-> (slurp file)
                ;; read with order preserving for objects/maps, since order is supposed to be significant for some objects
                ;; https://webpack.js.org/guides/package-exports/#notes-about-ordering
                ;; under normal circumstances these conditional maps should already be array maps
                ;; just making sure they are. who knows whats gonna happen with this mess.
                (json-order/read-str))

            package-dir
            (.getAbsoluteFile (.getParentFile file))

            content
            (-> {:package-name name
                 ;; :package-name is no longer a unique identifier with nested installs
                 ;; need a unique identifier for build reports since they get otherwise
                 ;; grouped together incorrectly. building it here since this has the most info
                 :package-id (str (.getAbsolutePath package-dir) "@" version)
                 :package-dir package-dir
                 :package-json package-json
                 :version version
                 :dependencies (into #{} (keys dependencies))}
                (cond->
                  (string? browser)
                  (assoc :browser browser)

                  ;; browser can point to a string and to an object
                  ;; since object has an entirely different meaning
                  ;; don't use it as a main
                  (map? browser)
                  (-> (assoc :browser-overrides browser)
                      (update :package-json dissoc "browser"))

                  exports
                  (merge-package-exports exports)))]

        (swap! index-ref assoc-in [:package-json-cache file] {:content content
                                                              :last-modified last-modified})
        content
        ))))

(defn find-package-json [^File file]
  (loop [root (if (.isDirectory file)
                (.getParentFile file)
                file)]
    (when root
      (let [package-json (io/file root "package.json")]
        (if (and (.exists package-json)
                 (.isFile package-json))
          package-json
          (recur (.getParentFile root))
          )))))

(defn find-package-for-file [npm file]
  (when-let [package-json-file (find-package-json file)]
    (read-package-json npm package-json-file)))

(defn test-file ^File [^File dir name]
  (when name
    (let [file
          (-> (io/file dir name)
              (absolute-file))]
      (when (.exists file)
        file))))

(defn test-file-exts ^File [npm ^File dir name]
  (let [extensions (get-in npm [:js-options :extensions])]
    (reduce
      (fn [_ ext]
        (when-let [path (test-file dir (str name ext))]
          (when (.isFile path)
            (reduced path))))
      nil
      extensions)))

(defn find-package** [npm modules-dir package-name]
  (let [package-dir (io/file modules-dir package-name)]
    (when (.exists package-dir)
      (let [package-json-file (io/file package-dir "package.json")]
        (when (.exists package-json-file)
          (read-package-json npm package-json-file))))))

(defn get-package-info [npm ^File package-json-file]
  (when (.exists package-json-file)
    (read-package-json npm package-json-file)))

(defn find-package* [{:keys [js-package-dirs] :as npm} package-name]
  ;; check all configured :js-package-dirs but only those
  ;; never automatically go up/down like node resolve does
  (reduce
    (fn [_ modules-dir]
      (when-let [pkg (find-package** npm modules-dir package-name)]
        (reduced (assoc pkg :js-package-dir modules-dir))))
    nil
    js-package-dirs))

(defn find-package [{:keys [index-ref] :as npm} package-name]
  {:pre [(string? package-name)
         (seq package-name)]}
  (or (get-in @index-ref [:packages package-name])
      (let [pkg-info (find-package* npm package-name)]
        (swap! index-ref assoc-in [:packages package-name] pkg-info)
        pkg-info)))

(defn with-npm-info [npm package rc]
  (assoc rc
    ::package package
    ;; FIXME: rewrite all uses of this so it looks at ::package instead
    :package-name (:package-name package)))

(defn is-npm-dep? [{:keys [npm-deps]} ^String require]
  (contains? npm-deps require))

(def empty-rc
  (let [ns 'shadow$empty]
    {:resource-id [::empty "shadow$empty.js"]
     :resource-name "shadow$empty.js"
     :output-name "shadow$empty.js"
     :type :js
     :cache-key [] ;; change this if this ever changes
     :last-modified 0
     :ns ns
     :provides #{ns}
     :requires #{}
     :deps '[shadow.js]
     :source ""}))

(defn maybe-convert-goog [dep]
  (if-not (str/starts-with? dep "goog:")
    dep
    (symbol (subs dep 5))))

(def asset-exts
  #{"css"
    "scss"
    "sass"
    "less"
    "png"
    "gif"
    "jpg"
    "jpeg"
    "svg"})

(defn asset-require? [require]
  (when-let [dot (str/last-index-of require ".")]
    (let [ext (str/lower-case (subs require (inc dot)))]
      (contains? asset-exts ext)
      )))

(defn disambiguate-module-name
  "the variable names chosen by closure are not unique enough
   object.assign creates the same variable as object-assign
   so this makes the name more unique to avoid the clash"
  [name]
  (let [slash-idx (str/index-of name "/")]
    (if-not slash-idx
      name
      (let [module-name (subs name 0 slash-idx)]
        (str (str/replace module-name #"\." "_DOT_")
             (subs name slash-idx))))))

(comment
  (disambiguate-module-name "object.assign/index.js")
  (disambiguate-module-name "object-assign/index.js")
  )

(defn resource-name-for-file [{:keys [^File project-dir js-package-dirs] :as npm} ^File file]
  (let [^Path abs-path
        (.toPath project-dir)

        ^Path file-path
        (.toPath file)

        ^Path node-modules-path
        (->> js-package-dirs
             (map (fn [^File modules-dir] (.toPath modules-dir)))
             (filter (fn [^Path path]
                       (.startsWith file-path path)))
             (sort-by (fn [^Path path] (.getNameCount path)))
             (reverse) ;; FIXME: pick longest match might not always be the best choice?
             (first))

        npm-file?
        (some? node-modules-path)

        _ (when-not (or npm-file? (.startsWith file-path abs-path))
            (throw (ex-info (format "files outside the project are not allowed: %s" file-path)
                     {:file file})))]

    (if npm-file?
      (->> (.relativize node-modules-path file-path)
           (str)
           (rc/normalize-name)
           (disambiguate-module-name)
           (str "node_modules/"))
      (->> (.relativize abs-path file-path)
           (str)
           (rc/normalize-name)))))

(defn get-file-info*
  "extract some basic information from a given file, does not resolve dependencies"
  [{:keys [compiler] :as npm} ^File file]
  {:pre [(service? npm)
         (util/is-file-instance? file)
         (.isAbsolute file)]}

  ;; normalize node_modules files since they may not be at the root of the project
  (let [resource-name
        (resource-name-for-file npm file)

        ns (-> (ModuleNames/fileToModuleName resource-name)
               ;; (cljs-comp/munge) ;; FIXME: the above already does basically the same, does it cover everything?
               ;; WTF node ppl ... node_modules/es5-ext/array/#/index.js
               (str/replace #"#" "_HASH_")
               (symbol))

        last-modified
        (.lastModified file)

        source
        (slurp file)

        cache-key
        [NPM-CACHE-KEY CLOSURE-CACHE-KEY (data/sha1-string source)]]

    ;; require("../package.json").version is a thing
    ;; no need to parse it since it can't have any require/import/export
    (-> (if (str/ends-with? (.getName file) ".json")
          {:resource-id [::resource resource-name]
           :resource-name resource-name
           :output-name (str ns ".js")
           :json true
           :type :js
           :file file
           :last-modified last-modified
           :cache-key cache-key
           :ns ns
           :provides #{ns}
           :requires #{}
           :source source
           :js-deps []}

          ;; FIXME: check if a .babelrc applies and then run source through babel first
          ;; that should take care of .jsx and others if I actually want to support that?
          ;; all requires are collected into
          ;; :js-requires ["foo" "bar/thing" "./baz]
          ;; all imports are collected into
          ;; :js-imports ["react"]
          (let [{:keys [js-requires js-dynamic-imports js-imports js-errors js-warnings js-invalid-requires js-language] :as info}
                (JsInspector/getFileInfoMap
                  compiler
                  ;; SourceFile/fromFile seems to leak file descriptors
                  (SourceFile/fromCode (.getAbsolutePath file) source))

                _
                (when (seq js-errors)
                  (throw (ex-info (format "errors in file: %s" (.getAbsolutePath file))
                           {:tag ::file-info-errors
                            :info {:js-errors js-errors}
                            :file file})))

                js-deps
                (->> (concat js-requires js-imports js-dynamic-imports)
                     (distinct)
                     (map maybe-convert-goog)
                     (into []))

                js-deps
                (cond-> js-deps
                  (:uses-global-buffer info)
                  (conj "buffer")
                  (:uses-global-process info)
                  (conj "process"))]

            (when (seq js-errors)
              (throw (ex-info (format "errors in file: %s" (.getAbsolutePath file))
                       {:tag ::file-info-errors
                        :info info
                        :file file})))

            ;; moment.js has require('./locale/' + name); inside a function
            ;; it shouldn't otherwise hurt though
            (when (seq js-invalid-requires)
              (log/info ::js-invalid-requires {:resource-name resource-name
                                               :requires js-invalid-requires}))

            (assoc info
              :resource-id [::resource resource-name]
              :resource-name resource-name
              ;; work around file names ending up too long on some linux systems for certain npm deps
              ;; FileNotFoundException: .shadow-cljs/builds/foo/dev/shadow-js/module$node_modules$$emotion$react$isolated_hoist_non_react_statics_do_not_use_this_in_your_code$dist$emotion_react_isolated_hoist_non_react_statics_do_not_use_this_in_your_code_browser_cjs.js (File name too long)
              :output-name
              (if (> (count resource-name) 127)
                (str "module$too_long_" (util/md5hex resource-name) ".js")
                (str ns ".js"))
              :type :js
              :file file
              :last-modified last-modified
              :cache-key cache-key
              :ns ns
              :provides #{ns}
              :requires #{}
              :source source
              :js-language js-language
              :js-deps js-deps
              :deps js-deps))))))

(defn get-file-info [{:keys [index-ref] :as npm} ^File file]
  {:pre [(service? npm)]}
  (or (get-in @index-ref [:files file])
      (let [file-info (get-file-info* npm file)]
        (swap! index-ref assoc-in [:files file] file-info)
        file-info
        )))

(defn find-package-for-require* [npm {::keys [package] :as require-from} require]
  (if (or (not require-from) (not (:allow-nested-packages (:js-options npm))))
    (find-package npm require)
    ;; node/webpack resolve rules look for nested node_modules packages
    ;; try to find those here
    (let [{:keys [js-package-dir package-dir]} package]
      (loop [^File ref-dir package-dir]
        (cond
          ;; don't go further than js-package-dir, just look up package regularly at that point
          ;; doing so by requiring without require-from, which will end up check all js-package-dirs again
          (= ref-dir js-package-dir)
          (find-package npm require)

          ;; no need to try node_modules/node_modules
          (= "node_modules" (.getName ref-dir))
          (recur (.getParentFile ref-dir))

          ;; otherwise check for nested install, which might have multiple levels, need to check all
          ;; node_modules/a/node_modules/b/node_modules/c
          ;; node_modules/a/node_modules/c
          ;; node_modules/c is then checked by find-package above again
          :check
          (let [nested-package-file (io/file ref-dir "node_modules" require "package.json")]
            (if (.exists nested-package-file)
              (when-some [pkg (read-package-json npm nested-package-file)]
                ;; although nested it inherits this from the initial package
                ;; in case of nesting two levels need to keep this to know which :js-package-dir this initially came from
                (assoc pkg :js-package-dir js-package-dir))

              (recur (.getParentFile ref-dir)))))))))

(defn find-package-for-require [npm require-from require]
  ;; finds package by require walking down from roots (done in find-package)
  ;; "a/b/c", checks a/package.json, a/b/package.json, a/b/c/package.json
  ;; first package.json wins, nested package.json may come in later when resolving in package
  (let [[start & more] (str/split require #"/")]
    (loop [path start
           more more]

      (let [pkg (find-package-for-require* npm require-from path)]
        (cond
          pkg
          (assoc pkg :match-name path)

          (not (seq more))
          nil

          :else
          (recur (str path "/" (first more)) (rest more))
          )))))

;; resolves foo/bar.js from /node_modules/package/nested/file.js in /node_modules/packages
;; returns foo/bar.js, or in cases where a parent dir is referenced ../foo/bar.js
(defn resolve-require-as-package-path [^File package-dir ^File file ^String require]
  {:pre [(.isFile file)]}
  (->> (.relativize
         (.toPath package-dir)
         (-> file (.getParentFile) (.toPath) (.resolve require)))
       (rc/normalize-name)))

;; /node_modules/foo/bar.js in /node_modules/foo returns ./bar.js
(defn as-package-rel-path [{:keys [^File package-dir] :as package} ^File file]
  (->> (.toPath file)
       (.relativize (.toPath package-dir))
       (str)
       (rc/normalize-name)
       (str "./")))

(comment
  (resolve-require-as-package-path
    (absolute-file (io/file "test-env" "pkg-a"))
    (absolute-file (io/file "test-env" "pkg-a" "nested" "thing.js"))
    "../../index.js")
  )

(defn drop-extension [s]
  (if (str/ends-with? s ".js")
    (subs s 0 (- (count s) 3))
    s))

(defn get-package-override
  [{:keys [js-options] :as npm}
   {:keys [package-name browser-overrides] :as package}
   rel-require]

  (let [package-overrides (:package-overrides js-options)]
    (when (or (and (:use-browser-overrides js-options) (seq browser-overrides))
              (seq package-overrides))

      ;; allow :js-options config to replace files in package by name
      ;; :js-options {:package-overrides {"codemirror" {"./lib/codemirror.js" "./addon/runmode/runmode.node.js"}}
      (or (get-in package-overrides [package-name rel-require])
          ;; direct map including extension
          (get browser-overrides rel-require)
          ;; and because npm is fun, also try without
          (let [without-ext (drop-extension rel-require)]
            (when (not= rel-require without-ext)
              (get browser-overrides without-ext)
              ))))))

;; returns [package file] in case a nested package.json was used overriding package
(defn find-match-in-package [npm {:keys [package-dir package-json] :as package} rel-require]
  (if (= rel-require "./")
    ;; package main, lookup entries
    (let [entries
          (->> (get-in npm [:js-options :entry-keys])
               (map #(get package-json %))
               (remove nil?)
               (into []))]

      (if (seq entries)
        (let [entry-match
              (reduce
                (fn [_ entry]
                  (when-let [match (find-match-in-package npm package entry)]
                    ;; we only want the first one in case more exist
                    (reduced match)))
                nil
                entries)]

          (when (not entry-match)
            (throw (ex-info
                     (str "package in " package-dir " specified entries but they were all missing")
                     {:tag ::missing-entries
                      :entries entries
                      :package-dir package-dir})))

          entry-match)

        ;; fallback for <package>/index.js without package.json
        (let [index (io/file package-dir "index.js")]
          (when (and (.exists index) (.isFile index))
            [package index]))))

    ;; path in package
    ;; rel-require might be ./foo
    ;; need to check ./foo.js and ./foo/package.json or ./foo/index.js
    (let [file (test-file package-dir rel-require)]
      (cond
        (nil? file)
        (when-some [match (test-file-exts npm package-dir rel-require)]
          [package match])

        (.isFile file)
        [package file]

        ;; babel-runtime has a ../core-js/symbol require
        ;; core-js/symbol is a directory
        ;; core-js/symbol.js is a file
        ;; so for each directory first test if there is file by the same name
        ;; then if there is a directory/package.json with a main entry
        ;; then if there is directory/index.js
        (.isDirectory file)
        (if-some [match (test-file-exts npm package-dir rel-require)]
          [package match]
          (let [nested-package-json (io/file file "package.json")]
            (if-not (.exists nested-package-json)
              (when-some [match (test-file-exts npm file "index")]
                [package match])
              (let [nested-package
                    (-> (read-package-json npm nested-package-json)
                        (assoc ::parent package :js-package-dir (:js-package-dir package)))]
                ;; rel-require resolved to a dir, continue with ./ from there
                (find-match-in-package npm nested-package "./")))))

        :else
        (throw
          (ex-info
            (format "found something unexpected %s for %s in %s" file rel-require package-dir)
            {:file file
             :package package
             :rel-require rel-require}))
        ))))

(declare find-resource find-resource-in-package)

(defn file-as-resource [npm package rel-require ^File file]
  (if (asset-require? (.getName file))
    ;; probable asset, parsing as JS would probably fail so don't event attempt
    (let [resource-name (resource-name-for-file npm file)
          last-mod (.lastModified file)
          ns (-> (ModuleNames/fileToModuleName resource-name)
                 (str/replace #"#" "_HASH_")
                 (symbol))]
      {:resource-id [::asset resource-name]
       :resource-name resource-name
       :output-name (util/flat-filename (str resource-name ".js"))
       :cache-key [(.getCanonicalPath file) last-mod]
       :last-modified last-mod
       :ns ns
       :asset true
       :provides #{ns}
       :requires #{}
       :deps []
       ;; still pretend that this is a "normal" resource, so it stays in the dependency graph
       ;; so we can later identify which module it should be in and so on
       :type :shadow-js
       ;; could just have the thing create a <style> tag and use an inline string
       ;; but I'd rather have that configurable in some way
       :source ""
       :file file})

    ;; regular js file
    (try
      (with-npm-info npm package (get-file-info npm file))
      (catch Exception e
        (throw (ex-info "failed to inspect node_modules file"
                 {:tag ::file-info-failed
                  :file file}
                 e))))))

(defn find-exports-conditional-match
  [npm match]
  (loop [conditions (get-in npm [:js-options :export-conditions])]
    (when (seq conditions)
      (let [c (first conditions)
            m (get match c)]
        (cond
          (not m)
          (recur (rest conditions))

          ;; nested conditional maps, can't recur because of loop, must recurse
          (map? m)
          (find-exports-conditional-match npm m)

          (string? m)
          m

          ;; @compiled/react has ./runtime with a conditional match to an array
          ;;  "./runtime": {
          ;;      "import": [
          ;;        "./dist/esm/runtime.js",
          ;;        "./src/runtime.ts"
          ;;      ],
          ;;      "require": [
          ;;        "./dist/cjs/runtime.js",
          ;;        "./src/runtime.ts"
          ;;      ]
          ;;    },
          ;; FIXME: this is assuming that the first entry always exists, but why are there multiple values in the first place ...
          ;; should probably properly test if the file actually exists and try next, but that requires rewriting this whole thing
          (and (vector? m) (every? string? m))
          (first m)

          :else
          nil)))))

(defn find-exports-replacement [npm match]
  (cond
    (string? match)
    match

    (vector? match)
    (reduce
      (fn [_ m]
        (when-some [x (find-exports-replacement npm m)]
          (reduced x)))
      nil
      match)

    (map? match)
    (find-exports-conditional-match npm match)

    :else
    nil))

(defn find-resource-from-exports-exact
  [npm {:keys [exports-exact ^File package-dir] :as package} rel-require]

  ;; ./ is the minimum rel-require we use internally, but exports uses "." to signal "no subpath"
  (when-some [match (get exports-exact (if (= "./" rel-require) "." rel-require))]
    (let [path
          (find-exports-replacement npm match)

          file
          (test-file package-dir path)]

      (cond
        (not file)
        nil
        #_(throw (ex-info (format "package export match referenced a file that doesn't exist")
                   {:rel-require rel-require :package-dir package-dir :match match :exports exports-exact}))

        (.isDirectory file)
        nil
        #_(throw (ex-info (format "package export match referenced a directory")
                   {:file file :rel-require rel-require :package-dir package-dir :match match :exports exports-exact}))

        :else
        (file-as-resource npm package rel-require file)))))

(defn find-resource-from-exports-by-prefix
  [npm {:keys [package-dir] :as package} rel-require]
  (reduce
    (fn [_ {:keys [prefix match]}]
      (when (str/starts-with? rel-require prefix)
        (let [suffix (subs rel-require (count prefix) (count rel-require))]

          (when-some [replacement (find-exports-replacement npm match)]
            (let [path (str replacement suffix)
                  file (test-file package-dir path)]
              (cond
                (not file)
                nil
                #_(throw (ex-info "package export prefix match referenced a file that doesn't exist"
                           {:rel-require rel-require :package-dir package-dir :prefix prefix :match match}))

                (.isDirectory file)
                nil
                #_(throw (ex-info (format "package export prefix match referenced a directory")
                           {:file file :rel-require rel-require :package-dir package-dir :prefix prefix :match match}))

                :else
                (reduced (file-as-resource npm package rel-require file)))
              )))))
    nil
    (:exports-prefix package)))

(defn find-resource-from-exports-by-wildcard
  [npm {:keys [package-dir] :as package} rel-require]
  (reduce
    (fn [_ {:keys [prefix suffix match] :as x}]
      (when (str/starts-with? rel-require prefix)
        (let [fill (subs rel-require (count prefix) (count rel-require))

              ;; patterns may either have ended in ./foo/* or with an additional suffix ./foo/*.js
              ;; the merge-package-exports* already parsed that and left :suffix nil if at the end
              ;; otherwise we need to check of the rel-require also matched the suffix
              fill (if (nil? suffix)
                     fill
                     (when (str/ends-with? fill suffix)
                       (subs fill 0 (- (count fill) (count suffix)))))]

          (when fill
            (when-some [replacement (find-exports-replacement npm match)]
              (let [path (str/replace replacement #"\*" fill)
                    file (test-file package-dir path)]

                (cond
                  (not file)
                  nil
                  #_(throw (ex-info "package export wildcard match referenced a file that doesn't exist"
                             {:rel-require rel-require :package-dir package-dir :prefix prefix :match match}))

                  (.isDirectory file)
                  nil
                  #_(throw (ex-info (format "package export wildcard match referenced a directory")
                             {:file file :rel-require rel-require :package-dir package-dir :prefix prefix :match match}))

                  :else
                  (reduced (file-as-resource npm package rel-require file)))
                ))))))
    nil
    (:exports-wildcard package)))

(defn find-resource-from-exports
  [npm package rel-require]
  ;; no hard fail in any of these, if no match can be found it should just return nil
  (or (find-resource-from-exports-exact npm package rel-require)
      (find-resource-from-exports-by-prefix npm package rel-require)
      (find-resource-from-exports-by-wildcard npm package rel-require)))

;; expects a require starting with ./ expressing a require relative in the package
;; using this instead of empty string because package exports use it too
(defn find-resource-in-package [npm package require-from rel-require]
  {:pre [(map? package)]}

  (when-not (str/starts-with? rel-require "./")
    (throw (ex-info "invalid require" {:package (:package-name package) :require-from (:resource-id require-from) :rel-require rel-require})))
  (let [use-exports?
        (and (:exports package)
             ;; always good to have a toggle to ignore exports mess
             (not (get-in npm [:js-options :ignore-exports])))

        package-internal-request?
        (= (:package-id package) (get-in require-from [::package :package-id]))]

    ;; package has exports, so they take priority over everything else when the request is from a different package
    ;; it should be a hard fail if a request cannot be matched to exports, unless user opts into bypassing that
    (if (and use-exports?
             (not package-internal-request?)
             (not (true? (get-in npm [:js-options :exports-bypass]))))
      (or (find-resource-from-exports npm package rel-require)

          ;; exports lock down the package, so only the package itself may require its files normally
          ;; outsiders should fail
          (throw (ex-info (format "package %s had exports, but could not resolve %s" (:package-name package) rel-require)
                   {:package (:package-dir package)
                    :require-from (:resource-id require-from)
                    :rel-require rel-require})))

      ;; package internal require may still use exports, but shouldn't fail when no match is found and instead
      ;; fall through to the regular npm lookup logic
      ;; always going that route when no exports are present in the package
      (or (and use-exports? (find-resource-from-exports npm package rel-require))
          (when-let [match (find-match-in-package npm package rel-require)]

            ;; might have used a nested package.json, continue from there
            ;; not the root package we started with
            (let [[package file] match
                  rel-path (as-package-rel-path package file)
                  override (get-package-override npm package rel-path)]

              (cond
                ;; override to disable require, sometimes used to skip certain requires for browser
                (false? override)
                empty-rc

                (and (string? override) (not= override rel-path))
                (or (if (util/is-relative? override)
                      (find-resource-in-package npm package require-from override)
                      (find-resource npm require-from override))
                    (throw (ex-info (format "require %s was overridden to %s but didn't exist in package %s"
                                      rel-require override (:package-name package))
                             {:package package
                              :rel-require rel-require})))

                (not (nil? override))
                (throw (ex-info (format "invalid override %s for %s in %s"
                                  override rel-require (:package-name package))
                         {:tag ::invalid-override
                          :package-dir (:package-dir package)
                          :require rel-require
                          :override override}))

                :no-override
                (file-as-resource npm package rel-require file)
                )))
          ))))

(defn find-resource
  [npm require-from ^String require]
  {:pre [(service? npm)
         (or (nil? require-from)
             (map? require-from))
         (string? require)]}

  (cond
    (util/is-absolute? require)
    (throw (ex-info "absolute require not allowed for node_modules files"
             {:tag ::absolute-path
              :require-from require-from
              :require require}))

    ;; package.json import support, must start with # according to node.js docs
    (and (str/starts-with? require "#") require-from)
    (let [package (::package require-from)
          override (get-in package [:package-json "imports" require])]
      (when-not override
        (throw (ex-info (str "require for import: " require " not found in package.json imports")
                 {:tag ::no-import
                  :require-from require-from
                  :require require})))

      (let [replacement
            (cond
              ;; might be a map similar to package.json exports
              ;; this is currently using :export-conditions config value
              ;; not sure if this is ever different that would warrant its own config value
              (map? override)
              (find-exports-conditional-match npm override)

              (string? override)
              override

              :else
              (throw (ex-info "unsupported package.json imports value" {:value override :require require})))]

        (if (util/is-relative? replacement)
          ;; relative paths must resolve relative to package, not the require-from
          ;; so going directly with find-resource-in-package here
          (find-resource-in-package npm package require-from replacement)
          ;; overrides may just replace entire packages too though, so won't find them in the package itself
          (find-resource npm require-from replacement))))


    ;; pkg relative require "./foo/bar.js"
    (util/is-relative? require)
    (do (when-not (and require-from (:file require-from))
          (throw (ex-info "relative require without require-from"
                   {:tag ::no-require-from
                    :require-from require-from
                    :require require})))

        (when-not (::package require-from)
          (throw (ex-info "require-from is missing package info"
                   {:tag ::no-package-require-from
                    :require-from require-from
                    :require require})))

        ;; in case of nested packages we may need to recurse since it is valid
        ;; for nested packages to refer to files from the parent
        ;; it is however not valid to refer to relative files outside of that
        ;; thing/foo/bar.js can go to ../bar.js but not ../../thing.js
        (loop [{:keys [^File package-dir] :as package} (::package require-from)]
          (let [{:keys [^File file]} require-from
                rel-require (resolve-require-as-package-path package-dir file require)]

            (if-not (str/starts-with? rel-require "../")
              (find-resource-in-package npm package require-from (str "./" rel-require))
              (if-some [parent (::parent package)]
                (recur parent)
                (throw (ex-info (format "relative require %s from %s outside package %s"
                                  require
                                  (.getAbsolutePath file)
                                  (.getAbsolutePath package-dir))
                         {:package-dir package-dir
                          :file file
                          :require require}))
                )))))

    ;; "package" require
    ;; when "package" is required from within another package its package.json
    ;; has a chance to override what that does, need to check it before actually
    ;; trying to find the package itself
    :package-require
    (let [override
          (when (and require-from (::package require-from) (:use-browser-overrides (:js-options npm)))
            (get-in require-from [::package :browser-overrides require]))]

      (cond
        ;; common path, no override
        (or (nil? override) (= override require))
        (when-let [{:keys [match-name] :as package} (find-package-for-require npm require-from require)]
          ;; must used the match-name provided by find-package-for-require
          ;; package-name cannot be trusted to match the actual package name
          ;; eg. react-intl-next has react-intl as "name" in package.json
          ;; can't just use the first /, package names can contain / and might be nested
          ;; @foo/bar and @foo/bar/some-nested/package.json are all valid packages
          (if (= require match-name)
            ;; plain package require turns into "./" rel require
            (find-resource-in-package npm package require-from "./")
            ;; strip package/ from package/foo turn it into ./foo
            (let [rel-require (str "." (subs require (count match-name)))]
              (find-resource-in-package npm package require-from rel-require)
              )))

        ;; disabled require
        (false? override)
        empty-rc

        (not (string? override))
        (throw (ex-info (format "invalid browser override in package: %s" require-from)
                 {:require require
                  :require-from require-from
                  :override override}))

        (util/is-relative? override)
        (find-resource-in-package npm (::package require-from) require-from override)

        :else
        (find-resource npm require-from override)
        ))))

(defn shadow-js-require
  ([rc]
   (shadow-js-require rc true))
  ([{:keys [ns require-id resource-config] :as rc} semi-colon?]
   (let [{:keys [export-global export-globals]}
         resource-config

         ;; FIXME: not the greatest idea to introduce two keys for this
         ;; but most of the time there will only be one exported global per resource
         ;; only in jQuery case sometimes we need jQuery and sometimes $
         ;; so it must export both
         globals
         (-> []
             (cond->
               (seq export-global)
               (conj export-global)
               (seq export-globals)
               (into export-globals)))

         opts
         (-> {}
             (cond->
               (seq globals)
               (assoc :globals globals)))]

     (str "shadow.js.require("
          (if require-id
            (pr-str require-id)
            (str "\"" ns "\""))
          ", " (json/write-str opts) ")"
          (when semi-colon? ";")))))

;; FIXME: allow configuration of :extensions :entry-keys
;; maybe some closure opts
(defn start [{:keys [node-modules-dir js-package-dirs] :as config}]
  (let [index-ref
        (atom {:files {}
               :require-cache {}
               :packages {}
               :package-json-cache {}})

        ;; FIXME: share this with classpath
        co
        (doto (CompilerOptions.)
          ;; FIXME: good idea to disable ALL warnings?
          ;; I think its fine since we are just looking for require anyways
          ;; if the code has any other problems we'll get to it when importing
          (.resetWarningsGuard)
          ;; should be the highest possible option, since we can't tell before parsing
          (.setLanguageIn CompilerOptions$LanguageMode/ECMASCRIPT_NEXT))

        cc
        (doto (data/make-closure-compiler)
          (.initOptions co))

        project-dir
        (-> (io/file "")
            (absolute-file))

        js-package-dirs
        (-> []
            (cond->
              (and (not (seq node-modules-dir))
                   (not (seq js-package-dirs)))
              (conj (io/file project-dir "node_modules"))

              (seq node-modules-dir)
              (conj (-> (io/file node-modules-dir)
                        (absolute-file)))

              (seq js-package-dirs)
              (into (->> js-package-dirs
                         (map (fn [path]
                                (-> (io/file path)
                                    (absolute-file))))))))]

    {::service true
     :index-ref index-ref
     :compiler cc
     :compiler-options co
     ;; JVM working dir always
     :project-dir project-dir
     :js-package-dirs js-package-dirs
     :npm-deps (collect-npm-deps-from-classpath)

     ;; browser defaults
     :js-options {:extensions [".js" ".mjs" ".json"]
                  :allow-nested-packages true
                  :target :browser
                  :use-browser-overrides true
                  ;; these should sort of line up, so that we don't end up mixing ESM and commonjs too much
                  :entry-keys ["browser" "main" "module"]
                  ;; kinda tough to pick these, some packages use esm as "default, some use commonjs
                  ;; hopefully by listing browser+require we cover most commonjs cases
                  ;; which so far is much more reliable than esm
                  :export-conditions ["browser" "require" "default" "module" "import"]}
     }))

(defn stop [npm])


(defn js-resource-for-global
  "a dependency might come from something already included in the page by other means

   a config like:
   {\"react\" {:type :global :global \"React\"}}

   means require(\"react\") returns the global React instance"
  [require {:keys [global] :as pkg}]
  (let [ns (symbol (ModuleNames/fileToModuleName require))]
    {:resource-id [::global require]
     :resource-name (str "global$" ns ".js")
     :output-name (str ns ".js")
     :global-ref true
     :type :js
     :cache-key [NPM-CACHE-KEY CLOSURE-CACHE-KEY]
     :last-modified 0
     :ns ns
     :provides #{ns}
     :requires #{}
     :deps []
     :source (str "module.exports=(" global ");")}))

(defn js-resource-for-file
  "if we want to include something that is not on npm or we want a custom thing
  {\"react\" {:type :file :file \"path/to/my/react.js\"}}"

  [npm require {:keys [file file-min] :as cfg}]
  (let [mode
        (get-in npm [:js-options :mode] :release)

        file
        (-> (if (and (= :release mode) (seq file-min))
              (io/file file-min)
              (io/file file))
            (absolute-file))]
    (when-not (.exists file)
      (throw (ex-info "file override for require doesn't exist" {:file file :require require :config cfg})))

    (get-file-info npm file)
    ))
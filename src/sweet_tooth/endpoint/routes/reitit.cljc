(ns sweet-tooth.endpoint.routes.reitit
  "Sugar for reitit routes. Lets you:

  1. Specify a map of options that apply to a group of routes
  2. Transform names (usually namespace names) into reitit
  routes that include both:
     2a. a collection routes, e.g. `/users`
     2b. a unary route, e.g. `/user/{id}`

  A sugared route definition might be:

  [[:my-app.endpoint.user]]

  This would expand to:

  [[\"/user\" {:name   :users
               ::ns    :my-app.endpoint.user
               ::type  :coll
               :id-key :id}]
  [\"/user/{id}\" {:name   :user
                   ::ns    :my-app.endpoint.user
                   ::type  :ent
                   :id-key :id}]]"
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [meta-merge.core :as mm]
            #?@(:cljs [[goog.string :as gstr]
                       [goog.string.format]])))

;;------
;; specs
;;------

;; paths
(s/def ::path-fragment (s/and string? not-empty))
(s/def ::full-path ::path-fragment)
(s/def ::path ::path-fragment)
(s/def ::path-prefix ::path-fragment)
(s/def ::path-suffix ::path-fragment)
(s/def ::path-opts (s/keys :opt-un [::full-path
                                    ::path
                                    ::path-prefix
                                    ::path-suffix]))

;; expanders
(s/def ::expander-name keyword?)
(s/def ::route-opts map?)
(s/def ::expander-with-opts (s/cat :expander-name ::expander-name
                                   :route-opts    (s/? ::route-opts)))
(s/def ::path-expander (s/cat :path       ::path-fragment
                              :route-opts (s/? ::route-opts)))
(s/def ::expander (s/or :expander-name      ::expander-name
                        :expander-with-opts ::expander-with-opts
                        :path-expander      ::path-expander))
(s/def ::expand-with (s/coll-of ::expander))

;; namespace-route
(s/def ::name keyword?)
(s/def ::name-route (s/cat :name       ::name
                           :route-opts (s/? ::route-opts)))

(def format-str  #?(:clj format :cljs gstr/format))


;;------
;; utils
;;------

(defn ksubs
  "full string representation of a keyword: 
  :x/y => \"x/y\"
  :y => \"y\""
  [k]
  (if (keyword? k)
    (-> k str (subs 1))
    k))

(defn slash
  "replace dots with slashes in namespace to create a string that's
  route-friendly"
  [name]
  (str/replace (ksubs name) #"\." "/"))


;;------
;; utils
;;------

(defn path
  [{:keys [::full-path ::path ::path-prefix ::path-suffix] :as opts}]
  (or full-path
      (->> [path-prefix path path-suffix]
           (map (fn [s] (if (fn? s) (s opts) s)))
           (remove empty?)
           (str/join ""))))

(s/fdef path
  :args (s/cat :path-opts ::path-opts)
  :ret ::path-fragment)

(defn- dissoc-opts
  "the final routes don't need to be cluttered with options specific to route expansion"
  [opts]
  (dissoc opts
          ::base-name
          ::full-path
          ::path
          ::path-prefix
          ::path-suffix
          ::expand-with
          ::expander-opts))

(defn route-opts
  [nsk expander defaults opts]
  (let [ro (merge {::ns   nsk
                   ::type expander}
                  defaults
                  opts
                  (::expander-opts opts))]
    [(path ro) (dissoc-opts ro)]))


;;------
;; expansion
;;------

(defmulti expand-with (fn [_nsk expander _opts]
                        (if (string? expander)
                          ::path
                          (let [ns (keyword (namespace expander))
                                n  (keyword (name expander))]
                            (cond (and (= ns :coll) (some? n)) ::coll-child
                                  (and (= ns :ent) (some? n))  ::ent-child
                                  :else                        expander)))))

(defmethod expand-with
  ::path
  [nsk path {:keys [::base-name]
             {:keys [:name]} ::expander-opts
             :as opts}]
  {:pre (some? name)}
  (route-opts nsk
              name
              {:name  name
               ::type name
               ::path (format-str "/%s%s" (slash base-name) path)}
              opts))

;; keys like :ent/some-key are treated like
;; ["/ent-type/{id}/some-key" {:name :ent-type/some-key}]
(defmethod expand-with
  ::ent-child
  [nsk expander {:keys [::base-name] :as opts}]
  (route-opts nsk
              expander
              {:name   (keyword base-name (name expander))
               :id-key :id
               ::path  (fn [{:keys [id-key] :as o}]
                         (format-str "/%s/{%s}/%s"
                                     (slash (::base-name o))
                                     (ksubs id-key)
                                     (name expander)))}
              opts))

(defmethod expand-with
  :coll
  [nsk expander {:keys [::base-name] :as opts}]
  (route-opts nsk
              expander
              {:name  (keyword (str base-name "s"))
               ::path (str "/" (slash base-name))}
              opts))

(defmethod expand-with
  :ent
  [nsk expander {:keys [::base-name] :as opts}]
  (route-opts nsk
              expander
              {:name   (keyword base-name)
               :id-key :id
               ::path  (fn [{:keys [id-key] :as o}]
                         (format-str "/%s/{%s}"
                                     (slash base-name)
                                     (ksubs id-key)))}
              opts))

(defmethod expand-with
  :singleton
  [nsk expander {:keys [::base-name] :as opts}]
  (route-opts nsk
              expander
              {:name  (keyword base-name)
               ::path (str "/" (slash base-name))}
              opts))

(defn expand-route
  "In a pair of [n m], if n is a keyword then the pair is treated as a
  name route and is expanded. Otherwise the pair returned as-is (it's
  probably a regular reitit route).

  `delimiter` is a regex used to specify what part of the name to
  ignore. By convention Sweet Tooth expects you to use names like
  `:my-app.backend.endpoint.user`, but you want to just use `user` -
  that's what the delimiter is for."
  ([pair] (expand-route pair #"endpoint\."))
  ([[ns opts :as pair] delimiter]
   (if (s/valid? ::name-route pair)
     (let [base-name (-> (str ns)
                         (str/split delimiter)
                         (second))
           expanders (s/assert ::expanders (::expand-with opts [:coll :ent]))
           opts      (assoc opts ::base-name base-name)]
       (reduce (fn [routes expander]
                 (let [expander-opts (if (sequential? expander) (second expander) {})
                       expander      (if (sequential? expander) (first expander) expander)]
                   (conj routes (expand-with ns expander (assoc opts ::expander-opts expander-opts)))))
               []
               expanders))
     [pair])))

(defn expand-routes
  "Returns vector of reitit-compatible routes from compact route syntax"
  ([pairs]
   (expand-routes pairs #"endpoint\."))
  ([pairs delimiter]
   (loop [common                {}
          [current & remaining] pairs
          routes                []]
     (cond (not current)  routes
           (map? current) (recur current remaining routes)
           :else          (recur common
                                 remaining
                                 (into routes (expand-route (update current 1 #(mm/meta-merge common %))
                                                            delimiter)))))))

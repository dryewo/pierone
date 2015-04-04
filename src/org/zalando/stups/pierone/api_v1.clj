(ns org.zalando.stups.pierone.api-v1
  (:require [org.zalando.stups.friboo.system.http :refer [def-http-component]]
            [org.zalando.stups.friboo.log :as log]
            [ring.util.response :as ring]
            [clojure.data.json :as json]
            [org.zalando.stups.pierone.sql :as sql]
            [org.zalando.stups.pierone.storage :as s])
  (:import (java.sql SQLException)))

(defn- resp
  "Returns a response including various Docker headers set."
  [body request & {:keys [status binary?]
                   :or   {status  200
                          binary? false}}]
  (let [content-type-fn (fn [response]
                          (if binary?
                            (ring/content-type response "application/octet-stream")
                            (ring/content-type response "application/json")))]
    (-> (ring/response body)
        (content-type-fn)
        (ring/status status)
        (ring/header "X-Docker-Registry-Version" "0.6.3")
        (ring/header "X-Docker-Token" "FakeToken")
        (ring/header "X-Docker-Endpoints" (get-in request [:headers "host"])))))

(defn ping
  "Client checks for compatibility."
  [_ request _ _]
  (resp true request))

(defn search
  "Dummy call. Searches for repositories."
  [_ request _ _]
  (resp {:results []} request))

(defn put-repo
  "Dummy call."
  [_ request _ _]
  (resp "OK" request))

(defn get-tags
  "Get a map of all tags for an artifact with its images."
  [parameters request db _]
  (let [tags (sql/read-tags parameters {:connection db})]
    (if (empty? tags)
      (resp {} request :status 404)
      (let [tags (reduce
                   (fn [tags tag]
                     (merge tags {(:name tag)
                                  (:image tag)}))
                   tags)]
        (resp tags request)))))

(defn put-tag
  "Stores a tag. Only '*-SNAPSHOT' tags are mutable."
  [parameters request db _]
  (try
    (sql/create-tag! parameters {:connection db})
    (log/info "Stored new tag %s." parameters)
    (resp "OK" request)

    (catch SQLException e
      (if (.endsWith name "-SNAPSHOT")
        (sql/update-tag! parameters {:connection db})
        (do
          (log/warn "Prevented update of tag: %s" (str e))
          (resp "tag already exists" request :status 409))))))

(defn put-images [_ request _ _]
  "Dummy call. this is the final call from Docker client when pushing an image
   Docker client expects HTTP status code 204 (No Content) instead of 200 here!"
  (resp "" request :status 204))

(defn get-images
  "Dummy call."
  [_ request _ _]
  (resp [] request))

(defn put-image-json
  "Stores an image's JSON metadata. First call in upload sequence."
  [{:keys [image metadata]} request db _]
  (try
    (sql/delete-unaccepted-image! {:image image} {:connection db})
    (sql/create-image!
      {:image    image
       :metadata (json/write-str metadata)
       :parent   (get metadata "parent")}
      {:connection db})
    (log/debug "Stored new image metadata %s." image)
    (resp "OK" request)

    (catch SQLException e
      (log/warn "Prevented update of image: %s", (str e))
      (resp "image already exists" request :status 409))))

(defn get-image-json
  "Returns an image's metadata."
  [parameters request db _]
  (let [result (sql/get-image-metadata parameters {:connection db})]
    (if (empty? result)
      (resp "image not found" request :status 404)
      (resp (-> result first :metadata json/read-str) request))))

(defn put-image-binary
  "Stores an image's binary data. Second call in upload sequence."
  [{:keys [image data]} request db storage]
  (s/write-data storage image data)
  (sql/accept-image! {:image image} {:connection db})
  (log/info "Stored new image %s." image)
  (resp "OK" request))

(defn get-image-binary
  "Reads the binary data of an image."
  [{:keys [image]} request _ storage]
  (if-let [data (s/read-data storage image)]
    (resp data request :binary? true)
    (resp "image not found" request :status 404)))

(defn put-image-checksum
  "Dummy call."
  [_ request _ _]
  (resp "OK" request))

(defn get-image-ancestry
  "Returns the whole ancestry of an image."
  [{:keys [image]} request db _]
  ; TODO solve recursion in postgresql (http://www.postgresql.org/docs/9.4/static/queries-with.html)
  (let [f (fn [images image]
            (let [result (sql/get-image-parent {:image image} {:connection db})
                  exists? (first result)
                  parent (:parent exists?)]
              (if exists?
                (if parent
                  (recur (conj images image) parent)
                  [image])
                [])))
        ancestry (f [] image)]
    (if (empty? ancestry)
      (resp "image not found" request :status 404)
      (resp ancestry request))))

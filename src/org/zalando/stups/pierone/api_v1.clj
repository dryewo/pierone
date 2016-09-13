(ns org.zalando.stups.pierone.api-v1
  (:require [org.zalando.stups.friboo.system.http :refer [def-http-component]]
            [org.zalando.stups.friboo.log :as log]
            [org.zalando.stups.friboo.ring :as r]
            [ring.util.response :as ring]
            [cheshire.core :as json]
            [org.zalando.stups.pierone.sql :as sql]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [io.sarnowski.swagger1st.util.api :as api]
            [io.sarnowski.swagger1st.util.security :as security]
            [org.zalando.stups.friboo.user :as u]
            [schema.core :as schema]
            [clj-time.core :as t]
            [clj-time.coerce :as tcoerce]
            [clojure.data.codec.base64 :as b64]
            [com.netflix.hystrix.core :refer [defcommand]]
            [clojure.core.memoize :as memo]
            [clojure.core.cache :as cache]
            [clj-http.client :as http]
            [org.zalando.stups.friboo.config :refer [require-config]]
            [org.zalando.stups.pierone.storage :as s])
  (:import (java.sql SQLException)

           (java.util UUID)
           (org.apache.commons.compress.compressors.gzip GzipCompressorInputStream)
           (org.apache.commons.compress.archivers.tar TarArchiveInputStream)
           (java.io FileInputStream File)))

(schema/defschema ScmSourceInformation
  {:url      schema/Str
   :revision schema/Str
   :author   schema/Str
   :status   schema/Str})


; relaxed pattern: hex, 3-64 chars
(def valid-image-v1-pattern #"^[a-f0-9]{3,64}$")

(def scm-source-file-pattern #"^(\./)?scm-source.json$")

(defn valid-image-v1 [image-id]
  (and (some? image-id) (re-matches valid-image-v1-pattern image-id)))

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
        (ring/header "X-Docker-Token" (get (:tokeninfo request) "access_token" "AnonFakeToken"))
        (ring/header "X-Docker-Endpoints" (get-in request [:headers "host"])))))


(defcommand
  fetch-service-team
  [kio-url access-token user-id]
  ; the service user's uid is usually "stups_<application-id>"
  (let [app-id (last (str/split user-id #"_"))]
       (:team_id (:body (http/get (r/conpath kio-url "/apps/" app-id)
                                  {:oauth-token access-token
                                   :as          :json})))))

(def get-service-team
    "Cache team information for 10 minutes"
    (memo/fifo fetch-service-team (cache/ttl-cache-factory {} :ttl 600000) :fifo/threshold 100))

(defn require-service-team
  [team request]
  (u/require-realms #{"services"} request)
  (let [kio-url (require-config (:configuration request) :kio-url)
        tokeninfo (:tokeninfo request)
        user-id (get tokeninfo "uid")
        token (get tokeninfo "access_token")
        service-team (get-service-team kio-url token user-id)]
        (when-not (= service-team team)
                  (log/info "ACCESS DENIED (unauthorized) because service user is not in team %s." team)
                  (api/throw-error 403 (str "service user not in team '" team "'")
                                   {:user          user-id
                                    :required-team team
                                    :service-team  service-team}))))

(defn require-write-access
  "Require write access to team repository"
  [team request]
  ; either user has write access to all (service user)
  ; or user belongs to a team (employee user)
  (when-not (get-in request [:tokeninfo "application.write_all"])
    (if (get-in request [:tokeninfo "application.write"])
        (require-service-team team request)
        (u/require-internal-team team request))))

(defn ping
  "Client checks for compatibility."
  [_ request _ _ _]
  (resp true request))

(defn search
  "Dummy call. Searches for repositories."
  [{:keys [q] :as parameters} request db _ _ _]
  (let [repos (sql/cmd-search-repos parameters {:connection db})
        num-results (count repos)]
    (resp {:results     repos
           :query       q
           :page        1
           :page_size   num-results
           :num_pages   1
           :num_results num-results} request)))

(defn put-repo
  "Dummy call."
  [{:keys [team]} request _ _ _ _]
  (require-write-access team request)
  (resp "OK" request))

(defn- load-tags
  "Loads all tags and includes fake latest"
  [team artifact db]
  (let [db-tags (sql/cmd-read-tags {:team team :artifact artifact} {:connection db})
        merge-tag (fn [tags tag] (merge tags {(:name tag)
                                              (:image tag)}))
        tags (reduce merge-tag
                     {}
                     db-tags)]
        (if (empty? tags)
            tags
            (let [ ; search for latest tag, e.g. the one that was last created
                  latest-tag (reduce (fn [tag1 tag2]
                              (if (t/after? (tcoerce/from-sql-time (:created tag1))
                                             (tcoerce/from-sql-time (:created tag2)))
                                   tag1
                                   tag2))
                              db-tags)]
                  (merge-tag tags {:name "latest"
                                   :image (:image latest-tag)})))))

(defn get-tags
  "Get a map of all tags for an artifact with its images. Also includes a 'latest' tag
   that references the image of the most recently created tag."
  [{:keys [team artifact]} request db _ _ _]
  (let [tags (load-tags team artifact db)]
    (if (empty? tags)
        (resp {} request :status 404)
        (resp tags request))))

(defn get-image-for-tag
  "Get the image id for given tag"
  [{:keys [team artifact name]} request db _ _ _]
  (let [tags (load-tags team artifact db)
        image-id (get tags name)]
    (if (valid-image-v1 image-id)
      (resp (str "\"" (get tags name) "\"") request)
      (resp "not found" request :status 404))))

(defn put-tag
  "Stores a tag. Only '*-SNAPSHOT' tags are mutable. 'latest' is not allowed."
  [parameters request db _ _ {:keys [log-fn]}]
  ; TODO NIKO fix here
  (require-write-access (:team parameters) request)
  (let [connection {:connection db}
        tag-name (:name parameters)
        uid (get-in request [:tokeninfo "uid"])
        params-with-user (merge parameters {:user uid})]
    (if (= "latest" tag-name)
      (resp "tag latest is not allowed" request :status 409)
      (try
        (sql/create-tag! params-with-user connection)
        (log/info "Stored new tag %s." params-with-user)
        (resp "OK" request)

        ; TODO check for hystrix exception and replace sql above with cmd- version
        (catch SQLException e
          (if (.endsWith tag-name "-SNAPSHOT")
            (let [updated-rows (sql/cmd-update-tag! params-with-user connection)]
              (if (pos? updated-rows)
                (do
                  (log/info "Updated snapshot tag %s." params-with-user)
                  (resp "OK" request))
                (do
                  (log/info "Did not update snapshot tag %s because image is the same." params-with-user)
                  (resp "tag not modified" request))))
            (do
              (log/warn "Prevented update of tag: %s" (str e))
              (resp "tag already exists" request :status 409))))))))

(defn put-images
  "Dummy call. this is the final call from Docker client when pushing an image
   Docker client expects HTTP status code 204 (No Content) instead of 200 here!"
  [_ request _ _ _ _]
  (resp "" request :status 204))

(defn get-images
  "Dummy call."
  [_ request _ _ _ _]
  (resp [] request))

(defn put-image-json
  "Stores an image's JSON metadata. First call in upload sequence."
  [{:keys [image metadata]} request db _ _ _]
  (try
    (sql/delete-unaccepted-image! {:image image} {:connection db})
    (sql/create-image!
      {:image    image
       :metadata (json/generate-string metadata)
       :parent   (get metadata "parent")
       :user     (get-in request [:tokeninfo "uid"])}
      {:connection db})
    (log/debug "Stored new image metadata %s." image)
    (resp "OK" request)

    ; TODO check for hystrix command and adjust sql above with cmd-
    (catch SQLException e
      (log/warn "Prevented update of image: %s", (str e))
      (resp "image already exists" request :status 409))))

(defn get-image-json
  "Returns an image's metadata."
  [parameters request db _ _ _]
  (let [result (sql/cmd-get-image-metadata parameters {:connection db})]
    (if (or (empty? result) (not (valid-image-v1 (:image parameters))))
      (resp "image not found" request :status 404)
      (resp (-> result first :metadata json/parse-string) request))))

(defn extract-scm-source
  [file]
  (schema/validate ScmSourceInformation (json/parse-string (slurp file) keyword)))

(defn get-scm-source-data
  [tmp-file]
  (try
    (with-open [fis (FileInputStream. tmp-file)
                tar-stream (TarArchiveInputStream. (GzipCompressorInputStream. fis))]
      (loop []
        (when-let [entry (.getNextTarEntry tar-stream)]
          (if (re-matches scm-source-file-pattern (.getName entry))
            (extract-scm-source tar-stream)
            (recur)))))
    (catch Exception e
      (log/warn "Failed to read image layer: %s" (str e))
      nil)))

(defcommand store-image
            [storage image tmp-file]
            (s/write-data storage image tmp-file))

(defcommand load-image
            [storage image]
            (s/read-data storage image))

(defn put-image-binary
  "Stores an image's binary data. Second call in upload sequence."
  [{:keys [image data]} request db storage _ _]
  (let [^File tmp-file (io/file (:directory storage)
                                (str image ".tmp-" (UUID/randomUUID)))
        connection {:connection db}]
    (io/copy data tmp-file)
    (store-image storage image tmp-file)
    (when-let [scm-source (get-scm-source-data tmp-file)]
      (log/info "Found scm-source.json in image %s: %s" image scm-source)
      (sql/cmd-create-scm-source-data! (assoc scm-source :image image) connection))
    (sql/cmd-accept-image! {:image image
                            :size (.length tmp-file)}
                           connection)
    (log/info "Stored new image %s." image)
    (io/delete-file tmp-file true)
    (resp "OK" request)))

(defn get-image-binary
  "Reads the binary data of an image."
  [{:keys [image]} request _ storage _ _]
  (if-let [data (load-image storage image)]
    (resp data request :binary? true)
    (resp "image not found" request :status 404)))

(defn put-image-checksum
  "Dummy call."
  [_ request _ _ _ _]
  (resp "OK" request))

(defn get-image-ancestry
  "Returns the whole ancestry of an image."
  [params request db _ _ _]
  (let [ancestry (map :id
                      (sql/cmd-get-image-ancestry params {:connection db}))]
    (if (empty? ancestry)
      (resp "image not found" request :status 404)
      (resp ancestry request))))

(defn post-users
  "Special handler for docker client"
  [_ request _ _ _ _]
  (resp "Not supported, please use GET /v1/users" request :status 401))

(defn login
  [_ request _ _ _ _]
  (resp "Login successful" request))

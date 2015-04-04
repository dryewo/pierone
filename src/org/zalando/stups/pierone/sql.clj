(ns org.zalando.stups.pierone.sql
  (:require [yesql.core :refer [defqueries]]
            [org.zalando.stups.friboo.system.db :refer [def-db-component]]))

(def-db-component DB :auto-migration? true)

(def default-db-configuration
  {:db-classname "org.postgresql.Driver"
   :db-subprotocol "postgresql"
   :db-subname "//localhost:5432/pierone"
   :db-user "postgres"
   :db-password "postgres"})

(defqueries "db/v1.sql")


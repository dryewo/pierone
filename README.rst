=================================
Pier One - STUPS' Docker Registry
=================================

.. image:: https://travis-ci.org/zalando-stups/pierone.svg?branch=master
   :target: https://travis-ci.org/zalando-stups/pierone
   :alt: Travis CI build status

.. image:: https://coveralls.io/repos/zalando-stups/pierone/badge.svg
   :target: https://coveralls.io/r/zalando-stups/pierone
   :alt: Coveralls status

Docker registry with immutable tags, repo permissions, S3 backend and OAuth.

Development
===========

The service is written in Clojure. You need Leiningen_ installed to build or develop.

To start a web server for the application, you need a running PostgreSQL instance on localhost with empty pierone database
(postgres/postgres as user/pass).
Run the development web server with:

.. code-block:: bash

    $ lein repl
    (go)

The web server will run on port 8080. You can find the Swagger UI on http://localhost:8080/ui/.

Testing
=======

Running unit tests with code coverage report:

.. code-block:: bash

    $ lein cloverage

.. code-block:: bash

    $ docker pull busybox
    $ docker tag busybox localhost:8080/example/foobar:1.0
    $ docker push localhost:8080/example/foobar:1.0
    $ docker pull localhost:8080/example/foobar:1.0

Building
========

.. code-block:: bash

    $ lein do uberjar, scm-source, docker build

Running
=======

Pier One supports a number of environment variables to use the Amazon S3 backend.
You will need a PostgreSQL database (database schemas are created automatically on first start).

.. code-block:: bash

    # run Pier One locally with file backend and connect to localhost PostgreSQL
    # NOTE: we simply use the "host" networking hack here to connect to the localhost DB
    $ docker run -it -p 8080:8080 --net=host stups/pierone

``DB_SUBNAME``
    Postgres connection string, e.g "//pierone.foo.eu-west-1.rds.amazonaws.com:5432/pierone?ssl=true". Default is "//localhost:5432/pierone"
``DB_PASSWORD``
    Postgres password. Default is "postgres".
``DB_USER``
    Postgres user name. Default is "postgres".
``HTTP_ALLOW_PUBLIC_READ``
    Allow Docker image downloads without authentication (e.g. to run Pier One as a registry for open source projects). Default is "false".
``HTTP_TEAM_SERVICE_URL``
    URL to get team membership information by user's UID.
``HTTP_TOKENINFO_URL``
    OAuth2 token info URL (e.g. https://example.org/oauth2/tokeninfo). You can leave away this configuration property to disable OAuth authentication.
``PGSSLMODE``
    Set to "verify-full" in order to fully verify the Postgres SSL cert.
``STORAGE_S3_BUCKET``
    Only for S3 backend: the Amazon S3 bucket name.

See the `STUPS Installation Guide section on Pier One`_ for details about deploying Pier One into your AWS account.

.. _Leiningen: http://leiningen.org/
.. _STUPS Installation Guide section on Pier One: http://docs.stups.io/en/latest/installation/service-deployments.html#pier-one

Security
========

Pier One uses OAuth 2 to protect its resources. The current implementation assumes:

* OAuth token information contains the ``uid`` field with the user's ID (e.g. "jdoe")
* The user's team membership can be looked up in the team service
* Authenticated users are allowed to pull all Docker images
* Authenticated users are allowed to push to their team's repository only

The `Pier One CLI`_ allows configuring the Docker client with the appropriate auth credentials.

.. _Pier One CLI: https://github.com/zalando-stups/pierone-cli

License
=======

Copyright © 2015 Zalando SE

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

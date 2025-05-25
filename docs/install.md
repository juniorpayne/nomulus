# Installation

This document covers the steps necessary to download, build, and deploy Nomulus.

## Prerequisites

You will need the following programs installed on your local machine:

* [Java 21 JDK][java-jdk21] (Java 11 JDK for development environment, but source/target compatibility is Java 21).
* [Google Cloud SDK][google-cloud-sdk] with the `gcloud` command-line tool.
* [Git](https://git-scm.com/) version control system.
* [Docker](https://docs.docker.com/get-docker/) (confirm with `docker info` - no permission issues, use `sudo usermod -aG docker $USER` for sudoless docker).
* [Python](https://python.org/) version 3.7 or newer.
* [Node.js](https://nodejs.org/) version 22.7.0 (required for frontend development).
* gnupg2 (e.g. run `sudo apt install gnupg2` on Debian-like Linux distributions)

**Note:** The instructions in this document have been tested on Linux. They should work with some alterations on other operating systems (macOS, Windows).

## Download the codebase

Start off by using git to download the latest version from the [Nomulus GitHub
page](https://github.com/google/nomulus). You may checkout any of the daily
tagged versions (e.g. `nomulus-20200629-RC00`), but in general it is also
safe to simply checkout from HEAD:

```shell
$ git clone git@github.com:google/nomulus.git
Cloning into 'nomulus'...
[ .. snip .. ]
$ cd nomulus
$ ls
AUTHORS          console-webapp  docs      gradle.properties  nomulus-logo.png  proxy
build.gradle     CONTRIBUTORS    gradle    gradlew            package.json      release
CLAUDE.md        core            gradle.lockfile  gradlew.bat  prober      services
common           db              integration  load-testing    processor   util
config           dependencies.gradle  jetty    networking      projects.gradle
```

Most of the directory tree is organized into Gradle sub-projects (see
`settings.gradle` for details). The following key directories are defined:

**Core Modules:**
*   `core/` -- Main registry application with EPP flows, DNS, WHOIS, RDAP
*   `db/` -- Database schema, Flyway migrations, and persistence layer
*   `console-webapp/` -- Angular-based registrar console web interface
*   `proxy/` -- TCP-to-HTTP proxy for EPP traffic
*   `jetty/` -- Jetty-based HTTP server deployment

**Services:**
*   `services/` -- App Engine service configurations (default, backend, tools, bsa, pubapi)

**Supporting:**
*   `common/` -- Shared utilities (Clock, DateTimeUtils, etc.)
*   `util/` -- Additional utility classes
*   `config/` -- Build tools and code hygiene scripts
*   `docs/` -- Documentation (including this install guide)
*   `gradle/` -- Gradle wrapper and configuration
*   `java-format/` -- Google Java formatter and scripts
*   `release/` -- CI/CD and deployment configuration

## Build the codebase

The first step is to build the project, and verify that this completes
successfully. This will also download and install dependencies.

```shell
$ ./nom_build build
Starting a Gradle Daemon (subsequent builds will be faster)
Plugins: Using default repo...

> Configure project :buildSrc
Java dependencies: Using Maven central...
[ .. snip .. ]
```

The `nom_build` script is just a wrapper around `gradlew`.  Its main
additional value is that it formalizes the various properties used in the
build as command-line flags.

The "build" command builds all of the code and runs all of the tests.  This
will take a while.

## Create an App Engine project

First, [create an
application](https://cloud.google.com/appengine/docs/java/quickstart) on Google
Cloud Platform. Make sure to choose a good Project ID, as it will be used
repeatedly in a large number of places. If your company is named Acme, then a
good Project ID for your production environment would be "acme-registry". Keep
in mind that project IDs for non-production environments should be suffixed with
the name of the environment (see the [Architecture
documentation](./architecture.md) for more details). For the purposes of this
example we'll deploy to the "alpha" environment, which is used for developer
testing. The Project ID will thus be `acme-registry-alpha`.

Now log in using the command-line Google Cloud Platform SDK and set the default
project to be this one that was newly created:

```shell
$ gcloud auth login
Your browser has been opened to visit:
[ ... snip logging in via browser ... ]
You are now logged in as [user@email.tld].
$ gcloud config set project acme-registry-alpha
```

Now modify `projects.gradle` with the name of your new project:

<pre>
// The projects to run your deployment Nomulus application.
rootProject.ext.projects = ['production': 'your-production-project',
                            'sandbox'   : 'your-sandbox-project',
                            'alpha'     : <strong>'acme-registry-alpha',</strong>
                            'crash'     : 'your-crash-project']
</pre>

Next follow the steps in [configuration](./configuration.md) to configure the
complete system or, alternately, read on for an initial deploy in which case
you'll need to deploy again after configuration.

## Deploy the code to App Engine

AppEngine deployment with gradle is straightforward:

    $ ./nom_build appengineDeploy --environment=alpha

To verify successful deployment, visit
https://acme-registry-alpha.appspot.com/registrar in your browser (adjusting
appropriately for the project ID that you actually used). If the project
deployed successfully, you'll see a "You need permission" page indicating that
you need to configure the system and grant access to your Google account. It's
time to go to the next step, configuration.

Configuration is handled by editing code, rebuilding the project, and deploying
again. See the [configuration guide](./configuration.md) for more details.
Once you have completed basic configuration (including most critically the
project ID, client id and secret in your copy of the `nomulus-config-*.yaml`
files), you can rebuild and start using the `nomulus` tool to create test
entities in your newly deployed system. See the [first steps tutorial](./first-steps-tutorial.md)
for more information.

[java-jdk21]: https://www.oracle.com/java/technologies/javase/jdk21-downloads.html

## Deploy the BEAM Pipelines

Nomulus is in the middle of migrating all pipelines to use flex-template. For
pipelines already based on flex-template, deployment in the testing environments
(alpha and crash) can be done using the following command:

```shell
./nom_build :core:stageBeamPipelines --environment=alpha
```

Pipeline deployment in other environments are through CloudBuild. Please refer
to the [release folder](http://github.com/google/nomulus/release) for details.

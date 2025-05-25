# Developing

This document contains advice on how to do development on the Nomulus codebase,
including how to set up an IDE environment and run tests.

## Running a local development server

`RegistryTestServer` is a lightweight test server for the registry that is
suitable for running locally for development. It uses local versions of all
Google Cloud Platform dependencies, when available. Correspondingly, its
functionality is limited compared to a Nomulus instance running on an actual App
Engine instance. It is most helpful for doing web UI development such as on the
registrar console: it allows you to update JS, CSS, images, and other front-end
resources, and see the changes instantly simply by refreshing the relevant page
in your browser.

To start a local development instance of the registry server, run:

```shell
$ ./nom_build :core:runTestServer
```

This will start the `RegistryTestServer` using Gradle. You can also run it directly with:

```shell
$ ./gradlew :core:runTestServer
```

Once it is running, you can interact with it via normal `nomulus` commands, or
view the registrar console in a web browser by navigating to
[http://localhost:8080/registrar](http://localhost:8080/registrar). The server
will continue running until you terminate the process.

If you are adding new URL paths, or new directories of web-accessible resources,
you will need to make the corresponding changes in `RegistryTestServer`. This
class is located at `core/src/test/java/google/registry/server/RegistryTestServer.java`
and contains all of the routing and static file information used by the local
development server.

## Frontend Development

For console-webapp frontend development:

```shell
# Install dependencies
$ cd console-webapp
$ npm install

# Start development server with live reload
$ npm run start:dev
```

The frontend development server will run on a different port and proxy API requests
to the backend registry server.

## Common Development Tasks

```shell
# Build the entire project
$ ./nom_build build

# Run all tests
$ ./nom_build test

# Run tests for a specific module
$ ./nom_build :core:test

# Run a specific test class
$ ./nom_build test --tests TestClassName

# Format code
$ ./nom_build javaIncrementalFormatApply

# Run presubmit checks
$ ./nom_build runPresubmits

# Run core development workflow (format, build, test, presubmits)
$ ./nom_build coreDev
```
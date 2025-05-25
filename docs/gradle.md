# Gradle Build Documentation

## Build System Overview

Nomulus uses Gradle as its build system with a custom wrapper script `./nom_build` that provides additional functionality and convenience commands. The project comes with the Gradle wrapper pre-configured, so you don't need to install Gradle separately.

## nom_build Wrapper

The `./nom_build` script is the recommended way to interact with the build system. It's a Python wrapper around `gradlew` that:

- Formalizes build properties as command-line flags
- Provides convenient pseudo-commands
- Ensures consistent build behavior across environments

### Common Commands

```shell
# Build entire project and run all tests
$ ./nom_build build

# Run all tests
$ ./nom_build test

# Run tests for specific module
$ ./nom_build :core:test

# Run specific test class
$ ./nom_build test --tests TestClassName

# Format code automatically
$ ./nom_build javaIncrementalFormatApply

# Check code formatting
$ ./nom_build javaIncrementalFormatCheck

# Run presubmit checks (licensing, formatting, style)
$ ./nom_build runPresubmits

# Complete development workflow (format, build, test, presubmits)
$ ./nom_build coreDev

# Deploy to App Engine
$ ./nom_build appengineDeploy --environment=alpha

# Generate Gradle properties file
$ ./nom_build --generate-gradle-properties

# Show help
$ ./nom_build --help
```

### Direct Gradle Usage

You can also use the Gradle wrapper directly for standard Gradle operations:

```shell
# Build project
$ ./gradlew build

# Run tests
$ ./gradlew test

# Run development server
$ ./gradlew :core:runTestServer
```

## Deploy to App Engine

Use the nom_build wrapper to deploy to App Engine:

```shell
$ ./nom_build appengineDeploy --environment=alpha
```

Before deploying, you must:
1. Configure your project ID in `projects.gradle`
2. Set up your environment configuration in the appropriate `nomulus-config-*.yaml` file
3. Install the [Google Cloud SDK](https://cloud.google.com/sdk/docs/install)
4. Authenticate with `gcloud auth login`

The deployment process will build the application, package it for App Engine, and deploy all configured services.


### Notable Issues

Test suites (RdeTestSuite and TmchTestSuite) are ignored to avoid duplicate
execution of tests. Neither suite performs any shared test setup routine, so it
is easier to exclude the suite classes than individual test classes. This is the
reason why all test tasks in the :core project contain the exclude pattern
'"**/*TestCase.*", "**/*TestSuite.*"'

Many Nomulus tests are not hermetic: they modify global state, but do not clean
up on completion. This becomes a problem with Gradle. In the beginning we forced
Gradle to run every test class in a new process, and incurred heavy overheads.
Since then, we have fixed some tests, and manged to divide all tests into three
suites that do not have intra-suite conflicts. We will revisit the remaining
tests soon.

Note that it is unclear if all conflicting tests have been identified. More may
be exposed if test execution order changes, e.g., when new tests are added or
execution parallelism level changes.

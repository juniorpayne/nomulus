# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Nomulus is an open-source, scalable registry system for operating top-level domains (TLDs). It's Google's domain registry software that powers TLDs like .google, .app, .how, .soy, and .みんな. The system runs on Google Kubernetes Engine and is primarily written in Java.

## Build Commands

The project uses a custom wrapper `./nom_build` around Gradle for all build operations:

- **Build project**: `./nom_build build`
- **Run all tests**: `./nom_build test`
- **Run single test**: `./nom_build test --tests TestClassName` or `./nom_build :module:test --tests TestClassName`
- **Format code**: `./nom_build javaIncrementalFormatApply`
- **Check formatting**: `./nom_build javaIncrementalFormatCheck`
- **Run presubmits**: `./nom_build runPresubmits` (includes license checks, formatting, style validation)
- **Core dev tasks**: `./nom_build coreDev` (formats, builds, tests, runs presubmits)
- **Deploy to App Engine**: `./nom_build appengineDeploy --environment=alpha`
- **Generate Gradle properties**: `./nom_build --generate-gradle-properties`

## Architecture & Module Structure

The project is organized into Gradle subprojects:

### Core Services
- **`core/`** - Main registry application (EPP flows, DNS, WHOIS, RDAP, domain operations)
- **`db/`** - Database schema, Flyway migrations, JPA entities
- **`console-webapp/`** - Angular-based registrar console web interface
- **`proxy/`** - TCP-to-HTTP proxy for EPP traffic
- **`jetty/`** - Jetty-based HTTP server deployment

### Service Modules (App Engine services)
- **`services/default`** - Frontend service (EPP, WHOIS, RDAP, web consoles)
- **`services/backend`** - Background tasks, cron jobs, async processing
- **`services/bsa`** - BSA (Brand Security Alliance) processing
- **`services/tools`** - Administrative tooling service
- **`services/pubapi`** - Public API endpoints

### Supporting Libraries
- **`common/`** - Shared utilities (Clock, DateTimeUtils, Sleeper)
- **`util/`** - Additional utility classes
- **`networking/`** - Network-related functionality
- **`processor/`** - Annotation processors
- **`prober/`** - Monitoring and health check probes

## Technology Stack

- **Java**: 11 JDK (development), 21 (source/target compatibility)
- **Build**: Gradle with custom `nom_build` wrapper
- **Database**: PostgreSQL with JPA/Hibernate, Flyway migrations
- **Frontend**: Angular with TypeScript, Angular Material
- **Server**: Jetty servlet container
- **Cloud**: Google Cloud Platform (App Engine, Cloud SQL, etc.)
- **Testing**: JUnit, Mockito, Karma/Jasmine for frontend

## Code Style Guidelines

- **Style Guide**: Google Java Style Guide
- **Line length**: 100 characters max
- **Indentation**: 2 spaces, no tabs
- **Naming**: camelCase for methods/variables, PascalCase for classes
- **Imports**: No wildcard imports, organize alphabetically (static imports first)
- **Documentation**: Javadoc required for public/protected classes
- **Error handling**: No empty catch blocks, use `assertThrows`/`expectThrows` for testing exceptions
- **Locale-aware operations**: Use `String.toUpperCase(Locale.ENGLISH)` instead of `String.toUpperCase()`
- **Time handling**: Use `DateTime.now(UTC)` instead of `DateTime.now()`
- **Optional**: Prefer `java.util.Optional` over Guava's `Optional`

## License Header Requirements

**CRITICAL**: All shell scripts (.sh files) MUST include the Apache 2.0 license header and end with a newline to pass presubmit checks.

### Required License Header Format for Shell Scripts:
```bash
#!/bin/bash
# Copyright 2025 The Nomulus Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Your script description here
```

### File Requirements:
- **License header**: Must be present at the top of every .sh file
- **Newline ending**: Files must end with a newline character
- **Presubmit validation**: `./nom_build runPresubmits` checks these requirements
- **Build dependency**: License compliance is required for successful builds

## Development Environment Requirements

- Java 11 JDK (development environment)
- Docker and Python 3.7+
- Node.js 22.7.0 (for console-webapp frontend development)
- Git and Google Cloud SDK

## Testing Strategy

- **Unit tests**: Located in `src/test/java` directories
- **Integration tests**: `integration/` module for cross-service testing
- **Frontend tests**: Karma/Jasmine for Angular components
- **Test execution**: Use `./nom_build test` with optional `--tests` filter
- **Coverage**: Jacoco coverage reporting enabled

## Key Domain Concepts

- **EPP (Extensible Provisioning Protocol)**: XML protocol for domain operations
- **DNS Interface**: Pluggable providers (Google Cloud DNS, RFC 2136/BIND)
- **WHOIS/RDAP**: Domain ownership information services
- **RDE (Registry Data Escrow)**: Daily exports for continuity
- **Premium Pricing**: Configurable pricing for premium domains
- **TMCH Integration**: Trademark protection via Trademark Clearinghouse
- **TLD Lifecycle**: Sunrise, Landrush, Claims, General Availability periods

## Database Management

- **Schema**: PostgreSQL with JPA/Hibernate ORM
- **Migrations**: Flyway-based versioned migrations in `db/src/main/resources/sql/flyway/`
- **Schema validation**: Automated checks against golden files
- **Test data**: Comprehensive test fixtures and builders

## Frontend Development (console-webapp)

- **Framework**: Angular with TypeScript
- **Build**: npm/Node.js build system
- **Development server**: `npm run start:dev` for live development
- **Testing**: `npm test` for Karma/Jasmine tests
- **Styling**: SCSS with Angular Material components

## Configuration Management

- **Environment-specific**: Separate configs for alpha, crash, qa, sandbox, production
- **Properties**: Gradle properties managed via `nom_build` script
- **Secrets**: Integration with Google Cloud Secret Manager
- **Feature flags**: Database-driven feature flag system
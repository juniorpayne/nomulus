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

## Memories

- Remember to check in your changes in git to make sure we have a way to roll back.
- Please update the issue you are working on in jira as you progress through your issues. To Do -> In Progress -> Done.
- Make sure that the issues you create are not duplicative in jira.

## Architecture & Module Structure

[Rest of the existing file content remains unchanged...]
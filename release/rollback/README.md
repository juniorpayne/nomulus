## Summary

This package contains an automated rollback tool for the Nomulus server on
AppEngine. When given the Nomulus tag of a deployed release, the tool directs
all traffics in the four recognized services (backend, default, pubapi, and
tools) to that release. In the process, it handles Nomulus tag to AppEngine
version ID translation, checks the target binary's compatibility with SQL
schema, starts/stops versions and redirects traffic in proper sequence, and
updates deployment metadata appropriately.

The tool has two limitations:

1.  This tool only accepts one release tag as rollback target, which is applied
    to all services.
2.  The tool immediately migrates all traffic to the new versions. It does not
    support gradual migration. This is not an issue now since gradual migration
    is only available in automatically scaled versions, while none of versions
    is using automatic scaling.

Although this tool is named a rollback tool, it can also reverse a rollback,
that is, rolling forward to a newer release.

## Prerequisites

This tool requires python version 3.7+. It also requires two GCP client
libraries: google-cloud-storage and google-api-python-client. They can be
installed using pip.

Registry team members should use either non-sudo pip3 or virtualenv/venv to
install the GCP libraries. A 'sudo pip install' may interfere with the Linux
tooling on your corp desktop. The non-sudo 'pip3 install' command installs the
libraries under $HOME/.local. The virtualenv or venv methods allow more control
over the installation location.

Below is an example of using virtualenv to install the libraries:

```shell
sudo apt-get install virtualenv python3-venv
python3 -m venv myproject
source myproject/bin/activate
pip install google-cloud-storage
pip install google-api-python-client
deactivate
```

If using virtualenv, make sure to run 'source myproject/bin/activate' before
running the rollback script.

## Usage

The tool can be invoked using the rollback_tool script in the Nomulus root
directory. The following parameters may be requested:

*   dev_project: This is the GCP project that hosts the release and deployment
    infrastructure, including the Spinnaker pipelines.
*   project: This is the GCP project that hosts the Nomulus server to be rolled
    back.
*   env: This is the name of the Nomulus environment, e.g., sandbox or
    production. Although the project to environment is available in Gradle
    scripts and internal configuration files, it is not easy to extract them.
    Therefore, we require the user to provide it for now.

A typical workflow goes as follows:

### Check Which Release is Serving

From the Nomulus root directory:

```shell
rollback_tool show_serving_release --dev_project ... --project ... --env ...
```

The output may look like:

```
backend nomulus-v049    nomulus-20201019-RC00
default nomulus-v049    nomulus-20201019-RC00
pubapi  nomulus-v049    nomulus-20201019-RC00
tools   nomulus-v049    nomulus-20201019-RC00
```

### Review Recent Deployments

```shell
rollback_tool show_recent_deployments --dev_project ... --project ... --env ...
```

This command displays up to 3 most recent deployments. The output (from sandbox
which only has two tracked deployments as of the writing of this document) may
look like:

```
backend nomulus-v048    nomulus-20201012-RC00
default nomulus-v048    nomulus-20201012-RC00
pubapi  nomulus-v048    nomulus-20201012-RC00
tools   nomulus-v048    nomulus-20201012-RC00
backend nomulus-v049    nomulus-20201019-RC00
default nomulus-v049    nomulus-20201019-RC00
pubapi  nomulus-v049    nomulus-20201019-RC00
tools   nomulus-v049    nomulus-20201019-RC00
```

### Roll to the Target Release

```shell
rollback_tool rollback --dev_project ... --project ... --env ... \
    --targt_release {YOUR_CHOSEN_TAG} --run_mode ...
```

The rollback subcommand has two new parameters:

*   target_release: This is the Nomulus tag of the target release, in the form
    of nomulus-YYYYMMDD-RC[0-9][0-9]
*   run_mode: This is the execution mode of the rollback action. There are three
    modes:
    1.  dryrun: The tool will only output information about every step of the
        rollback, including commands that a user can copy and run elsewhere.
    2.  interactive: The tool will prompt the user before executing each step.
        The user may choose to abort the rollback, skip the step, or continue
        with the step.
    3.  automatic: Tool will execute all steps in one shot.

The rollback steps are organized according to the following logic:

```
    for service in ['backend', 'default', 'pubapi', 'tools']:
        if service is on basicScaling: (See Notes # 1)
            start the target version
        if service is on manualScaling:
            start the target version
            set num_instances to its originally configured value

    for service in ['backend', 'default', 'pubapi', 'tools']:
        direct traffic to target version

    for service in ['backend', 'default', 'pubapi', 'tools']:
        if originally serving version is not the target version:
            if originally serving version is on basicaScaling
                stop the version
            if originally serving version is on manualScaling:
                stop the version
                set_num_instances to 1 (See Notes #2)
```

Notes:

1.  Versions on automatic scaling cannot be started or stopped by gcloud or the
    AppEngine Admin REST API.

2.  The minimum value assignable to num_instances through the REST API is 1.
    This instance eventually will be released too.

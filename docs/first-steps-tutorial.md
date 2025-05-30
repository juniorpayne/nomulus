# First steps tutorial

This document covers the first steps of creating some test entities in a newly
deployed and configured testing environment. It isn't required, but it does help
gain familiarity with the system. If you have not already done so, you must
first complete [installation](./install.md) and [initial
configuration](./configuration.md).

Note: Do not create these entities on a production environment! All commands
below use the [`nomulus` admin tool](./admin-tool.md) to interact with the
running registry system. We'll assume that all commands below are running in the
`alpha` environment; if you named your environment differently, then use that
everywhere that `alpha` appears.

## Temporary extra step

Using the `nomulus` admin tool currently requires an additional step to enable
full functionality. This step should **not** be done for a production
deployment—a suitable solution for production is in progress.

Modify the `core/src/main/java/google/registry/env/common/tools/WEB-INF/web.xml`
file to remove the admin-only restriction. Look for the
`<auth-constraint>admin</auth-constraint>` element, comment it out and redeploy
the tools module to your live app.

## Create a TLD

Pick the name of a TLD to create. For the purposes of this example we'll use
"example", which conveniently happens to be an ICANN reserved string, meaning
it'll never be created for real on the Internet at large.

TLDs are now configured using YAML files. First, create a TLD configuration file `example.yaml`:

```yaml
# example.yaml - TLD configuration for .example
addGracePeriodLength: "PT432000S"
allowedFullyQualifiedHostNames: []
allowedRegistrantContactIds: []
anchorTenantAddGracePeriodLength: "PT2592000S"
autoRenewGracePeriodLength: "PT3888000S"
automaticTransferLength: "PT432000S"
claimsPeriodEnd: "294247-01-10T04:00:54.775Z"
createBillingCost:
  currency: "USD"
  amount: 8.00
currency: "USD"
defaultPromoTokens: []
dnsAPlusAaaaTtl: null
dnsDsTtl: null
dnsNsTtl: null
dnsPaused: true
dnsWriters:
- "VoidDnsWriter"
driveFolderId: null
eapFeeSchedule:
  "1970-01-01T00:00:00.000Z":
    currency: "USD"
    amount: 0.00
escrowEnabled: false
idnTables: []
invoicingEnabled: false
lordnUsername: null
numDnsPublishLocks: 1
pendingDeleteLength: "PT432000S"
premiumListName: null
pricingEngineClassName: "google.registry.model.pricing.StaticPremiumListPricingEngine"
redemptionGracePeriodLength: "PT2592000S"
registryLockOrUnlockBillingCost:
  currency: "USD"
  amount: 0.00
renewBillingCostTransitions:
  "1970-01-01T00:00:00.000Z":
    currency: "USD"
    amount: 8.00
renewGracePeriodLength: "PT432000S"
reservedListNames: []
restoreBillingCost:
  currency: "USD"
  amount: 50.00
roidSuffix: "EXAMPLE"
serverStatusChangeBillingCost:
  currency: "USD"
  amount: 0.00
tldStateTransitions:
  "1970-01-01T00:00:00.000Z": "GENERAL_AVAILABILITY"
tldStr: "example"
tldType: "TEST"
tldUnicode: "example"
transferGracePeriodLength: "PT432000S"
```

Then configure the TLD using the YAML file:

```shell
$ nomulus -e alpha configure_tld --input example.yaml
[ ... snip confirmation prompt ... ]
Perform this command? (y/N): y
Updated 1 entities.
```

*   `-e` is the environment name (`alpha` in this example).
*   `configure_tld` is the subcommand to create or update a TLD using a YAML configuration file.
*   `--input` specifies the path to the YAML configuration file.
*   The YAML file defines all TLD properties including:
    *   `tldStr`: The TLD name ("example")
    *   `tldType`: `TEST` for testing purposes, `REAL` for live TLDs
    *   `roidSuffix`: Repository ID suffix (must be uppercase, max 8 ASCII characters)
    *   `tldStateTransitions`: Defines the TLD state over time (`GENERAL_AVAILABILITY` allows immediate domain creation)
    *   `dnsWriters`: List of DNS writer modules (`VoidDnsWriter` for testing without real DNS)
    *   Various grace periods, billing costs, and other registry parameters

You can see the complete example TLD configuration in `core/src/main/java/google/registry/config/files/tld/example.yaml`.

## Create a registrar

Now we need to create a registrar and give it access to operate on the example
TLD. For the purposes of our example we'll name the registrar "Acme".

```shell
$ nomulus -e alpha create_registrar acme --name 'ACME Corp' \
  --registrar_type TEST --password hunter2 \
  --icann_referral_email blaine@acme.example --street '123 Fake St' \
  --city 'Fakington' --state MA --zip 12345 --cc US --allowed_tlds example
[ ... snip confirmation prompt ... ]
Perform this command? (y/N): y
Updated 1 entities.
Skipping registrar groups creation because only production and sandbox
support it.
```

Where:

*   `create_registrar` is the subcommand to create a registrar. The argument you
    provide ("acme") is the registrar id, called the client identifier, that is
    the primary key used to refer to the registrar both internally and
    externally.
*   `--name` indicates the display name of the registrar, in this case `ACME
    Corp`.
*   `--registrar_type` is the type of registrar. `TEST` identifies that the
    registrar is for testing purposes, where `REAL` identifies the registrar is
    a real live registrar.
*   `--password` is the password used by the registrar to log in to the domain
    registry system.
*   `--icann_referral_email` is the email address associated with the initial
    creation of the registrar. This address cannot be changed.
*   `--allowed_tlds` is a comma-delimited list of top level domains where this
    registrar has access.

## Create a contact

Now we want to create a contact, as a contact is required before a domain can be
created. Contacts can be used on any number of domains across any number of
TLDs, and contain the information on who owns or provides technical support for
a TLD. These details will appear in WHOIS queries.

```shell
$ nomulus -e alpha create_contact -c acme --id abcd1234 \
  --name 'John Smith' --street '234 Fake St' --city 'North Fakington' \
  --state MA --zip 23456 --cc US --email jsmith@e.mail
[ ... snip EPP response ... ]
```

Where:

*   `create_contact` is the subcommand to create a contact.
*   `-c` is used to define the registrar. The `-c` option is used with most
    `registry_tool` commands to specify the id of the registrar executing the
    command. Contact, domain, and host creation all work by constructing an EPP
    message that is sent to the registry, and EPP commands need to run under the
    context of a registrar. The "acme" registrar that was created above is used
    for this purpose.
*   `--id` is the contact id, and is referenced elsewhere in the system (e.g.
    when a domain is created and the admin contact is specified).
*   `--name` is the display name of the contact, which is usually the name of a
    company or of a person.

The address and `email` fields are required to create a contact.

## Create a host

Hosts are used to specify the IP addresses (either v4 or v6) that are associated
with a given nameserver. Note that hosts may either be in-bailiwick (on a TLD
that this registry runs) or out-of-bailiwick. In-bailiwick hosts may
additionally be subordinate (a subdomain of a domain name that is on this
registry). Let's create an out-of-bailiwick nameserver, which is the simplest
type.

```shell
$ nomulus -e alpha create_host -c acme --host ns1.google.com
[ ... snip EPP response ... ]
```

Where:

*   `create_host` is the subcommand to create a host.
*   `--host` is the name of the host.
*   `--addresses` (not used here) is the comma-delimited list of IP addresses
    for the host in IPv4 or IPv6 format, if applicable.

Note that hosts are required to have IP addresses if they are subordinate, and
must not have IP addresses if they are not subordinate.

## Create a domain

To tie it all together, let's create a domain name that uses the above contact
and host.

```shell
$ nomulus -e alpha create_domain fake.example -c acme --admins abcd1234 \
  --techs abcd1234 --registrant abcd1234 --nameservers ns1.google.com
[ ... snip EPP response ... ]
```

Where:

*   `create_domain` is the subcommand to create a domain name. It accepts a
    whitespace-separated list of domain names to be created
*   `-c` is used to define the registrar (same as `--client`).
*   `--admins` is the administrative contact's id(s).
*   `--techs` is the technical contact's id(s).
*   `--registrant` is the registrant contact's id.
*   `--nameservers` is a comma-separated list of hosts.

Note how the same contact id is used for the administrative, technical, and
registrant contact. It is common for domain names to use the same details for
all contacts on a domain name.

## Verify test entities using WHOIS

To verify that everything worked, let's query the WHOIS information for
fake.example:

```shell
$ nomulus -e alpha whois_query fake.example
[ ... snip WHOIS response ... ]
```

You should see all of the information in WHOIS that you entered above for the
contact, nameserver, and domain.

[roids]: https://www.icann.org/resources/pages/correction-non-compliant-roids-2015-08-26-en

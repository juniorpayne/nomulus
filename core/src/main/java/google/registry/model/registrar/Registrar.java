// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.model.registrar;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.ImmutableSortedSet.toImmutableSortedSet;
import static com.google.common.collect.Sets.immutableEnumSet;
import static com.google.common.io.BaseEncoding.base64;
import static google.registry.config.RegistryConfig.getDefaultRegistrarWhoisServer;
import static google.registry.model.CacheUtils.memoizeWithShortExpiration;
import static google.registry.model.tld.Registries.assertTldsExist;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableSortedCopy;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.PasswordUtils.SALT_SUPPLIER;
import static google.registry.util.PasswordUtils.hashPassword;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static google.registry.util.X509Utils.getCertificateHash;
import static google.registry.util.X509Utils.loadCertificate;
import static java.util.Comparator.comparing;
import static java.util.function.Predicate.isEqual;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.re2j.Pattern;
import google.registry.model.Buildable;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.JsonMapBuilder;
import google.registry.model.Jsonifiable;
import google.registry.model.UpdateAutoTimestamp;
import google.registry.model.UpdateAutoTimestampEntity;
import google.registry.model.tld.Registry;
import google.registry.model.tld.Registry.TldType;
import google.registry.persistence.VKey;
import google.registry.util.CidrAddressBlock;
import java.security.cert.CertificateParsingException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.Embedded;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import org.joda.money.CurrencyUnit;
import org.joda.time.DateTime;

/** Information about a registrar. */
@Entity
@Table(
    indexes = {
      @Index(columnList = "registrarName", name = "registrar_name_idx"),
      @Index(columnList = "ianaIdentifier", name = "registrar_iana_identifier_idx"),
    })
@AttributeOverride(
    name = "updateTimestamp.lastUpdateTime",
    column = @Column(nullable = false, name = "lastUpdateTime"))
public class Registrar extends UpdateAutoTimestampEntity implements Buildable, Jsonifiable {

  /** Represents the type of a registrar entity. */
  public enum Type {
    /** A real-world, third-party registrar. Should have non-null IANA and billing account IDs. */
    REAL(Objects::nonNull),

    /**
     * A registrar account used by a real third-party registrar undergoing operational testing and
     * evaluation. Should only be created in sandbox, and should have null IANA/billing account IDs.
     */
    OTE(Objects::isNull),

    /**
     * A registrar used for predelegation testing. Should have a null billing account ID. The IANA
     * ID should be either 9995 or 9996, which are reserved for predelegation testing.
     */
    PDT(n -> ImmutableSet.of(9995L, 9996L).contains(n)),

    /**
     * A registrar used for external monitoring by ICANN. Should have IANA ID 9997 and a null
     * billing account ID.
     */
    EXTERNAL_MONITORING(isEqual(9997L)),

    /**
     * A registrar used for when the registry acts as a registrar. Must have either IANA ID 9998
     * (for billable transactions) or 9999 (for non-billable transactions).
     */
    // TODO(b/13786188): determine what billing account ID for this should be, if any.
    INTERNAL(n -> ImmutableSet.of(9998L, 9999L).contains(n)),

    /** A registrar used for internal monitoring. Should have null IANA/billing account IDs. */
    MONITORING(Objects::isNull),

    /** A registrar used for internal testing. Should have null IANA/billing account IDs. */
    TEST(Objects::isNull);

    /**
     * Predicate for validating IANA IDs for this type of registrar.
     *
     * @see <a href="http://www.iana.org/assignments/registrar-ids/registrar-ids.txt">Registrar
     *     IDs</a>
     */
    @SuppressWarnings("ImmutableEnumChecker")
    private final Predicate<Long> ianaIdValidator;

    Type(Predicate<Long> ianaIdValidator) {
      this.ianaIdValidator = ianaIdValidator;
    }

    /** Returns true if the given IANA identifier is valid for this registrar type. */
    public boolean isValidIanaId(Long ianaId) {
      return ianaIdValidator.test(ianaId);
    }
  }

  /** Represents the state of a persisted registrar entity. */
  public enum State {

    /** This registrar is provisioned but not yet active, and cannot log in. */
    PENDING,

    /** This is an active registrar account which is allowed to provision and modify domains. */
    ACTIVE,

    /**
     * This is a suspended account which is disallowed from provisioning new domains, but can
     * otherwise still perform other operations to continue operations.
     */
    SUSPENDED,

    /**
     * This registrar is completely disabled and cannot perform any EPP actions whatsoever, nor log
     * in to the registrar console.
     */
    DISABLED
  }

  /** Regex for E.164 phone number format specified by {@code contact.xsd}. */
  private static final Pattern E164_PATTERN = Pattern.compile("\\+[0-9]{1,3}\\.[0-9]{1,14}");

  /** Regex for telephone support passcode (5 digit string). */
  public static final Pattern PHONE_PASSCODE_PATTERN = Pattern.compile("\\d{5}");

  /** The states in which a {@link Registrar} is considered {@link #isLive live}. */
  private static final ImmutableSet<State> LIVE_STATES =
      immutableEnumSet(State.ACTIVE, State.SUSPENDED);

  /**
   * The types for which a {@link Registrar} should be included in WHOIS and RDAP output. We exclude
   * registrars of type TEST. We considered excluding INTERNAL as well, but decided that
   * troubleshooting would be easier with INTERNAL registrars visible. Before removing other types
   * from view, carefully consider the effect on things like prober monitoring and OT&E.
   */
  private static final ImmutableSet<Type> PUBLICLY_VISIBLE_TYPES =
      immutableEnumSet(
          Type.REAL, Type.PDT, Type.OTE, Type.EXTERNAL_MONITORING, Type.MONITORING, Type.INTERNAL);

  /** Compare two instances of {@link RegistrarPoc} by their email addresses lexicographically. */
  private static final Comparator<RegistrarPoc> CONTACT_EMAIL_COMPARATOR =
      comparing(RegistrarPoc::getEmailAddress, String::compareTo);

  /** A caching {@link Supplier} of a registrarId to {@link Registrar} map. */
  private static final Supplier<ImmutableMap<String, Registrar>> CACHE_BY_REGISTRAR_ID =
      memoizeWithShortExpiration(() -> Maps.uniqueIndex(loadAll(), Registrar::getRegistrarId));

  /**
   * Unique registrar client id. Must conform to "clIDType" as defined in RFC5730.
   *
   * @see <a href="http://tools.ietf.org/html/rfc5730#section-4.2">Shared Structure Schema</a>
   */
  @Id
  @Column(nullable = false)
  String registrarId;

  /**
   * Registrar name. This is a distinct from the client identifier since there are no restrictions
   * on its length.
   *
   * <p>NB: We are assuming that this field is unique across all registrar entities. This is not
   * formally enforced in Datastore, but should be enforced by ICANN in that no two registrars will
   * be accredited with the same name.
   *
   * @see <a href="http://www.icann.org/registrar-reports/accredited-list.html">ICANN-Accredited
   *     Registrars</a>
   */
  @Column(nullable = false)
  String registrarName;

  /** The type of this registrar. */
  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  Type type;

  /** The state of this registrar. */
  @Enumerated(EnumType.STRING)
  State state;

  /** The set of TLDs which this registrar is allowed to access. */
  Set<String> allowedTlds;

  /** Host name of WHOIS server. */
  String whoisServer;

  /** Base URLs for the registrar's RDAP servers. */
  Set<String> rdapBaseUrls;

  /**
   * Whether registration of premium names should be blocked over EPP. If this is set to true, then
   * the only way to register premium names is with the superuser flag.
   */
  boolean blockPremiumNames;

  // Authentication.

  /** X.509 PEM client certificate(s) used to authenticate registrar to EPP service. */
  String clientCertificate;

  /** Base64 encoded SHA256 hash of {@link #clientCertificate}. */
  String clientCertificateHash;

  /**
   * Optional secondary X.509 PEM certificate to try if {@link #clientCertificate} does not work.
   *
   * <p>This allows registrars to migrate certificates without downtime.
   */
  String failoverClientCertificate;

  /** Base64 encoded SHA256 hash of {@link #failoverClientCertificate}. */
  String failoverClientCertificateHash;

  /** An allow list of netmasks (in CIDR notation) which the client is allowed to connect from. */
  List<CidrAddressBlock> ipAddressAllowList;

  /** A hashed password for EPP access. The hash is a base64 encoded SHA256 string. */
  String passwordHash;

  /** Randomly generated hash salt. */
  @Column(name = "password_salt")
  String salt;

  // The following fields may appear redundant to the above, but are
  // implied by RFC examples and should be interpreted as "for the
  // Registrar as a whole".
  /**
   * Localized {@link RegistrarAddress} for this registrar. Contents can be represented in
   * unrestricted UTF-8.
   */
  @Embedded
  @AttributeOverrides({
    @AttributeOverride(
        name = "streetLine1",
        column = @Column(name = "localized_address_street_line1")),
    @AttributeOverride(
        name = "streetLine2",
        column = @Column(name = "localized_address_street_line2")),
    @AttributeOverride(
        name = "streetLine3",
        column = @Column(name = "localized_address_street_line3")),
    @AttributeOverride(name = "city", column = @Column(name = "localized_address_city")),
    @AttributeOverride(name = "state", column = @Column(name = "localized_address_state")),
    @AttributeOverride(name = "zip", column = @Column(name = "localized_address_zip")),
    @AttributeOverride(
        name = "countryCode",
        column = @Column(name = "localized_address_country_code"))
  })
  RegistrarAddress localizedAddress;

  /**
   * Internationalized {@link RegistrarAddress} for this registrar. All contained values must be
   * representable in the 7-bit US-ASCII character set.
   */
  @Embedded
  @AttributeOverrides({
    @AttributeOverride(name = "streetLine1", column = @Column(name = "i18n_address_street_line1")),
    @AttributeOverride(name = "streetLine2", column = @Column(name = "i18n_address_street_line2")),
    @AttributeOverride(name = "streetLine3", column = @Column(name = "i18n_address_street_line3")),
    @AttributeOverride(name = "city", column = @Column(name = "i18n_address_city")),
    @AttributeOverride(name = "state", column = @Column(name = "i18n_address_state")),
    @AttributeOverride(name = "zip", column = @Column(name = "i18n_address_zip")),
    @AttributeOverride(name = "countryCode", column = @Column(name = "i18n_address_country_code"))
  })
  RegistrarAddress internationalizedAddress;

  /** Voice number. */
  String phoneNumber;

  /** Fax number. */
  String faxNumber;

  /** Email address. */
  String emailAddress;

  // External IDs.

  /**
   * Registrar identifier used for reporting to ICANN.
   *
   * <ul>
   *   <li>8 is used for Testing Registrar.
   *   <li>9997 is used by ICANN for SLA monitoring.
   *   <li>9999 is used for cases when the registry operator acts as registrar.
   * </ul>
   *
   * @see <a href="http://www.iana.org/assignments/registrar-ids/registrar-ids.txt">Registrar
   *     IDs</a>
   */
  @Nullable Long ianaIdentifier;

  /** Purchase Order number used for invoices in external billing system, if applicable. */
  @Nullable String poNumber;

  /**
   * Map of currency-to-billing account for the registrar.
   *
   * <p>A registrar can have different billing accounts that are denoted in different currencies.
   * This provides flexibility for billing systems that require such distinction. When this field is
   * accessed by {@link #getBillingAccountMap}, a sorted map is returned to guarantee deterministic
   * behavior when serializing the map, for display purpose for instance.
   */
  @Nullable Map<CurrencyUnit, String> billingAccountMap;

  /** URL of registrar's website. */
  String url;

  /**
   * ICANN referral email address.
   *
   * <p>This value is specified in the initial registrar contact. It can't be edited in the web GUI,
   * and it must be specified when the registrar account is created.
   */
  String icannReferralEmail;

  /** Id of the folder in drive used to publish information for this registrar. */
  String driveFolderId;

  // Metadata.

  /** The time when this registrar was created. */
  CreateAutoTimestamp creationTime = CreateAutoTimestamp.create(null);

  /** The time that the certificate was last updated. */
  DateTime lastCertificateUpdateTime;

  /** The time that an expiring certificate notification email was sent to the registrar. */
  DateTime lastExpiringCertNotificationSentDate = START_OF_TIME;

  /**
   * The time that an expiring failover certificate notification email was sent to the registrar.
   */
  DateTime lastExpiringFailoverCertNotificationSentDate = START_OF_TIME;

  /** Telephone support passcode (5-digit numeric) */
  String phonePasscode;

  /**
   * A dirty bit for whether RegistrarContact changes have been made that haven't been synced to
   * Google Groups yet. When creating a new instance, contacts require syncing by default.
   */
  boolean contactsRequireSyncing = true;

  /** Whether or not registry lock is allowed for this registrar. */
  boolean registryLockAllowed = false;

  public String getRegistrarId() {
    return registrarId;
  }

  public DateTime getCreationTime() {
    return creationTime.getTimestamp();
  }

  @Nullable
  public Long getIanaIdentifier() {
    return ianaIdentifier;
  }

  public Optional<String> getPoNumber() {
    return Optional.ofNullable(poNumber);
  }

  public ImmutableSortedMap<CurrencyUnit, String> getBillingAccountMap() {
    return billingAccountMap == null
        ? ImmutableSortedMap.of()
        : ImmutableSortedMap.copyOf(billingAccountMap);
  }

  public DateTime getLastUpdateTime() {
    return getUpdateTimestamp().getTimestamp();
  }

  public DateTime getLastCertificateUpdateTime() {
    return lastCertificateUpdateTime;
  }

  public DateTime getLastExpiringCertNotificationSentDate() {
    return lastExpiringCertNotificationSentDate;
  }

  public DateTime getLastExpiringFailoverCertNotificationSentDate() {
    return lastExpiringFailoverCertNotificationSentDate;
  }

  public String getRegistrarName() {
    return registrarName;
  }

  public Type getType() {
    return type;
  }

  public State getState() {
    return state;
  }

  public ImmutableSortedSet<String> getAllowedTlds() {
    return nullToEmptyImmutableSortedCopy(allowedTlds);
  }

  /**
   * Returns {@code true} if the registrar is live.
   *
   * <p>A live registrar is one that can have live domains/contacts/hosts in the registry, meaning
   * that it is either currently active or used to be active (i.e. suspended).
   */
  public boolean isLive() {
    return LIVE_STATES.contains(state);
  }

  /** Returns {@code true} if registrar should be visible in WHOIS results. */
  public boolean isLiveAndPubliclyVisible() {
    return LIVE_STATES.contains(state) && PUBLICLY_VISIBLE_TYPES.contains(type);
  }

  /** Returns the client certificate string if it has been set, or empty otherwise. */
  public Optional<String> getClientCertificate() {
    return Optional.ofNullable(clientCertificate);
  }

  /** Returns the client certificate hash if it has been set, or empty otherwise. */
  public Optional<String> getClientCertificateHash() {
    return Optional.ofNullable(clientCertificateHash);
  }

  /** Returns the failover client certificate string if it has been set, or empty otherwise. */
  public Optional<String> getFailoverClientCertificate() {
    return Optional.ofNullable(failoverClientCertificate);
  }

  /** Returns the failover client certificate hash if it has been set, or empty otherwise. */
  public Optional<String> getFailoverClientCertificateHash() {
    return Optional.ofNullable(failoverClientCertificateHash);
  }

  public ImmutableList<CidrAddressBlock> getIpAddressAllowList() {
    return nullToEmptyImmutableCopy(ipAddressAllowList);
  }

  public RegistrarAddress getLocalizedAddress() {
    return localizedAddress;
  }

  public RegistrarAddress getInternationalizedAddress() {
    return internationalizedAddress;
  }

  public String getPhoneNumber() {
    return phoneNumber;
  }

  public String getFaxNumber() {
    return faxNumber;
  }

  public String getEmailAddress() {
    return emailAddress;
  }

  public String getWhoisServer() {
    return firstNonNull(whoisServer, getDefaultRegistrarWhoisServer());
  }

  public ImmutableSet<String> getRdapBaseUrls() {
    return nullToEmptyImmutableSortedCopy(rdapBaseUrls);
  }

  public boolean getBlockPremiumNames() {
    return blockPremiumNames;
  }

  public boolean getContactsRequireSyncing() {
    return contactsRequireSyncing;
  }

  public boolean isRegistryLockAllowed() {
    return registryLockAllowed;
  }

  public String getUrl() {
    return url;
  }

  public String getIcannReferralEmail() {
    return nullToEmpty(icannReferralEmail);
  }

  public String getDriveFolderId() {
    return driveFolderId;
  }

  /**
   * Returns a list of all {@link RegistrarPoc} objects for this registrar sorted by their email
   * address.
   */
  public ImmutableSortedSet<RegistrarPoc> getContacts() {
    return Streams.stream(getContactsIterable())
        .filter(Objects::nonNull)
        .collect(toImmutableSortedSet(CONTACT_EMAIL_COMPARATOR));
  }

  /**
   * Returns a list of {@link RegistrarPoc} objects of a given type for this registrar sorted by
   * their email address.
   */
  public ImmutableSortedSet<RegistrarPoc> getContactsOfType(final RegistrarPoc.Type type) {
    return Streams.stream(getContactsIterable())
        .filter(Objects::nonNull)
        .filter((@Nullable RegistrarPoc contact) -> contact.getTypes().contains(type))
        .collect(toImmutableSortedSet(CONTACT_EMAIL_COMPARATOR));
  }

  /**
   * Returns the {@link RegistrarPoc} that is the WHOIS abuse contact for this registrar, or empty
   * if one does not exist.
   */
  public Optional<RegistrarPoc> getWhoisAbuseContact() {
    return getContacts().stream().filter(RegistrarPoc::getVisibleInDomainWhoisAsAbuse).findFirst();
  }

  private Iterable<RegistrarPoc> getContactsIterable() {
    return tm().transact(
            () ->
                jpaTm()
                    .query("FROM RegistrarPoc WHERE registrarId = :registrarId", RegistrarPoc.class)
                    .setParameter("registrarId", registrarId)
                    .getResultStream()
                    .collect(toImmutableList()));
  }

  @Override
  public Map<String, Object> toJsonMap() {
    return new JsonMapBuilder()
        .put("registrarId", registrarId)
        .put("ianaIdentifier", ianaIdentifier)
        .putString("creationTime", creationTime.getTimestamp())
        .putString("lastUpdateTime", getUpdateTimestamp().getTimestamp())
        .putString("lastCertificateUpdateTime", lastCertificateUpdateTime)
        .putString("lastExpiringCertNotificationSentDate", lastExpiringCertNotificationSentDate)
        .putString(
            "lastExpiringFailoverCertNotificationSentDate",
            lastExpiringFailoverCertNotificationSentDate)
        .put("registrarName", registrarName)
        .put("type", type)
        .put("state", state)
        .put("clientCertificate", clientCertificate)
        .put("clientCertificateHash", clientCertificateHash)
        .put("failoverClientCertificate", failoverClientCertificate)
        .put("failoverClientCertificateHash", failoverClientCertificateHash)
        .put("localizedAddress", localizedAddress)
        .put("internationalizedAddress", internationalizedAddress)
        .put("phoneNumber", phoneNumber)
        .put("faxNumber", faxNumber)
        .put("emailAddress", emailAddress)
        .put("whoisServer", getWhoisServer())
        .putListOfStrings("rdapBaseUrls", getRdapBaseUrls())
        .put("blockPremiumNames", blockPremiumNames)
        .put("url", url)
        .put("icannReferralEmail", getIcannReferralEmail())
        .put("driveFolderId", driveFolderId)
        .put("phoneNumber", phoneNumber)
        .put("phonePasscode", phonePasscode)
        .putListOfStrings("allowedTlds", getAllowedTlds())
        .putListOfStrings("ipAddressAllowList", getIpAddressAllowList())
        .putListOfJsonObjects("contacts", getContacts())
        .put("registryLockAllowed", registryLockAllowed)
        .build();
  }

  private static String checkValidPhoneNumber(String phoneNumber) {
    checkArgument(
        E164_PATTERN.matcher(phoneNumber).matches(),
        "Not a valid E.164 phone number: %s",
        phoneNumber);
    return phoneNumber;
  }

  public boolean verifyPassword(String password) {
    return hashPassword(password, salt).equals(passwordHash);
  }

  public String getPhonePasscode() {
    return phonePasscode;
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** Creates a {@link VKey} for this instance. */
  @Override
  public VKey<Registrar> createVKey() {
    return createVKey(registrarId);
  }

  /** Creates a {@link VKey} for the given {@code registrarId}. */
  public static VKey<Registrar> createVKey(String registrarId) {
    checkArgumentNotNull(registrarId, "registrarId must be specified");
    return VKey.create(Registrar.class, registrarId);
  }

  /** A builder for constructing {@link Registrar}, since it is immutable. */
  public static class Builder extends Buildable.Builder<Registrar> {
    public Builder() {}

    private Builder(Registrar instance) {
      super(instance);
    }

    public Builder setRegistrarId(String registrarId) {
      // Registrar id must be [3,16] chars long. See "clIDType" in the base EPP schema of RFC 5730.
      // (Need to validate this here as there's no matching EPP XSD for validation.)
      checkArgument(
          Range.closed(3, 16).contains(registrarId.length()),
          "Registrar ID must be 3-16 characters long.");
      getInstance().registrarId = registrarId;
      return this;
    }

    public Builder setIanaIdentifier(@Nullable Long ianaIdentifier) {
      checkArgument(
          ianaIdentifier == null || ianaIdentifier > 0, "IANA ID must be a positive number");
      getInstance().ianaIdentifier = ianaIdentifier;
      return this;
    }

    public Builder setPoNumber(Optional<String> poNumber) {
      getInstance().poNumber = poNumber.orElse(null);
      return this;
    }

    public Builder setBillingAccountMap(@Nullable Map<CurrencyUnit, String> billingAccountMap) {
      getInstance().billingAccountMap = nullToEmptyImmutableCopy(billingAccountMap);
      return this;
    }

    public Builder setRegistrarName(String registrarName) {
      getInstance().registrarName = registrarName;
      return this;
    }

    public Builder setType(Type type) {
      getInstance().type = type;
      return this;
    }

    public Builder setState(State state) {
      getInstance().state = state;
      return this;
    }

    public Builder setAllowedTlds(Set<String> allowedTlds) {
      getInstance().allowedTlds = ImmutableSortedSet.copyOf(assertTldsExist(allowedTlds));
      return this;
    }

    /**
     * Same as {@link #setAllowedTlds}, but doesn't use the cache to check if the TLDs exist.
     *
     * <p>This should be used if the TLD we want to set is persisted in the same transaction -
     * meaning its existence can't be cached before we need to save the Registrar.
     *
     * <p>We can still only set the allowedTld AFTER we saved the Registry entity. Make sure to call
     * {@code .now()} when saving the Registry entity to make sure it's actually saved before trying
     * to set the allowed TLDs.
     */
    public Builder setAllowedTldsUncached(Set<String> allowedTlds) {
      ImmutableSet<VKey<Registry>> newTldKeys =
          Sets.difference(allowedTlds, getInstance().getAllowedTlds()).stream()
              .map(Registry::createVKey)
              .collect(toImmutableSet());
      Set<VKey<Registry>> missingTldKeys =
          Sets.difference(
              newTldKeys, tm().transact(() -> tm().loadByKeysIfPresent(newTldKeys)).keySet());
      checkArgument(missingTldKeys.isEmpty(), "Trying to set nonexistent TLDs: %s", missingTldKeys);
      getInstance().allowedTlds = ImmutableSortedSet.copyOf(allowedTlds);
      return this;
    }

    public Builder setClientCertificate(String clientCertificate, DateTime now) {
      clientCertificate = emptyToNull(clientCertificate);
      String clientCertificateHash = calculateHash(clientCertificate);
      if (!Objects.equals(clientCertificate, getInstance().clientCertificate)
          || !Objects.equals(clientCertificateHash, getInstance().clientCertificateHash)) {
        getInstance().clientCertificate = clientCertificate;
        getInstance().clientCertificateHash = clientCertificateHash;
        getInstance().lastCertificateUpdateTime = now;
      }
      return this;
    }

    public Builder setLastExpiringCertNotificationSentDate(DateTime now) {
      checkArgumentNotNull(now, "Registrar lastExpiringCertNotificationSentDate cannot be null");
      getInstance().lastExpiringCertNotificationSentDate = now;
      return this;
    }

    public Builder setLastExpiringFailoverCertNotificationSentDate(DateTime now) {
      checkArgumentNotNull(
          now, "Registrar lastExpiringFailoverCertNotificationSentDate cannot be null");
      getInstance().lastExpiringFailoverCertNotificationSentDate = now;
      return this;
    }

    public Builder setFailoverClientCertificate(String clientCertificate, DateTime now) {
      clientCertificate = emptyToNull(clientCertificate);
      String clientCertificateHash = calculateHash(clientCertificate);
      if (!Objects.equals(clientCertificate, getInstance().failoverClientCertificate)
          || !Objects.equals(clientCertificateHash, getInstance().failoverClientCertificateHash)) {
        getInstance().failoverClientCertificate = clientCertificate;
        getInstance().failoverClientCertificateHash = clientCertificateHash;
        getInstance().lastCertificateUpdateTime = now;
      }
      return this;
    }

    private static String calculateHash(String clientCertificate) {
      if (clientCertificate == null) {
        return null;
      }
      try {
        return getCertificateHash(loadCertificate(clientCertificate));
      } catch (CertificateParsingException e) {
        throw new IllegalArgumentException(e);
      }
    }

    public Builder setContactsRequireSyncing(boolean contactsRequireSyncing) {
      getInstance().contactsRequireSyncing = contactsRequireSyncing;
      return this;
    }

    public Builder setIpAddressAllowList(Iterable<CidrAddressBlock> ipAddressAllowList) {
      getInstance().ipAddressAllowList = ImmutableList.copyOf(ipAddressAllowList);
      return this;
    }

    public Builder setLocalizedAddress(RegistrarAddress localizedAddress) {
      getInstance().localizedAddress = localizedAddress;
      return this;
    }

    public Builder setInternationalizedAddress(RegistrarAddress internationalizedAddress) {
      getInstance().internationalizedAddress = internationalizedAddress;
      return this;
    }

    public Builder setPhoneNumber(String phoneNumber) {
      getInstance().phoneNumber = (phoneNumber == null) ? null : checkValidPhoneNumber(phoneNumber);
      return this;
    }

    public Builder setFaxNumber(String faxNumber) {
      getInstance().faxNumber = (faxNumber == null) ? null : checkValidPhoneNumber(faxNumber);
      return this;
    }

    public Builder setEmailAddress(String emailAddress) {
      getInstance().emailAddress = checkValidEmail(emailAddress);
      return this;
    }

    public Builder setWhoisServer(String whoisServer) {
      getInstance().whoisServer = whoisServer;
      return this;
    }

    public Builder setRdapBaseUrls(Set<String> rdapBaseUrls) {
      getInstance().rdapBaseUrls = ImmutableSet.copyOf(rdapBaseUrls);
      return this;
    }

    public Builder setBlockPremiumNames(boolean blockPremiumNames) {
      getInstance().blockPremiumNames = blockPremiumNames;
      return this;
    }

    public Builder setUrl(String url) {
      getInstance().url = url;
      return this;
    }

    public Builder setIcannReferralEmail(String icannReferralEmail) {
      getInstance().icannReferralEmail = checkValidEmail(icannReferralEmail);
      return this;
    }

    public Builder setDriveFolderId(@Nullable String driveFolderId) {
      checkArgument(
          driveFolderId == null || !driveFolderId.contains("/"),
          "Drive folder ID must not be a full URL");
      getInstance().driveFolderId = driveFolderId;
      return this;
    }

    public Builder setPassword(String password) {
      // Passwords must be [6,16] chars long. See "pwType" in the base EPP schema of RFC 5730.
      checkArgument(
          Range.closed(6, 16).contains(nullToEmpty(password).length()),
          "Password must be 6-16 characters long.");
      getInstance().salt = base64().encode(SALT_SUPPLIER.get());
      getInstance().passwordHash = hashPassword(password, getInstance().salt);
      return this;
    }

    /** @throws IllegalArgumentException if provided passcode is not 5-digit numeric */
    public Builder setPhonePasscode(String phonePasscode) {
      checkArgument(
          phonePasscode == null || PHONE_PASSCODE_PATTERN.matcher(phonePasscode).matches(),
          "Not a valid telephone passcode (must be 5 digits long): %s",
          phonePasscode);
      getInstance().phonePasscode = phonePasscode;
      return this;
    }

    public Builder setRegistryLockAllowed(boolean registryLockAllowed) {
      getInstance().registryLockAllowed = registryLockAllowed;
      return this;
    }

    /**
     * This lets tests set the update timestamp in cases where setting fields resets the timestamp
     * and breaks the verification that an object has not been updated since it was copied.
     */
    @VisibleForTesting
    public Builder setLastUpdateTime(DateTime timestamp) {
      getInstance().setUpdateTimestamp(UpdateAutoTimestamp.create(timestamp));
      return this;
    }

    /** Build the registrar, nullifying empty fields. */
    @Override
    public Registrar build() {
      checkArgumentNotNull(getInstance().type, "Registrar type cannot be null");
      checkArgumentNotNull(getInstance().registrarName, "Registrar name cannot be null");
      checkArgument(
          getInstance().localizedAddress != null || getInstance().internationalizedAddress != null,
          "Must specify at least one of localized or internationalized address");
      checkArgument(
          getInstance().type.isValidIanaId(getInstance().ianaIdentifier),
          String.format(
              "Supplied IANA ID is not valid for %s registrar type: %s",
              getInstance().type, getInstance().ianaIdentifier));

      // In order to grant access to real TLDs, the registrar must have a corresponding billing
      // account ID for that TLD's billing currency.
      ImmutableSet<String> nonBillableTlds =
          Registry.get(getInstance().getAllowedTlds()).stream()
              .filter(r -> r.getTldType() == TldType.REAL)
              .filter(r -> !getInstance().getBillingAccountMap().containsKey(r.getCurrency()))
              .map(Registry::getTldStr)
              .collect(toImmutableSet());
      checkArgument(
          nonBillableTlds.isEmpty(),
          "Cannot set these allowed, real TLDs because their currency is missing "
              + "from the billing account map: %s",
          nonBillableTlds);
      return cloneEmptyToNull(super.build());
    }
  }

  /** Verifies that the email address in question is not null and has a valid format. */
  public static String checkValidEmail(String email) {
    checkNotNull(email, "Provided email was null");
    try {
      InternetAddress internetAddress = new InternetAddress(email, true);
      internetAddress.validate();
    } catch (AddressException e) {
      throw new IllegalArgumentException(
          String.format("Provided email %s is not a valid email address", email));
    }
    return email;
  }

  /** Loads all registrar entities directly from Datastore. */
  public static Iterable<Registrar> loadAll() {
    return tm().transact(() -> tm().loadAllOf(Registrar.class));
  }

  /** Loads all registrar entities using an in-memory cache. */
  public static Iterable<Registrar> loadAllCached() {
    return CACHE_BY_REGISTRAR_ID.get().values();
  }

  /** Loads all registrar keys using an in-memory cache. */
  public static ImmutableSet<VKey<Registrar>> loadAllKeysCached() {
    return CACHE_BY_REGISTRAR_ID.get().keySet().stream()
        .map(Registrar::createVKey)
        .collect(toImmutableSet());
  }

  /** Loads and returns a registrar entity by its id directly from Datastore. */
  public static Optional<Registrar> loadByRegistrarId(String registrarId) {
    checkArgument(!Strings.isNullOrEmpty(registrarId), "registrarId must be specified");
    return tm().transact(() -> tm().loadByKeyIfPresent(createVKey(registrarId)));
  }

  /**
   * Loads and returns a registrar entity by its id using an in-memory cache.
   *
   * <p>Returns empty if the registrar isn't found.
   */
  public static Optional<Registrar> loadByRegistrarIdCached(String registrarId) {
    checkArgument(!Strings.isNullOrEmpty(registrarId), "registrarId must be specified");
    return Optional.ofNullable(CACHE_BY_REGISTRAR_ID.get().get(registrarId));
  }

  /**
   * Loads and returns a registrar entity by its id using an in-memory cache.
   *
   * <p>Throws if the registrar isn't found.
   */
  public static Registrar loadRequiredRegistrarCached(String registrarId) {
    Optional<Registrar> registrar = loadByRegistrarIdCached(registrarId);
    checkArgument(registrar.isPresent(), "couldn't find registrar '%s'", registrarId);
    return registrar.get();
  }
}

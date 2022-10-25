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

package google.registry.flows;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Sets.difference;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.model.eppcommon.EppXmlTransformer.marshal;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.stripBillingEventId;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.xml.XmlTestUtils.assertXmlEquals;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.ObjectArrays;
import google.registry.flows.EppTestComponent.FakesAndMocksModule;
import google.registry.flows.picker.FlowPicker;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.GracePeriod;
import google.registry.model.eppcommon.ProtocolDefinition;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppoutput.EppOutput;
import google.registry.model.reporting.HistoryEntryDao;
import google.registry.monitoring.whitebox.EppMetric;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.EppLoader;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeHttpSession;
import google.registry.testing.TestDataHelper;
import google.registry.util.TypeUtils.TypeInstantiator;
import google.registry.xml.ValidationMode;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Base class for resource flow unit tests.
 *
 * @param <F> the flow type
 */
public abstract class FlowTestCase<F extends Flow> {

  /** Whether to actually write to the database or just simulate. */
  public enum CommitMode {
    LIVE,
    DRY_RUN
  }

  /** Whether to run in normal or superuser mode. */
  public enum UserPrivileges {
    NORMAL,
    SUPERUSER
  }

  protected EppLoader eppLoader;
  protected SessionMetadata sessionMetadata;
  protected FakeClock clock = new FakeClock(DateTime.now(UTC));
  protected TransportCredentials credentials = new PasswordOnlyTransportCredentials();
  protected EppRequestSource eppRequestSource = EppRequestSource.UNIT_TEST;
  protected CloudTasksHelper cloudTasksHelper;

  private EppMetric.Builder eppMetricBuilder;

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder().withClock(clock).withCloudSql().withTaskQueue().build();

  @BeforeEach
  public void beforeEachFlowTestCase() {
    DatabaseHelper.setClock(clock);
    sessionMetadata = new HttpSessionMetadata(new FakeHttpSession());
    sessionMetadata.setRegistrarId("TheRegistrar");
    sessionMetadata.setServiceExtensionUris(ProtocolDefinition.getVisibleServiceExtensionUris());
  }

  protected void removeServiceExtensionUri(String uri) {
    sessionMetadata.setServiceExtensionUris(
        difference(sessionMetadata.getServiceExtensionUris(), ImmutableSet.of(uri)));
  }

  protected void setEppInput(String inputFilename) {
    eppLoader = new EppLoader(this, inputFilename);
  }

  protected void setEppInput(String inputFilename, Map<String, String> substitutions) {
    eppLoader = new EppLoader(this, inputFilename, substitutions);
  }

  /** Returns the EPP data loaded by a previous call to setEppInput. */
  protected EppInput getEppInput() throws EppException {
    return eppLoader.getEpp();
  }

  protected EppMetric getEppMetric() {
    checkNotNull(eppMetricBuilder, "Run the flow first before checking EPP metrics");
    return eppMetricBuilder.build();
  }

  protected String loadFile(String filename) {
    return TestDataHelper.loadFile(getClass(), filename);
  }

  protected String loadFile(String filename, Map<String, String> substitutions) {
    return TestDataHelper.loadFile(getClass(), filename, substitutions);
  }

  @Nullable
  protected String getClientTrid() throws Exception {
    return eppLoader.getEpp().getCommandWrapper().getClTrid().orElse(null);
  }

  /** Gets the ID of the registrar that the flow will run as. */
  protected String getRegistrarIdForFlow() {
    return sessionMetadata.getRegistrarId();
  }

  /** Sets the ID of the registrar that the flow will run as. */
  protected void setRegistrarIdForFlow(String registrarId) {
    sessionMetadata.setRegistrarId(registrarId);
  }

  public void assertTransactionalFlow(boolean isTransactional) throws Exception {
    Class<? extends Flow> flowClass = FlowPicker.getFlowClass(eppLoader.getEpp());
    if (isTransactional) {
      assertThat(flowClass).isAssignableTo(TransactionalFlow.class);
    } else {
      // There's no "isNotAssignableTo" in Truth.
      assertWithMessage(flowClass.getSimpleName() + " implements TransactionalFlow")
          .that(TransactionalFlow.class.isAssignableFrom(flowClass))
          .isFalse();
    }
  }

  protected void assertNoHistory() {
    assertThat(HistoryEntryDao.loadAllHistoryObjects(START_OF_TIME, END_OF_TIME)).isEmpty();
  }

  /**
   * Helper to facilitate comparison of maps of GracePeriods to BillingEvents. This takes a map of
   * GracePeriods to BillingEvents and returns a map of the same entries that ignores the keys on
   * the grace periods and the IDs on the billing events (by setting them all to the same dummy
   * values), since they will vary between instantiations even when the other data is the same.
   */
  private static ImmutableMap<GracePeriod, BillingEvent> canonicalizeGracePeriods(
      ImmutableMap<GracePeriod, ? extends BillingEvent> gracePeriods) {
    ImmutableMap.Builder<GracePeriod, BillingEvent> builder = new ImmutableMap.Builder<>();
    for (Map.Entry<GracePeriod, ? extends BillingEvent> entry : gracePeriods.entrySet()) {
      builder.put(
          GracePeriod.create(
              entry.getKey().getType(),
              entry.getKey().getDomainRepoId(),
              entry.getKey().getExpirationTime(),
              entry.getKey().getRegistrarId(),
              null,
              1L),
          stripBillingEventId(entry.getValue()));
    }
    return builder.build();
  }

  private static BillingEvent expandGracePeriod(GracePeriod gracePeriod) {
    assertWithMessage("Billing event is present for grace period: " + gracePeriod)
        .that(gracePeriod.hasBillingEvent())
        .isTrue();
    return tm().transact(
            () ->
                tm().loadByKey(
                        firstNonNull(
                            gracePeriod.getOneTimeBillingEvent(),
                            gracePeriod.getRecurringBillingEvent())));
  }

  /**
   * Assert that the actual grace periods and the corresponding billing events referenced from their
   * keys match the expected map of grace periods to billing events. For the expected map, the keys
   * on the grace periods and IDs on the billing events are ignored.
   */
  protected static void assertGracePeriods(
      Iterable<GracePeriod> actual, ImmutableMap<GracePeriod, ? extends BillingEvent> expected) {
    assertThat(canonicalizeGracePeriods(Maps.toMap(actual, FlowTestCase::expandGracePeriod)))
        .isEqualTo(canonicalizeGracePeriods(expected));
  }

  private EppOutput runFlowInternal(CommitMode commitMode, UserPrivileges userPrivileges)
      throws Exception {
    eppMetricBuilder = EppMetric.builderForRequest(clock);
    // Assert that the xml triggers the flow we expect.
    assertThat(FlowPicker.getFlowClass(eppLoader.getEpp()))
        .isEqualTo(new TypeInstantiator<F>(getClass()){}.getExactType());

    FakesAndMocksModule fakesAndMocksModule = FakesAndMocksModule.create(clock);
    cloudTasksHelper = fakesAndMocksModule.getCloudTasksHelper();
    // Run the flow.
    return DaggerEppTestComponent.builder()
        .fakesAndMocksModule(fakesAndMocksModule)
        .build()
        .startRequest()
        .flowComponentBuilder()
        .flowModule(
            new FlowModule.Builder()
                .setSessionMetadata(sessionMetadata)
                .setCredentials(credentials)
                .setEppRequestSource(eppRequestSource)
                .setIsDryRun(commitMode.equals(CommitMode.DRY_RUN))
                .setIsSuperuser(userPrivileges.equals(UserPrivileges.SUPERUSER))
                .setInputXmlBytes(eppLoader.getEppXml().getBytes(UTF_8))
                .setEppInput(eppLoader.getEpp())
                .build())
        .build()
        .flowRunner()
        .run(eppMetricBuilder);
  }

  /** Run a flow and marshal the result to EPP, or throw if it doesn't validate. */
  public EppOutput runFlow(CommitMode commitMode, UserPrivileges userPrivileges) throws Exception {
    EppOutput output = runFlowInternal(commitMode, userPrivileges);
    marshal(output, ValidationMode.STRICT);
    return output;
  }

  /** Shortcut to call {@link #runFlow(CommitMode, UserPrivileges)} as normal user and live run. */
  public EppOutput runFlow() throws Exception {
    return runFlow(CommitMode.LIVE, UserPrivileges.NORMAL);
  }

  /** Shortcut to call {@link #runFlow(CommitMode, UserPrivileges)} as superuser and live run. */
  protected EppOutput runFlowAsSuperuser() throws Exception {
    return runFlow(CommitMode.LIVE, UserPrivileges.SUPERUSER);
  }

  /** Run a flow, marshal the result to EPP, and assert that the output is as expected. */
  public EppOutput runFlowAssertResponse(
      CommitMode commitMode, UserPrivileges userPrivileges, String xml, String... ignoredPaths)
      throws Exception {
    // Always ignore the server trid, since it's generated and meaningless to flow correctness.
    String[] ignoredPathsPlusTrid = ObjectArrays.concat(ignoredPaths, "epp.response.trID.svTRID");
    EppOutput output = runFlowInternal(commitMode, userPrivileges);
    if (output.isResponse()) {
      assertThat(output.isSuccess()).isTrue();
    }
    try {
      assertXmlEquals(
          xml, new String(marshal(output, ValidationMode.STRICT), UTF_8), ignoredPathsPlusTrid);
    } catch (Throwable e) {
      assertXmlEquals(
          xml, new String(marshal(output, ValidationMode.LENIENT), UTF_8), ignoredPathsPlusTrid);
      // If it was a marshaling error, augment the output.
      throw new Exception(
          String.format(
              "Invalid xml.\nExpected:\n%s\n\nActual:\n%s\n",
              xml,
              Arrays.toString(marshal(output, ValidationMode.LENIENT))),
          e);
    }
    return output;
  }

  public EppOutput dryRunFlowAssertResponse(String xml, String... ignoredPaths) throws Exception {
    List<Object> beforeEntities = DatabaseHelper.loadAllEntities();
    EppOutput output =
        runFlowAssertResponse(CommitMode.DRY_RUN, UserPrivileges.NORMAL, xml, ignoredPaths);
    assertThat(DatabaseHelper.loadAllEntities()).containsExactlyElementsIn(beforeEntities);
    return output;
  }

  public EppOutput runFlowAssertResponse(String xml, String... ignoredPaths) throws Exception {
    return runFlowAssertResponse(CommitMode.LIVE, UserPrivileges.NORMAL, xml, ignoredPaths);
  }
}

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

package google.registry.rde;

import static com.google.appengine.api.urlfetch.HTTPMethod.PUT;
import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.Cursor.CursorType.RDE_REPORT;
import static google.registry.model.common.Cursor.CursorType.RDE_UPLOAD;
import static google.registry.model.rde.RdeMode.FULL;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.persistResource;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.joda.time.Duration.standardDays;
import static org.joda.time.Duration.standardSeconds;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.appengine.api.urlfetch.HTTPHeader;
import com.google.appengine.api.urlfetch.HTTPRequest;
import com.google.appengine.api.urlfetch.HTTPResponse;
import com.google.appengine.api.urlfetch.URLFetchService;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteSource;
import google.registry.gcs.GcsUtils;
import google.registry.model.common.Cursor;
import google.registry.model.rde.RdeRevision;
import google.registry.model.tld.Registry;
import google.registry.request.HttpException.InternalServerErrorException;
import google.registry.request.HttpException.NoContentException;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.BouncyCastleProviderExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeKeyringModule;
import google.registry.testing.FakeResponse;
import google.registry.testing.FakeSleeper;
import google.registry.util.Retrier;
import google.registry.xjc.XjcXmlTransformer;
import google.registry.xjc.rdereport.XjcRdeReportReport;
import google.registry.xml.XmlException;
import java.io.ByteArrayInputStream;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.Optional;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link RdeReportAction}. */
public class RdeReportActionTest {

  private static final ByteSource REPORT_XML = RdeTestData.loadBytes("report.xml");
  private static final ByteSource IIRDEA_BAD_XML = RdeTestData.loadBytes("iirdea_bad.xml");
  private static final ByteSource IIRDEA_GOOD_XML = RdeTestData.loadBytes("iirdea_good.xml");

  @RegisterExtension
  public final BouncyCastleProviderExtension bouncy = new BouncyCastleProviderExtension();

  @RegisterExtension
  public final AppEngineExtension appEngine = AppEngineExtension.builder().withCloudSql().build();

  private final FakeResponse response = new FakeResponse();
  private final EscrowTaskRunner runner = mock(EscrowTaskRunner.class);
  private final URLFetchService urlFetchService = mock(URLFetchService.class);
  private final ArgumentCaptor<HTTPRequest> request = ArgumentCaptor.forClass(HTTPRequest.class);
  private final HTTPResponse httpResponse = mock(HTTPResponse.class);
  private final PGPPublicKey encryptKey =
      new FakeKeyringModule().get().getRdeStagingEncryptionKey();
  private final GcsUtils gcsUtils = new GcsUtils(LocalStorageHelper.getOptions());
  private final BlobId reportFile =
      BlobId.of("tub", "test_2006-06-06_full_S1_R0-report.xml.ghostryde");
  private Registry registry;

  private RdeReportAction createAction() {
    RdeReporter reporter = new RdeReporter();
    reporter.reportUrlPrefix = "https://rde-report.example";
    reporter.urlFetchService = urlFetchService;
    reporter.password = "foo";
    reporter.retrier = new Retrier(new FakeSleeper(new FakeClock()), 3);
    RdeReportAction action = new RdeReportAction();
    action.gcsUtils = gcsUtils;
    action.response = response;
    action.bucket = "tub";
    action.tld = "test";
    action.interval = standardDays(1);
    action.reporter = reporter;
    action.timeout = standardSeconds(30);
    action.stagingDecryptionKey = new FakeKeyringModule().get().getRdeStagingDecryptionKey();
    action.runner = runner;
    action.prefix = Optional.empty();
    return action;
  }

  @BeforeEach
  void beforeEach() throws Exception {
    registry = createTld("test");
    persistResource(Cursor.createScoped(RDE_REPORT, DateTime.parse("2006-06-06TZ"), registry));
    persistResource(Cursor.createScoped(RDE_UPLOAD, DateTime.parse("2006-06-07TZ"), registry));
    gcsUtils.createFromBytes(reportFile, Ghostryde.encode(REPORT_XML.read(), encryptKey));
    tm().transact(() -> RdeRevision.saveRevision("test", DateTime.parse("2006-06-06TZ"), FULL, 0));
  }

  @Test
  void testRun() {
    createTld("lol");
    RdeReportAction action = createAction();
    action.tld = "lol";
    action.run();
    verify(runner).lockRunAndRollForward(
        action, Registry.get("lol"), standardSeconds(30), RDE_REPORT, standardDays(1));
    verifyNoMoreInteractions(runner);
  }

  @Test
  void testRunWithLock() throws Exception {
    when(httpResponse.getResponseCode()).thenReturn(SC_OK);
    when(httpResponse.getContent()).thenReturn(IIRDEA_GOOD_XML.read());
    when(urlFetchService.fetch(request.capture())).thenReturn(httpResponse);
    createAction().runWithLock(loadRdeReportCursor());
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo("OK test 2006-06-06T00:00:00.000Z\n");

    // Verify the HTTP request was correct.
    assertThat(request.getValue().getMethod()).isSameInstanceAs(PUT);
    assertThat(request.getValue().getURL().getProtocol()).isEqualTo("https");
    assertThat(request.getValue().getURL().getPath()).endsWith("/test/20101017001");
    Map<String, String> headers = mapifyHeaders(request.getValue().getHeaders());
    assertThat(headers).containsEntry("CONTENT_TYPE", "text/xml");
    assertThat(headers).containsEntry("AUTHORIZATION", "Basic dGVzdF9yeTpmb28=");

    // Verify the payload XML was the same as what's in testdata/report.xml.
    XjcRdeReportReport report = parseReport(request.getValue().getPayload());
    assertThat(report.getId()).isEqualTo("20101017001");
    assertThat(report.getCrDate()).isEqualTo(DateTime.parse("2010-10-17T00:15:00.0Z"));
    assertThat(report.getWatermark()).isEqualTo(DateTime.parse("2010-10-17T00:00:00Z"));
  }

  @Test
  void testRunWithLock_withPrefix() throws Exception {
    when(httpResponse.getResponseCode()).thenReturn(SC_OK);
    when(httpResponse.getContent()).thenReturn(IIRDEA_GOOD_XML.read());
    when(urlFetchService.fetch(request.capture())).thenReturn(httpResponse);
    RdeReportAction action = createAction();
    action.prefix = Optional.of("job-name/");
    gcsUtils.delete(reportFile);
    gcsUtils.createFromBytes(
        BlobId.of("tub", "job-name/test_2006-06-06_full_S1_R0-report.xml.ghostryde"),
        Ghostryde.encode(REPORT_XML.read(), encryptKey));
    action.runWithLock(loadRdeReportCursor());
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo("OK test 2006-06-06T00:00:00.000Z\n");

    // Verify the HTTP request was correct.
    assertThat(request.getValue().getMethod()).isSameInstanceAs(PUT);
    assertThat(request.getValue().getURL().getProtocol()).isEqualTo("https");
    assertThat(request.getValue().getURL().getPath()).endsWith("/test/20101017001");
    Map<String, String> headers = mapifyHeaders(request.getValue().getHeaders());
    assertThat(headers).containsEntry("CONTENT_TYPE", "text/xml");
    assertThat(headers).containsEntry("AUTHORIZATION", "Basic dGVzdF9yeTpmb28=");

    // Verify the payload XML was the same as what's in testdata/report.xml.
    XjcRdeReportReport report = parseReport(request.getValue().getPayload());
    assertThat(report.getId()).isEqualTo("20101017001");
    assertThat(report.getCrDate()).isEqualTo(DateTime.parse("2010-10-17T00:15:00.0Z"));
    assertThat(report.getWatermark()).isEqualTo(DateTime.parse("2010-10-17T00:00:00Z"));
  }

  @Test
  void testRunWithLock_regeneratedReport() throws Exception {
    gcsUtils.delete(reportFile);
    BlobId newReport = BlobId.of("tub", "test_2006-06-06_full_S1_R1-report.xml.ghostryde");
    PGPPublicKey encryptKey = new FakeKeyringModule().get().getRdeStagingEncryptionKey();
    gcsUtils.createFromBytes(newReport, Ghostryde.encode(REPORT_XML.read(), encryptKey));
    tm().transact(() -> RdeRevision.saveRevision("test", DateTime.parse("2006-06-06TZ"), FULL, 1));
    when(httpResponse.getResponseCode()).thenReturn(SC_OK);
    when(httpResponse.getContent()).thenReturn(IIRDEA_GOOD_XML.read());
    when(urlFetchService.fetch(request.capture())).thenReturn(httpResponse);
    createAction().runWithLock(loadRdeReportCursor());
    assertThat(response.getStatus()).isEqualTo(200);
  }

  void testRunWithLock_nonexistentCursor_throws204() {
    tm().transact(() -> tm().delete(Cursor.createScopedVKey(RDE_UPLOAD, Registry.get("test"))));
    NoContentException thrown =
        assertThrows(
            NoContentException.class, () -> createAction().runWithLock(loadRdeReportCursor()));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Waiting on RdeUploadAction for TLD test to send 2006-06-06T00:00:00.000Z report; last"
                + " upload completion was at 1970-01-01T00:00:00.000Z");
  }

  @Test
  void testRunWithLock_uploadNotFinished_throws204() {
    persistResource(
        Cursor.createScoped(RDE_UPLOAD, DateTime.parse("2006-06-06TZ"), Registry.get("test")));
    NoContentException thrown =
        assertThrows(
            NoContentException.class, () -> createAction().runWithLock(loadRdeReportCursor()));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Waiting on RdeUploadAction for TLD test to send 2006-06-06T00:00:00.000Z report; "
                + "last upload completion was at 2006-06-06T00:00:00.000Z");
  }

  @Test
  void testRunWithLock_badRequest_throws500WithErrorInfo() throws Exception {
    when(httpResponse.getResponseCode()).thenReturn(SC_BAD_REQUEST);
    when(httpResponse.getContent()).thenReturn(IIRDEA_BAD_XML.read());
    when(urlFetchService.fetch(request.capture())).thenReturn(httpResponse);
    InternalServerErrorException thrown =
        assertThrows(
            InternalServerErrorException.class,
            () -> createAction().runWithLock(loadRdeReportCursor()));
    assertThat(thrown).hasMessageThat().contains("The structure of the report is invalid.");
  }

  @Test
  void testRunWithLock_fetchFailed_throwsRuntimeException() throws Exception {
    class ExpectedThrownException extends RuntimeException {}
    when(urlFetchService.fetch(any(HTTPRequest.class))).thenThrow(new ExpectedThrownException());
    assertThrows(
        ExpectedThrownException.class, () -> createAction().runWithLock(loadRdeReportCursor()));
  }

  @Test
  void testRunWithLock_socketTimeout_doesRetry() throws Exception {
    when(httpResponse.getResponseCode()).thenReturn(SC_OK);
    when(httpResponse.getContent()).thenReturn(IIRDEA_GOOD_XML.read());
    when(urlFetchService.fetch(request.capture()))
        .thenThrow(new SocketTimeoutException())
        .thenReturn(httpResponse);
    createAction().runWithLock(loadRdeReportCursor());
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo("OK test 2006-06-06T00:00:00.000Z\n");
  }

  private DateTime loadRdeReportCursor() {
    return loadByKey(Cursor.createScopedVKey(RDE_REPORT, registry)).getCursorTime();
  }

  private static ImmutableMap<String, String> mapifyHeaders(Iterable<HTTPHeader> headers) {
    ImmutableMap.Builder<String, String> builder = new ImmutableMap.Builder<>();
    for (HTTPHeader header : headers) {
      builder.put(Ascii.toUpperCase(header.getName().replace('-', '_')), header.getValue());
    }
    return builder.build();
  }

  private static XjcRdeReportReport parseReport(byte[] data) {
    try {
      return XjcXmlTransformer.unmarshal(XjcRdeReportReport.class, new ByteArrayInputStream(data));
    } catch (XmlException e) {
      throw new RuntimeException(e);
    }
  }
}

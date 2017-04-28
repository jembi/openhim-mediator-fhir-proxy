/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.openhim.mediator.fhir;

import akka.actor.Actor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.testkit.JavaTestKit;
import ca.uhn.fhir.context.FhirContext;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.json.JSONException;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openhim.mediator.engine.MediatorConfig;
import org.openhim.mediator.engine.messages.ExceptError;
import org.openhim.mediator.engine.messages.FinishRequest;
import org.openhim.mediator.engine.messages.MediatorHTTPRequest;
import org.openhim.mediator.engine.testing.MockHTTPConnector;
import org.openhim.mediator.engine.testing.MockLauncher;
import org.openhim.mediator.engine.testing.TestingUtils;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class FhirProxyHandlerTest {

    private abstract class FhirProxyTestKit extends JavaTestKit {
        protected final ActorRef fhirProxyHandler;

        public FhirProxyTestKit(ActorSystem actorSystem, Class<? extends Actor> context, Class<? extends MockHTTPConnector> upstreamMock) {
            super(actorSystem);
            List<MockLauncher.ActorToLaunch> testActors = new ArrayList<>();
            testActors.add(new MockLauncher.ActorToLaunch("fhir-context", context));
            testActors.add(new MockLauncher.ActorToLaunch("http-connector", upstreamMock));
            TestingUtils.launchActors(system, testConfig.getName(), testActors);
            expectNoMsg((FiniteDuration) dilated(Duration.create(20, TimeUnit.MILLISECONDS))); //delay a bit - the actors sometimes need a moment

            fhirProxyHandler = system.actorOf(Props.create(FhirProxyHandler.class, testConfig));
        }

        protected void cleanup() {
            TestingUtils.clearRootContext(system, testConfig.getName());
            expectNoMsg((FiniteDuration) dilated(Duration.create(20, TimeUnit.MILLISECONDS)));
        }


        protected MediatorHTTPRequest POSTPatientRequest(String contentType, String body) {
            return new MediatorHTTPRequest(
                    getRef(),
                    getRef(),
                    "unit-test",
                    "POST",
                    "http",
                    "localhost",
                    8604,
                    "/fhir/Patient",
                    body,
                    Collections.singletonMap("Content-Type", contentType),
                    Collections.<Pair<String, String>>emptyList()
            );
        }

        protected MediatorHTTPRequest GETPatientRequest(String accept) {
            return new MediatorHTTPRequest(
                    getRef(),
                    getRef(),
                    "unit-test",
                    "GET",
                    "http",
                    "localhost",
                    8604,
                    "/fhir/Patient/1",
                    null,
                    Collections.singletonMap("Accept", accept),
                    Collections.<Pair<String, String>>emptyList()
            );
        }

        protected MediatorHTTPRequest GETPatientRequest_formatParam(String accept) {
            return new MediatorHTTPRequest(
                    getRef(),
                    getRef(),
                    "unit-test",
                    "GET",
                    "http",
                    "localhost",
                    8604,
                    "/fhir/Patient/1",
                    null,
                    Collections.<String, String>emptyMap(),
                    Collections.singletonList(Pair.of("_format", accept))
            );
        }
    }

    private static class DSTU1FhirContext extends UntypedActor {
        @Override
        public void onReceive(Object o) throws Exception {
            if (o instanceof FhirContextActor.FhirContextRequest) {
                getSender().tell(new FhirContextActor.FhirContextResponse(null, FhirContext.forDstu1()), getSelf());
            } else {
                unhandled(o);
            }
        }
    }

    private static class AcceptJSONCreateFhirServer extends MockHTTPConnector {
        @Override
        public String getResponse() {
            return null;
        }

        @Override
        public Integer getStatus() {
            return 201;
        }

        @Override
        public Map<String, String> getHeaders() {
            return Collections.emptyMap();
        }

        @Override
        public void executeOnReceive(MediatorHTTPRequest request) {
            assertEquals(testConfig.getDynamicConfig().get("upstream-scheme"), request.getScheme());
            assertEquals(testConfig.getDynamicConfig().get("upstream-host"), request.getHost());
            assertEquals(testConfig.getDynamicConfig().get("upstream-port"), new Double(request.getPort()));
            assertEquals(Constants.FHIR_MIME_JSON, request.getHeaders().get("Content-Type"));

            if (request.getParams()!=null) {
                for (Pair<String, String> param : request.getParams()) {
                    if (param.getKey().equals("_format")) {
                        fail("Mediator should not forward _format param");
                    }
                }
            }

            try {
                JSONAssert.assertEquals(patientJSON, request.getBody(), JSONCompareMode.LENIENT);
            } catch (JSONException ex) {
                fail(ex.getMessage());
            }
        }
    }

    private static class AcceptXMLCreateFhirServer extends MockHTTPConnector {
        @Override
        public String getResponse() {
            return null;
        }

        @Override
        public Integer getStatus() {
            return 201;
        }

        @Override
        public Map<String, String> getHeaders() {
            return Collections.emptyMap();
        }

        @Override
        public void executeOnReceive(MediatorHTTPRequest request) {
            assertEquals(testConfig.getDynamicConfig().get("upstream-scheme"), request.getScheme());
            assertEquals(testConfig.getDynamicConfig().get("upstream-host"), request.getHost());
            assertEquals(testConfig.getDynamicConfig().get("upstream-port"), new Double(request.getPort()));
            assertEquals(Constants.FHIR_MIME_XML, request.getHeaders().get("Content-Type"));

            if (request.getParams()!=null) {
                for (Pair<String, String> param : request.getParams()) {
                    if (param.getKey().equals("_format")) {
                        fail("Mediator should not forward _format param");
                    }
                }
            }

            Diff diff = DiffBuilder
                    .compare(Input.fromString(patientXML))
                    .withTest(Input.fromString(request.getBody()))
                    .ignoreComments()
                    .ignoreWhitespace()
                    .build();
            assertFalse(diff.hasDifferences());
        }
    }

    private static class AcceptJSONGetFhirServer extends MockHTTPConnector {
        @Override
        public String getResponse() {
            return patientJSON;
        }

        @Override
        public Integer getStatus() {
            return 200;
        }

        @Override
        public Map<String, String> getHeaders() {
            return Collections.singletonMap("Content-Type", Constants.FHIR_MIME_JSON);
        }

        @Override
        public void executeOnReceive(MediatorHTTPRequest request) {
            assertEquals(testConfig.getDynamicConfig().get("upstream-scheme"), request.getScheme());
            assertEquals(testConfig.getDynamicConfig().get("upstream-host"), request.getHost());
            assertEquals(testConfig.getDynamicConfig().get("upstream-port"), new Double(request.getPort()));
            assertEquals(Constants.FHIR_MIME_JSON, request.getHeaders().get("Accept"));

            if (request.getParams()!=null) {
                for (Pair<String, String> param : request.getParams()) {
                    if (param.getKey().equals("_format")) {
                        fail("Mediator should not forward _format param");
                    }
                }
            }
        }
    }

    private static class AcceptXMLGetFhirServer extends MockHTTPConnector {
        @Override
        public String getResponse() {
            return patientXML;
        }

        @Override
        public Integer getStatus() {
            return 200;
        }

        @Override
        public Map<String, String> getHeaders() {
            return Collections.singletonMap("Content-Type", Constants.FHIR_MIME_XML);
        }

        @Override
        public void executeOnReceive(MediatorHTTPRequest request) {
            assertEquals(testConfig.getDynamicConfig().get("upstream-scheme"), request.getScheme());
            assertEquals(testConfig.getDynamicConfig().get("upstream-host"), request.getHost());
            assertEquals(testConfig.getDynamicConfig().get("upstream-port"), new Double(request.getPort()));
            assertEquals(Constants.FHIR_MIME_XML, request.getHeaders().get("Accept"));

            if (request.getParams()!=null) {
                for (Pair<String, String> param : request.getParams()) {
                    if (param.getKey().equals("_format")) {
                        fail("Mediator should not forward _format param");
                    }
                }
            }
        }
    }

    private static class TrapServer extends MockHTTPConnector {
        @Override
        public String getResponse() {
            return null;
        }

        @Override
        public Integer getStatus() {
            return null;
        }

        @Override
        public Map<String, String> getHeaders() {
            return null;
        }

        @Override
        public void executeOnReceive(MediatorHTTPRequest mediatorHTTPRequest) {
            fail();
        }
    }

    static ActorSystem system;
    static final MediatorConfig testConfig = new MediatorConfig("fhir-proxy-handler", "localhost", 8604);

    static String patientJSON;
    static String patientXML;
    static String patientJSON_invalid;
    static String patientJSON_invalidSyntax;


    @BeforeClass
    public static void setup() throws IOException {
        system = ActorSystem.create();
        testConfig.getDynamicConfig().put("fhir-context", "DSTU1");
        testConfig.getDynamicConfig().put("upstream-scheme", "http");
        testConfig.getDynamicConfig().put("upstream-host", "localhost");
        testConfig.getDynamicConfig().put("upstream-port", 80d);

        patientJSON = IOUtils.toString(FhirProxyHandlerTest.class.getClassLoader().getResourceAsStream("fhir-patient.json"));
        patientXML = IOUtils.toString(FhirProxyHandlerTest.class.getClassLoader().getResourceAsStream("fhir-patient.xml"));
        patientJSON_invalid = IOUtils.toString(FhirProxyHandlerTest.class.getClassLoader().getResourceAsStream("fhir-patient-invalid.json"));
        patientJSON_invalidSyntax = IOUtils.toString(FhirProxyHandlerTest.class.getClassLoader().getResourceAsStream("fhir-patient-invalid-syntax.json"));
    }

    @AfterClass
    public static void teardown() {
        JavaTestKit.shutdownActorSystem(system);
        system = null;
    }




    /**
     * If upstream format is 'Client' and validation is disabled, the proxy acts as a pass-through
     */
    @Test
    public void testPassthrough_POST() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, AcceptJSONCreateFhirServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "Client");
            testConfig.getDynamicConfig().put("validation-enabled", false);

            try {
                MediatorHTTPRequest POST_Request = POSTPatientRequest(Constants.FHIR_MIME_JSON, patientJSON);
                fhirProxyHandler.tell(POST_Request, getRef());

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError)result).getError();
                }

                assertEquals(new Integer(201), ((FinishRequest)result).getResponseStatus());
            } finally {
                cleanup();
            }
        }};
    }

    /**
     * If upstream format is 'Client' and validation is disabled, the proxy acts as a pass-through
     */
    @Test
    public void testPassthrough_GET() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, AcceptJSONGetFhirServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "Client");
            testConfig.getDynamicConfig().put("validation-enabled", false);

            try {
                MediatorHTTPRequest GET_Request = GETPatientRequest(Constants.FHIR_MIME_JSON);
                fhirProxyHandler.tell(GET_Request, getRef());

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError)result).getError();
                }

                assertEquals(new Integer(200), ((FinishRequest)result).getResponseStatus());
                assertEquals(Constants.FHIR_MIME_JSON, ((FinishRequest)result).getResponseMimeType());
                JSONAssert.assertEquals(patientJSON, ((FinishRequest)result).getResponse(), JSONCompareMode.LENIENT);
            } finally {
                cleanup();
            }
        }};
    }


    /**
     * Should forward valid contents upstream
     */
    @Test
    public void testValidContentShouldForward() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, AcceptJSONCreateFhirServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "Client");
            testConfig.getDynamicConfig().put("validation-enabled", true);

            try {
                MediatorHTTPRequest POST_Request = POSTPatientRequest(Constants.FHIR_MIME_JSON, patientJSON);
                fhirProxyHandler.tell(POST_Request, getRef());

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError)result).getError();
                }

                assertEquals(new Integer(201), ((FinishRequest)result).getResponseStatus());
            } finally {
                cleanup();
            }
        }};
    }

    /**
     * Should not forward invalid contents upstream
     */
    @Test
    public void testInvalidContentShouldNotForward() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, TrapServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "Client");
            testConfig.getDynamicConfig().put("validation-enabled", true);

            try {
                MediatorHTTPRequest POST_Request = POSTPatientRequest(Constants.FHIR_MIME_JSON, patientJSON_invalid);
                fhirProxyHandler.tell(POST_Request, getRef());

                //should not hit the trap server

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError)result).getError();
                }
            } finally {
                cleanup();
            }
        }};
    }

    /**
     * Should respond with bad request OperationOutcome if content invalid
     */
    @Test
    public void testInvalidContentShouldRespondWithBadRequest() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, TrapServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "Client");
            testConfig.getDynamicConfig().put("validation-enabled", true);

            try {
                MediatorHTTPRequest POST_Request = POSTPatientRequest(Constants.FHIR_MIME_JSON, patientJSON_invalid);
                fhirProxyHandler.tell(POST_Request, getRef());

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError)result).getError();
                }

                assertEquals(new Integer(400), ((FinishRequest)result).getResponseStatus());
                assertEquals(Constants.FHIR_MIME_JSON, ((FinishRequest)result).getResponseMimeType());
                JSONAssert.assertEquals("{\"resourceType\": \"OperationOutcome\"}", ((FinishRequest)result).getResponse(), JSONCompareMode.LENIENT);

            } finally {
                cleanup();
            }
        }};
    }

    /**
     * Should respond with bad request OperationOutcome if JSON syntax is invalid
     */
    @Test
    public void testInvalidJSONSyntaxContentShouldRespondWithBadRequest() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, TrapServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "Client");
            testConfig.getDynamicConfig().put("validation-enabled", true);

            try {
                MediatorHTTPRequest POST_Request = POSTPatientRequest(Constants.FHIR_MIME_JSON, patientJSON_invalidSyntax);
                fhirProxyHandler.tell(POST_Request, getRef());

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError)result).getError();
                }

                assertEquals(new Integer(400), ((FinishRequest)result).getResponseStatus());
                assertEquals(Constants.FHIR_MIME_JSON, ((FinishRequest)result).getResponseMimeType());
                JSONAssert.assertEquals("{\"resourceType\": \"OperationOutcome\"}", ((FinishRequest)result).getResponse(), JSONCompareMode.LENIENT);
            } finally {
                cleanup();
            }
        }};
    }

    /**
     * Invalid content: response should be formatted according to Accept header value
     */
    @Test
    public void testInvalidContentShouldFormatAccordingToAccept_JSON() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, TrapServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "Client");
            testConfig.getDynamicConfig().put("validation-enabled", true);

            try {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", Constants.FHIR_MIME_JSON);
                headers.put("Accept", Constants.FHIR_MIME_JSON);

                MediatorHTTPRequest POST_Request = new MediatorHTTPRequest(
                        getRef(),
                        getRef(),
                        "unit-test",
                        "POST",
                        "http",
                        null,
                        null,
                        "/fhir/Patient",
                        patientJSON_invalid,
                        headers,
                        Collections.<Pair<String, String>>emptyList()
                );

                fhirProxyHandler.tell(POST_Request, getRef());

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError)result).getError();
                }

                assertEquals(Constants.FHIR_MIME_JSON, ((FinishRequest)result).getResponseMimeType());
                JSONAssert.assertEquals("{\"resourceType\": \"OperationOutcome\"}", ((FinishRequest)result).getResponse(), JSONCompareMode.LENIENT);

            } finally {
                cleanup();
            }
        }};
    }

    @Test
    public void testInvalidContentShouldFormatAccordingToAccept_XML() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, TrapServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "Client");
            testConfig.getDynamicConfig().put("validation-enabled", true);

            try {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", Constants.FHIR_MIME_JSON);
                headers.put("Accept", Constants.FHIR_MIME_XML);

                MediatorHTTPRequest POST_Request = new MediatorHTTPRequest(
                        getRef(),
                        getRef(),
                        "unit-test",
                        "POST",
                        "http",
                        null,
                        null,
                        "/fhir/Patient",
                        patientJSON_invalid,
                        headers,
                        Collections.<Pair<String, String>>emptyList()
                );

                fhirProxyHandler.tell(POST_Request, getRef());

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError)result).getError();
                }

                // sent as JSON, expect XML
                assertEquals(Constants.FHIR_MIME_XML, ((FinishRequest)result).getResponseMimeType());

            } finally {
                cleanup();
            }
        }};
    }

    /**
     * If Accept header is not present, result should be formatted according to _format param
     */
    @Test
    public void testInvalidContentShouldFormatAccordingToFormatParam_XML() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, TrapServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "Client");
            testConfig.getDynamicConfig().put("validation-enabled", true);

            try {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", Constants.FHIR_MIME_JSON);

                List<Pair<String, String>> params = new ArrayList<>();
                params.add(Pair.of("_format", Constants.FHIR_MIME_XML));

                MediatorHTTPRequest POST_Request = new MediatorHTTPRequest(
                        getRef(),
                        getRef(),
                        "unit-test",
                        "POST",
                        "http",
                        null,
                        null,
                        "/fhir/Patient",
                        patientJSON_invalid,
                        headers,
                        params
                );

                fhirProxyHandler.tell(POST_Request, getRef());

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError)result).getError();
                }

                // sent as JSON, expect XML
                assertEquals(Constants.FHIR_MIME_XML, ((FinishRequest)result).getResponseMimeType());

            } finally {
                cleanup();
            }
        }};
    }

    /**
     * If both Accept header and _format param is present, Accept should take precedence
     */
    @Test
    public void testInvalidContentAcceptHeaderShouldTakePrecedence() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, TrapServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "Client");
            testConfig.getDynamicConfig().put("validation-enabled", true);

            try {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", Constants.FHIR_MIME_JSON);
                headers.put("Accept", Constants.FHIR_MIME_JSON);

                List<Pair<String, String>> params = new ArrayList<>();
                params.add(Pair.of("_format", Constants.FHIR_MIME_XML));

                MediatorHTTPRequest POST_Request = new MediatorHTTPRequest(
                        getRef(),
                        getRef(),
                        "unit-test",
                        "POST",
                        "http",
                        null,
                        null,
                        "/fhir/Patient",
                        patientJSON_invalid,
                        headers,
                        params
                );

                fhirProxyHandler.tell(POST_Request, getRef());

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError)result).getError();
                }

                assertEquals(Constants.FHIR_MIME_JSON, ((FinishRequest)result).getResponseMimeType());

            } finally {
                cleanup();
            }
        }};
    }

    /**
     * Test POST JSON -> XML conversion
     */
    @Test
    public void testPOSTJSONToXML() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, AcceptXMLCreateFhirServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "XML");
            testConfig.getDynamicConfig().put("validation-enabled", false);

            try {
                MediatorHTTPRequest POST_Request = POSTPatientRequest(Constants.FHIR_MIME_JSON, patientJSON);
                fhirProxyHandler.tell(POST_Request, getRef());

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError)result).getError();
                }

                assertEquals(new Integer(201), ((FinishRequest)result).getResponseStatus());
            } finally {
                cleanup();
            }
        }};
    }

    /**
     * Test POST JSON -> XML conversion - specified with _format param
     */
    @Test
    public void testPOSTJSONToXML_formatParam() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, AcceptXMLCreateFhirServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "XML");
            testConfig.getDynamicConfig().put("validation-enabled", false);

            try {
                MediatorHTTPRequest POST_Request = POSTPatientRequest(Constants.FHIR_MIME_JSON, patientJSON);
                fhirProxyHandler.tell(POST_Request, getRef());

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError)result).getError();
                }

                assertEquals(new Integer(201), ((FinishRequest)result).getResponseStatus());
            } finally {
                cleanup();
            }
        }};
    }

    /**
     * Test POST XML -> JSON conversion
     */
    @Test
    public void testPOSTXMLToJSON() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, AcceptJSONCreateFhirServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "JSON");
            testConfig.getDynamicConfig().put("validation-enabled", false);

            try {
                MediatorHTTPRequest POST_Request = POSTPatientRequest(Constants.FHIR_MIME_XML, patientXML);
                fhirProxyHandler.tell(POST_Request, getRef());

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError) result).getError();
                }

                assertEquals(new Integer(201), ((FinishRequest) result).getResponseStatus());
            } finally {
                cleanup();
            }
        }};
    }

    /**
     * Test POST XML -> XML conversion
     */
    @Test
    public void testPOSTXMLToXML() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, AcceptXMLCreateFhirServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "XML");
            testConfig.getDynamicConfig().put("validation-enabled", false);

            try {
                MediatorHTTPRequest POST_Request = POSTPatientRequest(Constants.FHIR_MIME_XML, patientXML);
                fhirProxyHandler.tell(POST_Request, getRef());

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError) result).getError();
                }

                assertEquals(new Integer(201), ((FinishRequest) result).getResponseStatus());
            } finally {
                cleanup();
            }
        }};
    }

    /**
     * Test POST JSON -> JSON conversion
     */
    @Test
    public void testPOSTJSONToJSON() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, AcceptJSONCreateFhirServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "JSON");
            testConfig.getDynamicConfig().put("validation-enabled", false);

            try {
                MediatorHTTPRequest POST_Request = POSTPatientRequest(Constants.FHIR_MIME_JSON, patientJSON);
                fhirProxyHandler.tell(POST_Request, getRef());

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError) result).getError();
                }

                assertEquals(new Integer(201), ((FinishRequest) result).getResponseStatus());
            } finally {
                cleanup();
            }
        }};
    }

    /**
     * Test GET JSON -> JSON conversion
     */
    @Test
    public void testGETJSONToJSON() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, AcceptJSONGetFhirServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "JSON");
            testConfig.getDynamicConfig().put("validation-enabled", false);

            try {
                MediatorHTTPRequest GET_Request = GETPatientRequest(Constants.FHIR_MIME_JSON);
                fhirProxyHandler.tell(GET_Request, getRef());

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError) result).getError();
                }

                assertEquals(new Integer(200), ((FinishRequest) result).getResponseStatus());
                assertEquals(Constants.FHIR_MIME_JSON, ((FinishRequest)result).getResponseMimeType());
                JSONAssert.assertEquals(patientJSON, ((FinishRequest)result).getResponse(), JSONCompareMode.LENIENT);
            } finally {
                cleanup();
            }
        }};
    }

    /**
     * Test GET JSON -> JSON conversion - specified with _format param
     */
    @Test
    public void testGETJSONToJSON_formatParam() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, AcceptJSONGetFhirServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "JSON");
            testConfig.getDynamicConfig().put("validation-enabled", false);

            try {
                MediatorHTTPRequest GET_Request = GETPatientRequest_formatParam(Constants.FHIR_MIME_JSON);
                fhirProxyHandler.tell(GET_Request, getRef());

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError) result).getError();
                }

                assertEquals(new Integer(200), ((FinishRequest) result).getResponseStatus());
                assertEquals(Constants.FHIR_MIME_JSON, ((FinishRequest)result).getResponseMimeType());
                JSONAssert.assertEquals(patientJSON, ((FinishRequest)result).getResponse(), JSONCompareMode.LENIENT);
            } finally {
                cleanup();
            }
        }};
    }

    /**
     * Test GET XML -> XML conversion
     */
    @Test
    public void testGETXMLToXML() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, AcceptXMLGetFhirServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "XML");
            testConfig.getDynamicConfig().put("validation-enabled", false);

            try {
                MediatorHTTPRequest GET_Request = GETPatientRequest(Constants.FHIR_MIME_XML);
                fhirProxyHandler.tell(GET_Request, getRef());

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError) result).getError();
                }

                assertEquals(new Integer(200), ((FinishRequest) result).getResponseStatus());
                assertEquals(Constants.FHIR_MIME_XML, ((FinishRequest)result).getResponseMimeType());

                Diff diff = DiffBuilder
                        .compare(Input.fromString(patientXML))
                        .withTest(Input.fromString(((FinishRequest)result).getResponse()))
                        .ignoreComments()
                        .ignoreWhitespace()
                        .build();
                assertFalse(diff.hasDifferences());
            } finally {
                cleanup();
            }
        }};
    }

    /**
     * Test GET XML -> XML conversion - specified with _format param
     */
    @Test
    public void testGETXMLToXML_formatParam() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, AcceptXMLGetFhirServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "XML");
            testConfig.getDynamicConfig().put("validation-enabled", false);

            try {
                MediatorHTTPRequest GET_Request = GETPatientRequest_formatParam(Constants.FHIR_MIME_XML);
                fhirProxyHandler.tell(GET_Request, getRef());

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError) result).getError();
                }

                assertEquals(new Integer(200), ((FinishRequest) result).getResponseStatus());
                assertEquals(Constants.FHIR_MIME_XML, ((FinishRequest)result).getResponseMimeType());

                Diff diff = DiffBuilder
                        .compare(Input.fromString(patientXML))
                        .withTest(Input.fromString(((FinishRequest)result).getResponse()))
                        .ignoreComments()
                        .ignoreWhitespace()
                        .build();
                assertFalse(diff.hasDifferences());
            } finally {
                cleanup();
            }
        }};
    }

    /**
     * Test GET XML -> JSON conversion
     */
    @Test
    public void testGETXMLToJSON() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, AcceptXMLGetFhirServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "XML");
            testConfig.getDynamicConfig().put("validation-enabled", false);

            try {
                MediatorHTTPRequest GET_Request = GETPatientRequest(Constants.FHIR_MIME_JSON);
                fhirProxyHandler.tell(GET_Request, getRef());

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError) result).getError();
                }

                assertEquals(new Integer(200), ((FinishRequest) result).getResponseStatus());
                assertEquals(Constants.FHIR_MIME_JSON, ((FinishRequest)result).getResponseMimeType());
                JSONAssert.assertEquals(patientJSON, ((FinishRequest)result).getResponse(), JSONCompareMode.LENIENT);
            } finally {
                cleanup();
            }
        }};
    }

    /**
     * Test GET XML -> JSON conversion - specified with _format param
     */
    @Test
    public void testGETXMLToJSON_formatParam() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, AcceptXMLGetFhirServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "XML");
            testConfig.getDynamicConfig().put("validation-enabled", false);

            try {
                MediatorHTTPRequest GET_Request = GETPatientRequest_formatParam(Constants.FHIR_MIME_JSON);
                fhirProxyHandler.tell(GET_Request, getRef());

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError) result).getError();
                }

                assertEquals(new Integer(200), ((FinishRequest) result).getResponseStatus());
                assertEquals(Constants.FHIR_MIME_JSON, ((FinishRequest)result).getResponseMimeType());
                String response = ((FinishRequest) result).getResponse();
                JSONAssert.assertEquals(patientJSON, response, JSONCompareMode.LENIENT);
            } finally {
                cleanup();
            }
        }};
    }

    /**
     * Test GET JSON -> XML conversion
     */
    @Test
    public void testGETJSONToXML() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, AcceptJSONGetFhirServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "JSON");
            testConfig.getDynamicConfig().put("validation-enabled", false);

            try {
                MediatorHTTPRequest GET_Request = GETPatientRequest(Constants.FHIR_MIME_XML);
                fhirProxyHandler.tell(GET_Request, getRef());

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError) result).getError();
                }

                assertEquals(new Integer(200), ((FinishRequest) result).getResponseStatus());
                assertEquals(Constants.FHIR_MIME_XML, ((FinishRequest)result).getResponseMimeType());

                Diff diff = DiffBuilder
                        .compare(Input.fromString(patientXML))
                        .withTest(Input.fromString(((FinishRequest)result).getResponse()))
                        .ignoreComments()
                        .ignoreWhitespace()
                        .build();
                assertFalse(diff.hasDifferences());
            } finally {
                cleanup();
            }
        }};
    }

    /**
     * Test GET JSON -> XML conversion - specified with _format param
     */
    @Test
    public void testGETJSONToXML_formatParam() throws Throwable {
        new FhirProxyTestKit(system, DSTU1FhirContext.class, AcceptJSONGetFhirServer.class) {{
            testConfig.getDynamicConfig().put("upstream-format", "JSON");
            testConfig.getDynamicConfig().put("validation-enabled", false);

            try {
                MediatorHTTPRequest GET_Request = GETPatientRequest_formatParam(Constants.FHIR_MIME_XML);
                fhirProxyHandler.tell(GET_Request, getRef());

                Object result = expectMsgAnyClassOf(Duration.create(5, TimeUnit.SECONDS), FinishRequest.class, ExceptError.class);
                if (result instanceof ExceptError) {
                    throw ((ExceptError) result).getError();
                }

                assertEquals(new Integer(200), ((FinishRequest) result).getResponseStatus());
                assertEquals(Constants.FHIR_MIME_XML, ((FinishRequest)result).getResponseMimeType());

                Diff diff = DiffBuilder
                        .compare(Input.fromString(patientXML))
                        .withTest(Input.fromString(((FinishRequest)result).getResponse()))
                        .ignoreComments()
                        .ignoreWhitespace()
                        .build();
                assertFalse(diff.hasDifferences());
            } finally {
                cleanup();
            }
        }};
    }
}

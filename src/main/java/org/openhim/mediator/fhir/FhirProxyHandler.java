/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.openhim.mediator.fhir;

import akka.actor.ActorRef;
import akka.actor.ActorSelection;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.util.OperationOutcomeUtil;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import org.apache.http.HttpStatus;
import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.openhim.mediator.engine.MediatorConfig;
import org.openhim.mediator.engine.messages.ExceptError;
import org.openhim.mediator.engine.messages.FinishRequest;
import org.openhim.mediator.engine.messages.MediatorHTTPRequest;
import org.openhim.mediator.engine.messages.MediatorHTTPResponse;
import java.util.Map;
import java.util.TreeMap;


public class FhirProxyHandler extends UntypedActor {
    private static class FhirValidationResult {
        boolean passed;
        IBaseOperationOutcome operationOutcome;
    }

    private static class Contents {
        String contentType;
        String content;

        public Contents(String contentType, String content) {
            this.contentType = contentType;
            this.content = content;
        }
    }

    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    private final MediatorConfig config;

    private FhirContext fhirContext;
    private ActorRef requestHandler;
    private ActorRef respondTo;
    private MediatorHTTPRequest request;
    private MediatorHTTPResponse response;
    private String upstreamFormat;


    public FhirProxyHandler(MediatorConfig config) {
        this.config = config;
    }


    private void loadFhirContext() {
        ActorSelection actor = getContext().actorSelection(config.userPathFor("fhir-context"));
        actor.tell(new FhirContextActor.FhirContextRequest(requestHandler, getSelf()), getSelf());
    }


    private FhirValidationResult validateFhirRequest(String contentType, String body) {
        FhirValidationResult result = new FhirValidationResult();
        FhirValidator validator = fhirContext.newValidator();

        IParser parser = newParser(contentType);
        IBaseResource resource = parser.parseResource(body);
        ValidationResult vr = validator.validateWithResult(resource);

        if (vr.isSuccessful()) {
            result.passed = true;
        } else {
            result.passed = false;
            result.operationOutcome = vr.toOperationOutcome();
        }

        return result;
    }


    private void forwardRequest(Map<String, String> headers, String body) {
        String upstreamAccept = Constants.FHIR_MIME_JSON;
        if ("XML".equalsIgnoreCase(upstreamFormat) ||
                ("Client".equalsIgnoreCase(upstreamFormat) && determineClientContentType().contains("xml"))) {
            upstreamAccept = Constants.FHIR_MIME_XML;
        }
        headers.put("Accept", upstreamAccept);

        MediatorHTTPRequest newRequest = new MediatorHTTPRequest(
                requestHandler,
                getSelf(),
                "FHIR Upstream",
                request.getMethod(),
                (String)config.getDynamicConfig().get("upstream-scheme"),
                (String)config.getDynamicConfig().get("upstream-host"),
                ((Double)config.getDynamicConfig().get("upstream-port")).intValue(),
                request.getPath(),
                body,
                headers,
                request.getParams()
        );

        log.info("Forwarding to " + newRequest.getHost() + ":" + newRequest.getPort() + newRequest.getPath());

        ActorSelection httpConnector = getContext().actorSelection(config.userPathFor("http-connector"));
        httpConnector.tell(newRequest, getSelf());
    }

    private Map<String, String> copyHeaders(Map<String, String> headers) {
        Map<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String header : headers.keySet()) {
            if ("Content-Type".equalsIgnoreCase(header) || "Content-Length".equalsIgnoreCase(header) || "Host".equalsIgnoreCase(header)) {
                continue;
            }

            copy.put(header, headers.get(header));
        }
        return copy;
    }

    private void forwardRequest(Contents contents) {
        Map<String, String> headers = copyHeaders(request.getHeaders());
        if (contents!=null) {
            headers.put("Content-Type", contents.contentType);
        }

        forwardRequest(headers, contents.content);
    }

    private void forwardRequest() {
        Map<String, String> headers = copyHeaders(request.getHeaders());
        forwardRequest(headers, null);
    }

    private boolean isUpstreamAndClientFormatsEqual(String upstreamFormat, String clientContentType) {
        return ("JSON".equalsIgnoreCase(upstreamFormat) && clientContentType.contains("json")) ||
                ("XML".equalsIgnoreCase(upstreamFormat) && clientContentType.contains("xml"));
    }

    private Contents convertBodyForUpstream(String upstreamFormat, Contents body) {
        String targetContentType = Constants.FHIR_MIME_JSON;
        if ("XML".equalsIgnoreCase(upstreamFormat) ||
                ("Client".equalsIgnoreCase(upstreamFormat) && body.contentType.contains("xml"))) {
            targetContentType = Constants.FHIR_MIME_XML;
        }

        if ("Client".equalsIgnoreCase(upstreamFormat) || isUpstreamAndClientFormatsEqual(upstreamFormat, body.contentType)) {
            return new Contents(targetContentType, body.content);
        }

        IParser inParser = newParser(body.contentType);
        IBaseResource resource = inParser.parseResource(body.content);

        if ("JSON".equalsIgnoreCase(upstreamFormat) || "XML".equalsIgnoreCase(upstreamFormat)) {
            IParser outParser = newParser(targetContentType);
            String converted = outParser.setPrettyPrint(true).encodeResourceToString(resource);
            return new Contents(targetContentType, converted);
        } else {
            requestHandler.tell(new ExceptError(new RuntimeException("Unknown upstream format specified " + upstreamFormat)), getSelf());
            return null;
        }
    }

    private void processRequestWithContents() {
        String contentType = request.getHeaders().get("Content-Type");
        String body = request.getBody();
        Contents contents = new Contents(contentType, body);

        if ((Boolean)config.getDynamicConfig().get("validation-enabled")) {
            FhirValidationResult validationResult = validateFhirRequest(contentType, body);

            if (!validationResult.passed) {
                sendBadRequest(validationResult.operationOutcome);
                return;
            }
        }

        contents = convertBodyForUpstream(upstreamFormat, contents);
        forwardRequest(contents);
    }

    private void processClientRequest() {
        try {
            if (request.getMethod().equalsIgnoreCase("POST") || request.getMethod().equalsIgnoreCase("PUT")) {
                processRequestWithContents();
            } else {
                forwardRequest();
            }
        } catch (DataFormatException ex) {
            sendBadRequest(throwableToOperationOutcome(ex));
        }
    }

    private IBaseOperationOutcome throwableToOperationOutcome(Throwable ex) {
        IBaseOperationOutcome outcome = OperationOutcomeUtil.newInstance(fhirContext);
        OperationOutcomeUtil.addIssue(fhirContext, outcome, "error", ex.getMessage(), null, null);
        return outcome;
    }

    private void sendBadRequest(IBaseOperationOutcome outcome) {
        String responseContentType = determineClientContentType();

        IParser parser = newParser(responseContentType);
        String body = parser.encodeResourceToString(outcome);

        FinishRequest badRequest = new FinishRequest(body, responseContentType, HttpStatus.SC_BAD_REQUEST);
        requestHandler.tell(badRequest, getSelf());
    }


    private IParser newParser(String contentType) {
        if (contentType.contains("json")) {
            return fhirContext.newJsonParser();
        } else {
            return fhirContext.newXmlParser();
        }
    }

    private String determineClientContentType() {
        // first check for Accept header
        String accept = request.getHeaders().get("Accept");
        if (accept!=null && !"*/*".equals(accept)) {
            return accept;
        }

        // secondly for _format param
        String _format = request.getParams().get("_format");
        if (_format!=null) {
            return _format;
        }

        // thirdly check for the format the client sent content with
        String contentType = request.getHeaders().get("Content-Type");
        if (contentType!=null) {
            return contentType.contains("json") ? Constants.FHIR_MIME_JSON : Constants.FHIR_MIME_XML;
        }

        // else use JSON as a default
        return Constants.FHIR_MIME_JSON;
    }


    private Contents getResponseBodyAsContents() {
        String contentType = response.getHeaders().get("Content-Type");
        String body = response.getBody();

        if (body==null || body.trim().isEmpty()) {
            return null;
        }

        if (contentType==null || !contentType.contains("json") && !contentType.contains("xml")) {
            return null;
        }

        return new Contents(contentType, body);
    }

    private void respondWithContents(Contents contents) {
        Map<String, String> headers = copyHeaders(response.getHeaders());
        headers.put("Content-Type", contents.contentType);
        FinishRequest fr = new FinishRequest(contents.content, headers, response.getStatusCode());
        respondTo.tell(fr, getSelf());
    }

    private Contents convertResponseContents(String clientAccept, Contents responseContents) {
        IParser inParser = newParser(responseContents.contentType);
        IBaseResource resource = inParser.parseResource(responseContents.content);

        IParser outParser = newParser(clientAccept);
        String converted = outParser.setPrettyPrint(true).encodeResourceToString(resource);
        return new Contents(clientAccept, converted);
    }

    private void processUpstreamResponse() {
        log.info("Processing upstream response and responding to client");
        Contents contents = getResponseBodyAsContents();

        if ("Client".equalsIgnoreCase(upstreamFormat) || contents==null) {
            respondTo.tell(response.toFinishRequest(true), getSelf());
        } else {
            String clientAccept = determineClientContentType();

            if (("JSON".equalsIgnoreCase(upstreamFormat) && clientAccept.contains("json")) ||
                    ("XML".equalsIgnoreCase(upstreamFormat) && clientAccept.contains("xml"))) {
                respondWithContents(contents);
            } else {
                respondWithContents(convertResponseContents(clientAccept, contents));
            }
        }
    }


    @Override
    public void onReceive(Object msg) throws Exception {
        if (msg instanceof MediatorHTTPRequest) { //inbound request
            request = (MediatorHTTPRequest) msg;
            requestHandler = request.getRequestHandler();
            respondTo = request.getRespondTo();
            upstreamFormat = (String) config.getDynamicConfig().get("upstream-format");
            loadFhirContext();

        } else if (msg instanceof FhirContextActor.FhirContextResponse) { //response from FHIR context handler
            fhirContext = ((FhirContextActor.FhirContextResponse) msg).getResponseObject();
            processClientRequest();

        } else if (msg instanceof MediatorHTTPResponse) { //response from target server
            response = (MediatorHTTPResponse) msg;
            processUpstreamResponse();

        } else {
            unhandled(msg);
        }
    }
}

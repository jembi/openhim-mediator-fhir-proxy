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
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpStatus;
import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.openhim.mediator.engine.MediatorConfig;
import org.openhim.mediator.engine.messages.ExceptError;
import org.openhim.mediator.engine.messages.FinishRequest;
import org.openhim.mediator.engine.messages.MediatorHTTPRequest;
import org.openhim.mediator.engine.messages.MediatorHTTPResponse;

import java.util.ArrayList;
import java.util.List;
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
    private String openhimTrxID;
    private String upstreamFormat;


    public FhirProxyHandler(MediatorConfig config) {
        this.config = config;
    }


    private void loadFhirContext() {
        ActorSelection actor = getContext().actorSelection(config.userPathFor("fhir-context"));
        actor.tell(new FhirContextActor.FhirContextRequest(requestHandler, getSelf()), getSelf());
    }


    private FhirValidationResult validateFhirRequest(Contents contents) {
        FhirValidationResult result = new FhirValidationResult();
        FhirValidator validator = fhirContext.newValidator();

        IParser parser = newParser(contents.contentType);
        IBaseResource resource = parser.parseResource(contents.content);
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
        String upstreamAccept = determineTargetContentType(determineClientContentType());
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
                copyParams(request.getParams())
        );

        log.info("[" + openhimTrxID + "] Forwarding to " + newRequest.getHost() + ":" + newRequest.getPort() + newRequest.getPath());

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

    private List<Pair<String, String>> copyParams(List<Pair<String, String>> params) {
        List<Pair<String, String>> copy = new ArrayList<>();
        for (Pair<String, String> param : params) {
            if ("_format".equalsIgnoreCase(param.getKey())) {
                continue;
            }

            copy.add(param);
        }
        return copy;
    }

    private void forwardRequest(Contents contents) {
        Map<String, String> headers = copyHeaders(request.getHeaders());
        headers.put("Content-Type", contents.contentType);
        forwardRequest(headers, contents.content);
    }

    private void forwardRequest() {
        Map<String, String> headers = copyHeaders(request.getHeaders());
        forwardRequest(headers, null);
    }

    private String determineTargetContentType(String fromContentType) {
        String contentType = Constants.FHIR_MIME_JSON;
        if ("XML".equalsIgnoreCase(upstreamFormat) ||
                ("Client".equalsIgnoreCase(upstreamFormat) && fromContentType.contains("xml"))) {
            contentType = Constants.FHIR_MIME_XML;
        }
        return contentType;
    }

    private boolean isUpstreamAndClientFormatsEqual(String clientContentType) {
        return ("JSON".equalsIgnoreCase(upstreamFormat) && clientContentType.contains("json")) ||
                ("XML".equalsIgnoreCase(upstreamFormat) && clientContentType.contains("xml"));
    }

    private Contents convertBodyForUpstream(Contents contents) {
        String targetContentType = determineTargetContentType(contents.contentType);

        if ("Client".equalsIgnoreCase(upstreamFormat) || isUpstreamAndClientFormatsEqual(contents.contentType)) {
            return new Contents(targetContentType, contents.content);
        }

        log.info("[" + openhimTrxID + "] Converting request body to " + targetContentType);

        IParser inParser = newParser(contents.contentType);
        IBaseResource resource = inParser.parseResource(contents.content);

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
            FhirValidationResult validationResult = validateFhirRequest(contents);

            if (!validationResult.passed) {
                sendBadRequest(validationResult.operationOutcome);
                return;
            }
        }

        contents = convertBodyForUpstream(contents);
        if (contents==null) {
            return;
        }

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
        for (Pair<String, String> param : request.getParams()) {
            if (param.getKey().equals("_format")) {
                return param.getValue();
            }
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
        log.info("[" + openhimTrxID + "] Converting response body to " + clientAccept);

        IParser inParser = newParser(responseContents.contentType);
        IBaseResource resource = inParser.parseResource(responseContents.content);

        IParser outParser = newParser(clientAccept);
        String converted = outParser.setPrettyPrint(true).encodeResourceToString(resource);
        return new Contents(clientAccept, converted);
    }

    private void processUpstreamResponse() {
        log.info("[" + openhimTrxID + "] Processing upstream response and responding to client");
        Contents contents = getResponseBodyAsContents();

        if ("Client".equalsIgnoreCase(upstreamFormat) || contents==null) {
            respondTo.tell(response.toFinishRequest(true), getSelf());
        } else {
            String clientAccept = determineClientContentType();

            if (isUpstreamAndClientFormatsEqual(clientAccept)) {
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
            openhimTrxID = request.getHeaders().get("X-OpenHIM-TransactionID");
            upstreamFormat = (String) config.getDynamicConfig().get("upstream-format");
            loadFhirContext();

        } else if (msg instanceof FhirContextActor.FhirContextResponse) { //response from FHIR context handler
            fhirContext = ((FhirContextActor.FhirContextResponse) msg).getResponseObject();
            processClientRequest();

        } else if (msg instanceof MediatorHTTPResponse) { //response from upstream server
            response = (MediatorHTTPResponse) msg;
            processUpstreamResponse();

        } else {
            unhandled(msg);
        }
    }
}
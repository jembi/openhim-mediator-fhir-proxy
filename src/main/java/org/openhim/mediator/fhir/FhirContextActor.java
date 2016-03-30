/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.openhim.mediator.fhir;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import ca.uhn.fhir.context.FhirContext;
import org.openhim.mediator.engine.MediatorConfig;
import org.openhim.mediator.engine.messages.ExceptError;
import org.openhim.mediator.engine.messages.MediatorRequestMessage;
import org.openhim.mediator.engine.messages.SimpleMediatorRequest;
import org.openhim.mediator.engine.messages.SimpleMediatorResponse;

/**
 * An actor for handling the instantiation of the HAPI FHIR Context.
 *
 * The FHIR Context is an expensive object to create, and this actor allows for sharing of it among requests.
 */
public class FhirContextActor extends UntypedActor {
    public static class FhirContextRequest extends SimpleMediatorRequest<Object> {
        public FhirContextRequest(ActorRef requestHandler, ActorRef respondTo) {
            super(requestHandler, respondTo, null);
        }
    }

    public static class FhirContextResponse extends SimpleMediatorResponse<FhirContext> {
        public FhirContextResponse(MediatorRequestMessage originalRequest, FhirContext responseObject) {
            super(originalRequest, responseObject);
        }
    }


    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    private final MediatorConfig config;

    private String setupContext;
    private FhirContext fhirContext;


    public FhirContextActor(MediatorConfig config) {
        this.config = config;
    }


    private boolean setupFhirContext(ActorRef requestHandler) {
        String targetContext = (String)config.getDynamicConfig().get("fhir-context");

        if (fhirContext==null || !setupContext.equals(targetContext)) {
            log.info("Initializing HAPI FHIR context");

            switch (targetContext) {
                case "DSTU1":
                    fhirContext = FhirContext.forDstu1();
                    break;
                case "DSTU2":
                    fhirContext = FhirContext.forDstu2();
                    break;
                default:
                    requestHandler.tell(new ExceptError(new RuntimeException("Unsupported option specified for fhir-context")), getSelf());
                    return false;
            }

            setupContext = targetContext;
        }

        return true;
    }

    @Override
    public void onReceive(Object msg) throws Exception {

        if (msg instanceof FhirContextRequest) {
            if (setupFhirContext(((FhirContextRequest) msg).getRequestHandler())) {
                FhirContextResponse response = new FhirContextResponse((FhirContextRequest) msg, fhirContext);
                ((FhirContextRequest) msg).getRespondTo().tell(response, getSelf());
            }
        } else {
            unhandled(msg);
        }
    }
}
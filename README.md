[![Build Status](https://travis-ci.org/jembi/openhim-mediator-fhir-proxy.svg)](https://travis-ci.org/jembi/openhim-mediator-fhir-proxy) [![OpenHIM Core](https://img.shields.io/badge/openhim--core-1.4%2B-brightgreen.svg)](http://openhim.readthedocs.org/en/latest/user-guide/versioning.html)

openhim-mediator-fhir-proxy
===========================

An [OpenHIM](http://openhim.org) mediator for complementing the capabilities of an upstream FHIR server (e.g. a JSON-only Node.js service). The mediator provides services for XML/JSON format conversions as well as validation of incoming messages.

Validation and format conversions are handled with thanks to the [HAPI-FHIR](http://jamesagnew.github.io/hapi-fhir/) library.

# Usage
* Ensure that a [Java Runtime Environment](http://java.com/en/) is installed on your system. At a minimum Java 7 is required.
* Download the latest release of the mediator: `curl -LO https://github.com/jembi/openhim-mediator-fhir-proxy/releases/download/v1.0.0/openhim-mediator-fhir-proxy-1.0.0.tar.gz`
* Extract the downloaded archive: `tar -xzf openhim-mediator-fhir-proxy-1.0.0.tar.gz`
* Edit the properties file `mediator.properties` and change it as required for your implementantion
* The mediator is packaged as a standalone jar and can be run as follows: `java -jar mediator-fhir-proxy-1.0.0-jar-with-dependencies.jar --conf mediator.properties`

You can access the mediator config via the _Mediators_ page in the OpenHIM Console.

# Compiling and running from source
* `git clone https://github.com/jembi/openhim-mediator-fhir-proxy.git`
* `cd openhim-mediator-fhir-proxy`
* `mvn install`
* `java -jar target/mediator-fhir-proxy-1.0.0-jar-with-dependencies.jar`

# License
This software is licensed under the Mozilla Public License Version 2.0.

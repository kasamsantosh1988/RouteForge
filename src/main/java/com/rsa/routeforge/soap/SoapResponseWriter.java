package com.rsa.routeforge.soap;

import com.rsa.routeforge.error.ErrorCode;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.util.Map;

/**
 * Writes SOAP 1.1 responses and faults in the exact shapes existing Axis 1.4 clients parse.
 * <p>
 * Built on {@link XMLStreamWriter}, so text is always escaped and the document is always well
 * formed; namespace declarations are written explicitly (non-repairing mode) because the
 * placement of {@code xmlns} attributes is part of each partner's contract.
 */
public final class SoapResponseWriter {

    static final String XSD_NS = "http://www.w3.org/2001/XMLSchema";
    static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String ENV = "soapenv";

    /** Used if writing a fault itself fails; constant, so it cannot fail. */
    public static final String FALLBACK_FAULT =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<soapenv:Envelope xmlns:soapenv=\"" + SoapRequest.SOAP11_NS + "\">"
                    + "<soapenv:Body><soapenv:Fault><faultcode>soapenv:Server</faultcode>"
                    + "<faultstring>An unexpected error occurred.</faultstring>"
                    + "</soapenv:Fault></soapenv:Body></soapenv:Envelope>";

    private static final XMLOutputFactory FACTORY = XMLOutputFactory.newFactory();

    private SoapResponseWriter() {
    }

    /**
     * A success response: the configured wrapper around one return element carrying {@code value}.
     *
     * @param value written as text or CDATA; null writes an empty return element
     */
    public static String success(SoapResponseConfig response, Map<String, String> namespaces, String value) {
        QName wrapper = QNames.resolve(response.element(), namespaces);
        String ns = wrapper.getNamespaceURI();
        String returnName = response.returnElement();
        try {
            StringWriter out = new StringWriter();
            XMLStreamWriter w = FACTORY.createXMLStreamWriter(out);
            startEnvelope(w, true);

            if (response.prefixed()) {
                w.writeStartElement("ns1", wrapper.getLocalPart(), ns);
                w.writeNamespace("ns1", ns);
                if (response.qualifiedReturn()) {
                    w.writeStartElement("ns1", returnName, ns);
                } else {
                    w.writeStartElement(returnName);
                }
            } else {
                w.writeStartElement("", wrapper.getLocalPart(), ns);
                w.writeDefaultNamespace(ns);
                if (response.qualifiedReturn()) {
                    w.writeStartElement("", returnName, ns);
                } else {
                    // Unqualified child under a default namespace: reset it, as Axis does.
                    w.writeStartElement("", returnName, "");
                    w.writeDefaultNamespace("");
                }
            }

            if (value != null && !value.isEmpty()) {
                if (response.cdata()) {
                    writeCData(w, value);
                } else {
                    w.writeCharacters(value);
                }
            }

            w.writeEndElement(); // return
            w.writeEndElement(); // wrapper
            endEnvelope(w);
            return out.toString();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("could not write SOAP response", e);
        }
    }

    /**
     * A SOAP 1.1 fault. {@code faultstring} is the client-safe message; the detail carries only the
     * error code and trace id, never a host name or stack trace.
     */
    public static String fault(String faultCode, String faultString, String errorCode, String traceId) {
        try {
            StringWriter out = new StringWriter();
            XMLStreamWriter w = FACTORY.createXMLStreamWriter(out);
            startEnvelope(w, false);
            w.writeStartElement(ENV, "Fault", SoapRequest.SOAP11_NS);
            element(w, "faultcode", ENV + ":" + faultCode);
            element(w, "faultstring", faultString == null ? "" : faultString);
            if (errorCode != null || traceId != null) {
                w.writeStartElement("detail");
                if (errorCode != null) {
                    element(w, "code", errorCode);
                }
                if (traceId != null) {
                    element(w, "traceId", traceId);
                }
                w.writeEndElement();
            }
            w.writeEndElement(); // Fault
            endEnvelope(w);
            return out.toString();
        } catch (XMLStreamException | RuntimeException e) {
            return FALLBACK_FAULT;
        }
    }

    /**
     * The SOAP 1.1 fault code for an engine error: {@code VersionMismatch} for a SOAP 1.2 envelope,
     * {@code Client} for anything the caller caused (status below 500), {@code Server} otherwise.
     */
    public static String faultCodeFor(String errorCode, int logicalStatus) {
        if (ErrorCode.SOAP_VERSION_MISMATCH.code().equals(errorCode)) {
            return "VersionMismatch";
        }
        return logicalStatus < 500 ? "Client" : "Server";
    }

    private static void startEnvelope(XMLStreamWriter w, boolean schemaNamespaces) throws XMLStreamException {
        w.writeStartDocument("UTF-8", "1.0");
        w.writeStartElement(ENV, "Envelope", SoapRequest.SOAP11_NS);
        w.writeNamespace(ENV, SoapRequest.SOAP11_NS);
        if (schemaNamespaces) {
            w.writeNamespace("xsd", XSD_NS);
            w.writeNamespace("xsi", XSI_NS);
        }
        w.writeStartElement(ENV, "Body", SoapRequest.SOAP11_NS);
    }

    private static void endEnvelope(XMLStreamWriter w) throws XMLStreamException {
        w.writeEndElement(); // Body
        w.writeEndElement(); // Envelope
        w.writeEndDocument();
        w.flush();
        w.close();
    }

    private static void element(XMLStreamWriter w, String name, String text) throws XMLStreamException {
        w.writeStartElement(name);
        w.writeCharacters(text);
        w.writeEndElement();
    }

    /** A CDATA section cannot contain {@code ]]>}; split it across two sections. */
    private static void writeCData(XMLStreamWriter w, String value) throws XMLStreamException {
        String[] parts = value.split("]]>", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i < parts.length - 1) {
                w.writeCData(part + "]]");
                w.writeCharacters(">");
            } else if (!part.isEmpty() || parts.length == 1) {
                w.writeCData(part);
            }
        }
    }
}

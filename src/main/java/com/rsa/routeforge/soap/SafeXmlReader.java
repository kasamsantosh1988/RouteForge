package com.rsa.routeforge.soap;

import com.rsa.routeforge.error.ErrorCode;
import com.rsa.routeforge.error.OrchestrationException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Parses untrusted XML into an {@link XmlNode} tree with the attacks that matter for an internet
 * facing endpoint closed off.
 * <p>
 * <ul>
 *   <li>DTDs are not supported and any {@code DOCTYPE} is rejected, which closes XXE (external
 *       entities, local file reads, SSRF) and entity expansion ("billion laughs").</li>
 *   <li>External entities are disabled at the factory as well, in case a different StAX
 *       implementation is on the classpath.</li>
 *   <li>Size, nesting depth and element count are bounded by {@link SoapLimits}.</li>
 * </ul>
 * Every failure is an {@link OrchestrationException} with a client-safe message; the parser's own
 * message, which can echo request content, goes only into the internal detail.
 */
public final class SafeXmlReader {

    static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";

    private static final XMLInputFactory FACTORY = createFactory();

    private SafeXmlReader() {
    }

    private static XMLInputFactory createFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory;
    }

    public static XmlNode parse(String xml, SoapLimits limits) {
        if (xml == null || xml.isBlank()) {
            throw malformed("empty request body", null);
        }
        if (utf8Length(xml, limits.maxBodyBytes()) > limits.maxBodyBytes()) {
            throw new OrchestrationException(ErrorCode.SOAP_REQUEST_TOO_LARGE,
                    "request exceeds " + limits.maxBodyBytes() + " bytes");
        }

        XMLStreamReader reader = null;
        try {
            reader = FACTORY.createXMLStreamReader(new StringReader(xml));
            Deque<XmlNode> open = new ArrayDeque<>();
            XmlNode root = null;
            int elements = 0;

            while (reader.hasNext()) {
                int event = reader.next();
                switch (event) {
                    case XMLStreamConstants.DTD ->
                            throw malformed("DOCTYPE is not allowed", null);
                    case XMLStreamConstants.ENTITY_REFERENCE ->
                            throw malformed("entity references are not allowed", null);
                    case XMLStreamConstants.START_ELEMENT -> {
                        elements++;
                        if (elements > limits.maxElements()) {
                            throw new OrchestrationException(ErrorCode.SOAP_REQUEST_TOO_LARGE,
                                    "more than " + limits.maxElements() + " elements");
                        }
                        if (open.size() + 1 > limits.maxDepth()) {
                            throw new OrchestrationException(ErrorCode.SOAP_REQUEST_TOO_LARGE,
                                    "nesting deeper than " + limits.maxDepth());
                        }
                        String nilValue = reader.getAttributeValue(XSI_NS, "nil");
                        boolean nil = "true".equals(nilValue) || "1".equals(nilValue);
                        XmlNode node = new XmlNode(reader.getNamespaceURI(), reader.getLocalName(), nil);
                        if (open.isEmpty()) {
                            if (root != null) {
                                throw malformed("more than one root element", null);
                            }
                            root = node;
                        } else {
                            open.peek().addChild(node);
                        }
                        open.push(node);
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA,
                         XMLStreamConstants.SPACE -> {
                        if (!open.isEmpty()) {
                            open.peek().appendText(reader.getText());
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> open.pop();
                    default -> {
                        // comments, processing instructions, document start/end: ignored
                    }
                }
            }
            if (root == null) {
                throw malformed("no root element", null);
            }
            return root;
        } catch (OrchestrationException e) {
            throw e;
        } catch (XMLStreamException | RuntimeException e) {
            throw malformed("not well-formed XML: " + e.getMessage(), e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // nothing useful to do
                }
            }
        }
    }

    /** UTF-8 length, stopping early once past the limit so a huge body is not fully encoded. */
    private static long utf8Length(String s, int limit) {
        if (s.length() > limit) {
            return s.length(); // at least one byte per char
        }
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static OrchestrationException malformed(String detail, Throwable cause) {
        return new OrchestrationException(ErrorCode.SOAP_MALFORMED, detail, cause);
    }
}

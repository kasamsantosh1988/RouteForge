package com.rsa.routeforge.soap;

import com.rsa.routeforge.error.ErrorCode;
import com.rsa.routeforge.error.OrchestrationException;

import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed SOAP 1.1 request: the single element inside {@code Body} that names the operation.
 * <p>
 * The SOAP {@code Header} is ignored: none of the partners use one, and the elements some WSDLs
 * call "header" ({@code shIn}, {@code soapHeader}) are ordinary body elements.
 */
public final class SoapRequest {

    public static final String SOAP11_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    public static final String SOAP12_NS = "http://www.w3.org/2003/05/soap-envelope";

    private final XmlNode operation;

    private SoapRequest(XmlNode operation) {
        this.operation = operation;
    }

    /** Parses and checks the envelope. Every failure is a client-safe {@link OrchestrationException}. */
    public static SoapRequest parse(String xml, SoapLimits limits) {
        XmlNode root = SafeXmlReader.parse(xml, limits);
        if (!"Envelope".equals(root.localName())) {
            throw new OrchestrationException(ErrorCode.SOAP_MALFORMED, "root element is " + root + ", not a SOAP Envelope");
        }
        if (SOAP12_NS.equals(root.namespaceUri())) {
            throw new OrchestrationException(ErrorCode.SOAP_VERSION_MISMATCH, "SOAP 1.2 envelope received");
        }
        if (!SOAP11_NS.equals(root.namespaceUri())) {
            throw new OrchestrationException(ErrorCode.SOAP_VERSION_MISMATCH, "envelope namespace is '" + root.namespaceUri() + "'");
        }
        List<XmlNode> bodies = root.children(SOAP11_NS, "Body");
        if (bodies.size() != 1) {
            throw new OrchestrationException(ErrorCode.SOAP_MALFORMED, "expected one Body, found " + bodies.size());
        }
        List<XmlNode> payload = bodies.getFirst().children();
        if (payload.size() != 1) {
            throw new OrchestrationException(ErrorCode.SOAP_MALFORMED, "expected one element in Body, found " + payload.size());
        }
        return new SoapRequest(payload.getFirst());
    }

    /** The operation element, the single child of {@code Body}. */
    public XmlNode operation() {
        return operation;
    }

    public QName operationName() {
        return new QName(operation.namespaceUri(), operation.localName());
    }

    /** The operation element as a map: see {@link #toValue(XmlNode)}. */
    public Map<String, Object> bodyAsMap() {
        return toMap(operation);
    }

    /**
     * Resolves a configured path under the operation element.
     *
     * @param path       steps separated by {@code /}; {@code prefix:name} or {@code name} (no namespace)
     * @param namespaces the route's prefix map
     * @param lenient    match on local name alone, ignoring namespaces
     * @return null when nothing matches; the element's value for one match; a list for several
     */
    public Object field(String path, Map<String, String> namespaces, boolean lenient) {
        List<XmlNode> current = List.of(operation);
        for (String step : path.split("/")) {
            if (step.isBlank()) {
                continue;
            }
            QName name = QNames.resolve(step, namespaces);
            List<XmlNode> next = new ArrayList<>();
            for (XmlNode node : current) {
                next.addAll(lenient
                        ? node.childrenByLocalName(name.getLocalPart())
                        : node.children(name.getNamespaceURI(), name.getLocalPart()));
            }
            if (next.isEmpty()) {
                return null;
            }
            current = next;
        }
        if (current.size() == 1) {
            return toValue(current.getFirst());
        }
        List<Object> values = new ArrayList<>(current.size());
        current.forEach(n -> values.add(toValue(n)));
        return values;
    }

    /**
     * An element as a value: {@code null} for {@code xsi:nil}; its text, unchanged, for a leaf; a
     * map of child local name to value otherwise, with repeated children collected into a list.
     */
    public static Object toValue(XmlNode node) {
        if (node.isNil()) {
            return null;
        }
        return node.hasChildren() ? toMap(node) : node.text();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(XmlNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (XmlNode child : node.children()) {
            Object value = toValue(child);
            String key = child.localName();
            if (!map.containsKey(key)) {
                map.put(key, value);
            } else if (map.get(key) instanceof RepeatedValues list) {
                list.add(value);
            } else {
                RepeatedValues list = new RepeatedValues();
                list.add(map.get(key));
                list.add(value);
                map.put(key, list);
            }
        }
        return map;
    }

    /** Marks a list built from repeated elements, so a value that is itself a list is not merged. */
    private static final class RepeatedValues extends ArrayList<Object> {
    }
}

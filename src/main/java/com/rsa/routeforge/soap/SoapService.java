package com.rsa.routeforge.soap;

import com.rsa.routeforge.config.TransactionConfig;

import javax.xml.namespace.QName;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The SOAP route files that share one path: one endpoint, one WSDL, several operations.
 * <p>
 * The loader has already checked that the operations agree on partner, WSDL and public URL and
 * that each body element appears once.
 */
public final class SoapService {

    private final String path;
    private final String routeId;
    private final List<TransactionConfig> operations;
    private final Map<QName, TransactionConfig> byName = new LinkedHashMap<>();
    private final TransactionConfig serviceConfig;

    public SoapService(String path, List<TransactionConfig> operations) {
        if (operations == null || operations.isEmpty()) {
            throw new IllegalArgumentException("a SOAP service needs at least one operation");
        }
        this.path = path;
        this.routeId = "soap:" + path;
        this.operations = List.copyOf(operations);
        for (TransactionConfig op : this.operations) {
            QName name = QNames.resolve(op.soap().operation(), op.soap().namespacesOrEmpty());
            byName.put(new QName(name.getNamespaceURI(), name.getLocalPart()), op);
        }
        TransactionConfig first = this.operations.getFirst();
        // Answers requests no operation can take (malformed XML, unknown operation): the SOAP codec
        // writes the fault, and the audit row and metrics carry the service's id.
        this.serviceConfig = new TransactionConfig(routeId, path, first.partnerCode(), null, null, null,
                null, "POST", null, null, TransactionConfig.CHANNEL_SOAP, first.soap());
    }

    public String path() {
        return path;
    }

    /** The Camel route id, also used in audit and metrics for requests no operation took. */
    public String routeId() {
        return routeId;
    }

    public List<TransactionConfig> operations() {
        return operations;
    }

    public TransactionConfig serviceConfig() {
        return serviceConfig;
    }

    public String wsdl() {
        return operations.getFirst().soap().wsdl();
    }

    public String publicUrl() {
        return operations.getFirst().soap().publicUrl();
    }

    /**
     * The operation for a body element: an exact namespace and name match first, then an operation
     * configured with {@code lenientNamespaces} whose local name matches.
     *
     * @return null when no operation handles the element
     */
    public TransactionConfig find(QName element) {
        TransactionConfig exact = byName.get(new QName(element.getNamespaceURI(), element.getLocalPart()));
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<QName, TransactionConfig> entry : byName.entrySet()) {
            if (entry.getValue().soap().lenient() && entry.getKey().getLocalPart().equals(element.getLocalPart())) {
                return entry.getValue();
            }
        }
        return null;
    }
}

package com.rsa.routeforge.soap;

import javax.xml.namespace.QName;
import java.util.Map;

/** Resolves config names such as {@code web:MIBLProposalInfo} against a route's namespace map. */
public final class QNames {

    private QNames() {
    }

    /**
     * @return the qualified name; an unprefixed name has no namespace
     * @throws IllegalArgumentException for a blank name or an undeclared prefix
     */
    public static QName resolve(String name, Map<String, String> namespaces) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is blank");
        }
        String trimmed = name.trim();
        int colon = trimmed.indexOf(':');
        if (colon < 0) {
            return new QName("", trimmed);
        }
        String prefix = trimmed.substring(0, colon);
        String local = trimmed.substring(colon + 1);
        if (local.isEmpty() || local.contains(":")) {
            throw new IllegalArgumentException("'" + name + "' is not a valid qualified name");
        }
        String uri = namespaces == null ? null : namespaces.get(prefix);
        if (uri == null) {
            throw new IllegalArgumentException("prefix '" + prefix + "' in '" + name + "' is not declared in namespaces");
        }
        return new QName(uri, local, prefix);
    }
}

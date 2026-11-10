package com.rsa.routeforge.soap;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Map;

/**
 * The {@code soap} block of a route file whose {@code channel} is {@code soap}. One route file is
 * one operation; route files that share a {@code path} form one SOAP service.
 *
 * @param operation         qualified name of the body element that selects this operation,
 *                          for example {@code web:MIBLProposalInfo}
 * @param namespaces        prefix to namespace URI, used by {@code operation}, {@code fields} and
 *                          {@code response.element}. Prefixes are config-only; the partner may use
 *                          any prefixes on the wire
 * @param lenientNamespaces when true, fields match on local name alone, for clients that get
 *                          namespaces wrong. Default false
 * @param fields            name in {@code ctx.input} to a path under the operation element, with
 *                          {@code /} between steps; an unprefixed step means "no namespace",
 *                          for example {@code prop:shIn/auth:userName} or {@code soapHeader/userName}
 * @param auth              how the caller is authenticated; default {@code none}
 * @param response          shape of the success response
 * @param wsdl              resource location of the WSDL served at {@code <path>?wsdl}
 * @param publicUrl         endpoint address written into the served WSDL
 */
public record SoapRouteConfig(
        String operation,
        Map<String, String> namespaces,
        Boolean lenientNamespaces,
        Map<String, String> fields,
        SoapAuthConfig auth,
        SoapResponseConfig response,
        String wsdl,
        String publicUrl) {

    @JsonCreator
    public SoapRouteConfig {
    }

    public boolean lenient() {
        return Boolean.TRUE.equals(lenientNamespaces);
    }

    public Map<String, String> namespacesOrEmpty() {
        return namespaces == null ? Map.of() : namespaces;
    }

    public Map<String, String> fieldsOrEmpty() {
        return fields == null ? Map.of() : fields;
    }

    public String authType() {
        return auth == null || auth.type() == null ? SoapAuthConfig.NONE : auth.type();
    }
}

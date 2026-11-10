package com.rsa.routeforge.soap;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Set;

/**
 * Shape of a SOAP operation's success response, matched to what the partner's client parses today.
 *
 * @param element         qualified name of the wrapper, for example {@code web:MIBLProposalInfoResponse}
 * @param returnElement   local name of the single child that carries the value, for example {@code return}
 * @param returnQualified whether the return element is in the wrapper's namespace (Maruti) or in
 *                        no namespace (MIBL, written with {@code xmlns=""}). Default true
 * @param prefixStyle     {@code defaultNamespace} ({@code xmlns="..."} on the wrapper, as Axis
 *                        writes it) or {@code prefixed} ({@code ns1:} wrapper). Default
 *                        {@code defaultNamespace}
 * @param returnEncoding  {@code text} (escaped) or {@code cdata}. Default {@code text}
 * @param returnType      {@code string} or {@code int}; informational, the value is written as text.
 *                        Default {@code string}
 */
public record SoapResponseConfig(
        String element,
        String returnElement,
        Boolean returnQualified,
        String prefixStyle,
        String returnEncoding,
        String returnType) {

    public static final String DEFAULT_NAMESPACE = "defaultNamespace";
    public static final String PREFIXED = "prefixed";
    public static final Set<String> PREFIX_STYLES = Set.of(DEFAULT_NAMESPACE, PREFIXED);

    public static final String TEXT = "text";
    public static final String CDATA = "cdata";
    public static final Set<String> ENCODINGS = Set.of(TEXT, CDATA);

    public static final Set<String> RETURN_TYPES = Set.of("string", "int");

    @JsonCreator
    public SoapResponseConfig {
    }

    public boolean qualifiedReturn() {
        return returnQualified == null || returnQualified;
    }

    public boolean prefixed() {
        return PREFIXED.equals(prefixStyle);
    }

    public boolean cdata() {
        return CDATA.equals(returnEncoding);
    }
}

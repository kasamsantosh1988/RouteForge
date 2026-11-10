package com.rsa.routeforge.soap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One parsed XML element: its namespace and local name, its child elements and its text.
 * <p>
 * Deliberately small: SOAP payloads here are element trees of strings, so attributes other than
 * {@code xsi:nil} are not kept. Text of an element that also has child elements (mixed content,
 * usually indentation or a stray character) is kept but never used as the element's value.
 */
public final class XmlNode {

    private final String namespaceUri;
    private final String localName;
    private final List<XmlNode> children = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();
    private final boolean nil;

    public XmlNode(String namespaceUri, String localName, boolean nil) {
        this.namespaceUri = namespaceUri == null ? "" : namespaceUri;
        this.localName = localName;
        this.nil = nil;
    }

    /** The element's namespace URI; {@code ""} for an unqualified element. */
    public String namespaceUri() {
        return namespaceUri;
    }

    public String localName() {
        return localName;
    }

    /** Whether the element carried {@code xsi:nil="true"}. */
    public boolean isNil() {
        return nil;
    }

    public List<XmlNode> children() {
        return Collections.unmodifiableList(children);
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    /** The element's text exactly as sent (entities resolved, CDATA unwrapped, not trimmed). */
    public String text() {
        return text.toString();
    }

    /** Child elements with this namespace and local name. */
    public List<XmlNode> children(String namespaceUri, String localName) {
        String ns = namespaceUri == null ? "" : namespaceUri;
        return children.stream()
                .filter(c -> c.localName.equals(localName) && c.namespaceUri.equals(ns))
                .toList();
    }

    /** Child elements with this local name, in any namespace. */
    public List<XmlNode> childrenByLocalName(String localName) {
        return children.stream().filter(c -> c.localName.equals(localName)).toList();
    }

    void addChild(XmlNode child) {
        children.add(child);
    }

    void appendText(String chunk) {
        text.append(chunk);
    }

    @Override
    public String toString() {
        return (namespaceUri.isEmpty() ? "" : "{" + namespaceUri + "}") + localName;
    }
}

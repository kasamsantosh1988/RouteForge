package com.rsa.routeforge.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsa.routeforge.config.EngineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Prepares a payload for storage: mask configured fields, then bound the length.
 * <p>
 * Order matters. Masking runs on the whole payload <em>before</em> truncation, because truncating
 * first would cut the JSON mid-structure and leave nothing parseable to mask.
 */
@Component
public class AuditPayloadSanitizer {

    private static final Logger log = LoggerFactory.getLogger(AuditPayloadSanitizer.class);

    static final String MASK = "***";
    static final String TRUNCATION_MARKER = "...<truncated>";

    /**
     * Stored in place of a payload that masking is configured for but that could not be parsed as
     * JSON. Fail-closed: an unparseable payload is exactly the case where we cannot prove the
     * sensitive fields were removed, so it is withheld rather than stored verbatim.
     */
    static final String UNPARSEABLE_WITHHELD =
            "<withheld: masking is configured and this payload is not parseable JSON>";

    private final EngineProperties props;
    private final ObjectMapper objectMapper;

    public AuditPayloadSanitizer(EngineProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /** Sanitizes a request or response body. Returns null for null/blank input. */
    public String sanitizeBody(String payload) {
        EngineProperties.Audit cfg = props.getAudit();
        if (!cfg.isStorePayloads()) {
            return null;
        }
        return sanitize(payload);
    }

    /**
     * Serializes and sanitizes the inbound header map. Returns null when header storage is off.
     * <p>
     * Values are stringified first. Camel header values are arbitrary objects and need not be
     * Jackson-friendly; without this, one unserializable value would cost us the whole header map.
     */
    public String sanitizeHeaders(Map<String, ?> headers) {
        EngineProperties.Audit cfg = props.getAudit();
        if (!cfg.isStoreHeaders() || headers == null || headers.isEmpty()) {
            return null;
        }
        String json;
        try {
            Map<String, String> flattened = new LinkedHashMap<>();
            // Inside the try: String.valueOf calls toString() on values the engine does not own, and
            // a header whose toString() throws must cost us the header map, not the audit row.
            headers.forEach((key, value) -> flattened.put(String.valueOf(key), String.valueOf(value)));
            json = objectMapper.writeValueAsString(flattened);
        } catch (Exception e) {
            log.debug("Audit: inbound headers could not be captured", e);
            return null;
        }
        return sanitize(json);
    }

    private String sanitize(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        return truncate(mask(payload));
    }

    /**
     * Replaces the values of {@code engine.audit.mask-fields} with {@value #MASK} at any depth.
     * With no fields configured — the default — the payload is returned untouched and never parsed.
     */
    private String mask(String payload) {
        Set<String> fields = maskFieldsLowercase();
        if (fields.isEmpty()) {
            return payload;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            maskNode(root, fields);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.debug("Audit: payload is not parseable JSON; withholding it because masking is configured", e);
            return UNPARSEABLE_WITHHELD;
        }
    }

    private void maskNode(JsonNode node, Set<String> fields) {
        if (node instanceof ObjectNode object) {
            // Field names are collected first: replacing while iterating the live node would mutate
            // the map the iterator is walking.
            List<String> names = new ArrayList<>(object.properties().size());
            for (Map.Entry<String, JsonNode> property : object.properties()) {
                names.add(property.getKey());
            }
            for (String name : names) {
                if (fields.contains(name.toLowerCase(Locale.ROOT))) {
                    object.put(name, MASK);
                } else {
                    maskNode(object.get(name), fields);
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(child -> maskNode(child, fields));
        }
    }

    private Set<String> maskFieldsLowercase() {
        List<String> configured = props.getAudit().getMaskFields();
        if (configured == null || configured.isEmpty()) {
            return Set.of();
        }
        Set<String> lowered = new HashSet<>();
        for (String field : configured) {
            if (field != null && !field.isBlank()) {
                lowered.add(field.trim().toLowerCase(Locale.ROOT));
            }
        }
        return lowered;
    }

    private String truncate(String payload) {
        int max = props.getAudit().getMaxPayloadChars();
        if (max <= 0 || payload.length() <= max) {
            return payload;
        }
        return payload.substring(0, max) + TRUNCATION_MARKER;
    }
}

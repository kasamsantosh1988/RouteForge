package com.rsa.routeforge.config;

import com.bazaarvoice.jolt.Chainr;
import com.bazaarvoice.jolt.JsonUtils;
import com.rsa.routeforge.error.ErrorCode;
import com.rsa.routeforge.error.OrchestrationException;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JoltTransformer {
    /*
     * Stores compiled JOLT specifications.
     */
    private final Map<String, Chainr> specCache = new ConcurrentHashMap<>();
    /*
     * Stores the expected static output paths extracted from each
     * JOLT shift specification.
     *
     */
    private final Map<String, Set<String>> outputPathCache = new ConcurrentHashMap<>();

    /**
     * Applies the JOLT transformation and globally ensures that every
     * static destination field declared in the shift specification exists.
     * <p>
     * Rules:
     * <p>
     * 1. Existing values are preserved.
     * 2. Missing destination fields are added with null.
     * 3. Empty and whitespace-only strings are converted to null.
     * 4. false remains false.
     * 5. Numeric zero remains zero.
     * 6. Nested maps and lists are normalized recursively.
     */
    public Object transform(String specPath, Object input) {
        validateSpecPath(specPath);
        Chainr chainr = specCache.computeIfAbsent(specPath, this::loadSpec);
        try {
            /*
             * Pass the original input to JOLT.
             *
             * Do not call stripNulls(), because null input values
             * should not be removed before transformation.
             */
            Object transformed = chainr.transform(input);
            /*
             * Get all expected static output paths extracted when
             * the JOLT specification was loaded.
             */
            Set<String> expectedOutputPaths = outputPathCache.getOrDefault(specPath, Collections.emptySet());
            /*
             * Convert the JOLT result into recursively mutable maps
             * and lists.
             */
            Object normalizedOutput = normalizeOutput(transformed);
            /*
             * Add destinations that JOLT did not create because
             * the third party omitted the corresponding source fields.
             */
            return addMissingOutputFields(normalizedOutput, expectedOutputPaths);
        } catch (OrchestrationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new OrchestrationException(ErrorCode.TRANSFORM_FAILED, "JOLT transform failed for spec '" + specPath + "': " + exception.getMessage(), exception);
        }
    }

    /**
     * Resolves and compiles a JOLT specification during application startup.
     */
    public void preload(String specPath) {
        validateSpecPath(specPath);
        specCache.computeIfAbsent(specPath, this::loadSpec);
    }

    /**
     * Loads a JOLT specification from the classpath, extracts its static
     * shift destinations and compiles the specification.
     */
    private Chainr loadSpec(String specPath) {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(specPath)) {
            if (inputStream == null) {
                throw new OrchestrationException(ErrorCode.CONFIG_ERROR, "JOLT spec not found on the classpath: " + specPath);
            }
            List<Object> specification =

                    JsonUtils.jsonToList(inputStream);
            /*
             * Extract and cache all static destination paths.
             */
            Set<String> outputPaths =

                    extractOutputPaths(specification);
            outputPathCache.put(specPath, Collections.unmodifiableSet(outputPaths));
            return Chainr.fromSpec(specification);
        } catch (OrchestrationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new OrchestrationException(ErrorCode.CONFIG_ERROR, "Failed to compile JOLT spec '" + specPath + "': " + exception.getMessage(), exception);
        }
    }

    /**
     * Finds all static destination paths in all shift operations.
     * <p>
     * Example mapping:
     * <p>
     * "owner_name": "vehicle.owner_name"
     * <p>
     * Extracted expected destination:
     * <p>
     * vehicle.owner_name
     */
    private Set<String> extractOutputPaths(List<Object> specification) {
        Set<String> outputPaths = new LinkedHashSet<>();
        if (specification == null) {
            return outputPaths;
        }
        for (Object operationObject : specification) {
            if (!(operationObject instanceof Map<?, ?> operation)) {
                continue;
            }
            Object operationName = operation.get("operation");
            if (!"shift".equals(String.valueOf(operationName))) {
                continue;
            }
            Object shiftSpecification = operation.get("spec");
            collectStaticDestinationPaths(shiftSpecification, outputPaths);
        }
        return outputPaths;
    }


    /**
     * Recursively walks through a shift specification and collects
     * destination-path strings.
     */

    private void collectStaticDestinationPaths(Object specificationValue, Set<String> outputPaths) {
        if (specificationValue == null) {
            return;
        }

        if (specificationValue instanceof Map<?, ?> map) {
            for (Object childValue : map.values()) {
                collectStaticDestinationPaths(childValue, outputPaths);
            }
            return;
        }


        if (specificationValue instanceof List<?> list) {
            for (Object childValue : list) {
                collectStaticDestinationPaths(childValue, outputPaths);
            }
            return;
        }


        if (specificationValue instanceof String destinationPath) {
            String normalizedPath = normalizeDestinationPath(destinationPath);
            if (isStaticDestinationPath(normalizedPath)) {
                outputPaths.add(normalizedPath);
            }
        }
    }


    /**
     * Removes unnecessary leading dots from a destination path.
     */
    private String normalizeDestinationPath(String destinationPath) {
        if (destinationPath == null) {
            return null;
        }
        String normalizedPath = destinationPath.trim();
        while (normalizedPath.startsWith(".")) {
            normalizedPath = normalizedPath.substring(1);
        }
        return normalizedPath;
    }

    /**
     * Determines whether a JOLT destination is a static object path.
     * <p>
     * Supported:
     * <p>
     * vehicle.owner_name
     * previous_policy.tp_policy_number
     * <p>
     * Not automatically completed:
     * <p>
     * items[&1].name
     * result.*
     *
     * @(1,dynamicField)
     */

    private boolean isStaticDestinationPath(String destinationPath) {
        if (destinationPath == null || destinationPath.isBlank()) {
            return false;
        }

        return !destinationPath.contains("*") && !destinationPath.contains("&") && !destinationPath.contains("@") && !destinationPath.contains("[") && !destinationPath.contains("]") && !destinationPath.contains("$") && !destinationPath.contains("#");
    }

    /**
     * Normalizes the JOLT output recursively.
     * <p>
     * It converts:
     * <p>
     * ""        to null
     * "   "     to null
     * <p>
     * It preserves:
     * <p>
     * false
     * 0
     * non-empty strings
     * empty lists
     * empty maps
     */
    private Object normalizeOutput(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String stringValue) {
            return (stringValue == null || "null".equalsIgnoreCase(stringValue)) ? null : stringValue.trim();
        }
        if (value instanceof Map<?, ?> sourceMap) {
            Map<String, Object> normalizedMap = new LinkedHashMap<>();
            sourceMap.forEach((key, fieldValue) -> {
                String fieldName = String.valueOf(key);
                Object normalizedValue = normalizeOutput(fieldValue);
                /*
                 * Always preserve the key, even when its normalized
                 * value is null.
                 */
                normalizedMap.put(fieldName, normalizedValue);
            });
            return normalizedMap;
        }
        if (value instanceof List<?> sourceList) {
            List<Object> normalizedList = new ArrayList<>(sourceList.size());
            for (Object item : sourceList) {
                normalizedList.add(normalizeOutput(item));
            }
            return normalizedList;
        }
        /*
         * Boolean, Number and other value types remain unchanged.
         */
        return value;
    }

    /**
     * Adds every missing static output destination to the transformed result.
     */
    private Object addMissingOutputFields(Object transformed, Set<String> expectedOutputPaths) {
        if (expectedOutputPaths == null || expectedOutputPaths.isEmpty()) {
            return transformed;
        }

        Map<String, Object> output;
        if (transformed == null) {
            output = new LinkedHashMap<>();
        } else if (transformed instanceof Map<?, ?> transformedMap) {
            output = toMutableMap(transformedMap);
        } else {
            /*
             * Output-path completion cannot be safely applied when
             * the root result is a scalar value or an array.
             */
            return transformed;
        }
        for (String expectedPath : expectedOutputPaths) {
            addMissingPath(output, expectedPath);
        }
        return output;
    }

    /**
     * Converts a map and its nested structures into mutable objects.
     */
    private Map<String, Object> toMutableMap(Map<?, ?> sourceMap) {
        Map<String, Object> mutableMap = new LinkedHashMap<>();
        sourceMap.forEach((key, value) -> mutableMap.put(String.valueOf(key), toMutableValue(value)));
        return mutableMap;
    }

    /**
     * Converts nested maps and lists into mutable objects.
     */
    private Object toMutableValue(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            return toMutableMap(mapValue);
        }
        if (value instanceof List<?> listValue) {
            List<Object> mutableList = new ArrayList<>(listValue.size());
            for (Object item : listValue) {
                mutableList.add(toMutableValue(item));
            }
            return mutableList;
        }
        return value;
    }

    /**
     * Creates a missing static destination path with null.
     * <p>
     * Example:
     * <p>
     * Expected path:
     * <p>
     * vehicle.owner_name
     * <p>
     * Existing result:
     * <p>
     * {
     * "vehicle": {
     * "type": "PRIVATE_CAR"
     * }
     * }
     * <p>
     * Completed result:
     * <p>
     * {
     * "vehicle": {
     * "type": "PRIVATE_CAR",
     * "owner_name": null
     * }
     * }
     */
    @SuppressWarnings("unchecked")
    private void addMissingPath(Map<String, Object> output, String destinationPath) {
        if (destinationPath == null || destinationPath.isBlank()) {
            return;
        }
        String[] pathParts = destinationPath.split("\\.");
        Map<String, Object> currentObject = output;
        for (int index = 0; index < pathParts.length; index++) {
            String pathPart = pathParts[index];
            if (pathPart == null || pathPart.isBlank()) {
                continue;
            }
            boolean lastPathPart = index == pathParts.length - 1;
            if (lastPathPart) {
                /*
                 * Add a null field only when JOLT did not already
                 * create the destination field.
                 */
                if (!currentObject.containsKey(pathPart)) {
                    currentObject.put(pathPart, null);
                }
                return;
            }
            Object existingValue = currentObject.get(pathPart);
            if (existingValue instanceof Map<?, ?> existingMap) {
                Map<String, Object> mutableChild;
                if (existingValue instanceof LinkedHashMap<?, ?>) {
                    mutableChild = (Map<String, Object>) existingValue;
                } else {
                    mutableChild = toMutableMap(existingMap);
                    currentObject.put(pathPart, mutableChild);
                }
                currentObject = mutableChild;
            } else if (existingValue == null) {
                Map<String, Object> newChild = new LinkedHashMap<>();
                currentObject.put(pathPart, newChild);
                currentObject = newChild;
            } else {
                /*
                 * Do not overwrite an existing scalar value to create
                 * a nested object.
                 */
                return;
            }
        }
    }

    /**
     * Validates a JOLT specification path.
     */
    private void validateSpecPath(String specPath) {
        if (specPath == null || specPath.isBlank()) {
            throw new OrchestrationException(ErrorCode.CONFIG_ERROR, "JOLT spec path must not be null or blank");
        }
    }
}
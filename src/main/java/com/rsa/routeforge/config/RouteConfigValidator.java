package com.rsa.routeforge.config;

import com.rsa.routeforge.error.OrchestrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates the whole route-config graph up front.
 * <p>
 * This exists because a family of config mistakes previously surfaced only when a request arrived,
 * and then only as a generic {@code INTERNAL_SERVER_ERROR}: a null {@code type} NPEs past the
 * {@code UNKNOWN_STEP_TYPE} arm of the switch, a {@code parallel} step with null branches NPEs on
 * {@code branches().size()}, a {@code call} with a null uri NPEs on {@code contains("?")}, and a
 * mistyped JOLT spec path fails on first use. All of those are deterministic properties of the
 * config, so they belong at startup.
 * <p>
 * Every problem is collected rather than thrown on first sight — one startup failure listing eight
 * problems beats eight restart cycles.
 */
@Component
public class RouteConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(RouteConfigValidator.class);

    private static final Set<String> KNOWN_STEP_TYPES =
            Set.of("call", "businessLogic", "conditional", "parallel", "validation");
    private static final Set<String> ALLOWED_METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^}]*)}}");
    private static final int MAX_DEPTH = 20;

    private final JoltTransformer joltTransformer;
    private final ApplicationContext applicationContext;

    /** Collected bean references, checked once the context is fully built. */
    private final List<BeanRefUsage> beanRefs = new ArrayList<>();

    public RouteConfigValidator(JoltTransformer joltTransformer, ApplicationContext applicationContext) {
        this.joltTransformer = joltTransformer;
        this.applicationContext = applicationContext;
    }

    /**
     * Structural validation of one route. Returns every problem found; an empty list means valid.
     * Bean references are recorded here but verified later — see {@link #verifyBeanRefs()}.
     */
    public List<String> validate(TransactionConfig cfg, String fileName) {
        log.debug("Validating route config: file={}, routeId={}, path={}", fileName, cfg.routeId(), cfg.path());
        List<String> errors = new ArrayList<>();
        String where = fileName + " (routeId=" + cfg.routeId() + ")";

        if (isBlank(cfg.routeId())) {
            errors.add(where + ": routeId is required");
        }
        if (isBlank(cfg.path())) {
            errors.add(where + ": path is required");
        } else if (!cfg.path().startsWith("/")) {
            errors.add(where + ": path must start with '/' but was '" + cfg.path() + "'");
        }

        checkSpec(cfg.requestJoltSpec(), where + " requestJoltSpec", errors);
        checkSpec(cfg.responseJoltSpec(), where + " responseJoltSpec", errors);

        // A null steps list is legal — bima_routes-config.json relies on it for a passthrough route.
        validateSteps(cfg.steps(), where, "steps", 0, errors);

        if (!errors.isEmpty()) {
            log.warn("Route validation failed: file={}, routeId={}, errors={}",fileName, cfg.routeId(), errors.size());
        } else {
            log.debug("Route validation successful: file={}, routeId={}",fileName, cfg.routeId());
        }
        return errors;
    }

    private void validateSteps(List<OrchestrationStep> steps, String where, String path,
                               int depth, List<String> errors) {
        if (steps == null) {
            return;
        }
        if (depth > MAX_DEPTH) {
            errors.add(where + " " + path + ": nesting deeper than " + MAX_DEPTH + " levels");
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            OrchestrationStep step = steps.get(i);
            String at = path + "[" + i + "]";
            if (step == null) {
                errors.add(where + " " + at + ": step is null");
                continue;
            }
            validateStep(step, where, at + (step.id() == null ? "" : "(" + step.id() + ")"), depth, errors);
        }
    }

    private void validateStep(OrchestrationStep step, String where, String at,
                              int depth, List<String> errors) {
        if (isBlank(step.id())) {
            errors.add(where + " " + at + ": id is required");
        }
        if (step.type() == null) {
            // Guarded explicitly: a null here would NPE inside switch-on-String at runtime.
            errors.add(where + " " + at + ": type is required (one of " + KNOWN_STEP_TYPES + ")");
            return;
        }
        if (!KNOWN_STEP_TYPES.contains(step.type())) {
            errors.add(where + " " + at + ": unknown type '" + step.type() + "', expected one of " + KNOWN_STEP_TYPES);
            return;
        }

        checkSpec(step.requestJoltSpec(), where + " " + at + " requestJoltSpec", errors);
        checkSpec(step.responseJoltSpec(), where + " " + at + " responseJoltSpec", errors);

        switch (step.type()) {
            case "call" -> validateCall(step, where, at, errors);
            case "businessLogic" -> {
                if (isBlank(step.beanRef())) {
                    errors.add(where + " " + at + ": businessLogic requires beanRef");
                } else {
                    beanRefs.add(new BeanRefUsage(where + " " + at, step.beanRef(), OrchestrationBusinessLogic.class));
                }
            }
            case "validation" -> {
                boolean hasFields = step.requiredFields() != null && !step.requiredFields().isEmpty();
                boolean hasFieldValidations = step.fieldValidations() != null && !step.fieldValidations().isEmpty();
                if (!hasFields && isBlank(step.beanRef())
                    && !hasFieldValidations) {
                    errors.add(where + " " + at + ": validation step has neither requiredFields nor beanRef, so it can never fail");
                }
                if (!isBlank(step.beanRef())) {
                    beanRefs.add(new BeanRefUsage(where + " " + at, step.beanRef(), OrchestrationValidator.class));
                }
                if (hasFields) {
                    for (String field : step.requiredFields()) {
                        if (isBlank(field)) {
                            errors.add(where + " " + at + ": requiredFields contains a blank entry");
                        }
                    }
                }
            }
            case "conditional" -> {
                if (isBlank(step.condition())) {
                    errors.add(where + " " + at + ": conditional requires a condition");
                } else {
                    try {
                        new SpelExpressionParser().parseExpression(step.condition());
                    } catch (Exception e) {
                        errors.add(where + " " + at + ": condition is not valid SpEL: " + e.getMessage());
                    }
                }
                if ((step.thenSteps() == null || step.thenSteps().isEmpty())
                        && (step.elseSteps() == null || step.elseSteps().isEmpty())) {
                    errors.add(where + " " + at + ": conditional has neither thenSteps nor elseSteps");
                }
                validateSteps(step.thenSteps(), where, at + ".thenSteps", depth + 1, errors);
                validateSteps(step.elseSteps(), where, at + ".elseSteps", depth + 1, errors);
            }
            case "parallel" -> {
                if (step.branches() == null || step.branches().isEmpty()) {
                    errors.add(where + " " + at + ": parallel requires a non-empty branches list");
                }
                validateSteps(step.branches(), where, at + ".branches", depth + 1, errors);
            }
            default -> throw new IllegalStateException("unreachable: " + step.type());
        }
    }

    private void validateCall(OrchestrationStep step, String where, String at, List<String> errors) {
        if (isBlank(step.uri())) {
            errors.add(where + " " + at + ": call requires a uri");
        } else {
            Matcher m = PLACEHOLDER.matcher(step.uri());
            while (m.find()) {
                if (m.group(1).trim().isEmpty()) {
                    errors.add(where + " " + at + ": empty placeholder '{{}}' in uri");
                }
            }
            // Placeholders are not legal URI characters, so substitute before parsing.
            /*String probe = PLACEHOLDER.matcher(step.uri()).replaceAll("placeholder");
            try {
               URI parsed = URI.create(probe);
                if (parsed.getScheme() == null) {
                    errors.add(where + " " + at + ": uri has no scheme: '" + step.uri() + "'");
                }
            } catch (Exception e) {
                errors.add(where + " " + at + ": uri is not parseable: " + e.getMessage());
            }*/
        }
        if (step.method() != null && !ALLOWED_METHODS.contains(step.method().toUpperCase())) {
            errors.add(where + " " + at + ": unsupported method '" + step.method() + "'");
        }
    }

    /** Resolves and compiles the spec now, so a mistyped path cannot become a runtime 500. */
    private void checkSpec(String specPath, String label, List<String> errors) {
        if (isBlank(specPath)) {
            return;
        }
        try {
            joltTransformer.preload(specPath);
        } catch (Exception e) {
            errors.add(label + ": " + diagnostic(e));
        }
    }

    /**
     * The message to put in a startup report.
     * <p>
     * {@link OrchestrationException#getMessage()} is deliberately the client-safe text ("An
     * unexpected error occurred."), which is useless to an operator reading a boot failure. The
     * detail naming the file and spec path lives in {@code internalDetail()}.
     */
    private static String diagnostic(Throwable t) {
        if (t instanceof OrchestrationException oe && oe.internalDetail() != null) {
            return oe.internalDetail();
        }
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    /**
     * Bean references are verified after the context is built — during route building the
     * application context may still be mid-refresh.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void verifyBeanRefs() {
        List<String> errors = new ArrayList<>();
        for (BeanRefUsage usage : beanRefs) {
            Object bean;
            try {
                bean = applicationContext.getBean(usage.beanRef());
            } catch (Exception e) {
                errors.add(usage.where() + ": bean '" + usage.beanRef() + "' not found");
                continue;
            }
            if (!usage.requiredType().isInstance(bean)) {
                errors.add(usage.where() + ": bean '" + usage.beanRef() + "' must implement "
                        + usage.requiredType().getSimpleName()
                        + " but is " + bean.getClass().getName());
            }
        }
        if (!errors.isEmpty()) {
            // Warn rather than kill a running context: routes still report BEAN_NOT_FOUND /
            // INVALID_BEAN_TYPE per request, and this is the early warning for that.
            log.error("Route config references {} invalid bean(s):\n  - {}",
                    errors.size(), String.join("\n  - ", errors));
        } else if (!beanRefs.isEmpty()) {
            log.info("Verified {} route bean reference(s)", beanRefs.size());
        }
    }

    /** Called by the loader before each full reload so references don't accumulate. */
    public void resetBeanRefs() {
        beanRefs.clear();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private record BeanRefUsage(String where, String beanRef, Class<?> requiredType) {
    }
}

package com.rsa.routeforge.config;

import java.util.List;
import java.util.Map;

public interface OrchestrationValidator {
    List<String> validate(Map<String, Object> ctx);
}

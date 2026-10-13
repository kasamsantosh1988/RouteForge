package com.rsa.routeforge.validation;


import com.rsa.routeforge.error.ErrorCode;
import com.rsa.routeforge.error.OrchestrationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component("productCombinationValidator")
public class ProductCombinationValidator implements FieldValidator {

    @Override
    public boolean isValid(Object fieldValue, Map<String, Object> params, Map<String, Object> ctx) {

        Map<String, Object> input = ctx.get("input") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : throwMissing("input");

        String productType = String.valueOf(input.get("product_type"));
        String quoteType = String.valueOf(input.get("quote_type"));
        String businessType = String.valueOf(input.get("business_type"));
        String coverType = String.valueOf(input.get("cover_type"));

        if (params == null || !params.containsKey("allowedCombinations")) {
            throw new IllegalArgumentException("ProductCombinationValidator requires a 'allowedCombinations' param in route config");
        }

        List<Map<String, String>> allowedCombinations = (List<Map<String, String>>) params.get("allowedCombinations");

        boolean valid = allowedCombinations.stream()
                .anyMatch(combination ->
                        productType.equalsIgnoreCase(
                                combination.get("productType"))
                                && quoteType.equalsIgnoreCase(
                                combination.get("quoteType"))
                                && businessType.equalsIgnoreCase(
                                combination.get("businessType"))
                                && coverType.equalsIgnoreCase(
                                combination.get("coverType")));
        if (!valid) {
            return false;
        } else {
            return true;
        }
    }

    private static <T> T throwMissing(String key) {
        throw new OrchestrationException(ErrorCode.VALIDATION_FAILED, "Error occurred in Product Combination Validator");
    }
}

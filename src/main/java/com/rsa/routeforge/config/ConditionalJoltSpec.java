package com.rsa.routeforge.config;

import com.fasterxml.jackson.annotation.JsonCreator;

public record ConditionalJoltSpec(String condition, String joltSpec) {

    @JsonCreator
    public ConditionalJoltSpec {
    }
}
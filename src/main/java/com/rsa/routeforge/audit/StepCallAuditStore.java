package com.rsa.routeforge.audit;

public interface StepCallAuditStore {
    void insert(StepCallAuditRecord record) throws Exception;
}
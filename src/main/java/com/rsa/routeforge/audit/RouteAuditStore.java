package com.rsa.routeforge.audit;

public interface RouteAuditStore {
    void insert(RouteAuditRecord record) throws Exception;
}

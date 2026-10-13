package com.rsa.routeforge.audit;


import com.datastax.oss.driver.api.core.CqlSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "engine.audit.storage-type", havingValue = "cassandra")
public class CassandraAuditConfig {

    @Bean(destroyMethod = "close")
    public CqlSession cqlSession(
            @Value("${spring.cassandra.contact-points}") String contactPoints,
            @Value("${spring.cassandra.local-datacenter}") String localDatacenter,
            @Value("${spring.cassandra.keyspace-name}") String keyspace) {

        String[] hostPort = contactPoints.split(":");
        return CqlSession.builder()
                .addContactPoint(new java.net.InetSocketAddress(hostPort[0], Integer.parseInt(hostPort[1])))
                .withLocalDatacenter(localDatacenter)
                .withKeyspace(keyspace)
                .build();
    }
}
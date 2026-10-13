package com.rsa.routeforge.audit;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

@Component
@ConditionalOnProperty(name = "engine.audit.storage-type", havingValue = "file")
public class FileStepCallAuditStore implements StepCallAuditStore {

    private final Path baseDir;
    private final ObjectMapper objectMapper;

    public FileStepCallAuditStore(@Value("${engine.audit.payload-dir}") String payloadDir,
                                  ObjectMapper objectMapper) {
        this.baseDir = Path.of(payloadDir);
        this.objectMapper = objectMapper;
    }

    @Override
    public void insert(StepCallAuditRecord record) throws Exception {
        Path  patnerBaseDir;
        if(StringUtils.isNotBlank(record.getPartnerCode()))
            patnerBaseDir= baseDir.resolve(record.getPartnerCode());
        else
            patnerBaseDir=baseDir;
        Path dir = patnerBaseDir.resolve(LocalDate.now().toString()).resolve("step-call");
        Files.createDirectories(dir);
        Path file = dir.resolve(record.getStepAuditId() + ".json");
        Files.writeString(file, objectMapper.writeValueAsString(record), StandardCharsets.UTF_8);
    }
}


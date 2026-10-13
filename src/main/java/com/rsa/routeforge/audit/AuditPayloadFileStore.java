package com.rsa.routeforge.audit;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Writes audit payload bodies to disk instead of the database, organized by date so the
 * directory tree stays navigable and a retention job can delete whole day-folders at once.
 * <p>
 * Layout: {baseDir}/{category}/{yyyy}/{MM}/{dd}/{id}-{suffix}.json
 * e.g. /var/rsa-audit-payloads/route/2026/09/15/3f2a...-request.json
 */
@Component
public class AuditPayloadFileStore {

    private final  Path baseDir;

    public AuditPayloadFileStore(@Value("${engine.audit.payload-dir}") String payloadDir) {
        this.baseDir = Path.of(payloadDir);
    }

    /**
     * @return the file path written, or null if content was null (nothing to store).
     * @throws IOException on any filesystem failure — caller decides how to degrade.
     */
    public String store(String partnerCode,String category, String id, String suffix, String content) throws IOException {
        if (content == null) {
            return null;
        }
        LocalDate today = LocalDate.now();
        Path  patnerBaseDir;
        if(StringUtils.isNotBlank(partnerCode))
            patnerBaseDir= baseDir.resolve(partnerCode);
        else
            patnerBaseDir=baseDir;

        String dateFolder=LocalDate.now().toString();
        Path dir = patnerBaseDir.resolve(dateFolder)
                .resolve(category);
        Files.createDirectories(dir);

        Path file = dir.resolve(id + "-" + suffix + ".json");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toString();
    }
}
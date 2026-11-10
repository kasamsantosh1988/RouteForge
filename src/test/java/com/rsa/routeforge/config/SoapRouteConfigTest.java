package com.rsa.routeforge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsa.routeforge.route.RouteForgeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ByteArrayResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Route files with "channel": "soap": parsing, validation and grouping by path. */
class SoapRouteConfigTest {

    private static final String MIBL_PROPOSAL = """
            {
              "routeId": "mibl-proposal",
              "path": "/ws/mibl/MiblWebSystem",
              "partnerCode": "MIBL",
              "channel": "soap",
              "soap": {
                "operation": "web:MIBLProposalInfo",
                "namespaces": { "web": "http://WebService.vam.com/" },
                "fields": { "data": "miblProposalData", "userName": "soapHeader/userName", "password": "soapHeader/password" },
                "auth": { "type": "bodyCredentials", "user": "userName", "password": "password" },
                "response": { "element": "web:MIBLProposalInfoResponse", "returnElement": "return", "returnQualified": false },
                "publicUrl": "https://pie.example/ws/mibl/MiblWebSystem"
              }
            }
            """;

    private static final String MIBL_PAYMENT = MIBL_PROPOSAL
            .replace("mibl-proposal", "mibl-payment")
            .replace("MIBLProposalInfo", "MIBLPaymentInfo")
            .replace("miblProposalData", "miblPaymentData");

    private static final String BIMA_REST = """
            { "routeId": "bimasugam-quote", "path": "/bimasugam/motor/quote", "partnerCode": "BIMA", "httpMethod": "POST" }
            """;

    @TempDir
    Path dir;

    private final ObjectMapper mapper = new ObjectMapper();
    private ApplicationContext context;
    private RouteConfigValidator validator;
    private RouteForgeProperties routeForge;

    @BeforeEach
    void setUp() {
        context = mock(ApplicationContext.class);
        when(context.getResource(anyString())).thenReturn(new ByteArrayResource("<wsdl/>".getBytes()));
        validator = new RouteConfigValidator(mock(JoltTransformer.class), context);
        routeForge = new RouteForgeProperties();
        routeForge.getSoap().setEnabled(true);
        routeForge.getRoutes().setLocation("file:" + dir.toAbsolutePath() + "/*.json");
    }

    // ------------------------------------------------------------------ parsing

    @Test
    void existingRestFileParsesWithoutChannel() throws Exception {
        TransactionConfig cfg = mapper.readValue(BIMA_REST, TransactionConfig.class);
        assertNull(cfg.channel());
        assertNull(cfg.soap());
        assertFalse(cfg.isSoap());
        assertTrue(validator.validate(cfg, "bima.json").isEmpty());
    }

    @Test
    void soapFileParses() throws Exception {
        TransactionConfig cfg = mapper.readValue(MIBL_PROPOSAL, TransactionConfig.class);
        assertTrue(cfg.isSoap());
        assertEquals("web:MIBLProposalInfo", cfg.soap().operation());
        assertEquals("soapHeader/userName", cfg.soap().fields().get("userName"));
        assertEquals("bodyCredentials", cfg.soap().authType());
        assertFalse(cfg.soap().response().qualifiedReturn());
        assertTrue(validator.validate(cfg, "mibl.json").isEmpty(), () -> validator.validate(cfg, "mibl.json").toString());
    }

    // ------------------------------------------------------------------ validation

    @Test
    void undeclaredPrefixesAreReported() throws Exception {
        List<String> errors = validate(MIBL_PROPOSAL
                .replace("\"web:MIBLProposalInfo\"", "\"x:MIBLProposalInfo\"")
                .replace("\"soapHeader/userName\"", "\"y:soapHeader/userName\""));
        assertTrue(errors.stream().anyMatch(e -> e.contains("soap.operation") && e.contains("'x'")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("soap.fields.userName") && e.contains("'y'")), errors.toString());
    }

    @Test
    void bodyCredentialsMustNameConfiguredFields() throws Exception {
        List<String> errors = validate(MIBL_PROPOSAL.replace("\"password\": \"password\"", "\"password\": \"pwd\""));
        assertTrue(errors.stream().anyMatch(e -> e.contains("soap.auth.password")), errors.toString());
    }

    @Test
    void soapRouteRestrictions() throws Exception {
        String json = MIBL_PROPOSAL.replace("\"channel\": \"soap\",",
                "\"channel\": \"soap\", \"httpMethod\": \"GET\", \"encryptionProfile\": \"p\",");
        List<String> errors = validate(json);
        assertTrue(errors.stream().anyMatch(e -> e.contains("POST only")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("encryptionProfile")), errors.toString());
    }

    @Test
    void responseIsRequiredAndChecked() throws Exception {
        List<String> missing = validate(MIBL_PROPOSAL.replaceAll("\"response\": \\{[^}]*},", ""));
        assertTrue(missing.stream().anyMatch(e -> e.contains("soap.response is required")), missing.toString());

        List<String> bad = validate(MIBL_PROPOSAL.replace("\"returnQualified\": false",
                "\"returnQualified\": false, \"returnEncoding\": \"base64\", \"prefixStyle\": \"odd\""));
        assertTrue(bad.stream().anyMatch(e -> e.contains("returnEncoding")), bad.toString());
        assertTrue(bad.stream().anyMatch(e -> e.contains("prefixStyle")), bad.toString());
    }

    @Test
    void channelAndBlockMustAgree() throws Exception {
        assertTrue(validate(MIBL_PROPOSAL.replace("\"channel\": \"soap\",", "\"channel\": \"ftp\","))
                .stream().anyMatch(e -> e.contains("unknown channel")));
        assertTrue(validate(MIBL_PROPOSAL.replace("\"channel\": \"soap\",", ""))
                .stream().anyMatch(e -> e.contains("only valid with")));
    }

    @Test
    void missingWsdlIsReported() throws Exception {
        when(context.getResource(anyString())).thenReturn(new ByteArrayResource(new byte[0]) {
            @Override
            public boolean exists() {
                return false;
            }
        });
        List<String> errors = validate(MIBL_PROPOSAL.replace("\"publicUrl\"", "\"wsdl\": \"classpath:none.wsdl\", \"publicUrl\""));
        assertTrue(errors.stream().anyMatch(e -> e.contains("soap.wsdl") && e.contains("not found")), errors.toString());
    }

    // ------------------------------------------------------------------ loading

    @Test
    void soapOperationsShareAPathAndRestRoutesAreUnaffected() throws Exception {
        write("bima.json", BIMA_REST);
        write("mibl-proposal.json", MIBL_PROPOSAL);
        write("mibl-payment.json", MIBL_PAYMENT);

        List<TransactionConfig> configs = loader().loadConfigs();

        assertEquals(3, configs.size());
        assertEquals(2, configs.stream().filter(TransactionConfig::isSoap).count());
    }

    @Test
    void sameOperationTwiceOnOnePathIsRejected() throws Exception {
        write("a.json", MIBL_PROPOSAL);
        write("b.json", MIBL_PROPOSAL.replace("\"routeId\": \"mibl-proposal\"", "\"routeId\": \"mibl-proposal-2\""));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> loader().loadConfigs());
        assertTrue(e.getMessage().contains("already handled by route"), e.getMessage());
    }

    @Test
    void operationsOnOnePathMustShareThePartner() throws Exception {
        write("a.json", MIBL_PROPOSAL);
        write("b.json", MIBL_PAYMENT.replace("\"partnerCode\": \"MIBL\"", "\"partnerCode\": \"OTHER\""));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> loader().loadConfigs());
        assertTrue(e.getMessage().contains("must share partnerCode"), e.getMessage());
    }

    @Test
    void restAndSoapCannotShareAPath() throws Exception {
        write("a.json", MIBL_PROPOSAL);
        write("b.json", BIMA_REST.replace("/bimasugam/motor/quote", "/ws/mibl/MiblWebSystem"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> loader().loadConfigs());
        assertTrue(e.getMessage().contains("duplicate path"), e.getMessage());
    }

    @Test
    void twoRestRoutesOnOnePathAreStillRejected() throws Exception {
        write("a.json", BIMA_REST);
        write("b.json", BIMA_REST.replace("bimasugam-quote", "bimasugam-quote-2"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> loader().loadConfigs());
        assertTrue(e.getMessage().contains("duplicate path"), e.getMessage());
    }

    @Test
    void soapRoutesNeedSoapEnabled() throws Exception {
        routeForge.getSoap().setEnabled(false);
        write("bima.json", BIMA_REST);
        write("mibl.json", MIBL_PROPOSAL);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> loader().loadConfigs());
        assertTrue(e.getMessage().contains("routeforge.soap.enabled=false"), e.getMessage());
    }

    @Test
    void soapDisabledWithOnlyRestRoutesLoadsAsBefore() throws Exception {
        routeForge.getSoap().setEnabled(false);
        write("bima.json", BIMA_REST);

        assertEquals(1, loader().loadConfigs().size());
    }

    private List<String> validate(String json) throws Exception {
        return validator.validate(mapper.readValue(json, TransactionConfig.class), "test.json");
    }

    private RouteConfigLoader loader() {
        return new RouteConfigLoader(mapper, validator, new EngineProperties(), routeForge);
    }

    private void write(String name, String json) throws Exception {
        Files.writeString(dir.resolve(name), json);
    }
}

package org.casbin.casdoor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.casbin.casdoor.config.Config;
import org.casbin.casdoor.entity.Application;
import org.casbin.casdoor.entity.Cert;
import org.casbin.casdoor.entity.Organization;
import org.casbin.casdoor.entity.Provier;
import org.casbin.casdoor.service.ApplicationService;
import org.casbin.casdoor.service.OrganizationService;
import org.casbin.casdoor.service.ProviderService;
import org.casbin.casdoor.util.http.CasdoorResponse;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;

public abstract class BaseCasdoorTest {
    protected static Network network;
    protected static MySQLContainer mySQLContainer;
    protected static CasdoorContainer casdoorContainer;
    protected static GenericContainer<?> wiremockContainer;
    protected static Config adminConfig;
    protected static Config config;

    @BeforeAll
    public static void init() {
        if (config != null) return;
        network = Network.newNetwork();
        mySQLContainer = new MySQLContainer(DockerImageName.parse("mysql:8.4-oracle"))
                .withNetwork(network)
                .withNetworkAliases("db")
                .withDatabaseName("casdoor")
                .withUsername("casdoor")
                .withPassword("casdoor");
        // noinspection resource
        wiremockContainer = new GenericContainer<>(DockerImageName.parse("wiremock/wiremock:3.13.2-alpine"))
                .withNetwork(network)
                .withNetworkAliases("wiremock")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/__admin").forPort(8080))
                .withCopyToContainer(
                        Transferable.of("{\n" +
                                "      \"request\": {\n" +
                                "        \"method\": \"POST\",\n" +
                                "        \"url\": \"/sms-mock\"\n" +
                                "      },\n" +
                                "      \"response\": {\n" +
                                "        \"status\": 200,\n" +
                                "        \"headers\": {\n" +
                                "          \"Content-Type\": \"application/json\"\n" +
                                "        },\n" +
                                "        \"jsonBody\": {\n" +
                                "          \"code\": 200,\n" +
                                "          \"msg\": \"SMS sent successfully\",\n" +
                                "          \"data\": {\n" +
                                "            \"messageId\": \"test-message-id-123\"\n" +
                                "          }\n" +
                                "        }\n" +
                                "      }\n" +
                                "    }"),
                        "/home/wiremock/mappings/sms-mock.json"
                )
                .withCopyToContainer(
                        Transferable.of("{\n" +
                                "      \"request\": {\n" +
                                "        \"method\": \"POST\",\n" +
                                "        \"url\": \"/email-mock\"\n" +
                                "      },\n" +
                                "      \"response\": {\n" +
                                "        \"status\": 200,\n" +
                                "        \"headers\": {\n" +
                                "          \"Content-Type\": \"application/json\"\n" +
                                "        },\n" +
                                "        \"jsonBody\": {\n" +
                                "          \"code\": 200,\n" +
                                "          \"msg\": \"Email sent successfully\",\n" +
                                "          \"data\": {\n" +
                                "            \"messageId\": \"test-message-id-123\"\n" +
                                "          }\n" +
                                "        }\n" +
                                "      }\n" +
                                "    }"),
                        "/home/wiremock/mappings/email-mock.json"
                );
        casdoorContainer = new CasdoorContainer(DockerImageName.parse("casbin/casdoor:latest"))
                .withLogConsumer(f -> System.out.println(f.getUtf8StringWithoutLineEnding()))
                .dependsOn(mySQLContainer, wiremockContainer)
                .withNetwork(network)
                .withDriverName("mysql")
                .withDataSourceName("casdoor:casdoor@tcp(db:3306)/")
                .withRunMode("prod")
                .withTmpFs(Collections.singletonMap("/files", "rw,mode=1777"));
        casdoorContainer.start();
        adminConfig = casdoorContainer.adminCasdoorConfig();
        Application testSuiteApplication = getTestSuiteApplication();
        try {
            new OrganizationService(adminConfig)
                    .addOrganization(new Organization(
                            "admin", "test-org",
                            LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                            "Test Org", "http://localhost.local", "plain",
                            "AtLeast6 Aa123 SpecialChar NoRepeat".split(" "),
                            "US ES FR DE GB CN JP KR VN ID SG IN".split(" "),
                            new String[]{}, "en zh es fr de id ja ko ru vi pt".split(" "), 2000, false, false
                    ));
            new ApplicationService(adminConfig)
                    .addApplication(testSuiteApplication);
            config = new Config();
            config.setEndpoint(casdoorContainer.endpoint());
            config.setClientId(testSuiteApplication.clientId);
            config.setClientSecret(testSuiteApplication.clientSecret);
            config.setCertificate(adminConfig.getCertificate());
            config.setApplicationName(testSuiteApplication.name);
            config.setOrganizationName(testSuiteApplication.organization);

            testSuiteApplication.providers = testSuiteApplication.providers != null ? testSuiteApplication.providers : new ArrayList<>();
            ProviderService providerService = new ProviderService(config);

            Provier testSuiteSmsProvier = getTestSuiteSmsProvider(config);
            providerService.addProvider(testSuiteSmsProvier);
            testSuiteApplication.providers.add(testSuiteSmsProvier);

            Provier testSuiteEmailProvier = getTestSuiteEmailProvider(config);
            providerService.addProvider(testSuiteEmailProvier);
            testSuiteApplication.providers.add(testSuiteEmailProvier);

            Provier testSuiteStorageProvier = getTestSuiteStorageProvider(config);
            providerService.addProvider(testSuiteStorageProvier);
            testSuiteApplication.providers.add(testSuiteStorageProvier);

            new ApplicationService(config).updateApplication(testSuiteApplication);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static Provier getTestSuiteSmsProvider(Config config) {
        return providerTemplate(config, "SMS");
    }

    static Provier getTestSuiteEmailProvider(Config config) {
        return providerTemplate(config, "Email");
    }

    static Provier getTestSuiteStorageProvider(Config config) {
        Provier provider = providerTemplate(config, "Storage");
        provider.type = "Local File System";
        return provider;
    }

    static Provier providerTemplate(Config config, String category) {
        Provier provider = new Provier(config.getOrganizationName(), "test-suite-" + category.toLowerCase() + "-provider", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), "Test Suite " + category + " Provider", category, "Custom HTTP " + category);
        provider.endpoint = "http://wiremock:8080/" + category.toLowerCase() + "-mock";
        provider.method = "POST";
        provider.title = "some title";
        provider.templateCode = null;
        provider.httpHeaders = Collections.emptyMap();
        provider.userMapping = Collections.emptyMap();
        provider.issuerUrl = null;
        return provider;
    }

    static Application getTestSuiteApplication() {
        Application application = new Application();
        application.owner = "admin";
        application.name = "app-test-suite";
        application.createdTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        application.displayName = "Test Suite Application";
        application.organization = "test-org";
        application.cert = "cert-built-in";
        application.enablePassword = true;
        application.enableSignUp = true;
        application.enableSigninSession = true;
        application.enableAutoSignin = true;
        application.enableCodeSignin = true;
        application.enableSamlCompress = true;
        application.enableWebAuthn = true;
        application.enableLinkWithEmail = true;
        application.clientId = "clientId";
        application.clientSecret = "clientSecret";
        application.redirectUris = Collections.singletonList("http://localhost/redirect");
        application.tokenFormat = "JWT";
        application.expireInHours = 24 * 7;
        application.refreshExpireInHours = 24 * 7;
        application.formOffset = 0;
        application.signinItems = Collections.emptyList();
        application.signupItems = Collections.emptyList();
        return application;
    }

    protected static class CasdoorContainer extends GenericContainer<CasdoorContainer> {
        static final DockerImageName[] SUPPORTED_IMAGES = new DockerImageName[]{
                DockerImageName.parse("casbin/casdoor"),
                DockerImageName.parse("casbin/casdoor-all-in-one"),
        };
        static final int CASDOOR_PORT = 8000;

        public CasdoorContainer(DockerImageName dockerImageName) {
            super(dockerImageName);
            dockerImageName.assertCompatibleWith(SUPPORTED_IMAGES);
            // noinspection resource
            withExposedPorts(CASDOOR_PORT);
        }

        public CasdoorContainer withDriverName(String driverName) {
            return withEnv("driveName", driverName);
        }

        public CasdoorContainer withDataSourceName(String dataSourceName) {
            return withEnv("dataSourceName", dataSourceName);
        }

        public CasdoorContainer withRunMode(String runMode) {
            return withEnv("runmode", runMode);
        }

        @SuppressWarnings("HttpUrlsUsage")
        public String endpoint() {
            return String.format("http://%s:%d", getHost(), getMappedPort(CASDOOR_PORT));
        }

        public Config adminCasdoorConfig() {
            ObjectMapper objectMapper = new ObjectMapper();
            OkHttpClient client = new OkHttpClient();

            Request createAdminApp = new Request.Builder()
                    .url(endpoint() + "/api/add-application?username=built-in/admin&password=123")
                    .post(RequestBody.create(getAdminApp(objectMapper), MediaType.parse("application/json")))
                    .build();
            try {
                client.newCall(createAdminApp).execute().close();
            } catch (IOException e) {
                throw new RuntimeException("failed to create admin app", e);
            }

            Request request = new Request.Builder()
                    .url(endpoint() + "/api/get-cert?id=built-in/cert-built-in&username=built-in/admin&password=123")
                    .build();

            String cert;
            try (Response response = client.newCall(request).execute()) {
                String responseBody = response.body().string();
                cert = objectMapper.readValue(responseBody, new TypeReference<CasdoorResponse<Cert, Object>>() {
                }).getData().certificate;
            } catch (IOException e) {
                throw new RuntimeException("failed to fetch cert", e);
            }

            return new Config(endpoint(), "admin", "123", cert, "built-in", "admin");
        }

        private String getAdminApp(ObjectMapper objectMapper) {
            Application application = new Application();
            application.owner = "admin";
            application.name = "admin";
            application.clientId = "admin";
            application.clientSecret = "123";
            try {
                return objectMapper.writeValueAsString(application);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
    }
}

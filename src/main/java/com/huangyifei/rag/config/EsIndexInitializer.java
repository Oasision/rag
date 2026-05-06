package com.huangyifei.rag.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.PutMappingRequest;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.ConnectionClosedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Order(2)
@Component
@ConditionalOnProperty(name = "elasticsearch.init.enabled", havingValue = "true", matchIfMissing = true)
public class EsIndexInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(EsIndexInitializer.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private ElasticsearchClient esClient;

    @Value("classpath:es-mappings/knowledge_base.json")
    private Resource mappingResource;

    @Value("${elasticsearch.host}")
    private String host;

    @Value("${elasticsearch.port}")
    private int port;

    @Value("${elasticsearch.scheme:https}")
    private String scheme;

    @Value("${elasticsearch.username:elastic}")
    private String username;

    @Override
    public void run(String... args) throws Exception {
        try {
            logger.info("Initializing Elasticsearch index 'knowledge_base' at {}://{}:{}, username={}",
                    scheme, host, port, maskUsername(username));
            initializeIndex();
        } catch (Exception exception) {
            if (exception instanceof ConnectionClosedException
                    || (exception.getCause() != null && exception.getCause() instanceof ConnectionClosedException)) {
                logger.error("Elasticsearch connection closed, retrying in 5 seconds");
                try {
                    Thread.sleep(5000);
                    initializeIndex();
                } catch (Exception retryException) {
                    String diagnostic = buildDiagnosticMessage(retryException);
                    logger.error("Retrying Elasticsearch index initialization failed. {}", diagnostic, retryException);
                    throw new RuntimeException("Failed to initialize Elasticsearch index after retry. " + diagnostic, retryException);
                }
            } else {
                String diagnostic = buildDiagnosticMessage(exception);
                logger.error("Failed to initialize Elasticsearch index. {}", diagnostic, exception);
                throw new RuntimeException("Failed to initialize Elasticsearch index. " + diagnostic, exception);
            }
        }
    }

    private void initializeIndex() throws Exception {
        BooleanResponse existsResponse = esClient.indices().exists(ExistsRequest.of(e -> e.index("knowledge_base")));
        if (!existsResponse.value()) {
            createIndex();
        } else {
            updateMapping();
            logger.info("Index 'knowledge_base' already exists");
        }
    }

    private void createIndex() throws Exception {
        String mappingJson = loadMappingJson();
        CreateIndexRequest createIndexRequest = CreateIndexRequest.of(c -> c
                .index("knowledge_base")
                .withJson(new StringReader(mappingJson))
        );
        esClient.indices().create(createIndexRequest);
        logger.info("Index 'knowledge_base' created");
    }

    private void updateMapping() throws Exception {
        String mappingJson = loadPutMappingJson();
        PutMappingRequest request = PutMappingRequest.of(p -> p
                .index("knowledge_base")
                .withJson(new StringReader(mappingJson))
        );
        esClient.indices().putMapping(request);
        logger.info("Index 'knowledge_base' mapping updated");
    }

    private String loadMappingJson() throws Exception {
        try (var inputStream = mappingResource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String loadPutMappingJson() throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(loadMappingJson());
        JsonNode mappings = root.path("mappings");
        return mappings.isMissingNode() || mappings.isNull() ? loadMappingJson() : mappings.toString();
    }

    private String buildDiagnosticMessage(Exception exception) {
        Throwable rootCause = getRootCause(exception);
        String rootMessage = safeMessage(rootCause);
        String normalizedMessage = rootMessage.toLowerCase(Locale.ROOT);
        String endpoint = scheme + "://" + host + ":" + port;
        List<String> hints = new ArrayList<>();

        hints.add("ES address=" + endpoint);

        if (isConnectionProblem(rootCause, normalizedMessage)) {
            hints.add("Check that Elasticsearch is running and reachable");
        }

        if (isSslMismatch(normalizedMessage)) {
            hints.add("Check whether ELASTICSEARCH_SCHEME matches the real HTTP/HTTPS protocol");
        }

        if (isAuthenticationProblem(normalizedMessage)) {
            hints.add("Check ELASTICSEARCH_USERNAME and ELASTICSEARCH_PASSWORD");
        }

        if (normalizedMessage.contains("ik_max_word") || normalizedMessage.contains("ik_smart")) {
            hints.add("Install the analysis-ik plugin used by the index mapping");
        }

        if (normalizedMessage.contains("dense_vector") && normalizedMessage.contains("dims")) {
            hints.add("Check embedding dimensions against the knowledge_base mapping");
        }

        if (hints.size() == 1) {
            hints.add("Check protocol, port, credentials, and mapping compatibility");
        }

        return "Root cause type=" + rootCause.getClass().getSimpleName()
                + ", message=" + rootMessage
                + ". Hints: " + String.join("; ", hints);
    }

    private Throwable getRootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private boolean isConnectionProblem(Throwable rootCause, String normalizedMessage) {
        return rootCause instanceof ConnectException
                || normalizedMessage.contains("connection refused")
                || normalizedMessage.contains("connect timed out")
                || normalizedMessage.contains("connection timed out")
                || normalizedMessage.contains("failed to connect")
                || normalizedMessage.contains("no such host")
                || normalizedMessage.contains("unknownhost")
                || normalizedMessage.contains("no reachable node")
                || normalizedMessage.contains("connection reset");
    }

    private boolean isSslMismatch(String normalizedMessage) {
        return normalizedMessage.contains("pkix")
                || normalizedMessage.contains("ssl")
                || normalizedMessage.contains("tls")
                || normalizedMessage.contains("handshake")
                || normalizedMessage.contains("plaintext connection")
                || normalizedMessage.contains("unrecognized ssl message");
    }

    private boolean isAuthenticationProblem(String normalizedMessage) {
        return normalizedMessage.contains("security_exception")
                || normalizedMessage.contains("authentication")
                || normalizedMessage.contains("unauthorized")
                || normalizedMessage.contains("status line [http/1.1 401")
                || normalizedMessage.contains("status line [http/1.1 403");
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return (message == null || message.isBlank()) ? "<empty>" : message;
    }

    private String maskUsername(String rawUsername) {
        if (rawUsername == null || rawUsername.isBlank()) {
            return "<empty>";
        }
        if (rawUsername.length() <= 2) {
            return rawUsername.charAt(0) + "*";
        }
        return rawUsername.charAt(0) + "***" + rawUsername.charAt(rawUsername.length() - 1);
    }
}

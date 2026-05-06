package com.huangyifei.rag.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.huangyifei.rag.entity.EsDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ElasticsearchService {

    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchService.class);
    private static final String INDEX_NAME = "knowledge_base";

    private final ElasticsearchClient esClient;

    public ElasticsearchService(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    public void bulkIndex(List<EsDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }

        try {
            List<BulkOperation> operations = documents.stream()
                    .map(doc -> BulkOperation.of(op -> op.index(idx -> idx
                            .index(INDEX_NAME)
                            .id(doc.getId())
                            .document(doc))))
                    .toList();

            BulkResponse response = esClient.bulk(request -> request.operations(operations));
            if (response.errors()) {
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        logger.error("Elasticsearch bulk index failed: id={}, reason={}",
                                item.id(), item.error().reason());
                    }
                }
                throw new RuntimeException("Elasticsearch bulk index failed");
            }
            logger.info("Elasticsearch bulk index completed: count={}", documents.size());
        } catch (Exception e) {
            throw new RuntimeException("Elasticsearch bulk index failed", e);
        }
    }

    public void deleteByFileMd5(String fileMd5) {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index(INDEX_NAME)
                    .query(q -> q.term(t -> t.field("fileMd5").value(fileMd5))));
            esClient.deleteByQuery(request);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete Elasticsearch documents by fileMd5", e);
        }
    }

    public long countByFileMd5(String fileMd5) {
        try {
            CountResponse response = esClient.count(c -> c
                    .index(INDEX_NAME)
                    .query(q -> q.term(t -> t.field("fileMd5").value(fileMd5))));
            return response.count();
        } catch (Exception e) {
            throw new RuntimeException("Failed to count Elasticsearch documents by fileMd5", e);
        }
    }
}

package com.huangyifei.rag.consumer;

import com.huangyifei.rag.config.KafkaConfig;
import com.huangyifei.rag.model.FileProcessingTask;
import com.huangyifei.rag.service.DocumentService;
import com.huangyifei.rag.service.ParseService;
import com.huangyifei.rag.service.SpeechTranscriptionService;
import com.huangyifei.rag.service.VectorizationService;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.InsufficientDataException;
import io.minio.errors.InternalException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.ServerException;
import io.minio.errors.XmlParserException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

@Service
@Slf4j
public class FileProcessingConsumer {

    private final ParseService parseService;
    private final VectorizationService vectorizationService;
    private final DocumentService documentService;
    private final SpeechTranscriptionService speechTranscriptionService;

    @Autowired
    private KafkaConfig kafkaConfig;

    public FileProcessingConsumer(ParseService parseService,
                                  VectorizationService vectorizationService,
                                  DocumentService documentService,
                                  SpeechTranscriptionService speechTranscriptionService) {
        this.parseService = parseService;
        this.vectorizationService = vectorizationService;
        this.documentService = documentService;
        this.speechTranscriptionService = speechTranscriptionService;
    }

    @KafkaListener(topics = "#{kafkaConfig.getFileProcessingTopic()}", groupId = "#{kafkaConfig.getFileProcessingGroupId()}")
    public void processTask(FileProcessingTask task) {
        log.info("Received file processing task: {}", task);
        log.info("Task context: userId={}, orgTag={}, isPublic={}",
                task.getUserId(), task.getOrgTag(), task.isPublic());

        documentService.markVectorizationProcessing(task.getFileMd5(), false);

        if (FileProcessingTask.TASK_TYPE_REINDEX.equals(task.getTaskType())) {
            processReindexTask(task);
            return;
        }

        InputStream fileStream = null;
        try {
            if (speechTranscriptionService.isMediaFile(task.getFileName())) {
                parseService.parseMediaAndSave(task.getFileMd5(), task.getFileName(),
                        task.getUserId(), task.getOrgTag(), task.isPublic());

                VectorizationService.VectorizationUsageResult vectorizationResult = vectorizationService.vectorizeWithUsage(
                        task.getFileMd5(),
                        task.getUserId(),
                        task.getOrgTag(),
                        task.isPublic(),
                        task.getUserId()
                );
                documentService.markVectorizationCompleted(task.getFileMd5(), vectorizationResult);
                log.info("Media vectorization completed, fileMd5={}", task.getFileMd5());
                return;
            }

            fileStream = downloadFileFromStorage(task.getFilePath());
            if (fileStream == null) {
                throw new IOException("File stream is null");
            }

            if (!fileStream.markSupported()) {
                fileStream = new BufferedInputStream(fileStream);
            }

            parseService.parseAndSave(task.getFileMd5(), fileStream, task.getFileName(),
                    task.getUserId(), task.getOrgTag(), task.isPublic());
            log.info("Document parsed, fileMd5={}", task.getFileMd5());

            VectorizationService.VectorizationUsageResult vectorizationResult = vectorizationService.vectorizeWithUsage(
                    task.getFileMd5(),
                    task.getUserId(),
                    task.getOrgTag(),
                    task.isPublic(),
                    task.getUserId()
            );
            documentService.markVectorizationCompleted(task.getFileMd5(), vectorizationResult);
            log.info("Document vectorization completed, fileMd5={}", task.getFileMd5());
        } catch (Exception e) {
            documentService.markVectorizationFailed(task.getFileMd5(), e);
            log.error("Error processing task: {}", task, e);
        } finally {
            if (fileStream != null) {
                try {
                    fileStream.close();
                } catch (IOException e) {
                    log.error("Error closing file stream", e);
                }
            }
        }
    }

    private void processReindexTask(FileProcessingTask task) {
        try {
            String requesterId = task.getRequesterId() == null || task.getRequesterId().isBlank()
                    ? task.getUserId()
                    : task.getRequesterId();
            documentService.reindexDocument(task.getFileMd5(), requesterId);
        } catch (Exception e) {
            documentService.markVectorizationFailed(task.getFileMd5(), e);
            log.error("Error reindexing task: {}", task, e);
        }
    }

    private InputStream downloadFileFromStorage(String filePath) throws ServerException, InsufficientDataException,
            ErrorResponseException, IOException, NoSuchAlgorithmException, InvalidKeyException,
            InvalidResponseException, XmlParserException, InternalException {
        log.info("Downloading file from storage: {}", filePath);

        File file = new File(filePath);
        if (file.exists()) {
            log.info("Detected file system path: {}", filePath);
            return new FileInputStream(file);
        }

        if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
            log.info("Detected remote URL: {}", filePath);
            URL url = new URL(filePath);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(30000);
            connection.setRequestProperty("User-Agent", "RAG-FileProcessor/1.0");

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                log.info("Successfully connected to URL, starting download...");
                return connection.getInputStream();
            }
            if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                log.error("Access forbidden - possible expired presigned URL");
                throw new IOException("Access forbidden - the presigned URL may have expired");
            }
            if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new FileNotFoundException("File not found in object storage, HTTP response code: 404");
            }
            log.error("Failed to download file, HTTP response code: {} for URL: {}", responseCode, filePath);
            throw new IOException(String.format("Failed to download file, HTTP response code: %d", responseCode));
        }

        throw new IllegalArgumentException("Unsupported file path format: " + filePath);
    }
}

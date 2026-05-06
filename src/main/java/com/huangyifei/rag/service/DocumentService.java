package com.huangyifei.rag.service;

import com.huangyifei.rag.config.KafkaConfig;
import com.huangyifei.rag.model.FileProcessingTask;
import com.huangyifei.rag.model.FileUpload;
import com.huangyifei.rag.repository.DocumentVectorRepository;
import com.huangyifei.rag.repository.FileUploadRepository;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
public class DocumentService {

    private static final String BUCKET = "uploads";
    private static final String MERGED_PREFIX = "merged/";

    public record PdfSinglePagePreview(byte[] content, boolean cacheHit) {
    }

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private VectorizationService vectorizationService;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private KafkaConfig kafkaConfig;

    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(String fileMd5, String userId) {
        List<FileUpload> files = fileUploadRepository.findAllByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId);
        if (files.isEmpty()) {
            files = fileUploadRepository.findAllByFileMd5(fileMd5);
        }
        fileUploadRepository.deleteAll(files);
        documentVectorRepository.deleteByFileMd5(fileMd5);
        try {
            elasticsearchService.deleteByFileMd5(fileMd5);
        } catch (Exception ignored) {
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(BUCKET)
                    .object(MERGED_PREFIX + fileMd5)
                    .build());
        } catch (Exception ignored) {
        }
    }

    public VectorizationService.VectorizationUsageResult reindexDocument(String fileMd5, String requesterId) {
        FileUpload file = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5)
                .orElseThrow(() -> new RuntimeException("文件不存在"));
        markVectorizationProcessing(fileMd5, true);
        try {
            VectorizationService.VectorizationUsageResult result = vectorizationService.vectorizeWithUsage(
                    fileMd5,
                    file.getUserId(),
                    file.getOrgTag(),
                    file.isPublic(),
                    requesterId
            );
            markVectorizationCompleted(fileMd5, result);
            return result;
        } catch (RuntimeException e) {
            markVectorizationFailed(fileMd5, e);
            throw e;
        }
    }

    public FileUpload enqueueAsyncVectorizationRetry(String fileMd5, String requesterId) {
        FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5)
                .orElseThrow(() -> new RuntimeException("文件不存在"));
        markVectorizationProcessing(fileMd5, true);

        FileProcessingTask task = new FileProcessingTask(
                fileMd5,
                generateDownloadUrl(fileMd5),
                fileUpload.getFileName(),
                fileUpload.getUserId(),
                fileUpload.getOrgTag(),
                fileUpload.isPublic(),
                FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS,
                requesterId
        );
        kafkaTemplate.send(kafkaConfig.getFileProcessingTopic(), task);
        return fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5).orElse(fileUpload);
    }

    public void markVectorizationProcessing(String fileMd5, boolean resetActualUsage) {
        updateByMd5(fileMd5, file -> {
            file.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_PROCESSING);
            file.setVectorizationErrorMessage(null);
            if (resetActualUsage) {
                file.setActualEmbeddingTokens(null);
                file.setActualChunkCount(null);
            }
        });
    }

    public void markVectorizationCompleted(String fileMd5, VectorizationService.VectorizationUsageResult result) {
        updateByMd5(fileMd5, file -> {
            file.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_COMPLETED);
            file.setVectorizationErrorMessage(null);
            file.setActualEmbeddingTokens((long) result.actualEmbeddingTokens());
            file.setActualChunkCount(result.actualChunkCount());
        });
    }

    public void markVectorizationFailed(String fileMd5, String errorMessage) {
        updateByMd5(fileMd5, file -> {
            file.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_FAILED);
            file.setVectorizationErrorMessage(truncate(errorMessage, 1000));
        });
    }

    public void markVectorizationFailed(String fileMd5, Throwable error) {
        markVectorizationFailed(fileMd5, error == null ? "未知错误" : error.getMessage());
    }

    public List<FileUpload> getAccessibleFiles(String userId, String orgTags) {
        List<String> orgTagList = orgTags == null || orgTags.isBlank()
                ? List.of()
                : Arrays.stream(orgTags.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
        if (orgTagList.isEmpty()) {
            return fileUploadRepository.findByUserIdOrIsPublicTrue(userId);
        }
        return fileUploadRepository.findAccessibleFiles(userId, orgTagList);
    }

    public List<FileUpload> getUserUploadedFiles(String userId) {
        return fileUploadRepository.findByUserId(userId);
    }

    public String generateDownloadUrl(String fileMd5) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(BUCKET)
                    .object(MERGED_PREFIX + fileMd5)
                    .method(Method.GET)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("生成下载链接失败: " + e.getMessage(), e);
        }
    }

    public String getFilePreviewContent(String fileMd5, String fileName) {
        try (InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(BUCKET)
                .object(MERGED_PREFIX + fileMd5)
                .build())) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public PdfSinglePagePreview getPdfSinglePagePreview(String fileMd5, int pageNumber) {
        try (InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(BUCKET)
                .object(MERGED_PREFIX + fileMd5)
                .build());
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            inputStream.transferTo(outputStream);
            return new PdfSinglePagePreview(outputStream.toByteArray(), false);
        } catch (Exception e) {
            throw new RuntimeException("生成 PDF 预览失败: " + e.getMessage(), e);
        }
    }

    private void updateByMd5(String fileMd5, java.util.function.Consumer<FileUpload> updater) {
        List<FileUpload> files = fileUploadRepository.findAllByFileMd5(fileMd5);
        for (FileUpload file : files) {
            updater.accept(file);
            file.setMergedAt(LocalDateTime.now());
        }
        fileUploadRepository.saveAll(files);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

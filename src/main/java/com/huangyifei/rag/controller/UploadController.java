package com.huangyifei.rag.controller;

import com.huangyifei.rag.config.KafkaConfig;
import com.huangyifei.rag.exception.CustomException;
import com.huangyifei.rag.model.FileProcessingTask;
import com.huangyifei.rag.model.FileUpload;
import com.huangyifei.rag.model.OrganizationTag;
import com.huangyifei.rag.repository.FileUploadRepository;
import com.huangyifei.rag.service.FileTypeValidationService;
import com.huangyifei.rag.service.UploadService;
import com.huangyifei.rag.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/upload")
public class UploadController {

    private static final long DEFAULT_CHUNK_SIZE_BYTES = 5L * 1024 * 1024L;

    @Autowired
    private UploadService uploadService;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private KafkaConfig kafkaConfig;

    @Autowired
    private UserService userService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private FileTypeValidationService fileTypeValidationService;

    @PostMapping("/chunk")
    public ResponseEntity<Map<String, Object>> uploadChunk(
            @RequestParam("fileMd5") String fileMd5,
            @RequestParam("chunkIndex") int chunkIndex,
            @RequestParam("totalSize") long totalSize,
            @RequestParam("fileName") String fileName,
            @RequestParam(value = "totalChunks", required = false) Integer totalChunks,
            @RequestParam(value = "orgTag", required = false) String orgTag,
            @RequestParam(value = "isPublic", required = false, defaultValue = "false") boolean isPublic,
            @RequestParam("file") MultipartFile file,
            @RequestAttribute("userId") String userId) throws IOException {

        FileTypeValidationService.FileTypeValidationResult validationResult =
                fileTypeValidationService.validateFileType(fileName);
        if (!validationResult.isValid()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("code", HttpStatus.BAD_REQUEST.value());
            errorResponse.put("message", validationResult.getMessage());
            errorResponse.put("fileType", validationResult.getFileType());
            errorResponse.put("supportedTypes", fileTypeValidationService.getSupportedFileTypes());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }

        if (orgTag == null || orgTag.isBlank()) {
            orgTag = userService.getUserPrimaryOrg(userId);
        }

        if (!userService.isAdminUser(userId)) {
            OrganizationTag uploadOrg = userService.getOrganizationTag(orgTag);
            Long uploadMaxSizeBytes = uploadOrg.getUploadMaxSizeBytes();
            long estimatedUploadedBytes = (long) chunkIndex * DEFAULT_CHUNK_SIZE_BYTES + file.getSize();
            boolean exceedsLimit = uploadMaxSizeBytes != null
                    && uploadMaxSizeBytes > 0
                    && (totalSize > uploadMaxSizeBytes || estimatedUploadedBytes > uploadMaxSizeBytes);
            if (exceedsLimit) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("code", HttpStatus.PAYLOAD_TOO_LARGE.value());
                errorResponse.put("message", "文件大小超过组织上传上限: " + formatSize(uploadMaxSizeBytes));
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(errorResponse);
            }
        }

        uploadService.uploadChunk(fileMd5, chunkIndex, totalSize, fileName, file, orgTag, isPublic, userId);
        List<Integer> uploadedChunks = uploadService.getUploadedChunks(fileMd5, userId);

        Map<String, Object> data = new HashMap<>();
        data.put("fileMd5", fileMd5);
        data.put("chunkIndex", chunkIndex);
        data.put("uploadedChunks", uploadedChunks);
        data.put("uploaded", uploadedChunks);
        if (totalChunks != null && totalChunks > 0) {
            data.put("progress", calculateProgress(uploadedChunks, totalChunks));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "分片上传成功");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getUploadStatus(
            @RequestParam("file_md5") String fileMd5,
            @RequestAttribute("userId") String userId) {
        List<Integer> uploadedChunks = uploadService.getUploadedChunks(fileMd5, userId);
        Map<String, Object> data = new HashMap<>();
        data.put("fileMd5", fileMd5);
        data.put("uploadedChunks", uploadedChunks);
        data.put("uploaded", uploadedChunks);
        fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId)
                .ifPresent(upload -> {
                    int totalChunks = (int) Math.ceil((double) upload.getTotalSize() / DEFAULT_CHUNK_SIZE_BYTES);
                    data.put("totalChunks", totalChunks);
                    data.put("progress", calculateProgress(uploadedChunks, totalChunks));
                });

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "获取上传状态成功");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/merge")
    public ResponseEntity<Map<String, Object>> mergeFile(
            @RequestBody MergeRequest request,
            @RequestAttribute("userId") String userId) {
        try {
            String objectUrl = uploadService.mergeChunks(request.fileMd5(), request.fileName(), userId);

            FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(request.fileMd5(), userId)
                    .orElseThrow(() -> new CustomException("文件上传记录不存在", HttpStatus.NOT_FOUND));

            FileProcessingTask task = new FileProcessingTask(
                    request.fileMd5(),
                    objectUrl,
                    request.fileName(),
                    fileUpload.getUserId(),
                    fileUpload.getOrgTag(),
                    fileUpload.isPublic(),
                    FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS,
                    userId
            );

            fileUpload.setStatus(FileUpload.STATUS_COMPLETED);
            fileUpload.setMergedAt(LocalDateTime.now());
            fileUpload.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_PROCESSING);
            fileUpload.setVectorizationErrorMessage(null);
            fileUpload.setEstimatedEmbeddingTokens(null);
            fileUpload.setEstimatedChunkCount(null);
            fileUpload.setActualEmbeddingTokens(null);
            fileUpload.setActualChunkCount(null);
            fileUploadRepository.save(fileUpload);

            try {
                kafkaTemplate.send(kafkaConfig.getFileProcessingTopic(), task)
                        .whenComplete((result, exception) -> {
                            if (exception == null) {
                                return;
                            }
                            fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(request.fileMd5(), userId)
                                    .ifPresent(upload -> {
                                        upload.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_FAILED);
                                        upload.setVectorizationErrorMessage(truncate("向量化任务提交失败: " + exception.getMessage(), 1000));
                                        fileUploadRepository.save(upload);
                                    });
                        });
            } catch (Exception e) {
                fileUpload.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_FAILED);
                fileUpload.setVectorizationErrorMessage(truncate("向量化任务提交失败: " + e.getMessage(), 1000));
                fileUploadRepository.save(fileUpload);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("object_url", objectUrl);
            data.put("objectUrl", objectUrl);
            data.put("fileMd5", request.fileMd5());
            data.put("fileName", request.fileName());
            data.put("estimatedEmbeddingTokens", fileUpload.getEstimatedEmbeddingTokens());
            data.put("estimatedChunkCount", fileUpload.getEstimatedChunkCount());
            data.put("vectorizationStatus", fileUpload.getVectorizationStatus());
            data.put("vectorizationErrorMessage", fileUpload.getVectorizationErrorMessage());

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", FileUpload.VECTORIZATION_STATUS_FAILED.equals(fileUpload.getVectorizationStatus())
                    ? "文件合并成功，但向量化任务提交失败，请稍后重试"
                    : "文件合并成功，已提交向量化任务");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            Map<String, Object> response = new HashMap<>();
            response.put("code", e.getStatus().value());
            response.put("message", e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "文件合并失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/supported-types")
    public ResponseEntity<Map<String, Object>> getSupportedFileTypes() {
        Set<String> supportedTypes = fileTypeValidationService.getSupportedFileTypes();
        Set<String> supportedExtensions = fileTypeValidationService.getSupportedExtensions();

        Map<String, Object> data = new HashMap<>();
        data.put("supportedTypes", supportedTypes);
        data.put("supportedExtensions", supportedExtensions);
        data.put("description", "支持常见文档、图片、音频和视频文件上传");

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "获取支持文件类型成功");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    private double calculateProgress(List<Integer> uploadedChunks, int totalChunks) {
        if (totalChunks == 0) {
            return 0.0;
        }
        return (double) uploadedChunks.size() / totalChunks * 100;
    }

    private String formatSize(long sizeInBytes) {
        double sizeInMb = sizeInBytes / (1024d * 1024d);
        if (sizeInMb >= 1024d) {
            return String.format("%.2f GB", sizeInMb / 1024d);
        }
        if (sizeInMb >= 1d) {
            return String.format("%.2f MB", sizeInMb);
        }
        return String.format("%.2f KB", sizeInBytes / 1024d);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record MergeRequest(String fileMd5, String fileName) {
    }
}

package com.huangyifei.rag.controller;

import com.huangyifei.rag.model.FileUpload;
import com.huangyifei.rag.model.OrganizationTag;
import com.huangyifei.rag.repository.FileUploadRepository;
import com.huangyifei.rag.repository.OrganizationTagRepository;
import com.huangyifei.rag.service.ChatHandler;
import com.huangyifei.rag.service.DocumentService;
import com.huangyifei.rag.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private OrganizationTagRepository organizationTagRepository;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ChatHandler chatHandler;

    @DeleteMapping("/{fileMd5}")
    public ResponseEntity<?> deleteDocument(
            @PathVariable String fileMd5,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("role") String role) {
        Optional<FileUpload> fileOpt = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5);
        if (fileOpt.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "文件不存在");
        }
        FileUpload file = fileOpt.get();
        if (!file.getUserId().equals(userId) && !"ADMIN".equals(role)) {
            return error(HttpStatus.FORBIDDEN, "无权删除该文件");
        }

        documentService.deleteDocument(fileMd5, userId);
        return ok("文件删除成功", null);
    }

    @PostMapping("/{fileMd5}/reindex")
    public ResponseEntity<?> reindexDocument(
            @PathVariable String fileMd5,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("role") String role) {
        Optional<FileUpload> fileOpt = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5);
        if (fileOpt.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "文件不存在");
        }
        FileUpload file = fileOpt.get();
        if (!file.getUserId().equals(userId) && !"ADMIN".equals(role)) {
            return error(HttpStatus.FORBIDDEN, "无权重建该文件索引");
        }

        var result = documentService.reindexDocument(fileMd5, userId);
        Map<String, Object> data = new HashMap<>();
        data.put("fileMd5", fileMd5);
        data.put("fileName", file.getFileName());
        data.put("actualEmbeddingTokens", result.actualEmbeddingTokens());
        data.put("actualChunkCount", result.actualChunkCount());
        data.put("modelVersion", result.modelVersion());
        return ok("文件索引重建成功", data);
    }

    @PostMapping("/{fileMd5}/vectorization/retry")
    public ResponseEntity<?> retryVectorizationAsync(
            @PathVariable String fileMd5,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("role") String role) {
        Optional<FileUpload> fileOpt = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5);
        if (fileOpt.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "文件不存在");
        }
        FileUpload file = fileOpt.get();
        if (!file.getUserId().equals(userId) && !"ADMIN".equals(role)) {
            return error(HttpStatus.FORBIDDEN, "无权重试该文件向量化");
        }

        FileUpload queuedFile = documentService.enqueueAsyncVectorizationRetry(fileMd5, userId);
        Map<String, Object> data = new HashMap<>();
        data.put("fileMd5", queuedFile.getFileMd5());
        data.put("fileName", queuedFile.getFileName());
        data.put("vectorizationStatus", queuedFile.getVectorizationStatus());
        return ok("已提交向量化重试任务", data);
    }

    @GetMapping("/accessible")
    public ResponseEntity<?> getAccessibleFiles(
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags) {
        return ok("获取可访问文件成功", convertFilesToResponse(documentService.getAccessibleFiles(userId, orgTags)));
    }

    @GetMapping("/uploads")
    public ResponseEntity<?> getUserUploadedFiles(@RequestAttribute("userId") String userId) {
        return ok("获取上传文件成功", convertFilesToResponse(documentService.getUserUploadedFiles(userId)));
    }

    @GetMapping("/download")
    public ResponseEntity<?> downloadFileByName(
            @RequestParam String fileName,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "token", required = false) String token) {
        RequestAuthContext authContext = resolveRequestAuthContext(authorization, token);
        Optional<FileUpload> fileOpt = findAccessibleFileByName(fileName, authContext);
        if (fileOpt.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "文件不存在或无权访问");
        }
        return downloadResponse(fileOpt.get());
    }

    @GetMapping("/preview")
    public ResponseEntity<?> previewFileByName(
            @RequestParam String fileName,
            @RequestParam(required = false) Integer pageNumber,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "token", required = false) String token) {
        RequestAuthContext authContext = resolveRequestAuthContext(authorization, token);
        Optional<FileUpload> fileOpt = findAccessibleFileByName(fileName, authContext);
        if (fileOpt.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "文件不存在或无权访问");
        }
        Map<String, Object> payload = buildPreviewResponse(fileOpt.get(), pageNumber, pageNumber != null);
        if (payload == null) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "生成预览失败");
        }
        return ok("获取预览成功", payload);
    }

    @GetMapping("/page-preview")
    public ResponseEntity<?> previewPdfPage(
            @RequestParam String fileMd5,
            @RequestParam Integer pageNumber,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "token", required = false) String token) {
        RequestAuthContext authContext = resolveRequestAuthContext(authorization, token);
        Optional<FileUpload> fileOpt = findAccessibleFileByMd5(fileMd5, authContext);
        if (fileOpt.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "文件不存在或无权访问");
        }

        DocumentService.PdfSinglePagePreview preview = documentService.getPdfSinglePagePreview(fileMd5, pageNumber);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=1800")
                .body(preview.content());
    }

    @GetMapping("/download-by-md5")
    public ResponseEntity<?> downloadFileByMd5(
            @RequestParam String fileMd5,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "token", required = false) String token) {
        RequestAuthContext authContext = resolveRequestAuthContext(authorization, token);
        Optional<FileUpload> fileOpt = findAccessibleFileByMd5(fileMd5, authContext);
        if (fileOpt.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "文件不存在或无权访问");
        }
        return downloadResponse(fileOpt.get());
    }

    @GetMapping("/reference-detail")
    public ResponseEntity<?> getReferenceDetail(
            @RequestParam String sessionId,
            @RequestParam Integer referenceNumber,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        ChatHandler.ReferenceInfo detail = chatHandler.getReferenceDetail(sessionId, referenceNumber);
        if (detail == null) {
            return error(HttpStatus.NOT_FOUND, "引用详情不存在");
        }

        RequestAuthContext authContext = resolveRequestAuthContext(authorization, null);
        if (authContext.userId() != null) {
            boolean hasAccess = documentService.getAccessibleFiles(authContext.userId(), authContext.orgTags()).stream()
                    .anyMatch(file -> file.getFileMd5().equals(detail.fileMd5()));
            if (!hasAccess) {
                return error(HttpStatus.FORBIDDEN, "无权访问该引用文件");
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("fileMd5", detail.fileMd5());
        data.put("fileName", detail.fileName());
        data.put("referenceNumber", referenceNumber);
        data.put("pageNumber", detail.pageNumber());
        data.put("anchorText", detail.anchorText());
        data.put("retrievalMode", detail.retrievalMode());
        data.put("retrievalLabel", detail.retrievalLabel());
        data.put("retrievalQuery", detail.retrievalQuery());
        data.put("matchedChunkText", detail.matchedChunkText());
        data.put("evidenceSnippet", detail.evidenceSnippet());
        data.put("score", detail.score());
        data.put("chunkId", detail.chunkId());
        return ok("获取引用详情成功", data);
    }

    private ResponseEntity<?> downloadResponse(FileUpload file) {
        String downloadUrl = documentService.generateDownloadUrl(file.getFileMd5());
        if (downloadUrl == null) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "生成下载链接失败");
        }
        return ok("获取下载链接成功", Map.of(
                "fileName", file.getFileName(),
                "downloadUrl", downloadUrl,
                "fileSize", file.getTotalSize(),
                "fileMd5", file.getFileMd5()
        ));
    }

    private Optional<FileUpload> findAccessibleFileByName(String fileName, RequestAuthContext authContext) {
        if (authContext.userId() != null) {
            return documentService.getAccessibleFiles(authContext.userId(), authContext.orgTags()).stream()
                    .filter(file -> file.getFileName().equals(fileName))
                    .findFirst();
        }
        return fileUploadRepository.findFirstByFileNameAndIsPublicTrueOrderByCreatedAtDesc(fileName);
    }

    private Optional<FileUpload> findAccessibleFileByMd5(String fileMd5, RequestAuthContext authContext) {
        if (authContext.userId() != null) {
            return documentService.getAccessibleFiles(authContext.userId(), authContext.orgTags()).stream()
                    .filter(file -> file.getFileMd5().equals(fileMd5))
                    .findFirst();
        }
        return fileUploadRepository.findFirstByFileMd5AndIsPublicTrueOrderByCreatedAtDesc(fileMd5);
    }

    private List<Map<String, Object>> convertFilesToResponse(List<FileUpload> files) {
        return files.stream().map(file -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", file.getId());
            item.put("fileMd5", file.getFileMd5());
            item.put("fileName", file.getFileName());
            item.put("totalSize", file.getTotalSize());
            item.put("status", file.getStatus());
            item.put("userId", file.getUserId());
            item.put("orgTag", file.getOrgTag());
            item.put("orgTagName", getOrgTagName(file.getOrgTag()));
            item.put("isPublic", file.isPublic());
            item.put("estimatedEmbeddingTokens", file.getEstimatedEmbeddingTokens());
            item.put("estimatedChunkCount", file.getEstimatedChunkCount());
            item.put("actualEmbeddingTokens", file.getActualEmbeddingTokens());
            item.put("actualChunkCount", file.getActualChunkCount());
            item.put("vectorizationStatus", file.getVectorizationStatus());
            item.put("vectorizationErrorMessage", file.getVectorizationErrorMessage());
            item.put("createdAt", file.getCreatedAt());
            item.put("mergedAt", file.getMergedAt());
            return item;
        }).toList();
    }

    private Map<String, Object> buildPreviewResponse(FileUpload file, Integer pageNumber, boolean preferSinglePagePreview) {
        String fileName = file.getFileName();
        String extension = getFileExtension(fileName);
        String previewType = getPreviewType(extension);

        Map<String, Object> payload = new HashMap<>();
        payload.put("fileName", fileName);
        payload.put("fileMd5", file.getFileMd5());
        payload.put("fileSize", file.getTotalSize());
        payload.put("previewType", previewType);

        if ("text".equals(previewType)) {
            String previewContent = documentService.getFilePreviewContent(file.getFileMd5(), fileName);
            if (previewContent == null) {
                return null;
            }
            payload.put("content", previewContent);
            return payload;
        }

        String previewUrl = documentService.generateDownloadUrl(file.getFileMd5());
        if (previewUrl == null) {
            return null;
        }

        if (preferSinglePagePreview && "pdf".equals(previewType) && pageNumber != null && pageNumber > 0) {
            payload.put("previewUrl", buildSinglePagePreviewUrl(file.getFileMd5(), pageNumber));
            payload.put("sourceUrl", previewUrl);
            payload.put("singlePageMode", true);
            payload.put("sourcePageNumber", pageNumber);
            return payload;
        }

        payload.put("previewUrl", previewUrl);
        return payload;
    }

    private String buildSinglePagePreviewUrl(String fileMd5, Integer pageNumber) {
        return "/api/v1/documents/page-preview?fileMd5="
                + URLEncoder.encode(fileMd5, StandardCharsets.UTF_8)
                + "&pageNumber="
                + pageNumber;
    }

    private String getPreviewType(String extension) {
        if (extension == null || extension.isEmpty()) {
            return "download";
        }

        String lowerCaseExtension = extension.toLowerCase();
        if ("pdf".equals(lowerCaseExtension)) {
            return "pdf";
        }

        if (List.of("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg").contains(lowerCaseExtension)) {
            return "image";
        }

        if (List.of("txt", "md", "json", "xml", "csv", "html", "htm", "css", "js", "java", "py", "sql", "yaml", "yml").contains(lowerCaseExtension)) {
            return "text";
        }

        return "download";
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) {
            return "";
        }

        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }

        return fileName.substring(dotIndex + 1);
    }

    private RequestAuthContext resolveRequestAuthContext(String authorization, String fallbackToken) {
        String jwtToken = extractBearerToken(authorization);
        if ((jwtToken == null || jwtToken.isBlank()) && fallbackToken != null && !fallbackToken.isBlank()) {
            jwtToken = fallbackToken.trim();
        }

        if (jwtToken == null || jwtToken.isBlank()) {
            return new RequestAuthContext(null, null);
        }

        return new RequestAuthContext(
                jwtUtils.extractUserIdFromToken(jwtToken),
                jwtUtils.extractOrgTagsFromToken(jwtToken)
        );
    }

    private String extractBearerToken(String authorization) {
        if (authorization == null) {
            return null;
        }

        String trimmed = authorization.trim();
        if (trimmed.startsWith("Bearer ")) {
            return trimmed.substring(7);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String getOrgTagName(String tagId) {
        if (tagId == null || tagId.isEmpty()) {
            return null;
        }

        return organizationTagRepository.findByTagId(tagId)
                .map(OrganizationTag::getName)
                .orElse(tagId);
    }

    private ResponseEntity<?> ok(String message, Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", message);
        if (data != null) {
            response.put("data", data);
        }
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<?> error(HttpStatus status, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", status.value());
        response.put("message", message);
        return ResponseEntity.status(status).body(response);
    }

    private record RequestAuthContext(String userId, String orgTags) {
    }
}

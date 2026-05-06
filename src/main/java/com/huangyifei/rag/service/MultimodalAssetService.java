package com.huangyifei.rag.service;

import com.huangyifei.rag.model.FileUpload;
import com.huangyifei.rag.repository.FileUploadRepository;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Base64;
import java.util.Locale;

@Service
public class MultimodalAssetService {

    private final FileUploadRepository fileUploadRepository;
    private final UploadService uploadService;
    private final OcrService ocrService;

    public MultimodalAssetService(FileUploadRepository fileUploadRepository,
                                  UploadService uploadService,
                                  OcrService ocrService) {
        this.fileUploadRepository = fileUploadRepository;
        this.uploadService = uploadService;
        this.ocrService = ocrService;
    }

    public boolean isImageFile(String fileMd5) {
        return resolveFile(fileMd5)
                .map(FileUpload::getFileName)
                .map(ocrService::isImageFile)
                .orElse(false);
    }

    public ImageAsset loadImageAsset(String fileMd5) {
        FileUpload fileUpload = resolveFile(fileMd5)
                .orElseThrow(() -> new IllegalArgumentException("Image asset not found: " + fileMd5));

        if (!ocrService.isImageFile(fileUpload.getFileName())) {
            throw new IllegalArgumentException("File is not an image asset: " + fileUpload.getFileName());
        }

        try (InputStream inputStream = uploadService.getMergedFileStream(fileMd5)) {
            byte[] bytes = inputStream.readAllBytes();
            String mimeType = detectMimeType(fileUpload.getFileName());
            String dataUrl = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
            return new ImageAsset(fileMd5, fileUpload.getFileName(), mimeType, bytes, dataUrl);
        } catch (Exception exception) {
            throw new RuntimeException("Failed to load image asset: " + fileMd5, exception);
        }
    }

    private java.util.Optional<FileUpload> resolveFile(String fileMd5) {
        return fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5);
    }

    private String detectMimeType(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "image/png";
        }

        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            case "tif", "tiff" -> "image/tiff";
            default -> "image/png";
        };
    }

    public record ImageAsset(
            String fileMd5,
            String fileName,
            String mimeType,
            byte[] bytes,
            String dataUrl
    ) {
    }
}

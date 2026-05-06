package com.huangyifei.rag.service;

import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class FileTypeValidationService {

    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "rtf", "md", "odt", "ods", "odp", "html", "htm",
            "xml", "json", "csv", "epub", "pages", "numbers", "keynote"
    );

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "bmp", "gif", "tif", "tiff", "webp"
    );

    private static final Set<String> MEDIA_EXTENSIONS = Set.of(
            "mp3", "wav", "flac", "aac", "ogg", "wma", "m4a",
            "mp4", "avi", "mov", "wmv", "mkv", "webm", "m4v"
    );

    private static final Map<String, String> TYPE_NAMES = Map.ofEntries(
            Map.entry("pdf", "PDF 文件"),
            Map.entry("doc", "Word 文件"),
            Map.entry("docx", "Word 文件"),
            Map.entry("xls", "Excel 表格"),
            Map.entry("xlsx", "Excel 表格"),
            Map.entry("ppt", "PowerPoint 演示文稿"),
            Map.entry("pptx", "PowerPoint 演示文稿"),
            Map.entry("txt", "文本文件"),
            Map.entry("md", "Markdown 文件"),
            Map.entry("jpg", "JPEG 图片"),
            Map.entry("jpeg", "JPEG 图片"),
            Map.entry("png", "PNG 图片"),
            Map.entry("mp3", "MP3 音频"),
            Map.entry("mp4", "MP4 视频")
    );

    private final OcrService ocrService;

    public FileTypeValidationService(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    public FileTypeValidationResult validateFileType(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return new FileTypeValidationResult(false, "文件名不能为空", "unknown", null);
        }

        String extension = extractFileExtension(fileName);
        if (extension == null) {
            return new FileTypeValidationResult(false, "文件缺少扩展名", "unknown", null);
        }

        String fileType = getFileTypeDescription(extension);
        if (DOCUMENT_EXTENSIONS.contains(extension) || MEDIA_EXTENSIONS.contains(extension)) {
            return new FileTypeValidationResult(true, "文件类型支持", fileType, extension);
        }

        if (IMAGE_EXTENSIONS.contains(extension)) {
            if (ocrService.canProcessImageFiles()) {
                return new FileTypeValidationResult(true, "图片文件支持 OCR 处理", fileType, extension);
            }
            return new FileTypeValidationResult(false, "图片文件需要先配置 OCR Provider", fileType, extension);
        }

        return new FileTypeValidationResult(false, "暂不支持该文件类型: " + fileType, fileType, extension);
    }

    public Set<String> getSupportedFileTypes() {
        Set<String> supportedTypes = new HashSet<>();
        getSupportedExtensions().forEach(extension -> supportedTypes.add(getFileTypeDescription(extension)));
        return supportedTypes;
    }

    public Set<String> getSupportedExtensions() {
        Set<String> supportedExtensions = new HashSet<>(DOCUMENT_EXTENSIONS);
        if (ocrService.canProcessImageFiles()) {
            supportedExtensions.addAll(IMAGE_EXTENSIONS);
        }
        supportedExtensions.addAll(MEDIA_EXTENSIONS);
        return supportedExtensions;
    }

    private String extractFileExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index < 0 || index == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String getFileTypeDescription(String extension) {
        return TYPE_NAMES.getOrDefault(extension.toLowerCase(Locale.ROOT), extension.toUpperCase(Locale.ROOT) + " 文件");
    }

    public static class FileTypeValidationResult {
        private final boolean valid;
        private final String message;
        private final String fileType;
        private final String extension;

        public FileTypeValidationResult(boolean valid, String message, String fileType, String extension) {
            this.valid = valid;
            this.message = message;
            this.fileType = fileType;
            this.extension = extension;
        }

        public boolean isValid() {
            return valid;
        }

        public String getMessage() {
            return message;
        }

        public String getFileType() {
            return fileType;
        }

        public String getExtension() {
            return extension;
        }
    }
}

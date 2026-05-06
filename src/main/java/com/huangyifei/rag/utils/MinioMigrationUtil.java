package com.huangyifei.rag.utils;

import com.huangyifei.rag.model.FileUpload;
import com.huangyifei.rag.repository.FileUploadRepository;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MinioMigrationUtil {

    private static final Logger logger = LoggerFactory.getLogger(MinioMigrationUtil.class);
    private static final String BUCKET = "uploads";

    private final MinioClient minioClient;
    private final FileUploadRepository fileUploadRepository;

    public MinioMigrationUtil(MinioClient minioClient, FileUploadRepository fileUploadRepository) {
        this.minioClient = minioClient;
        this.fileUploadRepository = fileUploadRepository;
    }

    public MigrationReport migrateAllFiles() {
        MigrationReport report = new MigrationReport();
        for (FileUpload file : fileUploadRepository.findAll()) {
            try {
                migrateFile(file, report);
            } catch (Exception exception) {
                report.errorCount++;
                report.addError(file.getFileName(), exception.getMessage());
            }
        }
        logger.info("MinIO migration finished: {}", report);
        return report;
    }

    public void clearAllData() {
        logger.warn("clearAllData is disabled in this build to avoid accidental destructive operations");
    }

    private void migrateFile(FileUpload file, MigrationReport report) throws Exception {
        if (file == null || file.getFileMd5() == null || file.getFileName() == null) {
            report.skipCount++;
            return;
        }
        String targetPath = "merged/" + file.getFileMd5();
        if (objectExists(targetPath)) {
            report.skipCount++;
            return;
        }

        String oldPath = "merged/" + file.getFileName();
        if (!objectExists(oldPath)) {
            report.skipCount++;
            return;
        }

        minioClient.copyObject(CopyObjectArgs.builder()
                .bucket(BUCKET)
                .object(targetPath)
                .source(CopySource.builder().bucket(BUCKET).object(oldPath).build())
                .build());
        report.successCount++;
    }

    private boolean objectExists(String objectPath) {
        try {
            minioClient.statObject(StatObjectArgs.builder().bucket(BUCKET).object(objectPath).build());
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    public static class MigrationReport {
        public int successCount = 0;
        public int skipCount = 0;
        public int errorCount = 0;
        private final StringBuilder errors = new StringBuilder();

        public void addError(String fileName, String error) {
            errors.append(String.format("  - %s: %s%n", fileName, error));
        }

        public String getErrors() {
            return errors.length() > 0 ? errors.toString() : "无错误";
        }

        @Override
        public String toString() {
            return "MigrationReport{" +
                    "successCount=" + successCount +
                    ", skipCount=" + skipCount +
                    ", errorCount=" + errorCount +
                    '}';
        }
    }
}

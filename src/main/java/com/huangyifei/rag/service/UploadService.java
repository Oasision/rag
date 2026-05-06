package com.huangyifei.rag.service;

import com.huangyifei.rag.exception.CustomException;
import com.huangyifei.rag.model.ChunkInfo;
import com.huangyifei.rag.model.FileUpload;
import com.huangyifei.rag.repository.ChunkInfoRepository;
import com.huangyifei.rag.repository.FileUploadRepository;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
public class UploadService {

    private static final String BUCKET = "uploads";

    private final MinioClient minioClient;
    private final FileUploadRepository fileUploadRepository;
    private final ChunkInfoRepository chunkInfoRepository;

    public UploadService(
            MinioClient minioClient,
            FileUploadRepository fileUploadRepository,
            ChunkInfoRepository chunkInfoRepository
    ) {
        this.minioClient = minioClient;
        this.fileUploadRepository = fileUploadRepository;
        this.chunkInfoRepository = chunkInfoRepository;
    }

    @Transactional
    public void uploadChunk(
            String fileMd5,
            int chunkIndex,
            long totalSize,
            String fileName,
            MultipartFile file,
            String orgTag,
            boolean isPublic,
            String userId
    ) throws IOException {
        if (chunkIndex < 0) {
            throw new CustomException("分片序号不能小于 0", HttpStatus.BAD_REQUEST);
        }

        FileUpload fileUpload = getOrCreateFileUpload(fileMd5, totalSize, fileName, orgTag, isPublic, userId);
        if (fileUpload.getStatus() == FileUpload.STATUS_MERGING) {
            throw new CustomException("文件正在合并中，请稍后再试", HttpStatus.CONFLICT);
        }
        if (fileUpload.getStatus() == FileUpload.STATUS_COMPLETED) {
            throw new CustomException("文件已经上传完成", HttpStatus.CONFLICT);
        }

        String storagePath = chunkObjectName(fileMd5, chunkIndex);
        String chunkMd5 = DigestUtils.md5Hex(file.getInputStream());
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(BUCKET)
                    .object(storagePath)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
        } catch (Exception e) {
            throw new IOException("分片上传到 MinIO 失败: " + e.getMessage(), e);
        }

        if (!chunkInfoRepository.existsByFileMd5AndChunkIndex(fileMd5, chunkIndex)) {
            ChunkInfo chunkInfo = new ChunkInfo();
            chunkInfo.setFileMd5(fileMd5);
            chunkInfo.setChunkIndex(chunkIndex);
            chunkInfo.setChunkMd5(chunkMd5);
            chunkInfo.setStoragePath(storagePath);
            chunkInfoRepository.save(chunkInfo);
        }
    }

    public List<Integer> getUploadedChunks(String fileMd5, String userId) {
        return chunkInfoRepository.findByFileMd5OrderByChunkIndexAsc(fileMd5).stream()
                .map(ChunkInfo::getChunkIndex)
                .toList();
    }

    @Transactional
    public String mergeChunks(String fileMd5, String fileName, String userId) {
        FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId)
                .orElseThrow(() -> new CustomException("文件上传记录不存在", HttpStatus.NOT_FOUND));

        List<ChunkInfo> chunks = chunkInfoRepository.findByFileMd5OrderByChunkIndexAsc(fileMd5);
        if (chunks.isEmpty()) {
            throw new CustomException("没有找到已上传的分片", HttpStatus.BAD_REQUEST);
        }

        fileUpload.setStatus(FileUpload.STATUS_MERGING);
        fileUploadRepository.save(fileUpload);

        String mergedObjectName = mergedObjectName(fileMd5);
        try {
            List<ComposeSource> sources = chunks.stream()
                    .map(chunk -> ComposeSource.builder()
                            .bucket(BUCKET)
                            .object(chunk.getStoragePath())
                            .build())
                    .toList();
            minioClient.composeObject(ComposeObjectArgs.builder()
                    .bucket(BUCKET)
                    .object(mergedObjectName)
                    .sources(sources)
                    .build());

            fileUpload.setStatus(FileUpload.STATUS_COMPLETED);
            fileUpload.setMergedAt(LocalDateTime.now());
            fileUploadRepository.save(fileUpload);
            return generateMergedObjectUrl(fileMd5);
        } catch (Exception e) {
            fileUpload.setStatus(FileUpload.STATUS_UPLOADING);
            fileUploadRepository.save(fileUpload);
            throw new RuntimeException("文件合并失败: " + e.getMessage(), e);
        }
    }

    public String generateMergedObjectUrl(String fileMd5) throws Exception {
        return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(BUCKET)
                .object(mergedObjectName(fileMd5))
                .expiry(7, TimeUnit.DAYS)
                .build());
    }

    public InputStream getMergedFileStream(String fileMd5) throws Exception {
        return minioClient.getObject(GetObjectArgs.builder()
                .bucket(BUCKET)
                .object(mergedObjectName(fileMd5))
                .build());
    }

    private FileUpload getOrCreateFileUpload(
            String fileMd5,
            long totalSize,
            String fileName,
            String orgTag,
            boolean isPublic,
            String userId
    ) {
        return fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId)
                .orElseGet(() -> {
                    FileUpload upload = new FileUpload();
                    upload.setFileMd5(fileMd5);
                    upload.setFileName(fileName);
                    upload.setTotalSize(totalSize);
                    upload.setStatus(FileUpload.STATUS_UPLOADING);
                    upload.setUserId(userId);
                    upload.setOrgTag(orgTag);
                    upload.setPublic(isPublic);
                    upload.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_PENDING);
                    return fileUploadRepository.save(upload);
                });
    }

    private String chunkObjectName(String fileMd5, int chunkIndex) {
        return "chunks/" + fileMd5 + "/" + chunkIndex;
    }

    private String mergedObjectName(String fileMd5) {
        return "merged/" + fileMd5;
    }

    @SuppressWarnings("unused")
    private String getFileType(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "unknown";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}

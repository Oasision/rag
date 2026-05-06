package com.huangyifei.rag.service;

import com.huangyifei.rag.config.MinioConfig;
import com.huangyifei.rag.exception.CustomException;
import com.huangyifei.rag.model.FileUpload;
import com.huangyifei.rag.repository.ChunkInfoRepository;
import com.huangyifei.rag.repository.FileUploadRepository;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UploadServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private MinioClient minioClient;

    @Mock
    private FileUploadRepository fileUploadRepository;

    @Mock
    private ChunkInfoRepository chunkInfoRepository;

    @Mock
    private MinioConfig minioConfig;

    private UploadService uploadService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        uploadService = new UploadService();
        ReflectionTestUtils.setField(uploadService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(uploadService, "minioClient", minioClient);
        ReflectionTestUtils.setField(uploadService, "fileUploadRepository", fileUploadRepository);
        ReflectionTestUtils.setField(uploadService, "chunkInfoRepository", chunkInfoRepository);
        ReflectionTestUtils.setField(uploadService, "minioConfig", minioConfig);
    }

    @Test
    void uploadChunkRejectsWhenFileAlreadyCompleted() throws Exception {
        FileUpload fileUpload = new FileUpload();
        fileUpload.setFileMd5("md5");
        fileUpload.setUserId("1");
        fileUpload.setStatus(FileUpload.STATUS_COMPLETED);

        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(fileUpload));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        CustomException exception = assertThrows(
                CustomException.class,
                () -> uploadService.uploadChunk("md5", 0, 1024L, "test.pdf", file, "TEAM_A", false, "1")
        );

        assertEquals("鏂囦欢宸插畬鎴愬悎骞讹紝涓嶅厑璁哥户缁笂浼犲垎鐗?, exception.getMessage());
        verifyNoInteractions(chunkInfoRepository);
    }

    @Test
    void uploadChunkRejectsWhenFileIsMerging() throws Exception {
        FileUpload fileUpload = new FileUpload();
        fileUpload.setFileMd5("md5");
        fileUpload.setUserId("1");
        fileUpload.setStatus(FileUpload.STATUS_MERGING);

        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(fileUpload));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        CustomException exception = assertThrows(
                CustomException.class,
                () -> uploadService.uploadChunk("md5", 0, 1024L, "test.pdf", file, "TEAM_A", false, "1")
        );

        assertEquals("鏂囦欢姝ｅ湪鍚堝苟涓紝璇风◢鍚庨噸璇?, exception.getMessage());
        verifyNoInteractions(chunkInfoRepository);
    }
}

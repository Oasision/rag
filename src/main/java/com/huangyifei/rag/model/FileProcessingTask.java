package com.huangyifei.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FileProcessingTask {
    public static final String TASK_TYPE_UPLOAD_PROCESS = "UPLOAD_PROCESS";
    public static final String TASK_TYPE_REINDEX = "REINDEX";

    private String fileMd5;
    private String filePath;
    private String fileName;
    private String userId;
    private String orgTag;
    private boolean isPublic;
    private String taskType;
    private String requesterId;

    public FileProcessingTask(String fileMd5, String filePath, String fileName) {
        this(fileMd5, filePath, fileName, null, "DEFAULT", false, TASK_TYPE_UPLOAD_PROCESS, null);
    }

    public FileProcessingTask(String fileMd5, String filePath, String fileName, String orgTag, String taskType) {
        this(fileMd5, filePath, fileName, null, orgTag, false, taskType, null);
    }
}

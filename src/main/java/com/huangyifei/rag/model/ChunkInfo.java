package com.huangyifei.rag.model;

import jakarta.persistence.*;
import lombok.Data;





@Data
@Entity
@Table(
        name = "chunk_info",
        uniqueConstraints = @UniqueConstraint(name = "uk_file_md5_chunk_index", columnNames = {"file_md5", "chunk_index"})
)
public class ChunkInfo {
    

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    

    @Column(name = "file_md5", nullable = false, length = 32)
    private String fileMd5;

    


    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    

    @Column(name = "chunk_md5", nullable = false, length = 32)
    private String chunkMd5;

    


    @Column(name = "storage_path", nullable = false, length = 255)
    private String storagePath;
}

package com.huangyifei.rag.service;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;





@SpringBootTest
@ActiveProfiles("test")
public class UploadServicePerformanceTest {

    private static final Logger logger = LoggerFactory.getLogger(UploadServicePerformanceTest.class);

    













    @Test
    public void testPerformanceComparison() {
        logger.info("=== UploadService 鎬ц兘浼樺寲璇存槑 ===");
        logger.info("浼樺寲鍓嶏細閫愪釜鏌ヨRedis锛?000涓垎鐗囬渶瑕?000娆＄綉缁滃線杩?);
        logger.info("浼樺寲鍚庯細涓€娆℃€ц幏鍙朾itmap锛?000涓垎鐗囧彧闇€瑕?娆＄綉缁滃線杩?);
        logger.info("棰勬湡鎬ц兘鎻愬崌锛氱害100-1000鍊嶏紙鍙栧喅浜庡垎鐗囨暟閲忓拰缃戠粶寤惰繜锛?);
        logger.info("==========================================");
        
        // 妯℃嫙鎬ц兘瀵规瘮
        int totalChunks = 1000;
        int networkLatencyMs = 3; // 姣忔Redis鏌ヨ鐨勭綉缁滃欢杩?        
        // 浼樺寲鍓嶇殑鑰楁椂
        int oldMethodTime = totalChunks * networkLatencyMs;
        
        // 浼樺寲鍚庣殑鑰楁椂
        int newMethodTime = networkLatencyMs + 1; // 1娆＄綉缁滄煡璇?+ 1ms鏈湴澶勭悊
        
        logger.info("妯℃嫙 {} 涓垎鐗囩殑鎬ц兘瀵规瘮锛?, totalChunks);
        logger.info("浼樺寲鍓嶈€楁椂锛歿}ms", oldMethodTime);
        logger.info("浼樺寲鍚庤€楁椂锛歿}ms", newMethodTime);
        logger.info("鎬ц兘鎻愬崌锛歿}鍊?, oldMethodTime / newMethodTime);
    }
} 
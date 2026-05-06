package com.huangyifei.rag.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;




class ParseServiceUnitTest {

    private ParseService parseService;

    @BeforeEach
    void setUp() {
        parseService = new ParseService();
        
        ReflectionTestUtils.setField(parseService, "bufferSize", 8192);
        ReflectionTestUtils.setField(parseService, "maxMemoryThreshold", 0.8);
    }

    @Test
    void testSplitLongSentence_BasicFunctionality() throws Exception {
        
        String sentence = "杩欐槸涓€涓祴璇曞彞瀛愶紝鐢ㄦ潵楠岃瘉鍒嗚瘝鏁堟灉銆?;
        int chunkSize = 15;

        Method method = ParseService.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(parseService, sentence, chunkSize);

        assertNotNull(result);
        assertFalse(result.isEmpty());
        
        // 楠岃瘉鎷兼帴鍚庣瓑浜庡師鏂?        String reconstructed = String.join("", result);
        assertEquals(sentence, reconstructed);
        
        System.out.println("=== 鍩烘湰鍔熻兘娴嬭瘯 ===");
        System.out.println("鍘熸枃: " + sentence + " (闀垮害: " + sentence.length() + ")");
        System.out.println("鍒嗗潡鏁伴噺: " + result.size());
        for (int i = 0; i < result.size(); i++) {
            System.out.println("鍒嗗潡 " + i + ": " + result.get(i) + " (闀垮害: " + result.get(i).length() + ")");
        }
    }

    @Test
    void testSplitLongSentence_EdgeCases() throws Exception {
        Method method = ParseService.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);

        // 娴嬭瘯绌哄瓧绗︿覆
        @SuppressWarnings("unchecked")
        List<String> emptyResult = (List<String>) method.invoke(parseService, "", 100);
        assertTrue(emptyResult.isEmpty() || (emptyResult.size() == 1 && emptyResult.get(0).isEmpty()));

        // 娴嬭瘯鍗曚釜瀛楃
        @SuppressWarnings("unchecked")
        List<String> singleCharResult = (List<String>) method.invoke(parseService, "娴?, 10);
        assertEquals(1, singleCharResult.size());
        assertEquals("娴?, singleCharResult.get(0));

        // 娴嬭瘯寰堥暱鐨勬枃鏈?        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            longText.append("杩欐槸绗?).append(i).append("娈垫枃鏈€?);
        }
        
        @SuppressWarnings("unchecked")
        List<String> longResult = (List<String>) method.invoke(parseService, longText.toString(), 30);
        assertTrue(longResult.size() > 1);
        
        // 楠岃瘉鎷兼帴
        String reconstructed = String.join("", longResult);
        assertEquals(longText.toString(), reconstructed);

        System.out.println("=== 杈圭晫鎯呭喌娴嬭瘯 ===");
        System.out.println("闀挎枃鏈垎鍧楁暟閲? " + longResult.size());
    }

    @Test
    void testSplitLongSentence_ChunkSizeValidation() throws Exception {
        String sentence = "杩欐槸鐢ㄦ潵娴嬭瘯鍒嗗潡澶у皬闄愬埗鐨勫彞瀛愶紝鍖呭惈鏍囩偣绗﹀彿鍜屾暟瀛?23銆?;
        
        Method method = ParseService.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);

        
        
        for (int chunkSize : chunkSizes) {
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) method.invoke(parseService, sentence, chunkSize);
            
            
            for (int i = 0; i < result.size() - 1; i++) {
                assertTrue(result.get(i).length() <= chunkSize, 
                    "鍒嗗潡澶у皬 " + chunkSize + " 鏃讹紝鍒嗗潡 " + i + " 闀垮害瓒呴檺: " + result.get(i).length());
            }
            
            
            String reconstructed = String.join("", result);
            assertEquals(sentence, reconstructed, "鍒嗗潡澶у皬 " + chunkSize + " 鏃舵嫾鎺ョ粨鏋滀笉鍖归厤");
            
            System.out.println("鍒嗗潡澶у皬 " + chunkSize + " -> 鍒嗗潡鏁伴噺: " + result.size());
        }
    }

    @Test
    void testSplitLongSentence_Performance() throws Exception {
        
        StringBuilder largeText = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            largeText.append("杩欐槸涓€涓敤浜庢€ц兘娴嬭瘯鐨勯暱鍙ュ瓙锛屽寘鍚悇绉嶄腑鏂囧瓧绗﹀拰鏍囩偣绗﹀彿銆?);
        }
        
        String sentence = largeText.toString();
        int chunkSize = 100;
        
        Method method = ParseService.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);

        long startTime = System.currentTimeMillis();
        
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(parseService, sentence, chunkSize);
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        assertNotNull(result);
        assertTrue(result.size() > 1);
        
        // 楠岃瘉鎷兼帴缁撴灉
        String reconstructed = String.join("", result);
        assertEquals(sentence, reconstructed);

        System.out.println("=== 鎬ц兘娴嬭瘯 ===");
        System.out.println("鍘熸枃闀垮害: " + sentence.length());
        System.out.println("鍒嗗潡鏁伴噺: " + result.size());
        System.out.println("澶勭悊鏃堕棿: " + duration + "ms");
        
        // 鎬ц兘鏂█锛氬鐞嗘椂闂村簲璇ュ湪鍚堢悊鑼冨洿鍐?        assertTrue(duration < 5000, "澶勭悊鏃堕棿杩囬暱: " + duration + "ms");
    }
}
package com.huangyifei.rag.service;

import com.huangyifei.rag.repository.DocumentVectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;




@SpringBootTest
class ParseServiceTest {

    @Mock
    private DocumentVectorRepository documentVectorRepository;

    @InjectMocks
    private ParseService parseService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        ReflectionTestUtils.setField(parseService, "bufferSize", 8192);
        ReflectionTestUtils.setField(parseService, "maxMemoryThreshold", 0.8);
    }

    @Test
    void testSplitLongSentence_NormalChineseText() throws Exception {
        
        String sentence = "杩欐槸涓€涓祴璇曞彞瀛愶紝鐢ㄦ潵楠岃瘉HanLP鍒嗚瘝鍔熻兘鏄惁姝ｅ父宸ヤ綔銆傛垜浠渶瑕佺‘淇濆畠鑳藉姝ｇ‘鍦拌繘琛岃涔夊垏鍓诧紝鑰屼笉鏄畝鍗曠殑瀛楃鍒嗗壊銆?;
        int chunkSize = 30;

        // 浣跨敤鍙嶅皠璋冪敤绉佹湁鏂规硶
        Method method = ParseService.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(parseService, sentence, chunkSize);

        // 楠岃瘉缁撴灉
        assertNotNull(result, "鍒嗗壊缁撴灉涓嶅簲涓虹┖");
        assertFalse(result.isEmpty(), "鍒嗗壊缁撴灉涓嶅簲涓虹┖鍒楄〃");
        
        // 楠岃瘉姣忎釜鍒嗗潡鐨勯暱搴﹂兘涓嶈秴杩囬檺鍒讹紙闄や簡鏈€鍚庝竴涓彲鑳借緝鐭級
        for (int i = 0; i < result.size() - 1; i++) {
            assertTrue(result.get(i).length() <= chunkSize, 
                "鍒嗗潡 " + i + " 鐨勯暱搴﹁秴杩囦簡闄愬埗: " + result.get(i).length());
        }
        
        // 楠岃瘉鎵€鏈夊垎鍧楁嫾鎺ュ悗绛変簬鍘熸枃
        String reconstructed = String.join("", result);
        assertEquals(sentence, reconstructed, "鍒嗗壊鍚庨噸鏂版嫾鎺ュ簲璇ョ瓑浜庡師鏂?);
        
        
        System.out.println("鍘熸枃闀垮害: " + sentence.length());
        System.out.println("鍒嗗潡鏁伴噺: " + result.size());
        for (int i = 0; i < result.size(); i++) {
            System.out.println("鍒嗗潡 " + i + " (闀垮害:" + result.get(i).length() + "): " + result.get(i));
        }
    }

    @Test
    void testSplitLongSentence_ShortText() throws Exception {
        
        int chunkSize = 100;

        Method method = ParseService.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(parseService, sentence, chunkSize);

        
        assertEquals(sentence, result.get(0), "鐭枃鏈垎鍧楀唴瀹瑰簲璇ョ瓑浜庡師鏂?);
    }

    @Test
    void testSplitLongSentence_EmptyText() throws Exception {
        // 鍑嗗娴嬭瘯鏁版嵁 - 绌烘枃鏈?        String sentence = "";
        int chunkSize = 100;

        Method method = ParseService.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(parseService, sentence, chunkSize);

        // 楠岃瘉缁撴灉 - 绌烘枃鏈簲璇ヨ繑鍥炵┖鍒楄〃鎴栧寘鍚竴涓┖瀛楃涓?        assertTrue(result.isEmpty() || (result.size() == 1 && result.get(0).isEmpty()), 
            "绌烘枃鏈簲璇ヨ繑鍥炵┖鍒楄〃鎴栧寘鍚竴涓┖瀛楃涓?);
    }

    @Test
    void testSplitLongSentence_MixedLanguage() throws Exception {
        
        int chunkSize = 25;

        Method method = ParseService.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(parseService, sentence, chunkSize);

        
        assertNotNull(result);
        assertFalse(result.isEmpty());
        
        
        assertEquals(sentence, reconstructed, "娣峰悎璇█鏂囨湰鍒嗗壊鍚庨噸鏂版嫾鎺ュ簲璇ョ瓑浜庡師鏂?);
        
        System.out.println("娣峰悎璇█娴嬭瘯 - 鍘熸枃闀垮害: " + sentence.length());
        System.out.println("鍒嗗潡鏁伴噺: " + result.size());
        for (int i = 0; i < result.size(); i++) {
            System.out.println("鍒嗗潡 " + i + ": " + result.get(i));
        }
    }

    @Test
    void testSplitLongSentence_VerySmallChunkSize() throws Exception {
        // 鍑嗗娴嬭瘯鏁版嵁 - 闈炲父灏忕殑鍒嗗潡澶у皬
        String sentence = "娴嬭瘯鏋佸皬鍒嗗潡";
        int chunkSize = 3;

        Method method = ParseService.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(parseService, sentence, chunkSize);

        // 楠岃瘉缁撴灉
        assertNotNull(result);
        assertFalse(result.isEmpty());
        
        // 楠岃瘉鎷兼帴鍚庣瓑浜庡師鏂?        String reconstructed = String.join("", result);
        assertEquals(sentence, reconstructed, "鏋佸皬鍒嗗潡娴嬭瘯閲嶆柊鎷兼帴搴旇绛変簬鍘熸枃");
    }

    @Test
    void testSplitLongSentence_LongText() throws Exception {
        // 鍑嗗娴嬭瘯鏁版嵁 - 闀挎枃鏈?        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            longText.append("杩欐槸涓€涓緢闀跨殑娴嬭瘯鏂囨湰锛岀敤鏉ラ獙璇丠anLP鍒嗚瘝鍦ㄥ鐞嗛暱鏂囨湰鏃剁殑鎬ц兘鍜屽噯纭€с€?);
            longText.append("鎴戜滑甯屾湜瀹冭兘澶熸櫤鑳藉湴鏍规嵁璇箟杩涜鍒嗗壊锛岃€屼笉鏄畝鍗曞湴鎸夌収瀛楃鏁伴噺杩涜鍒囧垎銆?);
        }
        
        String sentence = longText.toString();
        int chunkSize = 50;

        Method method = ParseService.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(parseService, sentence, chunkSize);

        // 楠岃瘉缁撴灉
        assertNotNull(result);
        assertTrue(result.size() > 1, "闀挎枃鏈簲璇ヨ鍒嗗壊鎴愬涓潡");
        
        // 楠岃瘉鎷兼帴鍚庣瓑浜庡師鏂?        String reconstructed = String.join("", result);
        assertEquals(sentence, reconstructed, "闀挎枃鏈垎鍓插悗閲嶆柊鎷兼帴搴旇绛変簬鍘熸枃");
        
        System.out.println("闀挎枃鏈祴璇?- 鍘熸枃闀垮害: " + sentence.length());
        System.out.println("鍒嗗潡鏁伴噺: " + result.size());
    }
}
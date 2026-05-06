package com.huangyifei.rag.controller;

import com.huangyifei.rag.service.ParseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/parse")
public class ParseController {

    @Autowired
    private ParseService parseService;

    @PostMapping
    public ResponseEntity<String> parseDocument(@RequestParam("file") MultipartFile file,
                                                @RequestParam("file_md5") String fileMd5,
                                                @RequestAttribute(value = "userId", required = false) String userId) {
        try {
            parseService.parseAndSave(fileMd5, file.getInputStream());
            return ResponseEntity.ok("文件解析成功");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("文件解析失败: " + e.getMessage());
        }
    }
}

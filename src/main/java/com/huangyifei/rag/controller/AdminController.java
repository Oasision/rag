package com.huangyifei.rag.controller;

import com.huangyifei.rag.exception.CustomException;
import com.huangyifei.rag.model.OrganizationTag;
import com.huangyifei.rag.model.RechargePackage;
import com.huangyifei.rag.model.User;
import com.huangyifei.rag.repository.OrganizationTagRepository;
import com.huangyifei.rag.repository.RechargePackageRepository;
import com.huangyifei.rag.repository.UserRepository;
import com.huangyifei.rag.service.ConversationService;
import com.huangyifei.rag.service.InviteCodeService;
import com.huangyifei.rag.service.ModelProviderConfigService;
import com.huangyifei.rag.service.RateLimitConfigService;
import com.huangyifei.rag.service.RegistrationSettingsService;
import com.huangyifei.rag.service.UsageDashboardService;
import com.huangyifei.rag.service.UserService;
import com.huangyifei.rag.utils.JwtUtils;
import com.huangyifei.rag.utils.LogUtils;
import com.huangyifei.rag.utils.MinioMigrationUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserService userService;

    @Autowired
    private OrganizationTagRepository organizationTagRepository;

    @Autowired
    private MinioMigrationUtil migrationUtil;

    @Autowired
    private InviteCodeService inviteCodeService;

    @Autowired
    private UsageDashboardService usageDashboardService;

    @Autowired
    private RateLimitConfigService rateLimitConfigService;

    @Autowired
    private ModelProviderConfigService modelProviderConfigService;

    @Autowired
    private RechargePackageRepository rechargePackageRepository;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private RegistrationSettingsService registrationSettingsService;

    @GetMapping("/users")
    public ResponseEntity<?> getAllUsers(@RequestHeader("Authorization") String token) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        List<User> users = userRepository.findAll();
        users.forEach(user -> user.setPassword(null));
        return ok("Get all users successful", users);
    }

    @PostMapping("/knowledge/add")
    public ResponseEntity<?> addKnowledgeDocument(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile file,
            @RequestParam("description") String description) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        return ok("知识文档上传接口已接收", Map.of("filename", file.getOriginalFilename(), "description", description));
    }

    @DeleteMapping("/knowledge/{documentId}")
    public ResponseEntity<?> deleteKnowledgeDocument(
            @RequestHeader("Authorization") String token,
            @PathVariable("documentId") String documentId) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        return ok("知识文档删除接口已接收", Map.of("documentId", documentId));
    }

    @GetMapping("/system/status")
    public ResponseEntity<?> getSystemStatus(@RequestHeader("Authorization") String token) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        Map<String, Object> status = new HashMap<>();
        status.put("cpu_usage", "30%");
        status.put("memory_usage", "45%");
        status.put("disk_usage", "60%");
        status.put("active_users", 15);
        status.put("total_documents", 250);
        status.put("total_conversations", 1200);
        return ResponseEntity.ok(Map.of("data", status));
    }

    @GetMapping("/user-activities")
    public ResponseEntity<?> getUserActivities(
            @RequestHeader("Authorization") String token,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String start_date,
            @RequestParam(required = false) String end_date) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        List<Map<String, Object>> activities = List.of(
                Map.of("username", "user1", "action", "LOGIN", "timestamp", "2023-03-01T10:15:30", "ip_address", "192.168.1.100"),
                Map.of("username", "user2", "action", "UPLOAD_FILE", "timestamp", "2023-03-01T11:20:45", "ip_address", "192.168.1.101")
        );
        return ResponseEntity.ok(Map.of("data", activities));
    }

    @GetMapping("/usage/overview")
    public ResponseEntity<?> getUsageOverview(
            @RequestHeader("Authorization") String token,
            @RequestParam(defaultValue = "7") int days) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        return ok("获取用量概览成功", usageDashboardService.buildOverview(days));
    }

    @GetMapping("/rate-limits")
    public ResponseEntity<?> getRateLimits(@RequestHeader("Authorization") String token) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        return ok("获取限流配置成功", rateLimitConfigService.getCurrentSettings());
    }

    @PutMapping("/rate-limits")
    public ResponseEntity<?> updateRateLimits(
            @RequestHeader("Authorization") String token,
            @RequestBody RateLimitConfigService.UpdateRateLimitRequest request) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        return ok("更新限流配置成功", rateLimitConfigService.updateSettings(request, adminUsername));
    }

    @GetMapping("/model-providers")
    public ResponseEntity<?> getModelProviders(@RequestHeader("Authorization") String token) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        return ok("获取模型配置成功", modelProviderConfigService.getCurrentSettings());
    }

    @PutMapping("/model-providers/{scope}")
    public ResponseEntity<?> updateModelProviders(
            @RequestHeader("Authorization") String token,
            @PathVariable String scope,
            @RequestBody ModelProviderConfigService.UpdateScopeRequest request) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        return ok("更新模型配置成功", modelProviderConfigService.updateScope(scope, request, adminUsername));
    }

    @PostMapping("/model-providers/{scope}/test")
    public ResponseEntity<?> testModelProviderConnection(
            @RequestHeader("Authorization") String token,
            @PathVariable String scope,
            @RequestBody ModelProviderConfigService.ProviderConnectionTestRequest request) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        return ok("模型连接测试完成", modelProviderConfigService.testConnection(scope, request));
    }

    @PostMapping("/users/create-admin")
    public ResponseEntity<?> createAdminUser(
            @RequestHeader("Authorization") String token,
            @RequestBody AdminUserRequest request) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        userService.createAdminUser(request.username(), request.password(), adminUsername);
        return ok("管理员账号创建成功", null);
    }

    @PostMapping("/invite-codes")
    public ResponseEntity<?> createInviteCode(
            @RequestHeader("Authorization") String token,
            @RequestBody CreateInviteCodeRequest request) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        var created = inviteCodeService.createInviteCodes(adminUsername, request.code(), request.maxUses(), null, request.count());
        return ok("邀请码创建成功", created);
    }

    @GetMapping("/invite-codes")
    public ResponseEntity<?> listInviteCodes(
            @RequestHeader("Authorization") String token,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        return ok("获取邀请码成功", inviteCodeService.list(enabled, page, size));
    }

    @GetMapping("/registration-settings")
    public ResponseEntity<?> getRegistrationSettings(@RequestHeader("Authorization") String token) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        return ok("获取注册配置成功", registrationSettingsService.getSettings());
    }

    @PutMapping("/registration-settings")
    public ResponseEntity<?> updateRegistrationSettings(
            @RequestHeader("Authorization") String token,
            @RequestBody RegistrationSettingsRequest request) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        return ok("注册配置已更新",
                registrationSettingsService.updateInviteRequired(Boolean.TRUE.equals(request.inviteRequired()), adminUsername));
    }

    @PatchMapping("/invite-codes/{id}/disable")
    public ResponseEntity<?> disableInviteCode(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        inviteCodeService.disable(id, adminUsername);
        return ok("邀请码已停用", null);
    }

    @DeleteMapping("/invite-codes/{id}")
    public ResponseEntity<?> deleteInviteCode(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        inviteCodeService.delete(id, adminUsername);
        return ok("邀请码已删除", null);
    }

    @PutMapping("/invite-codes/{id}")
    public ResponseEntity<?> updateInviteCode(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id,
            @RequestBody UpdateInviteCodeRequest request) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        var updated = inviteCodeService.update(id, adminUsername, request.code(), request.maxUses(), null);
        return ok("邀请码已更新", updated);
    }

    @PostMapping("/org-tags")
    public ResponseEntity<?> createOrganizationTag(
            @RequestHeader("Authorization") String token,
            @RequestBody OrgTagRequest request) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        OrganizationTag tag = userService.createOrganizationTag(
                request.tagId(), request.name(), request.description(), request.parentTag(),
                request.uploadMaxSizeMb(), adminUsername);
        return ok("组织标签创建成功", tag);
    }

    @GetMapping("/org-tags")
    public ResponseEntity<?> getAllOrganizationTags(@RequestHeader("Authorization") String token) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        return ok("获取组织标签成功", organizationTagRepository.findAll());
    }

    @PutMapping("/users/{userId}/org-tags")
    public ResponseEntity<?> assignOrgTagsToUser(
            @RequestHeader("Authorization") String token,
            @PathVariable Long userId,
            @RequestBody AssignOrgTagsRequest request) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        userService.assignOrgTagsToUser(userId, request.orgTags(), adminUsername);
        return ok("组织标签分配成功", null);
    }

    @GetMapping("/org-tags/tree")
    public ResponseEntity<?> getOrganizationTagTree(@RequestHeader("Authorization") String token) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        return ok("获取组织标签树成功", userService.getOrganizationTagTree());
    }

    @PutMapping("/org-tags/{tagId}")
    public ResponseEntity<?> updateOrganizationTag(
            @RequestHeader("Authorization") String token,
            @PathVariable String tagId,
            @RequestBody OrgTagUpdateRequest request) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        OrganizationTag updatedTag = userService.updateOrganizationTag(
                tagId, request.name(), request.description(), request.parentTag(),
                request.uploadMaxSizeMb(), adminUsername);
        return ok("组织标签更新成功", updatedTag);
    }

    @DeleteMapping("/org-tags/{tagId}")
    public ResponseEntity<?> deleteOrganizationTag(
            @RequestHeader("Authorization") String token,
            @PathVariable String tagId) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        userService.deleteOrganizationTag(tagId, adminUsername);
        return ok("组织标签删除成功", null);
    }

    @GetMapping("/users/list")
    public ResponseEntity<?> getUserList(
            @RequestHeader("Authorization") String token,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String orgTag,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        return ok("获取用户列表成功", userService.getUserList(keyword, orgTag, status, page, size));
    }

    @GetMapping("/conversation")
    public ResponseEntity<?> getAllConversations(
            @RequestHeader("Authorization") String token,
            @RequestParam(required = false) String userid,
            @RequestParam(required = false) String start_date,
            @RequestParam(required = false) String end_date) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("ADMIN_GET_ALL_CONVERSATIONS");
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);

        String targetUsername = null;
        if (userid != null && !userid.isEmpty()) {
            try {
                Long userIdLong = Long.parseLong(userid);
                Optional<User> targetUser = userRepository.findById(userIdLong);
                if (targetUser.isEmpty()) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(Map.of("code", 404, "message", "用户不存在"));
                }
                targetUsername = targetUser.get().getUsername();
            } catch (NumberFormatException e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("code", 400, "message", "用户ID格式不正确"));
            }
        }

        LocalDateTime startDateTime = parseStartDate(start_date);
        LocalDateTime endDateTime = parseEndDate(end_date);
        List<Map<String, Object>> allConversations = conversationService.toMessageHistory(
                conversationService.getAllConversations(adminUsername, targetUsername, startDateTime, endDateTime),
                true
        );
        monitor.end("获取全部会话成功");
        return ok("获取会话记录成功", allConversations);
    }

    @PostMapping("/migrate-minio")
    public ResponseEntity<?> migrateMinioFiles(
            @RequestHeader("Authorization") String token,
            @RequestParam String adminKey) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);

        if (!"MIGRATE_MINIO_2024".equals(adminKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", 403, "message", "管理员密钥错误"));
        }

        MinioMigrationUtil.MigrationReport report = migrationUtil.migrateAllFiles();
        return ok("MinIO 迁移完成", Map.of(
                "successCount", report.successCount,
                "skipCount", report.skipCount,
                "errorCount", report.errorCount,
                "errors", report.getErrors()
        ));
    }

    @PostMapping("/clear-all-data")
    public ResponseEntity<?> clearAllData(
            @RequestHeader("Authorization") String token,
            @RequestParam String adminKey) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);

        if (!"CLEAR_ALL_2024".equals(adminKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", 403, "message", "管理员密钥错误"));
        }

        migrationUtil.clearAllData();
        return ok("全部数据已清理", null);
    }

    @GetMapping("/recharge-packages")
    public ResponseEntity<?> getAllRechargePackages(@RequestHeader("Authorization") String token) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        return ok("获取充值套餐成功", rechargePackageRepository.findAllByDeletedFalseOrderBySortOrderAsc());
    }

    @PostMapping("/recharge-packages")
    public ResponseEntity<?> createRechargePackage(
            @RequestHeader("Authorization") String token,
            @RequestBody RechargePackageRequest request) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);
        validateRechargePackageRequest(request, true);

        RechargePackage pkg = new RechargePackage();
        applyRechargePackageRequest(pkg, request, true);
        pkg.setDeleted(false);
        return ok("创建充值套餐成功", rechargePackageRepository.save(pkg));
    }

    @PutMapping("/recharge-packages/{id}")
    public ResponseEntity<?> updateRechargePackage(
            @RequestHeader("Authorization") String token,
            @PathVariable Integer id,
            @RequestBody RechargePackageRequest request) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);

        RechargePackage pkg = rechargePackageRepository.findById(id)
                .orElseThrow(() -> new CustomException("套餐不存在", HttpStatus.BAD_REQUEST));
        applyRechargePackageRequest(pkg, request, false);
        return ok("更新充值套餐成功", rechargePackageRepository.save(pkg));
    }

    @DeleteMapping("/recharge-packages/{id}")
    public ResponseEntity<?> deleteRechargePackage(
            @RequestHeader("Authorization") String token,
            @PathVariable Integer id) {
        String adminUsername = currentAdminUsername(token);
        validateAdmin(adminUsername);

        RechargePackage pkg = rechargePackageRepository.findById(id)
                .orElseThrow(() -> new CustomException("套餐不存在", HttpStatus.BAD_REQUEST));
        pkg.setDeleted(true);
        return ok("删除充值套餐成功", rechargePackageRepository.save(pkg));
    }

    private ResponseEntity<?> ok(String message, Object data) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", 200);
        body.put("message", message);
        if (data != null) {
            body.put("data", data);
        }
        return ResponseEntity.ok(body);
    }

    private String currentAdminUsername(String token) {
        return jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
    }

    private User validateAdmin(String username) {
        if (username == null || username.isEmpty()) {
            throw new CustomException("Invalid token", HttpStatus.UNAUTHORIZED);
        }

        User admin = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));

        if (admin.getRole() != User.Role.ADMIN) {
            throw new CustomException("Unauthorized access: Admin role required", HttpStatus.FORBIDDEN);
        }

        return admin;
    }

    private LocalDateTime parseStartDate(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(dateTimeStr);
        } catch (java.time.format.DateTimeParseException e1) {
            try {
                if (dateTimeStr.length() == 16) {
                    return LocalDateTime.parse(dateTimeStr + ":00");
                }
                if (dateTimeStr.length() == 13) {
                    return LocalDateTime.parse(dateTimeStr + ":00:00");
                }
                if (dateTimeStr.length() == 10) {
                    return LocalDate.parse(dateTimeStr).atStartOfDay();
                }
            } catch (Exception ignored) {
            }
        }
        throw new CustomException("开始时间格式不正确: " + dateTimeStr, HttpStatus.BAD_REQUEST);
    }

    private LocalDateTime parseEndDate(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(dateTimeStr);
        } catch (java.time.format.DateTimeParseException e1) {
            try {
                if (dateTimeStr.length() == 16) {
                    return LocalDateTime.parse(dateTimeStr + ":59");
                }
                if (dateTimeStr.length() == 13) {
                    return LocalDateTime.parse(dateTimeStr + ":59:59");
                }
                if (dateTimeStr.length() == 10) {
                    return LocalDate.parse(dateTimeStr).plusDays(1).atStartOfDay().minusSeconds(1);
                }
            } catch (Exception ignored) {
            }
        }
        throw new CustomException("结束时间格式不正确: " + dateTimeStr, HttpStatus.BAD_REQUEST);
    }

    private void validateRechargePackageRequest(RechargePackageRequest request, boolean create) {
        if (request == null) {
            throw new CustomException("套餐配置不能为空", HttpStatus.BAD_REQUEST);
        }
        if (create && (request.packageName() == null || request.packageName().isBlank())) {
            throw new CustomException("套餐名称不能为空", HttpStatus.BAD_REQUEST);
        }
        if (create && request.packagePrice() == null) {
            throw new CustomException("套餐价格不能为空", HttpStatus.BAD_REQUEST);
        }
        if (create && request.llmToken() == null) {
            throw new CustomException("LLM Token 额度不能为空", HttpStatus.BAD_REQUEST);
        }
        if (create && request.embeddingToken() == null) {
            throw new CustomException("Embedding Token 额度不能为空", HttpStatus.BAD_REQUEST);
        }
    }

    private void applyRechargePackageRequest(RechargePackage pkg, RechargePackageRequest request, boolean create) {
        validateRechargePackageRequest(request, create);
        if (request.packageName() != null) {
            pkg.setPackageName(request.packageName());
        }
        if (request.packagePrice() != null) {
            pkg.setPackagePrice(request.packagePrice());
        }
        if (request.packageDesc() != null) {
            pkg.setPackageDesc(request.packageDesc());
        }
        if (request.packageBenefit() != null) {
            pkg.setPackageBenefit(request.packageBenefit());
        }
        if (request.llmToken() != null) {
            pkg.setLlmToken(request.llmToken());
        }
        if (request.embeddingToken() != null) {
            pkg.setEmbeddingToken(request.embeddingToken());
        }
        if (request.enabled() != null) {
            pkg.setEnabled(request.enabled());
        } else if (create) {
            pkg.setEnabled(true);
        }
        if (request.sortOrder() != null) {
            pkg.setSortOrder(request.sortOrder());
        } else if (create) {
            pkg.setSortOrder(0);
        }
    }
}

record AdminUserRequest(String username, String password) {
}

record OrgTagRequest(String tagId, String name, String description, String parentTag, Long uploadMaxSizeMb) {
}

record OrgTagUpdateRequest(String name, String description, String parentTag, Long uploadMaxSizeMb) {
}

record AssignOrgTagsRequest(List<String> orgTags) {
}

record CreateInviteCodeRequest(String code, Integer maxUses, Integer count) {
}

record UpdateInviteCodeRequest(String code, Integer maxUses) {
}

record RegistrationSettingsRequest(Boolean inviteRequired) {
}

record RechargePackageRequest(
        String packageName,
        Long packagePrice,
        String packageDesc,
        String packageBenefit,
        Long llmToken,
        Long embeddingToken,
        Boolean enabled,
        Integer sortOrder
) {
}

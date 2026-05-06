package com.huangyifei.rag.service;

import com.huangyifei.rag.config.AppAuthProperties;
import com.huangyifei.rag.exception.CustomException;
import com.huangyifei.rag.model.OrganizationTag;
import com.huangyifei.rag.model.RegistrationMode;
import com.huangyifei.rag.model.User;
import com.huangyifei.rag.repository.OrganizationTagRepository;
import com.huangyifei.rag.repository.UserRepository;
import com.huangyifei.rag.utils.PasswordUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class UserService {

    private static final String DEFAULT_ORG_TAG = "DEFAULT";
    private static final String DEFAULT_ORG_NAME = "默认组织";
    private static final String DEFAULT_ORG_DESCRIPTION = "系统默认组织标签";
    private static final String PRIVATE_TAG_PREFIX = "PRIVATE_";
    private static final String PRIVATE_ORG_NAME_SUFFIX = " 的个人空间";
    private static final String PRIVATE_ORG_DESCRIPTION = "用户个人私有组织标签";
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final int MAX_TAG_ID_LENGTH = 255;
    private static final Pattern NON_ALNUM_PATTERN = Pattern.compile("[^a-z0-9]+");
    private static final Pattern TRIM_DASH_PATTERN = Pattern.compile("(^-+|-+$)");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{6,18}$");

    private final UserRepository userRepository;
    private final OrganizationTagRepository organizationTagRepository;
    private final OrgTagCacheService orgTagCacheService;
    private final AppAuthProperties appAuthProperties;
    private final InviteCodeService inviteCodeService;
    private final RegistrationSettingsService registrationSettingsService;
    private final UserTokenService userTokenService;
    private final UsageQuotaService usageQuotaService;

    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private String globalUploadMaxFileSize;

    public UserService(
            UserRepository userRepository,
            OrganizationTagRepository organizationTagRepository,
            OrgTagCacheService orgTagCacheService,
            AppAuthProperties appAuthProperties,
            InviteCodeService inviteCodeService,
            RegistrationSettingsService registrationSettingsService,
            UserTokenService userTokenService,
            UsageQuotaService usageQuotaService
    ) {
        this.userRepository = userRepository;
        this.organizationTagRepository = organizationTagRepository;
        this.orgTagCacheService = orgTagCacheService;
        this.appAuthProperties = appAuthProperties;
        this.inviteCodeService = inviteCodeService;
        this.registrationSettingsService = registrationSettingsService;
        this.userTokenService = userTokenService;
        this.usageQuotaService = usageQuotaService;
    }

    @Transactional
    public void registerUser(String username, String password) {
        registerUser(username, password, null);
    }

    @Transactional
    public void registerUser(String username, String password, String inviteCode) {
        boolean inviteRegistration = validateRegistrationPolicy(username, inviteCode);
        validatePassword(password);

        if (userRepository.findByUsername(username).isPresent()) {
            throw new CustomException("Username already exists", HttpStatus.BAD_REQUEST);
        }

        ensureDefaultOrgTagExists();

        User user = new User();
        user.setUsername(username);
        user.setPassword(PasswordUtil.encode(password));
        user.setRole(User.Role.USER);
        userRepository.save(user);

        String privateTagId = PRIVATE_TAG_PREFIX + username;
        createPrivateOrgTag(privateTagId, username, user);

        List<String> assignedOrgTags = List.of(DEFAULT_ORG_TAG, privateTagId);
        user.setOrgTags(String.join(",", assignedOrgTags));
        user.setPrimaryOrg(privateTagId);
        userRepository.save(user);

        if (inviteRegistration) {
            userTokenService.initializeTokenBalances(
                    String.valueOf(user.getId()),
                    appAuthProperties.getRegistration().getInviteGiftLlmTokens(),
                    appAuthProperties.getRegistration().getInviteGiftEmbeddingTokens(),
                    "邀请码注册赠送");
        }

        orgTagCacheService.cacheUserOrgTags(username, assignedOrgTags);
        orgTagCacheService.cacheUserPrimaryOrg(username, privateTagId);
    }

    public String authenticateUser(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("Invalid username or password", HttpStatus.UNAUTHORIZED));
        if (!PasswordUtil.matches(password, user.getPassword())) {
            throw new CustomException("Invalid username or password", HttpStatus.UNAUTHORIZED);
        }
        return user.getUsername();
    }

    @Transactional
    public void createAdminUser(String username, String password, String creatorUsername) {
        User creator = userRepository.findByUsername(creatorUsername)
                .orElseThrow(() -> new CustomException("Creator not found", HttpStatus.NOT_FOUND));
        if (creator.getRole() != User.Role.ADMIN) {
            throw new CustomException("Only administrators can create admin accounts", HttpStatus.FORBIDDEN);
        }
        if (userRepository.findByUsername(username).isPresent()) {
            throw new CustomException("Username already exists", HttpStatus.BAD_REQUEST);
        }
        validatePassword(password);

        User adminUser = new User();
        adminUser.setUsername(username);
        adminUser.setPassword(PasswordUtil.encode(password));
        adminUser.setRole(User.Role.ADMIN);
        adminUser.setOrgTags(DEFAULT_ORG_TAG);
        adminUser.setPrimaryOrg(DEFAULT_ORG_TAG);
        userRepository.save(adminUser);
    }

    @Transactional
    public OrganizationTag createOrganizationTag(
            String tagId,
            String name,
            String description,
            String parentTag,
            Long uploadMaxSizeMb,
            String creatorUsername
    ) {
        User creator = requireAdmin(creatorUsername, "create organization tags");
        String resolvedTagId = resolveOrGenerateTagId(tagId, name);
        validateParentTag(resolvedTagId, parentTag);

        OrganizationTag tag = new OrganizationTag();
        tag.setTagId(resolvedTagId);
        tag.setName(name);
        tag.setDescription(description);
        tag.setParentTag(blankToNull(parentTag));
        tag.setUploadMaxSizeBytes(normalizeUploadMaxSizeBytes(uploadMaxSizeMb));
        tag.setCreatedBy(creator);
        OrganizationTag saved = organizationTagRepository.save(tag);
        orgTagCacheService.invalidateAllEffectiveTagsCache();
        return saved;
    }

    @Transactional
    public void assignOrgTagsToUser(Long userId, List<String> orgTags, String adminUsername) {
        requireAdmin(adminUsername, "assign organization tags");
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));

        Set<String> finalTags = new HashSet<>(orgTags == null ? List.of() : orgTags);
        for (String tagId : finalTags) {
            if (!organizationTagRepository.existsByTagId(tagId)) {
                throw new CustomException("Organization tag " + tagId + " not found", HttpStatus.NOT_FOUND);
            }
        }

        String privateTagId = PRIVATE_TAG_PREFIX + user.getUsername();
        if (organizationTagRepository.existsByTagId(privateTagId)) {
            finalTags.add(privateTagId);
        }
        if (finalTags.isEmpty()) {
            finalTags.add(DEFAULT_ORG_TAG);
        }

        user.setOrgTags(String.join(",", finalTags));
        if (user.getPrimaryOrg() == null || !finalTags.contains(user.getPrimaryOrg())) {
            user.setPrimaryOrg(finalTags.contains(privateTagId) ? privateTagId : finalTags.iterator().next());
        }
        userRepository.save(user);

        orgTagCacheService.deleteUserOrgTagsCache(user.getUsername());
        orgTagCacheService.cacheUserOrgTags(user.getUsername(), new ArrayList<>(finalTags));
        orgTagCacheService.deleteUserEffectiveTagsCache(user.getUsername());
        orgTagCacheService.cacheUserPrimaryOrg(user.getUsername(), user.getPrimaryOrg());
    }

    public Map<String, Object> getUserOrgTags(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));
        List<String> orgTags = parseOrgTags(user.getOrgTags());
        String primaryOrg = user.getPrimaryOrg();

        List<Map<String, Object>> details = new ArrayList<>();
        for (String tagId : orgTags) {
            organizationTagRepository.findByTagId(tagId).ifPresent(tag -> {
                Map<String, Object> item = new HashMap<>();
                item.put("tagId", tag.getTagId());
                item.put("name", tag.getName());
                item.put("description", tag.getDescription());
                item.put("uploadMaxSizeBytes", tag.getUploadMaxSizeBytes());
                item.put("uploadMaxSizeMb", toUploadMaxSizeMb(tag.getUploadMaxSizeBytes()));
                details.add(item);
            });
        }

        Map<String, Object> result = new HashMap<>();
        result.put("orgTags", orgTags);
        result.put("primaryOrg", primaryOrg);
        result.put("orgTagDetails", details);
        return result;
    }

    public void setUserPrimaryOrg(String username, String primaryOrg) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));
        if (!parseOrgTags(user.getOrgTags()).contains(primaryOrg)) {
            throw new CustomException("Organization tag not assigned to user", HttpStatus.BAD_REQUEST);
        }
        user.setPrimaryOrg(primaryOrg);
        userRepository.save(user);
        orgTagCacheService.cacheUserPrimaryOrg(username, primaryOrg);
    }

    public String getUserPrimaryOrg(String userId) {
        User user = resolveUser(userId);
        String primaryOrg = user.getPrimaryOrg();
        if (primaryOrg == null || primaryOrg.isBlank()) {
            List<String> tags = parseOrgTags(user.getOrgTags());
            primaryOrg = tags.isEmpty() ? DEFAULT_ORG_TAG : tags.get(0);
            user.setPrimaryOrg(primaryOrg);
            userRepository.save(user);
        }
        return primaryOrg;
    }

    public List<Map<String, Object>> getOrganizationTagTree() {
        return buildTagTreeRecursive(organizationTagRepository.findByParentTag(null));
    }

    @Transactional
    public OrganizationTag updateOrganizationTag(
            String tagId,
            String name,
            String description,
            String parentTag,
            Long uploadMaxSizeMb,
            String adminUsername
    ) {
        requireAdmin(adminUsername, "update organization tags");
        OrganizationTag tag = organizationTagRepository.findByTagId(tagId)
                .orElseThrow(() -> new CustomException("Organization tag not found", HttpStatus.NOT_FOUND));
        validateParentTag(tagId, parentTag);

        if (name != null && !name.isBlank()) {
            tag.setName(name);
        }
        if (description != null) {
            tag.setDescription(description);
        }
        tag.setParentTag(blankToNull(parentTag));
        tag.setUploadMaxSizeBytes(normalizeUploadMaxSizeBytes(uploadMaxSizeMb));
        OrganizationTag updated = organizationTagRepository.save(tag);
        orgTagCacheService.invalidateAllEffectiveTagsCache();
        return updated;
    }

    @Transactional
    public void deleteOrganizationTag(String tagId, String adminUsername) {
        requireAdmin(adminUsername, "delete organization tags");
        if (DEFAULT_ORG_TAG.equals(tagId)) {
            throw new CustomException("Cannot delete the default organization tag", HttpStatus.BAD_REQUEST);
        }
        OrganizationTag tag = organizationTagRepository.findByTagId(tagId)
                .orElseThrow(() -> new CustomException("Organization tag not found", HttpStatus.NOT_FOUND));
        if (!organizationTagRepository.findByParentTag(tagId).isEmpty()) {
            throw new CustomException("Cannot delete a tag with child tags", HttpStatus.BAD_REQUEST);
        }
        for (User user : userRepository.findAll()) {
            if (parseOrgTags(user.getOrgTags()).contains(tagId) || tagId.equals(user.getPrimaryOrg())) {
                throw new CustomException("Cannot delete a tag that is assigned to users", HttpStatus.CONFLICT);
            }
        }
        organizationTagRepository.delete(tag);
        orgTagCacheService.invalidateAllEffectiveTagsCache();
    }

    public boolean isAdminUser(String userId) {
        return resolveUser(userId).getRole() == User.Role.ADMIN;
    }

    public OrganizationTag getOrganizationTag(String tagId) {
        return organizationTagRepository.findByTagId(tagId)
                .orElseThrow(() -> new CustomException("Organization tag not found", HttpStatus.NOT_FOUND));
    }

    public Map<String, Object> getUserList(String keyword, String orgTag, Integer status, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = size > 0 ? size : 10;
        Pageable pageable = PageRequest.of(safePage - 1, safeSize, Sort.by("createdAt").descending());

        List<User> filteredUsers = userRepository.findAll(Sort.by("createdAt").descending()).stream()
                .filter(user -> matchesUserListFilters(user, keyword, orgTag, status))
                .toList();

        int start = Math.min((int) pageable.getOffset(), filteredUsers.size());
        int end = Math.min(start + pageable.getPageSize(), filteredUsers.size());
        List<User> pageContent = start < end ? filteredUsers.subList(start, end) : Collections.emptyList();
        PageImpl<User> userPage = new PageImpl<>(pageContent, pageable, filteredUsers.size());
        Map<String, UsageQuotaService.UserUsageSnapshot> usageSnapshots = usageQuotaService.getSnapshots(
                pageContent.stream().map(user -> String.valueOf(user.getId())).toList());

        List<Map<String, Object>> userList = pageContent.stream()
                .map(user -> toUserListItem(user, usageSnapshots))
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("content", userList);
        result.put("totalElements", userPage.getTotalElements());
        result.put("totalPages", userPage.getTotalPages());
        result.put("size", userPage.getSize());
        result.put("number", userPage.getNumber() + 1);
        return result;
    }

    private boolean validateRegistrationPolicy(String username, String inviteCode) {
        RegistrationMode mode = registrationSettingsService.getEffectiveMode();
        if (mode == RegistrationMode.CLOSED) {
            throw new CustomException("REGISTRATION_CLOSED", HttpStatus.FORBIDDEN);
        }
        if (mode == RegistrationMode.INVITE_ONLY) {
            inviteCodeService.consume(inviteCode, username);
            return true;
        }
        return false;
    }

    private void createPrivateOrgTag(String privateTagId, String username, User owner) {
        if (!organizationTagRepository.existsByTagId(privateTagId)) {
            OrganizationTag privateTag = new OrganizationTag();
            privateTag.setTagId(privateTagId);
            privateTag.setName(username + PRIVATE_ORG_NAME_SUFFIX);
            privateTag.setDescription(PRIVATE_ORG_DESCRIPTION);
            privateTag.setCreatedBy(owner);
            organizationTagRepository.save(privateTag);
        }
    }

    private void ensureDefaultOrgTagExists() {
        if (organizationTagRepository.existsByTagId(DEFAULT_ORG_TAG)) {
            return;
        }
        User creator = userRepository.findAll().stream()
                .filter(user -> User.Role.ADMIN.equals(user.getRole()))
                .findFirst()
                .orElseThrow(() -> new CustomException("No admin user exists to initialize default organization tag", HttpStatus.INTERNAL_SERVER_ERROR));
        OrganizationTag defaultTag = new OrganizationTag();
        defaultTag.setTagId(DEFAULT_ORG_TAG);
        defaultTag.setName(DEFAULT_ORG_NAME);
        defaultTag.setDescription(DEFAULT_ORG_DESCRIPTION);
        defaultTag.setCreatedBy(creator);
        organizationTagRepository.save(defaultTag);
    }

    private User requireAdmin(String username, String action) {
        User admin = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("Admin not found", HttpStatus.NOT_FOUND));
        if (admin.getRole() != User.Role.ADMIN) {
            throw new CustomException("Only administrators can " + action, HttpStatus.FORBIDDEN);
        }
        return admin;
    }

    private void validatePassword(String password) {
        if (password == null || !PASSWORD_PATTERN.matcher(password).matches()) {
            throw new CustomException("密码必须为 6-18 位，且同时包含字母和数字", HttpStatus.BAD_REQUEST);
        }
    }

    private List<Map<String, Object>> buildTagTreeRecursive(List<OrganizationTag> tags) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (OrganizationTag tag : tags) {
            Map<String, Object> node = new HashMap<>();
            node.put("tagId", tag.getTagId());
            node.put("name", tag.getName());
            node.put("description", tag.getDescription());
            node.put("parentTag", tag.getParentTag());
            node.put("uploadMaxSizeBytes", tag.getUploadMaxSizeBytes());
            node.put("uploadMaxSizeMb", toUploadMaxSizeMb(tag.getUploadMaxSizeBytes()));
            List<OrganizationTag> children = organizationTagRepository.findByParentTag(tag.getTagId());
            if (!children.isEmpty()) {
                node.put("children", buildTagTreeRecursive(children));
            }
            result.add(node);
        }
        return result;
    }

    private void validateParentTag(String tagId, String parentTag) {
        if (parentTag == null || parentTag.isBlank()) {
            return;
        }
        if (tagId.equals(parentTag)) {
            throw new CustomException("A tag cannot be its own parent", HttpStatus.BAD_REQUEST);
        }
        organizationTagRepository.findByTagId(parentTag)
                .orElseThrow(() -> new CustomException("Parent tag not found", HttpStatus.NOT_FOUND));
        if (wouldFormCycle(tagId, parentTag)) {
            throw new CustomException("Setting this parent would create a cycle in the tag hierarchy", HttpStatus.BAD_REQUEST);
        }
    }

    private boolean wouldFormCycle(String tagId, String newParentId) {
        String currentParentId = newParentId;
        while (currentParentId != null && !currentParentId.isBlank()) {
            if (tagId.equals(currentParentId)) {
                return true;
            }
            Optional<OrganizationTag> parentTag = organizationTagRepository.findByTagId(currentParentId);
            if (parentTag.isEmpty()) {
                break;
            }
            currentParentId = parentTag.get().getParentTag();
        }
        return false;
    }

    private boolean matchesUserListFilters(User user, String keyword, String orgTag, Integer status) {
        if (orgTag != null && !orgTag.isBlank() && !parseOrgTags(user.getOrgTags()).contains(orgTag)) {
            return false;
        }
        if (keyword != null && !keyword.isBlank() && !user.getUsername().contains(keyword)) {
            return false;
        }
        if (status != null) {
            return user.getRole() == (status == 1 ? User.Role.USER : User.Role.ADMIN);
        }
        return true;
    }

    private Map<String, Object> toUserListItem(User user, Map<String, UsageQuotaService.UserUsageSnapshot> usageSnapshots) {
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("userId", user.getId());
        userMap.put("username", user.getUsername());
        userMap.put("orgTags", buildOrgTagDetails(parseOrgTags(user.getOrgTags())));
        userMap.put("primaryOrg", user.getPrimaryOrg());
        userMap.put("status", user.getRole() == User.Role.USER ? 1 : 0);
        userMap.put("createdAt", user.getCreatedAt());
        userMap.put("usage", usageSnapshots.getOrDefault(String.valueOf(user.getId()), usageQuotaService.getSnapshot(String.valueOf(user.getId()))));
        return userMap;
    }

    private List<Map<String, String>> buildOrgTagDetails(List<String> tagIds) {
        List<Map<String, String>> details = new ArrayList<>();
        for (String tagId : tagIds) {
            organizationTagRepository.findByTagId(tagId).ifPresent(tag -> {
                Map<String, String> item = new HashMap<>();
                item.put("tagId", tag.getTagId());
                item.put("name", tag.getName());
                details.add(item);
            });
        }
        return details;
    }

    private String resolveOrGenerateTagId(String tagId, String name) {
        String normalizedTagId = tagId == null ? "" : tagId.trim();
        if (!normalizedTagId.isEmpty()) {
            if (normalizedTagId.startsWith(PRIVATE_TAG_PREFIX)) {
                throw new CustomException("Tag ID cannot start with PRIVATE_", HttpStatus.BAD_REQUEST);
            }
            if (organizationTagRepository.existsByTagId(normalizedTagId)) {
                throw new CustomException("Tag ID already exists", HttpStatus.BAD_REQUEST);
            }
            return normalizedTagId;
        }
        return generateUniqueTagId(name);
    }

    private String generateUniqueTagId(String name) {
        String slug = buildTagSlug(name);
        String baseId = truncateTagId("ORG_" + slug);
        if (!organizationTagRepository.existsByTagId(baseId)) {
            return baseId;
        }
        for (int i = 2; i <= 9999; i++) {
            String candidate = appendSuffix(baseId, "_" + i);
            if (!organizationTagRepository.existsByTagId(candidate)) {
                return candidate;
            }
        }
        while (true) {
            String suffix = "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            String candidate = appendSuffix(baseId, suffix);
            if (!organizationTagRepository.existsByTagId(candidate)) {
                return candidate;
            }
        }
    }

    private String buildTagSlug(String name) {
        String raw = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        String slug = NON_ALNUM_PATTERN.matcher(raw).replaceAll("-");
        slug = TRIM_DASH_PATTERN.matcher(slug).replaceAll("");
        return slug.isEmpty() ? "tag" : slug;
    }

    private String appendSuffix(String base, String suffix) {
        if (base.length() + suffix.length() <= MAX_TAG_ID_LENGTH) {
            return base + suffix;
        }
        return base.substring(0, MAX_TAG_ID_LENGTH - suffix.length()) + suffix;
    }

    private String truncateTagId(String tagId) {
        return tagId.length() <= MAX_TAG_ID_LENGTH ? tagId : tagId.substring(0, MAX_TAG_ID_LENGTH);
    }

    private User resolveUser(String userId) {
        try {
            return userRepository.findById(Long.parseLong(userId))
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
        } catch (NumberFormatException e) {
            return userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
        }
    }

    private List<String> parseOrgTags(String orgTags) {
        if (orgTags == null || orgTags.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(orgTags.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isBlank())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private Long normalizeUploadMaxSizeBytes(Long uploadMaxSizeMb) {
        if (uploadMaxSizeMb == null) {
            return null;
        }
        if (uploadMaxSizeMb <= 0) {
            throw new CustomException("上传大小限制必须大于 0 MB", HttpStatus.BAD_REQUEST);
        }
        long uploadMaxSizeBytes = uploadMaxSizeMb * BYTES_PER_MB;
        long globalUploadMaxBytes = DataSize.parse(globalUploadMaxFileSize).toBytes();
        if (uploadMaxSizeBytes > globalUploadMaxBytes) {
            throw new CustomException("上传大小限制不能超过全局上限 " + (globalUploadMaxBytes / BYTES_PER_MB) + " MB", HttpStatus.BAD_REQUEST);
        }
        return uploadMaxSizeBytes;
    }

    private Long toUploadMaxSizeMb(Long uploadMaxSizeBytes) {
        if (uploadMaxSizeBytes == null || uploadMaxSizeBytes <= 0) {
            return null;
        }
        return uploadMaxSizeBytes / BYTES_PER_MB;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

package com.huangyifei.rag.service;

import com.huangyifei.rag.model.OrganizationTag;
import com.huangyifei.rag.repository.OrganizationTagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class OrgTagCacheService {

    private static final Logger logger = LoggerFactory.getLogger(OrgTagCacheService.class);
    private static final String USER_ORG_TAGS_KEY_PREFIX = "user:org_tags:";
    private static final String USER_PRIMARY_ORG_KEY_PREFIX = "user:primary_org:";
    private static final String USER_EFFECTIVE_TAGS_KEY_PREFIX = "user:effective_org_tags:";
    private static final String DEFAULT_ORG_TAG = "DEFAULT";
    private static final long CACHE_TTL_HOURS = 24;

    private final RedisTemplate<String, Object> redisTemplate;
    private final OrganizationTagRepository organizationTagRepository;

    public OrgTagCacheService(RedisTemplate<String, Object> redisTemplate, OrganizationTagRepository organizationTagRepository) {
        this.redisTemplate = redisTemplate;
        this.organizationTagRepository = organizationTagRepository;
    }

    public void cacheUserOrgTags(String username, List<String> orgTags) {
        try {
            String key = USER_ORG_TAGS_KEY_PREFIX + username;
            redisTemplate.delete(key);
            if (orgTags != null && !orgTags.isEmpty()) {
                redisTemplate.opsForList().rightPushAll(key, orgTags.toArray());
                redisTemplate.expire(key, CACHE_TTL_HOURS, TimeUnit.HOURS);
            }
        } catch (Exception e) {
            logger.warn("Failed to cache organization tags for user: {}", username, e);
        }
    }

    public List<String> getUserOrgTags(String username) {
        try {
            List<Object> values = redisTemplate.opsForList().range(USER_ORG_TAGS_KEY_PREFIX + username, 0, -1);
            if (values == null || values.isEmpty()) {
                return null;
            }
            return values.stream().map(String::valueOf).toList();
        } catch (Exception e) {
            logger.warn("Failed to get organization tags for user: {}", username, e);
            return null;
        }
    }

    public void cacheUserPrimaryOrg(String username, String primaryOrg) {
        try {
            String key = USER_PRIMARY_ORG_KEY_PREFIX + username;
            redisTemplate.opsForValue().set(key, primaryOrg);
            redisTemplate.expire(key, CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            logger.warn("Failed to cache primary organization for user: {}", username, e);
        }
    }

    public String getUserPrimaryOrg(String username) {
        try {
            Object value = redisTemplate.opsForValue().get(USER_PRIMARY_ORG_KEY_PREFIX + username);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            logger.warn("Failed to get primary organization for user: {}", username, e);
            return null;
        }
    }

    public void deleteUserOrgTagsCache(String username) {
        redisTemplate.delete(USER_ORG_TAGS_KEY_PREFIX + username);
        redisTemplate.delete(USER_PRIMARY_ORG_KEY_PREFIX + username);
    }

    public List<String> getUserEffectiveOrgTags(String username) {
        String cacheKey = USER_EFFECTIVE_TAGS_KEY_PREFIX + username;
        try {
            List<Object> cached = redisTemplate.opsForList().range(cacheKey, 0, -1);
            if (cached != null && !cached.isEmpty()) {
                return cached.stream().map(String::valueOf).toList();
            }

            Set<String> effective = new HashSet<>();
            List<String> userTags = getUserOrgTags(username);
            if (userTags != null) {
                effective.addAll(userTags);
                for (String tagId : userTags) {
                    collectParentTags(tagId, effective);
                }
            }
            effective.add(DEFAULT_ORG_TAG);

            List<String> result = new ArrayList<>(effective);
            redisTemplate.opsForList().rightPushAll(cacheKey, result.toArray());
            redisTemplate.expire(cacheKey, CACHE_TTL_HOURS, TimeUnit.HOURS);
            return result;
        } catch (Exception e) {
            logger.warn("Failed to get effective organization tags for user: {}", username, e);
            return List.of(DEFAULT_ORG_TAG);
        }
    }

    public void deleteUserEffectiveTagsCache(String username) {
        redisTemplate.delete(USER_EFFECTIVE_TAGS_KEY_PREFIX + username);
    }

    public void invalidateAllEffectiveTagsCache() {
        Set<String> keys = redisTemplate.keys(USER_EFFECTIVE_TAGS_KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private void collectParentTags(String tagId, Set<String> result) {
        OrganizationTag tag = organizationTagRepository.findByTagId(tagId).orElse(null);
        if (tag != null && tag.getParentTag() != null && !tag.getParentTag().isBlank()) {
            result.add(tag.getParentTag());
            collectParentTags(tag.getParentTag(), result);
        }
    }
}

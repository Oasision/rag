package com.huangyifei.rag.config;

import com.huangyifei.rag.model.FileUpload;
import com.huangyifei.rag.repository.FileUploadRepository;
import com.huangyifei.rag.utils.JwtUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class OrgTagAuthorizationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(OrgTagAuthorizationFilter.class);
    private static final String DEFAULT_ORG_TAG = "DEFAULT";
    private static final String PRIVATE_TAG_PREFIX = "PRIVATE_";

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String path = request.getRequestURI();

            if (shouldOnlyAttachAuthContext(path, request.getMethod())) {
                attachAuthContext(request);
                filterChain.doFilter(request, response);
                return;
            }

            String resourceId = extractResourceIdFromPath(request);
            if (resourceId == null) {
                filterChain.doFilter(request, response);
                return;
            }

            ResourceInfo resourceInfo = getResourceInfo(resourceId);
            if (resourceInfo == null) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }

            String resourceOrgTag = resourceInfo.getOrgTag();
            if (resourceInfo.isPublic()
                    || resourceOrgTag == null
                    || resourceOrgTag.isEmpty()
                    || DEFAULT_ORG_TAG.equalsIgnoreCase(resourceOrgTag)) {
                filterChain.doFilter(request, response);
                return;
            }

            String token = extractToken(request);
            if (token == null) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }

            String username = jwtUtils.extractUsernameFromToken(token);
            String role = jwtUtils.extractRoleFromToken(token);
            if (username != null && username.equals(resourceInfo.getOwner())) {
                filterChain.doFilter(request, response);
                return;
            }

            if ("ADMIN".equalsIgnoreCase(role)) {
                filterChain.doFilter(request, response);
                return;
            }

            if (resourceOrgTag.startsWith(PRIVATE_TAG_PREFIX)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }

            String userOrgTags = jwtUtils.extractOrgTagsFromToken(token);
            if (userOrgTags == null || userOrgTags.isEmpty()) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }

            if (isUserAuthorized(userOrgTags, resourceOrgTag)) {
                filterChain.doFilter(request, response);
            } else {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            }
        } catch (Exception e) {
            logger.error("Organization tag authorization failed: {}", e.getMessage(), e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private boolean shouldOnlyAttachAuthContext(String path, String method) {
        return path.matches(".*/upload/chunk.*")
                || path.matches(".*/upload/merge.*")
                || path.matches(".*/documents/uploads.*")
                || path.matches(".*/documents/accessible.*")
                || path.matches(".*/documents/page-preview.*")
                || path.matches(".*/search/hybrid.*")
                || path.matches(".*/users/conversation.*")
                || path.matches(".*/users/conversations.*")
                || path.matches(".*/agent/tools.*")
                || (path.matches(".*/documents/[a-fA-F0-9]{32}.*")
                && ("DELETE".equals(method) || "POST".equals(method)));
    }

    private void attachAuthContext(HttpServletRequest request) {
        String token = extractToken(request);
        if (token == null) {
            return;
        }
        String userId = jwtUtils.extractUserIdFromToken(token);
        if (userId != null) {
            request.setAttribute("userId", userId);
            request.setAttribute("role", jwtUtils.extractRoleFromToken(token));
            request.setAttribute("orgTags", jwtUtils.extractOrgTagsFromToken(token));
        }
    }

    private String extractResourceIdFromPath(HttpServletRequest request) {
        String path = request.getRequestURI();

        if (path.matches(".*/files/[^/]+.*")) {
            return path.replaceAll(".*/files/([^/]+).*", "$1");
        }

        if (path.matches(".*/documents/[a-fA-F0-9]{32}.*")) {
            return path.replaceAll(".*/documents/([a-fA-F0-9]{32}).*", "$1");
        }

        if (path.matches(".*/documents/\\d+.*")) {
            return path.replaceAll(".*/documents/(\\d+).*", "$1");
        }

        if (path.matches(".*/upload/chunk.*")) {
            return request.getHeader("X-File-MD5");
        }

        if (path.matches(".*/knowledge/[^/]+.*")) {
            return path.replaceAll(".*/knowledge/([^/]+).*", "$1");
        }

        return null;
    }

    private ResourceInfo getResourceInfo(String resourceId) {
        if (resourceId == null) {
            return null;
        }

        Optional<FileUpload> fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(resourceId);
        if (fileUpload.isPresent()) {
            FileUpload file = fileUpload.get();
            return new ResourceInfo(file.getUserId(), file.getOrgTag(), file.isPublic());
        }

        return null;
    }

    private boolean isPublicResource(String resourceId) {
        ResourceInfo resourceInfo = getResourceInfo(resourceId);
        return resourceInfo != null && resourceInfo.isPublic();
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    private boolean isUserAuthorized(String userOrgTags, String resourceOrgTag) {
        Set<String> userTags = Arrays.stream(userOrgTags.split(","))
                .map(String::trim)
                .collect(Collectors.toSet());
        return userTags.contains(resourceOrgTag);
    }

    private static class ResourceInfo {
        private final String owner;
        private final String orgTag;
        private final boolean isPublic;

        ResourceInfo(String owner, String orgTag, boolean isPublic) {
            this.owner = owner;
            this.orgTag = orgTag;
            this.isPublic = isPublic;
        }

        String getOwner() {
            return owner;
        }

        String getOrgTag() {
            return orgTag;
        }

        boolean isPublic() {
            return isPublic;
        }
    }
}

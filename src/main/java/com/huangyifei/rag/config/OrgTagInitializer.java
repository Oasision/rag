package com.huangyifei.rag.config;

import com.huangyifei.rag.model.OrganizationTag;
import com.huangyifei.rag.model.User;
import com.huangyifei.rag.repository.OrganizationTagRepository;
import com.huangyifei.rag.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(2)
public class OrgTagInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(OrgTagInitializer.class);

    private static final String DEFAULT_TAG = "default";
    private static final String DEFAULT_NAME = "默认组织";
    private static final String DEFAULT_DESCRIPTION = "系统默认组织标签，未指定组织的用户和文档会使用该标签";

    private static final String ADMIN_TAG = "admin";
    private static final String ADMIN_NAME = "管理员组织";
    private static final String ADMIN_DESCRIPTION = "管理员默认组织标签";

    @Autowired
    private OrganizationTagRepository organizationTagRepository;

    @Autowired
    private UserRepository userRepository;

    @Value("${admin.bootstrap.username:}")
    private String adminUsername;

    @Override
    public void run(String... args) {
        User adminUser = findAdminCreator();
        if (adminUser == null) {
            logger.warn("No admin user found, skip default organization tag initialization");
            return;
        }

        createOrganizationTagIfNotExists(DEFAULT_TAG, DEFAULT_NAME, DEFAULT_DESCRIPTION, adminUser);
        createOrganizationTagIfNotExists(ADMIN_TAG, ADMIN_NAME, ADMIN_DESCRIPTION, adminUser);

        logger.info("Organization tag initialization finished");
    }

    private User findAdminCreator() {
        if (adminUsername != null && !adminUsername.isBlank()) {
            return userRepository.findByUsername(adminUsername).orElse(null);
        }
        return userRepository.findAll().stream()
                .filter(user -> User.Role.ADMIN.equals(user.getRole()))
                .findFirst()
                .orElse(null);
    }

    private void createOrganizationTagIfNotExists(String tagId, String name, String description, User creator) {
        if (!organizationTagRepository.existsByTagId(tagId)) {
            OrganizationTag tag = new OrganizationTag();
            tag.setTagId(tagId);
            tag.setName(name);
            tag.setDescription(description);
            tag.setCreatedBy(creator);
            organizationTagRepository.save(tag);
            logger.info("Organization tag '{}' created", tagId);
        } else {
            logger.info("Organization tag '{}' already exists", tagId);
        }
    }
}

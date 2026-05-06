package com.huangyifei.rag.config;

import com.huangyifei.rag.model.User;
import com.huangyifei.rag.repository.UserRepository;
import com.huangyifei.rag.utils.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
@Order(1)
public class AdminUserInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(AdminUserInitializer.class);
    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "admin123", "admin", "password", "123456", "12345678", "qwerty"
    );

    @Autowired
    private UserRepository userRepository;

    @Value("${admin.bootstrap.enabled:false}")
    private boolean bootstrapEnabled;

    @Value("${admin.bootstrap.username:}")
    private String adminUsername;

    @Value("${admin.bootstrap.password:}")
    private String adminPassword;

    @Value("${admin.bootstrap.primary-org:default}")
    private String adminPrimaryOrg;

    @Value("${admin.bootstrap.org-tags:default,admin}")
    private String adminOrgTags;

    @Override
    public void run(String... args) {
        if (!bootstrapEnabled) {
            logger.info("Admin bootstrap disabled: admin.bootstrap.enabled=false");
            return;
        }

        validateBootstrapConfig();

        Optional<User> existingAdmin = userRepository.findByUsername(adminUsername);
        if (existingAdmin.isPresent()) {
            logger.info("Admin bootstrap skipped because user '{}' already exists", adminUsername);
            return;
        }

        try {
            User adminUser = new User();
            adminUser.setUsername(adminUsername);
            adminUser.setPassword(PasswordUtil.encode(adminPassword));
            adminUser.setRole(User.Role.ADMIN);
            adminUser.setPrimaryOrg(adminPrimaryOrg);
            adminUser.setOrgTags(adminOrgTags);
            userRepository.save(adminUser);
            logger.info("Admin bootstrap created user '{}'", adminUsername);
        } catch (Exception e) {
            logger.error("Admin bootstrap failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create bootstrap admin user", e);
        }
    }

    private void validateBootstrapConfig() {
        if (adminUsername == null || adminUsername.isBlank()) {
            throw new IllegalStateException("admin.bootstrap.username cannot be blank");
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            throw new IllegalStateException("admin.bootstrap.password cannot be blank");
        }
        if (adminPassword.length() < 12) {
            throw new IllegalStateException("admin.bootstrap.password length must be >= 12");
        }
        if (WEAK_PASSWORDS.contains(adminPassword.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("admin.bootstrap.password is too weak");
        }
    }
}

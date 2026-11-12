package com.biggerink.watch.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biggerink.watch.entity.UserAccount;
import com.biggerink.watch.mapper.UserAccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首次启动创建默认管理员（BCrypt 真实编码）。
 * 密码由 WATCH_ADMIN_PASSWORD 注入，未配置时回落到开发默认值（仅限本地）。
 * 数据库不可达或表未建时仅记日志，不阻断启动。
 */
@Component
public class AdminInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    private final UserAccountMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final String adminPassword;

    public AdminInitializer(UserAccountMapper userMapper,
                            PasswordEncoder passwordEncoder,
                            @Value("${watch.admin.password:admin123}") String adminPassword) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(String... args) {
        try {
            Long admins = userMapper.selectCount(
                    new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getRole, "ADMIN"));
            if (admins != null && admins > 0) {
                return;
            }
            UserAccount admin = new UserAccount();
            admin.setUsername("admin");
            admin.setPasswordHash(passwordEncoder.encode(adminPassword));
            admin.setEmail("admin@bigger.ink");
            admin.setRole("ADMIN");
            admin.setStatus("NORMAL");
            admin.setQuotaSubscription(9999);
            admin.setQuotaScanDaily(99999);
            userMapper.insert(admin);
            log.info("已创建默认管理员 admin（密码由 WATCH_ADMIN_PASSWORD 指定；上线前务必设置强密码）");
        } catch (Exception e) {
            log.warn("默认管理员初始化跳过（数据库未就绪或表未创建）: {}", e.getMessage());
        }
    }
}

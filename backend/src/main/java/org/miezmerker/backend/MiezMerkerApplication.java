package org.miezmerker.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MiezMerkerApplication {
    public static void main(String[] args) throws java.sql.SQLException {
        if (args.length > 0 && "sysadmin".equals(args[0])) {
            org.miezmerker.backend.bootstrap.SystemRoleMaintenance.run(args);
            return;
        }
        SpringApplication.run(MiezMerkerApplication.class, args);
    }
}

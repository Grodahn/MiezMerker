package org.miezmerker.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MiezMerkerApplication {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "dev-demo".equals(args[0])) {
            org.miezmerker.backend.bootstrap.DevDemoCommand.run(args);
            return;
        }
        if (args.length > 0 && "sysadmin".equals(args[0])) {
            org.miezmerker.backend.bootstrap.SystemRoleMaintenance.run(args);
            return;
        }
        SpringApplication.run(MiezMerkerApplication.class, args);
    }
}

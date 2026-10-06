package com.fingress.migration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MigrationApplication {
    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--desktop")) {
            DesktopLauncher.launch(args);
            return;
        }
        if (args.length > 0 && (args[0].equals("cli") || args[0].equals("--help"))) {
            System.exit(MigrationCli.mainRun(args[0].equals("cli") ? java.util.Arrays.copyOfRange(args,1,args.length) : new String[]{"help"}));
        } else SpringApplication.run(MigrationApplication.class, args);
    }
}

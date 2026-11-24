package io.github.yuvrajtiwary.earlybird;

import io.github.yuvrajtiwary.earlybird.sources.ValidateBoardsCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class EarlyBirdApplication {

    public static void main(String[] args) {
        if (ValidateBoardsCommand.requested(args)) {
            // A one-off check of the seed list: no Spring context, web server or database needed.
            System.exit(ValidateBoardsCommand.run(args));
        }
        SpringApplication.run(EarlyBirdApplication.class, args);
    }
}

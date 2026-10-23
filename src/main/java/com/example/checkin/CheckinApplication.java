package com.example.checkin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class CheckinApplication {

    public static void main(String[] args) {
        SpringApplication.run(CheckinApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    public FaceEngine faceEngine(
            @Value("${MODELS_DIR:models}") String modelsDir,
            @Value("${FACE_MOCK:0}") String mock) throws Exception {
        return new FaceEngine(modelsDir, "1".equals(mock));
    }
}

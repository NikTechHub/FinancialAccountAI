package com.example.accounting.assistant;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AssistantConfig {

    @Bean
    public Clock assistantClock() {
        return Clock.systemDefaultZone();
    }
}

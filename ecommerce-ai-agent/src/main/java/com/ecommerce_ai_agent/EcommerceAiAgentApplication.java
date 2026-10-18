package com.ecommerce_ai_agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EcommerceAiAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(EcommerceAiAgentApplication.class, args);
	}

}

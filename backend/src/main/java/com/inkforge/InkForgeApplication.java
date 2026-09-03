package com.inkforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class InkForgeApplication {

	public static void main(String[] args) {
		SpringApplication.run(InkForgeApplication.class, args);
	}

}

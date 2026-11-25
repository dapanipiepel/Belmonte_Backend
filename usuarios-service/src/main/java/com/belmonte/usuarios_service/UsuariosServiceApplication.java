package com.belmonte.usuarios_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class UsuariosServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(UsuariosServiceApplication.class, args);
	}

}

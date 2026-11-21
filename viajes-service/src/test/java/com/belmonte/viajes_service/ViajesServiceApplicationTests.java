package com.belmonte.viajes_service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class ViajesServiceApplicationTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void contextLoads() {
	}

	@Test
	void unsafePrototypeRoutesAreNotAvailable() throws Exception {
		mvc.perform(get("/api/viajes")).andExpect(status().isNotFound());
		mvc.perform(post("/api/viajes")).andExpect(status().isNotFound());
		mvc.perform(put("/api/viajes/1/asignar")).andExpect(status().isNotFound());
		mvc.perform(put("/api/viajes/1/estado")).andExpect(status().isNotFound());
		mvc.perform(get("/api/traslados")).andExpect(status().isNotFound());
	}
}

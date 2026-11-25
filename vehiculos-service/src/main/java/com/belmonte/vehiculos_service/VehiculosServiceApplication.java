package com.belmonte.vehiculos_service;

import com.belmonte.vehiculos_service.model.Vehiculo;
import com.belmonte.vehiculos_service.model.MarcaVehiculo;
import com.belmonte.vehiculos_service.model.ModeloVehiculo;
import com.belmonte.vehiculos_service.model.TipoVehiculo;
import com.belmonte.vehiculos_service.repository.MarcaVehiculoRepository;
import com.belmonte.vehiculos_service.repository.ModeloVehiculoRepository;
import com.belmonte.vehiculos_service.repository.TipoVehiculoRepository;
import com.belmonte.vehiculos_service.repository.VehiculoRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VehiculosServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(VehiculosServiceApplication.class, args);
	}

	@Bean
	@Profile("default")
	CommandLineRunner initDatabase(VehiculoRepository repository, TipoVehiculoRepository tipos,
			MarcaVehiculoRepository marcas, ModeloVehiculoRepository modelos) {
		return args -> {
			if (repository.count() == 0) {
				TipoVehiculo tipo = tipos.save(new TipoVehiculo("DEMO", "Vehiculo de prueba"));
				ModeloVehiculo corolla = modelos.save(new ModeloVehiculo(marcas.save(new MarcaVehiculo("Toyota")), "Corolla"));
				ModeloVehiculo staria = modelos.save(new ModeloVehiculo(marcas.save(new MarcaVehiculo("Hyundai")), "Staria"));
				ModeloVehiculo sprinter = modelos.save(new ModeloVehiculo(marcas.save(new MarcaVehiculo("Mercedes-Benz")), "Sprinter"));
				repository.save(new Vehiculo(tipo, corolla, "ABCD-12", 2020, null, 4, true));
				repository.save(new Vehiculo(tipo, staria, "EFGH-34", 2020, null, 8, true));
				repository.save(new Vehiculo(tipo, sprinter, "IJKL-56", 2020, null, 16, true));
			}
		};
	}
}
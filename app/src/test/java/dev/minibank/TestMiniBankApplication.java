package dev.minibank;

import org.springframework.boot.SpringApplication;

public class TestMiniBankApplication {

	public static void main(String[] args) {
		SpringApplication.from(MiniBankApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

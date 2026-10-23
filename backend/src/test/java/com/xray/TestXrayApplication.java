package com.xray;

import org.springframework.boot.SpringApplication;

public class TestXrayApplication {

	public static void main(String[] args) {
		SpringApplication.from(XrayApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

package com.settle;

import com.settle.support.TestcontainersConfiguration;
import org.springframework.boot.SpringApplication;

public class TestIncidentManagementApplication {

	public static void main(String[] args) {
		SpringApplication.from(IncidentManagementApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

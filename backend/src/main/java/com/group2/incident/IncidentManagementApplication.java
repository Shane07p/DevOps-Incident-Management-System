package com.group2.incident;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IncidentManagementApplication {

	static {
		// Every timestamp is stored and compared in UTC. Setting it here rather than in the
		// launch command covers the IDE and the tests too, and keeps the JDBC driver from
		// sending a local zone id that the database image does not recognise.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	public static void main(String[] args) {
		SpringApplication.run(IncidentManagementApplication.class, args);
	}

}

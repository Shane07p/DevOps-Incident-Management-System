package com.settle.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI at /swagger-ui.html; its Authorize button takes the JWT from /api/auth/login. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

	private static final String BEARER = "bearerAuth";

	@Bean
	OpenAPI incidentManagementApi() {
		return new OpenAPI()
				.info(new Info().title("Incident Management API").version("v1"))
				.addSecurityItem(new SecurityRequirement().addList(BEARER))
				.components(new Components().addSecuritySchemes(BEARER,
						new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
	}

}

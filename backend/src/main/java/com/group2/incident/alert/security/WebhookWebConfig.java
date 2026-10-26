package com.group2.incident.alert.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** The webhook chain in SecurityConfig lets every request through; the tool token is checked here (D31). */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
class WebhookWebConfig implements WebMvcConfigurer {

	private final ToolTokenInterceptor toolTokenInterceptor;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(toolTokenInterceptor).addPathPatterns("/webhooks/**");
	}
}

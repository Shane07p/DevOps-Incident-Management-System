package com.group2.incident.common.tenancy;

import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TenancyConfig {

	@Bean
	HibernatePropertiesCustomizer orgTenantResolverCustomizer(OrgTenantResolver resolver) {
		return properties -> properties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
	}

}

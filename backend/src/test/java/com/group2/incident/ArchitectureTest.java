package com.group2.incident;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Entity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The AGENTS.md rules that a build can check. Each one names the rule it enforces. */
@AnalyzeClasses(packages = "com.group2.incident", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	// Lombok annotations are gone once compiled, so this checks what they would generate.
	@ArchTest
	static final ArchRule entitiesChangeStateThroughNamedMethods = noMethods()
			.that().areDeclaredInClassesThat().areAnnotatedWith(Entity.class)
			.should().haveNameMatching("set[A-Z].*|equals|hashCode|toString|builder")
			.because("entities change state through named methods, and generated equality breaks lazy loading");

	@ArchTest
	static final ArchRule controllersReturnNoEntities = methods()
			.that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
			.should(notReturnAnEntity())
			.because("controllers return DTO records, never JPA entities");

	@ArchTest
	static final ArchRule controllersHaveATag = classes()
			.that().areAnnotatedWith(RestController.class)
			.should().beAnnotatedWith(Tag.class)
			.because("every controller is documented in Swagger UI");

	@ArchTest
	static final ArchRule endpointsHaveAnOperation = methods()
			.that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
			.and().areMetaAnnotatedWith(RequestMapping.class)
			.should().beAnnotatedWith(Operation.class)
			.because("every endpoint has an @Operation summary in Swagger UI");

	// TimeConfig is where the one Clock comes from; everything else injects it.
	@ArchTest
	static final ArchRule timeComesFromTheInjectedClock = noClasses()
			.that().doNotHaveSimpleName("TimeConfig")
			.should().callMethod(Instant.class, "now")
			.orShould().callMethod(LocalDate.class, "now")
			.orShould().callMethod(LocalDateTime.class, "now")
			.orShould().callMethod(OffsetDateTime.class, "now")
			.orShould().callMethod(ZonedDateTime.class, "now")
			.orShould().callMethod(System.class, "currentTimeMillis")
			.orShould().callMethod(Clock.class, "systemUTC")
			.orShould().callMethod(Clock.class, "systemDefaultZone")
			.because("code that reads the time injects Clock, so tests can control it");

	@ArchTest
	static final ArchRule jsonUsesJackson3 = noClasses()
			.should().dependOnClassesThat().resideInAnyPackage("com.fasterxml.jackson.databind..", "com.fasterxml.jackson.core..")
			.because("Spring Boot 4 uses Jackson 3 (tools.jackson); Jackson 2 is only on the classpath through other libraries");

	private static ArchCondition<JavaMethod> notReturnAnEntity() {
		return new ArchCondition<>("not return a JPA entity") {
			@Override
			public void check(JavaMethod method, ConditionEvents events) {
				for (JavaClass type : method.getReturnType().getAllInvolvedRawTypes()) {
					if (type.isAnnotatedWith(Entity.class)) {
						events.add(SimpleConditionEvent.violated(method,
								method.getFullName() + " returns the entity " + type.getSimpleName()));
					}
				}
			}
		};
	}
}

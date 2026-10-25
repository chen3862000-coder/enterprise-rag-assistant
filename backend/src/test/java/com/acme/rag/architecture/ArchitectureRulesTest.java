package com.acme.rag.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.acme.rag", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {
    @ArchTest
    static final ArchRule packagesShouldBeFreeOfCycles =
            com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices()
                    .matching("com.acme.rag.(*)..")
                    .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule transportShouldNotDependOnJdbcOrPersistenceImplementations = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.jdbc..", "..persistence..", "..repository..")
            .allowEmptyShould(true);
}

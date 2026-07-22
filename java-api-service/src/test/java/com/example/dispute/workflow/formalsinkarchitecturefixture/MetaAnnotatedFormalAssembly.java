package com.example.dispute.workflow.formalsinkarchitecturefixture;

import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Named
@Singleton
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.ANNOTATION_TYPE, ElementType.TYPE})
@interface FixtureNamedStereotype {}

@FixtureNamedStereotype
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@interface NestedFixtureStereotype {}

@NestedFixtureStereotype
@FormalSinkArchitectureFixture
class MetaAnnotatedFormalAssembly {

    private final CrossFileFormalWrapper wrapper;

    MetaAnnotatedFormalAssembly(CrossFileFormalWrapper wrapper) {
        this.wrapper = wrapper;
    }

    Object wrapper() {
        return wrapper;
    }
}

package com.example.dispute.workflow.formalsinkarchitecturefixture;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.TestComponent;

@TestComponent
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@interface FormalSinkArchitectureFixture {}

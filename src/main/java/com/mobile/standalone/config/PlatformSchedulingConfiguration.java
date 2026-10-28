package com.mobile.standalone.config;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
/** Debug instance never claims production queues or sends scheduled callbacks. */
@Configuration
@Profile("!qa-debug")
@EnableScheduling
public class PlatformSchedulingConfiguration {}

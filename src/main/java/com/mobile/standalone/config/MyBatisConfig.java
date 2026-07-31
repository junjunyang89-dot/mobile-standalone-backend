package com.mobile.standalone.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.mobile.standalone.mapper")
public class MyBatisConfig {
}

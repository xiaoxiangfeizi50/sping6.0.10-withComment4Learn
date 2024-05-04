package org.springframework.axpltest.testAop.annotation.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@Configuration
@ComponentScan(basePackages="com.mashibing.aop.annotation")
@EnableAspectJAutoProxy
public class SpringConfiguration {

}

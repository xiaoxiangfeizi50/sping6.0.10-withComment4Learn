package org.springframework.axpltest.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.annotation.Order;

@Configuration
@PropertySource({"classpath:myconfig2.properties"})
@ComponentScan("com.mashibing.selftag")
public class MyComponentScan {

    @ComponentScan("com.mashibing.selftag")
    @Configuration
    @Order(90)
    class InnerClass{

    }

}

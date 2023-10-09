package org.springframework.axpltest.test;

import org.springframework.axpltest.entity.Person;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class ATest {

	public static void main(String[] args) {
//		ApplicationContext ac = new ClassPathXmlApplicationContext("applicationContext.xml");
		ApplicationContext ac = new ClassPathXmlApplicationContext("spring-${username}.xml");
		Person person = (Person) ac.getBean("person");
		System.out.println(person);
	}


}

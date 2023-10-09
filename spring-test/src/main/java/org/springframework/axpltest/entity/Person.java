package org.springframework.axpltest.entity;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

public class Person implements ApplicationContextAware, BeanFactoryAware {
	private String id;
	private String name;
	private int age;

	private ApplicationContext applicationContext;
	private BeanFactory beanFactory;

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}

	// 这个set方法有谁来进行调用，是用户还是spring？一定不能是用户，
	// 我应该什么时候去调用这个方法，怎么调用这个方法？给定好一个约束，在统一的地方对这些set方法来进行调用
	public void setApplicationContext(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	public BeanFactory getBeanFactory() {
		return beanFactory;
	}

	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}


	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public int getAge() {
		return age;
	}

	public void setAge(int age) {
		this.age = age;
	}

	@Override
	public String toString() {
		return "Person{" +
				"id='" + id + '\'' +
				", name='" + name + '\'' +
				", age=" + age +
				'}';
	}
}

package demo;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;



public class Demo {
	public static void main(String[] args) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext("demo");
		A bean = context.getBean("a", A.class);
		A bean2 = context.getBean("a", A.class);
		System.out.println(bean);
		System.out.println(bean2);
		context.close();
	}

}


@Component
class A  {
	@Autowired
	B b;
	public A() {
		System.out.println("初始化");
	}
}
@Component
class B {
	@Autowired
	A a;

	public B() {
		System.out.println("初始化");
	}
}

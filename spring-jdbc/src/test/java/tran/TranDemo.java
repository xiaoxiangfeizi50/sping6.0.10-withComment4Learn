package tran;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;


/**
 *
 * @author YouDong
 * @date 2023/6/6 15:22
 */
//@Import({A.class, B.class})
public class TranDemo {
	public static void main(String[] args) {
		
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext("tran");

		PayServiceImpl payServiceImpl = context.getBean("payServiceImpl", PayServiceImpl.class);
		payServiceImpl.pay();
		context.close();

	}
}

//@Component
//class A implements BeanDefinitionRegistryPostProcessor, BeanPostProcessor {
//	@Override
//	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
//		System.out.println(bean + "postProcessBeforeInitialization");
//		return bean;
//	}
//
//	@Override
//	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
//		System.out.println(bean + "postProcessAfterInitialization");
//		return bean;
//	}
//
//	@Override
//	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
//		System.out.println("执行postProcessBeanFactory");
//	}
//
//	@Override
//	public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
//		System.out.println("执行postProcessBeanDefinitionRegistry");
//	}
//}
//
//@Component
//class B extends ConfigurationClassPostProcessor {
//
//	@Override
//	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
//		System.out.println("执行postProcessBeanFactory");
//	}
//
//	@Override
//	public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
//		System.out.println("执行postProcessBeanDefinitionRegistry");
//	}
//}
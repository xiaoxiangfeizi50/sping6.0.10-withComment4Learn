package org.springframework.axpltest.testResolveBeforeInstantiation;

import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.cglib.proxy.Enhancer;

public class MyInstantiationAwareBeanPostProcessor implements InstantiationAwareBeanPostProcessor {

    @Override
    public Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) throws BeansException {
        System.out.println("beanName:"+beanName+"----执行postProcessBeforeInstantiation方法");
        if (beanClass == BeforeInstantiation.class){
            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(beanClass);
            enhancer.setCallback(new MyMethodInterceptor());
            BeforeInstantiation beforeInstantiation = (BeforeInstantiation) enhancer.create();
            System.out.println("创建代理对象："+beforeInstantiation);
			return beforeInstantiation;
			// 问：此处直接new，不通过代理，有什么区别？
			// 答：不通过代理，没办法对目标方法进行拦截了。但是new出来的对象，仍然会被Spring管理。
//			return new BeforeInstantiation();
        }
        return null;
    }

    @Override
    public boolean postProcessAfterInstantiation(Object bean, String beanName) throws BeansException {
        System.out.println("beanName:"+beanName+"----执行postProcessAfterInstantiation方法");

        return false;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        System.out.println("beanName:"+beanName+"----执行postProcessBeforeInitialization方法");

        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        System.out.println("beanName:"+beanName+"----执行postProcessAfterInitialization方法");

        return bean;
    }

    @Override
    public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) throws BeansException {
        System.out.println("beanName:"+beanName+"----执行postProcessProperties方法");

        return pvs;
    }
}

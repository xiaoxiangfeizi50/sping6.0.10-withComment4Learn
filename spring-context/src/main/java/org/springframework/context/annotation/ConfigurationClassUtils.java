/*
 * Copyright 2002-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.context.annotation;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.context.event.EventListenerFactory;
import org.springframework.core.Conventions;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Utilities for identifying and configuring {@link Configuration} classes.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @author Stephane Nicoll
 * @since 6.0
 */
public abstract class ConfigurationClassUtils {

	static final String CONFIGURATION_CLASS_FULL = "full";

	static final String CONFIGURATION_CLASS_LITE = "lite";

	static final String CONFIGURATION_CLASS_ATTRIBUTE =
			Conventions.getQualifiedAttributeName(ConfigurationClassPostProcessor.class, "configurationClass");

	static final String ORDER_ATTRIBUTE =
			Conventions.getQualifiedAttributeName(ConfigurationClassPostProcessor.class, "order");


	private static final Log logger = LogFactory.getLog(ConfigurationClassUtils.class);

	private static final Set<String> candidateIndicators = Set.of(
					Component.class.getName(),
					ComponentScan.class.getName(),
					Import.class.getName(),
					ImportResource.class.getName()
			);


	/**
	 * Initialize a configuration class proxy for the specified class.
	 * @param userClass the configuration class to initialize
	 */
	@SuppressWarnings("unused") // Used by AOT-optimized generated code
	public static Class<?> initializeConfigurationClass(Class<?> userClass) {
		Class<?> configurationClass = new ConfigurationClassEnhancer().enhance(userClass, null);
		Enhancer.registerStaticCallbacks(configurationClass, ConfigurationClassEnhancer.CALLBACKS);
		return configurationClass;
	}


	/**
	 * 	 * 检查当前给定的beanDefinition中是否是一个配置类的候选者，
	 * 	 * 判断在嵌套的关系中是否包含配置类或者组件类或者自动注册进去的，并做相应的标记
	 *
	 * Check whether the given bean definition is a candidate for a configuration class
	 * (or a nested component class declared within a configuration/component class,
	 * to be auto-registered as well), and mark it accordingly.
	 * @param beanDef the bean definition to check
	 * @param metadataReaderFactory the current factory in use by the caller
	 * @return whether the candidate qualifies as (any kind of) configuration class
	 */
	static boolean checkConfigurationClassCandidate(
			BeanDefinition beanDef, MetadataReaderFactory metadataReaderFactory) {
		// 获取bean定义信息中的class类名
		String className = beanDef.getBeanClassName();
		// 如果className为空，或者bean定义信息中的factoryMethod不等于空，那么直接返回
		if (className == null || beanDef.getFactoryMethodName() != null) {
			return false;
		}

		AnnotationMetadata metadata;
		// 根据bean的定义方式，用不同的类进行解析：
		// XML 定义 Bean                     >>>> GenericBeanDefinition
		// @Component 以及派生注解定义 Bean    >>>> ScannedGenericBeanDefinition
		// 借助于 @Import 导入 Bean           >>>> AnnotatedGenericBeanDefinition
		// @Bean 定义的方法                   >>>> ConfigurationClassBeanDefinition私有静态类
		// 通过注解注入的BD都实现了AnnotatedBeanDefinition
		// AbstractBeanDefinition 是 BeanDefinition 的子抽象类，也是其他 BeanDefinition 类型的基类，
		// 其实现了接口中定义的一系列操作方法，并定义了一系列的常量属性，这些常量会直接影响到 Spring 实例化 Bean 时的策略。

		// 此处主要用于判断是否归属于AnnotatedBeanDefinition
		if (beanDef instanceof AnnotatedBeanDefinition annotatedBd &&
				className.equals(annotatedBd.getMetadata().getClassName())) {
			// Can reuse the pre-parsed metadata from the given BeanDefinition...
			// 从当前bean的定义信息中获取元数据信息
			metadata = annotatedBd.getMetadata();
		}
		// 判断是否是spring中默认的BeanDefinition（所有都继承了abstractBeanDefiniton，包括注解/xml）
		else if (beanDef instanceof AbstractBeanDefinition abstractBd && abstractBd.hasBeanClass()) {
			// Check already loaded Class if present...
			// since we possibly can't even load the class file for this Class.
			// 获取当前bean对象的Class对象
			Class<?> beanClass = abstractBd.getBeanClass();
			// 如果class实例是下面四种类或接口的子类、父接口等任何一种情况，直接返回
			if (BeanFactoryPostProcessor.class.isAssignableFrom(beanClass) ||
					BeanPostProcessor.class.isAssignableFrom(beanClass) ||
					AopInfrastructureBean.class.isAssignableFrom(beanClass) ||
					EventListenerFactory.class.isAssignableFrom(beanClass)) {
				return false;
			}
			// 为给定类创建新的AnnotationMetadata实例
			metadata = AnnotationMetadata.introspect(beanClass);
		}
		// 如果上述两种情况都不符合
		else {
			try {
				// 获取className的MetadataReader实例
				MetadataReader metadataReader = metadataReaderFactory.getMetadataReader(className);
				// 读取底层类的完整注释元数据，包括带注解方法的元数据
				metadata = metadataReader.getAnnotationMetadata();
			}
			catch (IOException ex) {
				if (logger.isDebugEnabled()) {
					logger.debug("Could not find class file for introspecting configuration annotations: " +
							className, ex);
				}
				return false;
			}
		}

		//***********************************************************************************************************************
		//*************处理Configuration，@Component，，@ComponentScan、@Import、@ImportResource或者@Bean注解************************
		//***********************************************************************************************************************

		// 获取bean定义的元数据被@Configuration注解标注的属性字典值
		Map<String, Object> config = metadata.getAnnotationAttributes(Configuration.class.getName());
		// 如果bean被@Configuration注解标注，且属性proxyBeanMethods为false(使用代理模式)，则将bean定义记为full
		if (config != null && !Boolean.FALSE.equals(config.get("proxyBeanMethods"))) {
			beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_FULL);
		}
		// 如果bean被@configuration注解标注，且被注解@Component，@ComponentScan、@Import、@ImportResource或者@Bean标记的方法，则将bean定义标记为lite
		else if (config != null || isConfigurationCandidate(metadata)) {
			beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_LITE);
		}
		else {
			return false;
		}

		// It's a full or lite configuration candidate... Let's determine the order value, if any.
		// bean定义是一个标记为full或lite的候选项，如果设置order则设置order属性值
		Integer order = getOrder(metadata);
		// 如果值不为空的话，那么直接设置值到具体的beanDefinition
		if (order != null) {
			// 设置bean定义的order值
			beanDef.setAttribute(ORDER_ATTRIBUTE, order);
		}

		return true;
	}

	/**
	 * 检查给定的元数据，以查找给定的候选配置类是否被指定的注解标注
	 *
	 * Check the given metadata for a configuration class candidate
	 * (or nested component class declared within a configuration/component class).
	 * @param metadata the metadata of the annotated class
	 * @return {@code true} if the given class is to be registered for
	 * configuration class processing; {@code false} otherwise
	 */
	static boolean isConfigurationCandidate(AnnotationMetadata metadata) {
		// Do not consider an interface or an annotation...
		// 不考虑接口或注解
		if (metadata.isInterface()) {
			return false;
		}

		// Any of the typical annotations found?
		// 检查是否被注解@Component、@ComponentScan、@Import、@ImportResource标注
		for (String indicator : candidateIndicators) {
			if (metadata.isAnnotated(indicator)) {
				return true;
			}
		}

		// Finally, let's look for @Bean methods...
		// 最后检查是否有@Bean标注的方法
		return hasBeanMethods(metadata);
	}

	static boolean hasBeanMethods(AnnotationMetadata metadata) {
		try {
			return metadata.hasAnnotatedMethods(Bean.class.getName());
		}
		catch (Throwable ex) {
			if (logger.isDebugEnabled()) {
				logger.debug("Failed to introspect @Bean methods on class [" + metadata.getClassName() + "]: " + ex);
			}
			return false;
		}
	}

	/**
	 * Determine the order for the given configuration class metadata.
	 * @param metadata the metadata of the annotated class
	 * @return the {@code @Order} annotation value on the configuration class,
	 * or {@code Ordered.LOWEST_PRECEDENCE} if none declared
	 * @since 5.0
	 */
	@Nullable
	public static Integer getOrder(AnnotationMetadata metadata) {
		Map<String, Object> orderAttributes = metadata.getAnnotationAttributes(Order.class.getName());
		return (orderAttributes != null ? ((Integer) orderAttributes.get(AnnotationUtils.VALUE)) : null);
	}

	/**
	 * Determine the order for the given configuration class bean definition,
	 * as set by {@link #checkConfigurationClassCandidate}.
	 * @param beanDef the bean definition to check
	 * @return the {@link Order @Order} annotation value on the configuration class,
	 * or {@link Ordered#LOWEST_PRECEDENCE} if none declared
	 * @since 4.2
	 */
	public static int getOrder(BeanDefinition beanDef) {
		Integer order = (Integer) beanDef.getAttribute(ORDER_ATTRIBUTE);
		return (order != null ? order : Ordered.LOWEST_PRECEDENCE);
	}

}

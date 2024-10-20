/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.aop.config;

import org.w3c.dom.Element;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.lang.Nullable;

/**
 * Utility class for handling registration of auto-proxy creators used internally
 * by the '{@code aop}' namespace tags.
 *
 * <p>Only a single auto-proxy creator should be registered and multiple configuration
 * elements may wish to register different concrete implementations. As such this class
 * delegates to {@link AopConfigUtils} which provides a simple escalation protocol.
 * Callers may request a particular auto-proxy creator and know that creator,
 * <i>or a more capable variant thereof</i>, will be registered as a post-processor.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @since 2.0
 * @see AopConfigUtils
 */
public abstract class AopNamespaceUtils {

	/**
	 * The {@code proxy-target-class} attribute as found on AOP-related XML tags.
	 */
	public static final String PROXY_TARGET_CLASS_ATTRIBUTE = "proxy-target-class";

	/**
	 * The {@code expose-proxy} attribute as found on AOP-related XML tags.
	 */
	private static final String EXPOSE_PROXY_ATTRIBUTE = "expose-proxy";


	public static void registerAutoProxyCreatorIfNecessary(
			ParserContext parserContext, Element sourceElement) {

		BeanDefinition beanDefinition = AopConfigUtils.registerAutoProxyCreatorIfNecessary(
				parserContext.getRegistry(), parserContext.extractSource(sourceElement));
		useClassProxyingIfNecessary(parserContext.getRegistry(), sourceElement);
		registerComponentIfNecessary(beanDefinition, parserContext);
	}

	public static void registerAspectJAutoProxyCreatorIfNecessary(
			ParserContext parserContext, Element sourceElement) {

		// 注册名为org.springframework.aop.config.internalAutoProxyCreator的beanDefinition，
		// 其中的class类为`AspectJAwareAdvisorAutoProxyCreator`，其也会被注册到bean工厂中
		BeanDefinition beanDefinition = AopConfigUtils.registerAspectJAutoProxyCreatorIfNecessary(
				parserContext.getRegistry(), parserContext.extractSource(sourceElement));
		// * 如果被代理的目标对象实现了至少一个接口，则会使用JDK动态代理。所有该目标类型实现的接口都将被代理，
		// * 若该目标对象没有实现任何接口，则创建一个cglib代理
		// 如果指定proxy-target-class=true，则使用CGLIB代理，否则使用JDK代理
		// 其实其为AspectJAwareAdvisorAutoProxyCreator类的proxyTargetClass属性
		useClassProxyingIfNecessary(parserContext.getRegistry(), sourceElement);
		// 注册到spring的bean工厂中，再次校验是否已注册
		registerComponentIfNecessary(beanDefinition, parserContext);
	}

	public static void registerAspectJAnnotationAutoProxyCreatorIfNecessary(
			ParserContext parserContext, Element sourceElement) {

		// 注册AutoProxyCreator定义beanName为org.Springframework.aop.config.internalAutoProxyCreator的BeanDefinition
		BeanDefinition beanDefinition = AopConfigUtils.registerAspectJAnnotationAutoProxyCreatorIfNecessary(
				parserContext.getRegistry(), parserContext.extractSource(sourceElement));
		// 对于proxy-target-class以及expose-proxy属性的处理，其中beanDefinition的className为AnnotationAwareJAutoProxyCreator
		useClassProxyingIfNecessary(parserContext.getRegistry(), sourceElement);
		registerComponentIfNecessary(beanDefinition, parserContext);
	}

	private static void useClassProxyingIfNecessary(BeanDefinitionRegistry registry, @Nullable Element sourceElement) {
		/**
		 * SpringAOP部分使用JDK动态代理或者CGLIB来为目标创建代理，
		 * 		如果被代理的目标对象实现了至少一个接口，则会使用JDK动态代理。所有该目标类型实现的接口都将被代理，
		 * 		若该目标对象没有实现任何接口，则创建一个cglib代理
		 */
		if (sourceElement != null) {
			// 对于 proxy-target-class 属性的处理
			/**
			 *  proxy-target-class : 是否强制指定 CGLIB 代理，为 true 则强制使用 CGLIB 代理。 <aop:config proxy-target-class = "true" > ... </aop:config>
			 *                       为false，则根据是否有实现接口自动优先JDK代理。
			 *
			 *
			 * expose-proxy: 暴露代理，用于解决自调用失效问题
			 *        自调用失效的解决：
			 *       	不要调用内部方法时候在a()事务里直接调用b()事务
			 *       			错误示例：this.b()
			 *        			正确方式：
			 *        				XML:
			 *        					第1步：开启配置：<aop:config expose-proxy = "true" > ... </aop:config> 或 @EnableAspectJAutoProxy(exposeProxy = true)
			 *       					第2步：((AService)AopContext.currentProxy()).b()
			 *						注解：
			 *							@EnableAspectJAutoProxy(exposeProxy=true)
			 *        自调用有几种解决方案：
			 *        		1.打开 expose-proxy 配置，使用 AopContext.currentProxy()获取 代理类 再调用 b() 方法。
			 *        		2.@Autowire 注入当前bean，使用被注入的bean 调用 b() 方法。
			 *        		3.编程获取当前bean（跟2类似） ：AInterface a = applicationContext.getBean(AInterface.class
			 *        		4.编程式事务
			 *        		5.
			 *
			 */
			boolean proxyTargetClass = Boolean.parseBoolean(sourceElement.getAttribute(PROXY_TARGET_CLASS_ATTRIBUTE));
			if (proxyTargetClass) {
				AopConfigUtils.forceAutoProxyCreatorToUseClassProxying(registry);
			}
			// 对 expose-proxy 属性的处理
			boolean exposeProxy = Boolean.parseBoolean(sourceElement.getAttribute(EXPOSE_PROXY_ATTRIBUTE));
			if (exposeProxy) {
				AopConfigUtils.forceAutoProxyCreatorToExposeProxy(registry);
			}
		}
	}

	private static void registerComponentIfNecessary(@Nullable BeanDefinition beanDefinition, ParserContext parserContext) {
		if (beanDefinition != null) {
			parserContext.registerComponent(
					new BeanComponentDefinition(beanDefinition, AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME));
		}
	}

}

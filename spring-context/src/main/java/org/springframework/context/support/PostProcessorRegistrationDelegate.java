/*
 * Copyright 2002-2022 the original author or authors.
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

package org.springframework.context.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionValueResolver;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;
import org.springframework.lang.Nullable;

/**
 * Delegate for AbstractApplicationContext's post-processor handling.
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @author Stephane Nicoll
 * @since 4.0
 */
final class PostProcessorRegistrationDelegate {

	private PostProcessorRegistrationDelegate() {
	}

	// 执行自定义处理器的逻辑
	public static void invokeBeanFactoryPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanFactoryPostProcessor> beanFactoryPostProcessors) {
		// 记录已经处理过的扩展点name
		Set<String> processedBeans = new HashSet<>();
		// 第一次肯定是true，获取所有的beanFactoryPostProcessors（这玩意好像只有自己context.addBeanFactoryPostProcessor()才能用）
		if (beanFactory instanceof BeanDefinitionRegistry registry) {
			//regularPostProcessors用来存放BeanFactoryPostProcessor，
			List<BeanFactoryPostProcessor> regularPostProcessors = new ArrayList<>();
			// registryProcessors用来存放BeanDefinitionRegistryPostProcessor，BeanDefinitionRegistryPostProcessor扩展了BeanFactoryPostProcessor
			List<BeanDefinitionRegistryPostProcessor> registryProcessors = new ArrayList<>();
			for (BeanFactoryPostProcessor postProcessor : beanFactoryPostProcessors) {
				// 判断这个处理器是否扩展了BeanDefinitionRegistryPostProcessor
				// 如果是保存到registryProcessors
				if (postProcessor instanceof BeanDefinitionRegistryPostProcessor registryProcessor) {
					registryProcessor.postProcessBeanDefinitionRegistry(registry);
					registryProcessors.add(registryProcessor);
				}
				else {
					regularPostProcessors.add(postProcessor);
				}
			}

			// 临时记录真正的Bean
			List<BeanDefinitionRegistryPostProcessor> currentRegistryProcessors = new ArrayList<>();

			// 获取所有实现BeanDefinitionRegistryPostProcessor接口的名称
			String[] postProcessorNames =
					beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			for (String ppName : postProcessorNames) {
				// 判断是否实现PriorityOrdered，优先级最高执行（internalConfigurationAnnotationProcessor）
				if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
					// 获取这个真正的bean实例，并且实例化
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					processedBeans.add(ppName);
				}
			}
			// 处理排序（用户允许自定义更改优先级）
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			// 合并，spring还没有执行BeanDefinitionRegistryPostProcessor，保存起来等着统一执行
			registryProcessors.addAll(currentRegistryProcessors);
			// 执行后置处理器扩展点（这里必定会有系统级别的后置处理器ConfigurationClassPostProcessor，解析配置类等，@Bean，@Configuration，@Import等）
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());
			currentRegistryProcessors.clear();

			// Ordered换了个这个判断
			postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			for (String ppName : postProcessorNames) {
					if (!processedBeans.contains(ppName) && beanFactory.isTypeMatch(ppName, Ordered.class)) {
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					processedBeans.add(ppName);
				}
			}
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			registryProcessors.addAll(currentRegistryProcessors);
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup()); // 执行后置处理器
			currentRegistryProcessors.clear();

			// 又重复，但是没有了实现类判断
			boolean reiterate = true;
			while (reiterate) {
				reiterate = false;
				postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
				for (String ppName : postProcessorNames) {
					if (!processedBeans.contains(ppName)) { // 判断是否实现了BeanDefinitionRegistryPostProcessor
						currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
						processedBeans.add(ppName);
						reiterate = true;
					}
				}
				sortPostProcessors(currentRegistryProcessors, beanFactory);
				registryProcessors.addAll(currentRegistryProcessors);
				// 根据所有的bean定义，postProcessBeanFactory处理
				invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());
				currentRegistryProcessors.clear();
			}

			// 统一处理registryProcessors的BeanDefinitionRegistryPostProcessor（父类的方法还未执行）
			invokeBeanFactoryPostProcessors(registryProcessors, beanFactory);
			invokeBeanFactoryPostProcessors(regularPostProcessors, beanFactory);
		}

		else {
			// Invoke factory processors registered with the context ins tance.
			invokeBeanFactoryPostProcessors(beanFactoryPostProcessors, beanFactory);
		}

		// Do not initialize FactoryBeans here: We need to leave all regular beans
		// uninitialized to let the bean factory post-processors apply to them!
		String[] postProcessorNames =
				beanFactory.getBeanNamesForType(BeanFactoryPostProcessor.class, true, false);

		// Separate between BeanFactoryPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		List<BeanFactoryPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		List<String> orderedPostProcessorNames = new ArrayList<>();
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		for (String ppName : postProcessorNames) {
			//如果这个Bean被执行过了，跳过
			if (processedBeans.contains(ppName)) {
				// skip - already processed in first phase above
			}
			//如果实现了PriorityOrdered接口，加入到priorityOrderedPostProcessors
			else if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				priorityOrderedPostProcessors.add(beanFactory.getBean(ppName, BeanFactoryPostProcessor.class));
			}
			//如果实现了Ordered接口，加入到orderedPostProcessorNames
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			//如果既没有实现PriorityOrdered，也没有实现Ordered。加入到nonOrderedPostProcessorNames
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// 继续排序处理
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		//执行priorityOrderedPostProcessors
		invokeBeanFactoryPostProcessors(priorityOrderedPostProcessors, beanFactory);

		// 执行实现了Ordered接口的BeanFactoryPostProcessor
		List<BeanFactoryPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		for (String postProcessorName : orderedPostProcessorNames) {
			orderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		sortPostProcessors(orderedPostProcessors, beanFactory);
		invokeBeanFactoryPostProcessors(orderedPostProcessors, beanFactory);

		// 执行既没有实现PriorityOrdered接口，也没有实现Ordered接口的BeanFactoryPostProcessor
		List<BeanFactoryPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		for (String postProcessorName : nonOrderedPostProcessorNames) {
			nonOrderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		invokeBeanFactoryPostProcessors(nonOrderedPostProcessors, beanFactory);

		// Clear cached merged bean definitions since the post-processors might have
		// modified the original metadata, e.g. replacing placeholders in values...
		beanFactory.clearMetadataCache();
	}

	public static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, AbstractApplicationContext applicationContext) {

		// 获取所有实现BeanPostProcessor 全类名
		String[] postProcessorNames = beanFactory.getBeanNamesForType(BeanPostProcessor.class, true, false);

		// Register BeanPostProcessorChecker that logs an info message when
		// a bean is not eligible for getting processed by all BeanPostProcessors.
		int beanProcessorTargetCount = beanFactory.getBeanPostProcessorCount() + 1 + postProcessorNames.length;
		// 把bean的后置处理器保存到列表中
		beanFactory.addBeanPostProcessor(new BeanPostProcessorChecker(beanFactory, beanProcessorTargetCount));
		// 根据不同类型的bean后置处理器，定义临时列表，处理排序
		// Ordered, and the rest.
		List<BeanPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		List<BeanPostProcessor> internalPostProcessors = new ArrayList<>();
		List<String> orderedPostProcessorNames = new ArrayList<>();
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		// 循环遍历所有的bean后置处理器，根据不同类型，加入到不同的列表中，会处理PriorityOrdered的逻辑并记录临时列表
		for (String ppName : postProcessorNames) {
			if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) { // 是否实现PriorityOrdered，优先优先级最高，优先实例化
				BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class); // 实例化
				priorityOrderedPostProcessors.add(pp);
				if (pp instanceof MergedBeanDefinitionPostProcessor) {
					internalPostProcessors.add(pp);
				}
			}
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) { // 是否实现Ordered
				orderedPostProcessorNames.add(ppName);
			}
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}
		// 对priorityOrdered临时列表进行排序
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		// 注册bean后置处理器
		registerBeanPostProcessors(beanFactory, priorityOrderedPostProcessors);
		// 处理实现Ordered的逻辑并记录临时列表
		List<BeanPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		for (String ppName : orderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class); // 实例化
			orderedPostProcessors.add(pp);
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		// 排序并注册
		sortPostProcessors(orderedPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, orderedPostProcessors);

		// 处理常规bean后置处理器
		List<BeanPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		for (String ppName : nonOrderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			nonOrderedPostProcessors.add(pp);
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		// 注册
		registerBeanPostProcessors(beanFactory, nonOrderedPostProcessors);
		// Finally, re-register all internal BeanPostProcessors.
		sortPostProcessors(internalPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, internalPostProcessors);

		// Re-register post-processor for detecting inner beans as ApplicationListeners,
		// moving it to the end of the processor chain (for picking up proxies etc).
		beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(applicationContext));
	}

	/**
	 * Load and sort the post-processors of the specified type.
	 * @param beanFactory the bean factory to use
	 * @param beanPostProcessorType the post-processor type
	 * @param <T> the post-processor type
	 * @return a list of sorted post-processors for the specified type
	 */
	static <T extends BeanPostProcessor> List<T> loadBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, Class<T> beanPostProcessorType) {

		String[] postProcessorNames = beanFactory.getBeanNamesForType(beanPostProcessorType, true, false);
		List<T> postProcessors = new ArrayList<>();
		for (String ppName : postProcessorNames) {
			postProcessors.add(beanFactory.getBean(ppName, beanPostProcessorType));
		}
		sortPostProcessors(postProcessors, beanFactory);
		return postProcessors;

	}

	/**
	 * Selectively invoke {@link MergedBeanDefinitionPostProcessor} instances
	 * registered in the specified bean factory, resolving bean definitions as
	 * well as any inner bean definitions that they may contain.
	 * @param beanFactory the bean factory to use
	 */
	static void invokeMergedBeanDefinitionPostProcessors(DefaultListableBeanFactory beanFactory) {
		new MergedBeanDefinitionPostProcessorInvoker(beanFactory).invokeMergedBeanDefinitionPostProcessors();
	}

	private static void sortPostProcessors(List<?> postProcessors, ConfigurableListableBeanFactory beanFactory) {
		// Nothing to sort?
		if (postProcessors.size() <= 1) {
			return;
		}
		Comparator<Object> comparatorToUse = null;
		if (beanFactory instanceof DefaultListableBeanFactory dlbf) {
			comparatorToUse = dlbf.getDependencyComparator();
		}
		if (comparatorToUse == null) {
			comparatorToUse = OrderComparator.INSTANCE;
		}
		postProcessors.sort(comparatorToUse);
	}

	/**
	 * 根据所有的bean定义执行他们的后置处理器
	 */
	private static void invokeBeanDefinitionRegistryPostProcessors(
			Collection<? extends BeanDefinitionRegistryPostProcessor> postProcessors, BeanDefinitionRegistry registry, ApplicationStartup applicationStartup) {
		// 遍历全部的后置处理器
		for (BeanDefinitionRegistryPostProcessor postProcessor : postProcessors) {
			StartupStep postProcessBeanDefRegistry = applicationStartup.start("spring.context.beandef-registry.post-process")
					.tag("postProcessor", postProcessor::toString);
			// 调用后置处理器方法，（会有系统级别的后置处理器处理的逻辑）
			postProcessor.postProcessBeanDefinitionRegistry(registry);
			postProcessBeanDefRegistry.end();
		}
	}

	/**
	 * 处理器执行
	 */
	private static void invokeBeanFactoryPostProcessors(
			Collection<? extends BeanFactoryPostProcessor> postProcessors, ConfigurableListableBeanFactory beanFactory) {

		for (BeanFactoryPostProcessor postProcessor : postProcessors) {
			StartupStep postProcessBeanFactory = beanFactory.getApplicationStartup().start("spring.context.bean-factory.post-process")
					.tag("postProcessor", postProcessor::toString);
			// 执行前置处理，或后置处理等
			postProcessor.postProcessBeanFactory(beanFactory);
			postProcessBeanFactory.end();
		}
	}

	/**
	 * Register the given BeanPostProcessor beans.
	 */
	private static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<? extends BeanPostProcessor> postProcessors) {

		if (beanFactory instanceof AbstractBeanFactory abstractBeanFactory) {
			// Bulk addition is more efficient against our CopyOnWriteArrayList there
			abstractBeanFactory.addBeanPostProcessors(postProcessors);
		}
		else {
			for (BeanPostProcessor postProcessor : postProcessors) {
				beanFactory.addBeanPostProcessor(postProcessor);
			}
		}
	}


	/**
	 * BeanPostProcessor that logs an info message when a bean is created during
	 * BeanPostProcessor instantiation, i.e. when a bean is not eligible for
	 * getting processed by all BeanPostProcessors.
	 */
	private static final class BeanPostProcessorChecker implements BeanPostProcessor {

		private static final Log logger = LogFactory.getLog(BeanPostProcessorChecker.class);

		private final ConfigurableListableBeanFactory beanFactory;

		private final int beanPostProcessorTargetCount;

		public BeanPostProcessorChecker(ConfigurableListableBeanFactory beanFactory, int beanPostProcessorTargetCount) {
			this.beanFactory = beanFactory;
			this.beanPostProcessorTargetCount = beanPostProcessorTargetCount;
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			return bean;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			if (!(bean instanceof BeanPostProcessor) && !isInfrastructureBean(beanName) &&
					this.beanFactory.getBeanPostProcessorCount() < this.beanPostProcessorTargetCount) {
				if (logger.isInfoEnabled()) {
					logger.info("Bean '" + beanName + "' of type [" + bean.getClass().getName() +
							"] is not eligible for getting processed by all BeanPostProcessors " +
							"(for example: not eligible for auto-proxying)");
				}
			}
			return bean;
		}

		private boolean isInfrastructureBean(@Nullable String beanName) {
			if (beanName != null && this.beanFactory.containsBeanDefinition(beanName)) {
				BeanDefinition bd = this.beanFactory.getBeanDefinition(beanName);
				return (bd.getRole() == BeanDefinition.ROLE_INFRASTRUCTURE);
			}
			return false;
		}
	}


	private static final class MergedBeanDefinitionPostProcessorInvoker {

		private final DefaultListableBeanFactory beanFactory;

		private MergedBeanDefinitionPostProcessorInvoker(DefaultListableBeanFactory beanFactory) {
			this.beanFactory = beanFactory;
		}

		private void invokeMergedBeanDefinitionPostProcessors() {
			List<MergedBeanDefinitionPostProcessor> postProcessors = PostProcessorRegistrationDelegate.loadBeanPostProcessors(
					this.beanFactory, MergedBeanDefinitionPostProcessor.class);
			for (String beanName : this.beanFactory.getBeanDefinitionNames()) {
				RootBeanDefinition bd = (RootBeanDefinition) this.beanFactory.getMergedBeanDefinition(beanName);
				Class<?> beanType = resolveBeanType(bd);
				postProcessRootBeanDefinition(postProcessors, beanName, beanType, bd);
				bd.markAsPostProcessed();
			}
			registerBeanPostProcessors(this.beanFactory, postProcessors);
		}

		private void postProcessRootBeanDefinition(List<MergedBeanDefinitionPostProcessor> postProcessors,
				String beanName, Class<?> beanType, RootBeanDefinition bd) {

			BeanDefinitionValueResolver valueResolver = new BeanDefinitionValueResolver(this.beanFactory, beanName, bd);
			postProcessors.forEach(postProcessor -> postProcessor.postProcessMergedBeanDefinition(bd, beanType, beanName));
			for (PropertyValue propertyValue : bd.getPropertyValues().getPropertyValueList()) {
				Object value = propertyValue.getValue();
				if (value instanceof AbstractBeanDefinition innerBd) {
					Class<?> innerBeanType = resolveBeanType(innerBd);
					resolveInnerBeanDefinition(valueResolver, innerBd, (innerBeanName, innerBeanDefinition)
							-> postProcessRootBeanDefinition(postProcessors, innerBeanName, innerBeanType, innerBeanDefinition));
				}
			}
			for (ValueHolder valueHolder : bd.getConstructorArgumentValues().getIndexedArgumentValues().values()) {
				Object value = valueHolder.getValue();
				if (value instanceof AbstractBeanDefinition innerBd) {
					Class<?> innerBeanType = resolveBeanType(innerBd);
					resolveInnerBeanDefinition(valueResolver, innerBd, (innerBeanName, innerBeanDefinition)
							-> postProcessRootBeanDefinition(postProcessors, innerBeanName, innerBeanType, innerBeanDefinition));
				}
			}
		}

		private void resolveInnerBeanDefinition(BeanDefinitionValueResolver valueResolver, BeanDefinition innerBeanDefinition,
				BiConsumer<String, RootBeanDefinition> resolver) {

			valueResolver.resolveInnerBean(null, innerBeanDefinition, (name, rbd) -> {
				resolver.accept(name, rbd);
				return Void.class;
			});
		}

		private Class<?> resolveBeanType(AbstractBeanDefinition bd) {
			if (!bd.hasBeanClass()) {
				try {
					bd.resolveBeanClass(this.beanFactory.getBeanClassLoader());
				}
				catch (ClassNotFoundException ex) {
					// ignore
				}
			}
			return bd.getResolvableType().toClass();
		}
	}

}

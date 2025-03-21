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

package org.springframework.beans.factory.support;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyAccessorUtils;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.Aware;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.AutowiredPropertyMarker;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.ReflectionUtils.MethodCallback;
import org.springframework.util.StringUtils;
import org.springframework.util.function.ThrowingSupplier;

/**
 * Abstract bean factory superclass that implements default bean creation,
 * with the full capabilities specified by the {@link RootBeanDefinition} class.
 * Implements the {@link org.springframework.beans.factory.config.AutowireCapableBeanFactory}
 * interface in addition to AbstractBeanFactory's {@link #createBean} method.
 *
 * <p>Provides bean creation (with constructor resolution), property population,
 * wiring (including autowiring), and initialization. Handles runtime bean
 * references, resolves managed collections, calls initialization methods, etc.
 * Supports autowiring constructors, properties by name, and properties by type.
 *
 * <p>The main template method to be implemented by subclasses is
 * {@link #resolveDependency(DependencyDescriptor, String, Set, TypeConverter)},
 * used for autowiring by type. In case of a factory which is capable of searching
 * its bean definitions, matching beans will typically be implemented through such
 * a search. For other factory styles, simplified matching algorithms can be implemented.
 *
 * <p>Note that this class does <i>not</i> assume or implement bean definition
 * registry capabilities. See {@link DefaultListableBeanFactory} for an implementation
 * of the {@link org.springframework.beans.factory.ListableBeanFactory} and
 * {@link BeanDefinitionRegistry} interfaces, which represent the API and SPI
 * view of such a factory, respectively.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Chris Beams
 * @author Sam Brannen
 * @author Phillip Webb
 * @since 13.02.2004
 * @see RootBeanDefinition
 * @see DefaultListableBeanFactory
 * @see BeanDefinitionRegistry
 */
public abstract class AbstractAutowireCapableBeanFactory extends AbstractBeanFactory
		implements AutowireCapableBeanFactory {

	/** Strategy for creating bean instances. */
	private InstantiationStrategy instantiationStrategy;

	/** Resolver strategy for method parameter names. */
	@Nullable
	private ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

	/** Whether to automatically try to resolve circular references between beans. */
	private boolean allowCircularReferences = true;

	/**
	 * Whether to resort to injecting a raw bean instance in case of circular reference,
	 * even if the injected bean eventually got wrapped.
	 */
	private boolean allowRawInjectionDespiteWrapping = false;

	/**
	 * Dependency types to ignore on dependency check and autowire, as Set of
	 * Class objects: for example, String. Default is none.
	 */
	private final Set<Class<?>> ignoredDependencyTypes = new HashSet<>();

	/**
	 * Dependency interfaces to ignore on dependency check and autowire, as Set of
	 * Class objects. By default, only the BeanFactory interface is ignored.
	 */
	private final Set<Class<?>> ignoredDependencyInterfaces = new HashSet<>();

	/**
	 * The name of the currently created bean, for implicit dependency registration
	 * on getBean etc invocations triggered from a user-specified Supplier callback.
	 */
	private final NamedThreadLocal<String> currentlyCreatedBean = new NamedThreadLocal<>("Currently created bean");

	/** Cache of unfinished FactoryBean instances: FactoryBean name to BeanWrapper. */
	private final ConcurrentMap<String, BeanWrapper> factoryBeanInstanceCache = new ConcurrentHashMap<>();

	/** Cache of candidate factory methods per factory class. */
	private final ConcurrentMap<Class<?>, Method[]> factoryMethodCandidateCache = new ConcurrentHashMap<>();

	/** Cache of filtered PropertyDescriptors: bean Class to PropertyDescriptor array. */
	private final ConcurrentMap<Class<?>, PropertyDescriptor[]> filteredPropertyDescriptorsCache =
			new ConcurrentHashMap<>();


	/**
	 * Create a new AbstractAutowireCapableBeanFactory.
	 */
	public AbstractAutowireCapableBeanFactory() {
		super();
		ignoreDependencyInterface(BeanNameAware.class);
		ignoreDependencyInterface(BeanFactoryAware.class);
		ignoreDependencyInterface(BeanClassLoaderAware.class);
		// 在构造中创建了CGLIB的实例化策略类（见此类mbdToUse.prepareMethodOverrides()方法有初始化标志位，供实例化时候根据标志位选择不同实例化方式）
		// lookup-method replace-method
		this.instantiationStrategy = new CglibSubclassingInstantiationStrategy();
	}

	/**
	 * Create a new AbstractAutowireCapableBeanFactory with the given parent.
	 * @param parentBeanFactory parent bean factory, or {@code null} if none
	 */
	public AbstractAutowireCapableBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		this();
		setParentBeanFactory(parentBeanFactory);
	}


	/**
	 * Set the instantiation strategy to use for creating bean instances.
	 * Default is CglibSubclassingInstantiationStrategy.
	 * @see CglibSubclassingInstantiationStrategy
	 */
	public void setInstantiationStrategy(InstantiationStrategy instantiationStrategy) {
		this.instantiationStrategy = instantiationStrategy;
	}

	/**
	 * Return the instantiation strategy to use for creating bean instances.
	 */
	public InstantiationStrategy getInstantiationStrategy() {
		return this.instantiationStrategy;
	}

	/**
	 * Set the ParameterNameDiscoverer to use for resolving method parameter
	 * names if needed (e.g. for constructor names).
	 * <p>Default is a {@link DefaultParameterNameDiscoverer}.
	 */
	public void setParameterNameDiscoverer(@Nullable ParameterNameDiscoverer parameterNameDiscoverer) {
		this.parameterNameDiscoverer = parameterNameDiscoverer;
	}

	/**
	 * Return the ParameterNameDiscoverer to use for resolving method parameter
	 * names if needed.
	 */
	@Nullable
	public ParameterNameDiscoverer getParameterNameDiscoverer() {
		return this.parameterNameDiscoverer;
	}

	/**
	 * Set whether to allow circular references between beans - and automatically
	 * try to resolve them.
	 * <p>Note that circular reference resolution means that one of the involved beans
	 * will receive a reference to another bean that is not fully initialized yet.
	 * This can lead to subtle and not-so-subtle side effects on initialization;
	 * it does work fine for many scenarios, though.
	 * <p>Default is "true". Turn this off to throw an exception when encountering
	 * a circular reference, disallowing them completely.
	 * <p><b>NOTE:</b> It is generally recommended to not rely on circular references
	 * between your beans. Refactor your application logic to have the two beans
	 * involved delegate to a third bean that encapsulates their common logic.
	 */
	public void setAllowCircularReferences(boolean allowCircularReferences) {
		this.allowCircularReferences = allowCircularReferences;
	}

	/**
	 * Return whether to allow circular references between beans.
	 * @since 5.3.10
	 * @see #setAllowCircularReferences
	 */
	public boolean isAllowCircularReferences() {
		return this.allowCircularReferences;
	}

	/**
	 * Set whether to allow the raw injection of a bean instance into some other
	 * bean's property, despite the injected bean eventually getting wrapped
	 * (for example, through AOP auto-proxying).
	 * <p>This will only be used as a last resort in case of a circular reference
	 * that cannot be resolved otherwise: essentially, preferring a raw instance
	 * getting injected over a failure of the entire bean wiring process.
	 * <p>Default is "false", as of Spring 2.0. Turn this on to allow for non-wrapped
	 * raw beans injected into some of your references, which was Spring 1.2's
	 * (arguably unclean) default behavior.
	 * <p><b>NOTE:</b> It is generally recommended to not rely on circular references
	 * between your beans, in particular with auto-proxying involved.
	 * @see #setAllowCircularReferences
	 */
	public void setAllowRawInjectionDespiteWrapping(boolean allowRawInjectionDespiteWrapping) {
		this.allowRawInjectionDespiteWrapping = allowRawInjectionDespiteWrapping;
	}

	/**
	 * Return whether to allow the raw injection of a bean instance.
	 * @since 5.3.10
	 * @see #setAllowRawInjectionDespiteWrapping
	 */
	public boolean isAllowRawInjectionDespiteWrapping() {
		return this.allowRawInjectionDespiteWrapping;
	}

	/**
	 * Ignore the given dependency type for autowiring:
	 * for example, String. Default is none.
	 */
	public void ignoreDependencyType(Class<?> type) {
		this.ignoredDependencyTypes.add(type);
	}

	/**
	 * Ignore the given dependency interface for autowiring.
	 * <p>This will typically be used by application contexts to register
	 * dependencies that are resolved in other ways, like BeanFactory through
	 * BeanFactoryAware or ApplicationContext through ApplicationContextAware.
	 * <p>By default, only the BeanFactoryAware interface is ignored.
	 * For further types to ignore, invoke this method for each type.
	 * @see org.springframework.beans.factory.BeanFactoryAware
	 * @see org.springframework.context.ApplicationContextAware
	 */
	public void ignoreDependencyInterface(Class<?> ifc) {
		this.ignoredDependencyInterfaces.add(ifc);
	}

	@Override
	public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
		super.copyConfigurationFrom(otherFactory);
		if (otherFactory instanceof AbstractAutowireCapableBeanFactory otherAutowireFactory) {
			this.instantiationStrategy = otherAutowireFactory.instantiationStrategy;
			this.allowCircularReferences = otherAutowireFactory.allowCircularReferences;
			this.ignoredDependencyTypes.addAll(otherAutowireFactory.ignoredDependencyTypes);
			this.ignoredDependencyInterfaces.addAll(otherAutowireFactory.ignoredDependencyInterfaces);
		}
	}


	//-------------------------------------------------------------------------
	// Typical methods for creating and populating external bean instances
	//-------------------------------------------------------------------------

	@Override
	@SuppressWarnings("unchecked")
	public <T> T createBean(Class<T> beanClass) throws BeansException {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new CreateFromClassBeanDefinition(beanClass);
		bd.setScope(SCOPE_PROTOTYPE);
		bd.allowCaching = ClassUtils.isCacheSafe(beanClass, getBeanClassLoader());
		return (T) createBean(beanClass.getName(), bd, null);
	}

	@Override
	public void autowireBean(Object existingBean) {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(ClassUtils.getUserClass(existingBean));
		bd.setScope(SCOPE_PROTOTYPE);
		bd.allowCaching = ClassUtils.isCacheSafe(bd.getBeanClass(), getBeanClassLoader());
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(bd.getBeanClass().getName(), bd, bw);
	}

	@Override
	public Object configureBean(Object existingBean, String beanName) throws BeansException {
		markBeanAsCreated(beanName);
		BeanDefinition mbd = getMergedBeanDefinition(beanName);
		RootBeanDefinition bd = null;
		if (mbd instanceof RootBeanDefinition rbd) {
			bd = (rbd.isPrototype() ? rbd : rbd.cloneBeanDefinition());
		}
		if (bd == null) {
			bd = new RootBeanDefinition(mbd);
		}
		if (!bd.isPrototype()) {
			bd.setScope(SCOPE_PROTOTYPE);
			bd.allowCaching = ClassUtils.isCacheSafe(ClassUtils.getUserClass(existingBean), getBeanClassLoader());
		}
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(beanName, bd, bw);
		return initializeBean(beanName, existingBean, bd);
	}


	//-------------------------------------------------------------------------
	// Specialized methods for fine-grained control over the bean lifecycle
	//-------------------------------------------------------------------------

	@Override
	public Object createBean(Class<?> beanClass, int autowireMode, boolean dependencyCheck) throws BeansException {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass, autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		return createBean(beanClass.getName(), bd, null);
	}

	@Override
	public Object autowire(Class<?> beanClass, int autowireMode, boolean dependencyCheck) throws BeansException {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass, autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		if (bd.getResolvedAutowireMode() == AUTOWIRE_CONSTRUCTOR) {
			return autowireConstructor(beanClass.getName(), bd, null, null).getWrappedInstance();
		}
		else {
			Object bean = getInstantiationStrategy().instantiate(bd, null, this);
			populateBean(beanClass.getName(), bd, new BeanWrapperImpl(bean));
			return bean;
		}
	}

	@Override
	public void autowireBeanProperties(Object existingBean, int autowireMode, boolean dependencyCheck)
			throws BeansException {

		if (autowireMode == AUTOWIRE_CONSTRUCTOR) {
			throw new IllegalArgumentException("AUTOWIRE_CONSTRUCTOR not supported for existing bean instance");
		}
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd =
				new RootBeanDefinition(ClassUtils.getUserClass(existingBean), autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(bd.getBeanClass().getName(), bd, bw);
	}

	@Override
	public void applyBeanPropertyValues(Object existingBean, String beanName) throws BeansException {
		markBeanAsCreated(beanName);
		BeanDefinition bd = getMergedBeanDefinition(beanName);
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		applyPropertyValues(beanName, bd, bw, bd.getPropertyValues());
	}

	@Override
	public Object initializeBean(Object existingBean, String beanName) {
		return initializeBean(beanName, existingBean, null);
	}

	@Override
	public Object applyBeanPostProcessorsBeforeInitialization(Object existingBean, String beanName)
			throws BeansException {

		Object result = existingBean;
		for (BeanPostProcessor processor : getBeanPostProcessors()) { // 获取所有的bean后置处理器并遍历
			Object current = processor.postProcessBeforeInitialization(result, beanName); // 执行扩展点
			if (current == null) {
				return result;
			}
			result = current;
		}
		return result;
	}
	// 调用applyBeanPostProcessorsAfterInitialization扩展点，代理类型
	@Override
	public Object applyBeanPostProcessorsAfterInitialization(Object existingBean, String beanName)
			throws BeansException {
		Object result = existingBean;
		for (BeanPostProcessor processor : getBeanPostProcessors()) { // 获取所有的bean后置处理器并遍历
			// AbstractAutoProxyCreator处理代理
			Object current = processor.postProcessAfterInitialization(result, beanName);
			if (current == null) {
				return result;
			}
			result = current;
		}
		return result;
	}

	@Override
	public void destroyBean(Object existingBean) {
		new DisposableBeanAdapter(existingBean, getBeanPostProcessorCache().destructionAware).destroy();
	}


	//-------------------------------------------------------------------------
	// Delegate methods for resolving injection points
	//-------------------------------------------------------------------------

	@Override
	public Object resolveBeanByName(String name, DependencyDescriptor descriptor) {
		InjectionPoint previousInjectionPoint = ConstructorResolver.setCurrentInjectionPoint(descriptor);
		try {
			return getBean(name, descriptor.getDependencyType());
		}
		finally {
			ConstructorResolver.setCurrentInjectionPoint(previousInjectionPoint);
		}
	}

	@Override
	@Nullable
	public Object resolveDependency(DependencyDescriptor descriptor, @Nullable String requestingBeanName) throws BeansException {
		return resolveDependency(descriptor, requestingBeanName, null, null);
	}


	//---------------------------------------------------------------------
	// Implementation of relevant AbstractBeanFactory template methods
	//---------------------------------------------------------------------

	/**
	 * Central method of this class: creates a bean instance,
	 * populates the bean instance, applies post-processors, etc.
	 * @see #doCreateBean
	 */
	@Override
	protected Object createBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args)
			throws BeanCreationException {

		if (logger.isTraceEnabled()) {
			logger.trace("Creating instance of bean '" + beanName + "'");
		}
		RootBeanDefinition mbdToUse = mbd; // 获取bean定义

		// 解析bean信息得到类信息
		// 解析失败会抛出异常
		// Make sure bean class is actually resolved at this point, and
		// clone the bean definition in case of a dynamically resolved Class
		// which cannot be stored in the shared merged bean definition.
		// 锁定class，根据设置的class属性或者根据className来解析class
		// 里面封装调用了ClassUtils.forName(),进去有调用Class.forName()
		/** 核心代码： Class.forName() */
		Class<?> resolvedClass = resolveBeanClass(mbd, beanName);
		// 判断是否存在bean类信息不存在则进行解析，拿到了就set到Bean定义中
		// 进行条件筛选，重新赋值RootBeanDefinition,并设置BeanClass属性
		if (resolvedClass != null && !mbd.hasBeanClass() && mbd.getBeanClassName() != null) {
			// 重新创建一个RootBeanDefinition对象
			mbdToUse = new RootBeanDefinition(mbd);
			// 设置BeanClass属性值
			mbdToUse.setBeanClass(resolvedClass);
		}

		// 判断bean定义中是否存在方法重写校验，spring允许通过配置决定使用哪种实现
		// Prepare method overrides.
		// 验证及准备覆盖的方法,lookup-method  replace-method，当需要创建的bean对象中包含了lookup-method和replace-method标签的时候，会产生覆盖操作
		// 		此处设置标志位：mo.setOverloaded(false); 后续实例化的时候，在CglibSubclassingInstantiationStrategy中有根据标志位做不同拦截器处理，
		/**     在new ApplicationContext的时候，里面的生命周期里面有invokeBFPP(),该方法里面第一次调用了getBean()方法，*/
		//      在doCreateBean()里面的createBeanInstance()里面的 instantiateBean()里面的 getInstantiationStrategy().instantiate(mbd, beanName, this)代码
		//      跳转到SimpleInstantiationStrategy.instantiate()方法，判断是否有覆盖方法，然后通过 return instantiateWithMethodInjection(bd, beanName, owner)
		//      调用了子类CglibSubclassingInstantiationStrategy对象中的instantiateWithMethodInjection()方法【请按CTRL+ALT+B进入】
		//      里面return new CglibSubclassCreator(bd, owner).instantiate(ctor, args);其中instantiate()方法创建了CGLIB代理
		//      【请对比CGLIB的使用方式步骤1.设置super，2设置subClass，3设置callback】，
		//      然后直接将CGLIB代理生成的代理对象返回了，然后就逐步退出doCreateBean()，createBean(),doGetBean(),getBean()了；
		//      问： 那么，代理对象的callback设置的Interceptor的真正拦截方法在哪里调用的呢？
		//      答： 在调用被lookup-method指定的方法的时候，拦截该方法执行代理方法。如
		//          <bean id="apple" class="com.mashibing.methodOverrides.lookup.Apple"></bean>
		//    		<bean id="banana" class="com.mashibing.methodOverrides.lookup.Banana"></bean>
		//          <bean id="fruitplate1" class="com.mashibing.methodOverrides.lookup.FruitPlate">
		//		        <lookup-method name="getFruit" bean="apple"></lookup-method>
		//		    </bean>
		/**     在调用getBean("fruitplate1")的时候，里面的一级缓存已经有该实例了，另外也包括apple实例和banana实例【apple/banana此时设置是单例】。
		 *      在fruitplate1的里面有key：fruitplate1，value:CGLIB的代理实例(里面包括设置的三个callback)，此处拿到fruitplate1单例对象就直接返回了 */
		//      然后在执行fruitplate1的getFruit()方法时候，会直接调用callback设置的Interceptor的真正拦截方法：即：
		//      CglibSubclassingInstantiationStrategy.LookupOverrideMethodInterceptor.intercept()方法的this.owner.getBean(lo.getBeanName())
		//      因为使用lo.getBeanName()作为beanName【此处例子beanName为apple】重新调用了getBean()方法，然后从一级缓存拿到apple，调用了apple对象中的方法。
		//      问： 此时将apple/banana改为原型模式，会怎么样？
		/**     答： 因为是原型模式，所以在new applicationContext()的时候，没有在一级缓存放apple/banana，只有fruitplate1，
		 *      然后在getBean("fruitplate1")的时候，能从一级缓存拿到fruitplate1，然后继续调用fruitplate1.getFruit()方法，
		 *      此时直接跳转intercept()方法，其中getBean(apple)从一级缓存拿不到数据【因为一级缓存singletonObjects只放单例对象】，
		 *      会继续走bean的创建流程，然后以原型方式创建apple实例。
		 *
		 *      通过CGLIB拦截器的方式，每次需要的时候直接创建新对象，而不是把原型的对象在第一次用的时候实例化一个到缓存里面去，每次使用都是同一个对象。
		 *      三种情况：
		 *          1.不使用lookup-method标签，banana是原型，直接使用<property name="banana" ref="banana"></>，
		 *          		不使用拦截器，单例的fruitplate1里面DI注入了原型的banana一个实例，每次使用banana都是同一个实例。
		 *          2.使用lookup-method标签，banana是单例，
		 *          		使用了拦截器，但是因为缓存了banana到一级缓存，所以每次DI也是同一个实例。
		 *          3.使用lookup-method标签，banana是原型：
		 *          		使用了拦截器，但是因为一级缓存没有banana，每次DI以原型方式新建一个实例。
		 *      考虑：
		 *      	invokeBFPP()中ConfigurationClassPostProcessor.ppBF()中下面这行代码做什么的？为什么使用CGLIB代理？
		 *      	//TODO   CGLIB,此处为什么处理CGLIB？跟@COnfiguration和@Bean注解有关
		 * 			enhanceConfigurationClasses(beanFactory);
		 * */
		// 		LookupOverrideMethodInterceptor 中获取bean然后调用了beanFactory.getBean()；另一个同理：
		// 		ReplaceOverrideMethodInterceptor中获取bean然后调用mr.reimplement(obj, method, args)
		// 用法:Spring第14篇：lookup-method和replaced-method比较陌生，怎么玩的: https://blog.csdn.net/chenzoff/article/details/124165865
		try {
			mbdToUse.prepareMethodOverrides();
		}
		catch (BeanDefinitionValidationException ex) {
			throw new BeanDefinitionStoreException(mbdToUse.getResourceDescription(),
					beanName, "Validation of method overrides failed", ex);
		}

		try {
			// 解析 Bean 初始化前的扩展点，是否能获取代理对象，否则返回 null
			// AOP解析相关操作，如果Bean定义包含AOP配置，根据配置创建代理对象，实例化前后都会解析根据类型不同逻辑不同（事务不需要解析Advice，所以这里不会有返回）

			// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
			// 给BeanPostProcessors一个机会来返回代理来替代真正的实例，应用实例化前的前置处理器,
			// 用户自定义动态代理的方式，针对于当前的被代理类需要经过标准的代理流程来创建对象
			// 如：此处可以实现自己通过代理定义生成bean来绕过doCreateBean()方法
			Object bean = resolveBeforeInstantiation(beanName, mbdToUse);
			if (bean != null) {
				return bean;
			}
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbdToUse.getResourceDescription(), beanName,
					"BeanPostProcessor before instantiation of bean failed", ex);
		}


		// doCreateBean()方法是真正创建bean实例的方法：实例化，填充属性，应用后置处理器等
		try {
			// 实际创建bean的调用
			/**
			 *  问：Spring有几种对象创建方式？
			 * 	  答：1.自定义BPP，生成代理对象；
			 * 		 2.通过反射
			 * 		 3.通过factoryMethod创建对象
			 * 		 	分 实例工厂 和 静态工厂
			 * 		 4.通过FactoryBean创建对象
			 * 		 	抽象出一个接口规范，所有对象必须通过getObject方法获取-----------------接口规范实现
			 * 		 5.通过Supplier创建
			 * 		 	用法和4有相通之处，随便定义一个创建对象的方法，不止局限于getObject------只是BeanDefinition的一个属性值
			 */
			Object beanInstance = doCreateBean(beanName, mbdToUse, args);
			if (logger.isTraceEnabled()) {
				logger.trace("Finished creating instance of bean '" + beanName + "'");
			}
			return beanInstance;
		}
		catch (BeanCreationException | ImplicitlyAppearedSingletonException ex) {
			// A previously detected exception with proper bean creation context already,
			// or illegal singleton state to be communicated up to DefaultSingletonBeanRegistry.
			throw ex;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(
					mbdToUse.getResourceDescription(), beanName, "Unexpected exception during bean creation", ex);
		}
	}

	/**
	 * Actually create the specified bean. Pre-creation processing has already happened
	 * at this point, e.g. checking {@code postProcessBeforeInstantiation} callbacks.
	 * <p>Differentiates between default bean instantiation, use of a
	 * factory method, and autowiring a constructor.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param args explicit arguments to use for constructor or factory method invocation
	 * @return a new instance of the bean
	 * @throws BeanCreationException if the bean could not be created
	 * @see #instantiateBean
	 * @see #instantiateUsingFactoryMethod
	 * @see #autowireConstructor
	 */
	protected Object doCreateBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args)
			throws BeanCreationException {

		// 判断是否为单例的，尝试缓存中获取实例
		// Instantiate the bean.
		// 这个beanWrapper是用来持有创建出来的bean对象的
		BeanWrapper instanceWrapper = null;
		// 获取factoryBean实例缓存
		if (mbd.isSingleton()) {
			// 如果是单例对象，从factorybean实例缓存中移除当前bean定义信息
			instanceWrapper = this.factoryBeanInstanceCache.remove(beanName);
		}
		// 没有就创建实例
		if (instanceWrapper == null) {
			// 根据执行bean使用对应的策略创建新的实例，如，工厂方法，构造函数主动注入、简单初始化
			/**
			 *  问：Spring有几种对象创建方式？
			 * 	  答：1.自定义BPP，生成代理对象；
			 * 		 2.通过反射
			 * 		 3.通过factoryMethod创建对象
			 * 		 	分 实例工厂 和 静态工厂
			 * 		 4.通过FactoryBean创建对象
			 * 		 	抽象出一个接口规范，所有对象必须通过getObject方法获取-----------------接口规范实现
			 * 		 5.通过Supplier创建,调用其get()方法
			 * 		 	用法和4有相通之处，随便定义一个创建对象的方法，不止局限于getObject------只是BeanDefinition的一个属性值
			 */
			/** 生成beanWrapper对象，问：1.请说出beanWrapper的作用，并说出一个使用案例，2.请说出五种bean的创建方式，以及每种方式如何实现的 */
			instanceWrapper = createBeanInstance(beanName, mbd, args);
			System.out.println("实例化完成：" + beanName); // 具体的实例化（反射，工厂@Bean）
		}
		// 从包装类中获取原始bean
		Object bean = instanceWrapper.getWrappedInstance();
		// 获取具体的bean对象的Class属性
		Class<?> beanType = instanceWrapper.getWrappedClass();
		// 如果不等于NullBean类型，那么修改目标类型
		if (beanType != NullBean.class) {
			mbd.resolvedTargetType = beanType;
		}

		// 避免多线程同时修改bean定义
		// Allow post-processors to modify the merged bean definition.
		// 允许beanPostProcessor去修改合并的beanDefinition
		/** 锁代码块里面：
		 * 		调用 MergedBeanDefinitionPostProcessor 回调（在这里完成相关方法的扫描及保存）:
		 * 		这一点其实在 Spring 当中已经是非常固定的一个套路了，即 Spring 中注解的作用流程一般都是先要通过扫描，然后将对应的方法或者类进行缓存，最后在适时的时候进行调用
		 *
		 * 			1.InitDestroyAnnotationBeanPostProcessor： 注册@postContruct，@PreDestroy注解，
		 * 			2.CommonAnnotationBeanPostProcessor :      以及@Resource，@EJB注解（Spring5.x也包括@WebServiceRef注解）
		 * 			3.AutowiredAnnotationBeanPostProcessor：   以及@Autowired，@Value注解
		 * 		调用在本方法exposedObject = initializeBean(beanName, exposedObject, mbd)
		 * 		调用的1900多行：applyBeanPostProcessorsBeforeInitialization()方法
		 *
		 *
		 *   	问：如果XML配置注入和注解配置注入有冲突，听谁的？还是报错？
		 *   	答：自己试一下。
		 *
		 * */
		synchronized (mbd.postProcessingLock) {
			// 判断是否被后置处理器处理器过
			if (!mbd.postProcessed) {
				try {
					// MergedBeanDefinitionPostProcessor后置处理器修改合并bean的定义
					// 调用applyMergedBeanDefinitionPostProcessors，来执行BeanDefinitionPostProcessor的postProcessMergedBeanDefinition
					// 后置处理器修改bean位置
					// aMBDPP()
					applyMergedBeanDefinitionPostProcessors(mbd, beanType, beanName);
				}
				catch (Throwable ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName,
							"Post-processing of merged bean definition failed", ex);
				}
				mbd.markAsPostProcessed();
			}
		}



		// 判断是否循环依赖（判断是否为单例，是否允许循环引用，是否正在创建，是的话放到三级缓存中）
		// Eagerly cache singletons to be able to resolve circular references
		// even when triggered by lifecycle interfaces like BeanFactoryAware.
		// 判断当前bean是否需要提前曝光：单例&允许循环依赖&当前bean正在创建中，检测循环依赖
		// this.allowCircularReferences 在refresh()里面的obtainRefreshBeanFactory()里面的customizeBeanFactory()里面设置
		boolean earlySingletonExposure = (mbd.isSingleton() && this.allowCircularReferences &&
				isSingletonCurrentlyInCreation(beanName));
		if (earlySingletonExposure) {
			if (logger.isTraceEnabled()) {
				logger.trace("Eagerly caching bean '" + beanName +
						"' to allow for resolving potential circular references");
			}
			// 放到三级缓存中
			// 为避免后期循环依赖，可以在bean初始化完成前将创建实例的ObjectFactory加入工厂
			addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, mbd, bean));
		}

		// Initialize the bean instance.
		// 初始化bean实例
		Object exposedObject = bean;
		try {
			// 属性赋值
			// 对bean的属性进行填充，将各个属性值注入，其中，可能存在依赖于其他bean的属性，则会递归初始化依赖的bean
			// 基本数据类型，引用数据类型，集合类型T[]/List/Map/Properties，类型转换
			populateBean(beanName, mbd, instanceWrapper);
			// 初始化bean，执行BeanPostProcessor的postProcessBeforeInitialization和postProcessAfterInitialization的扩展点获取代理对象
			// AOP解析相关操作，检查bean是否需要增强，同时存储到缓存中，实例化前后都会解析根据类型不同逻辑不同（事务不会解析通知Advice，但是会扫描@Tranxxx注解，会在这里扫描然后初筛精筛）
			exposedObject = initializeBean(beanName, exposedObject, mbd);
		}
		catch (Throwable ex) {
			if (ex instanceof BeanCreationException bce && beanName.equals(bce.getBeanName())) {
				throw bce;
			}
			else {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName, ex.getMessage(), ex);
			}
		}
		// 判断是否循环依赖（判断是否为单例，是否允许循环引用，是否正在创建）
		if (earlySingletonExposure) {
			// 已经确定是循环依赖了，尝试从三级缓存中获取早期对象
			Object earlySingletonReference = getSingleton(beanName, false);
			if (earlySingletonReference != null) {
				// 如果获取到了早期对象，则直接使用早期对象
				if (exposedObject == bean) {
					exposedObject = earlySingletonReference;
				}
				// 检查当前 bean 是否存在其他依赖它的 bean，如果有，则需要抛出一个 BeanCurrentlyInCreationException 异常，提示存在循环依赖问题
				else if (!this.allowRawInjectionDespiteWrapping && hasDependentBean(beanName)) {
					String[] dependentBeans = getDependentBeans(beanName);
					Set<String> actualDependentBeans = new LinkedHashSet<>(dependentBeans.length);
					for (String dependentBean : dependentBeans) {
						// 返回false说明依赖还没实例化好
						if (!removeSingletonIfCreatedForTypeCheckOnly(dependentBean)) {
							actualDependentBeans.add(dependentBean);
						}
					}
					// 因为bean创建后所依赖的bean一定是已经创建的
					// actualDependentBeans不为空则表示当前bean创建后其依赖的bean却没有全部创建完，也就是说存在循环依赖
					if (!actualDependentBeans.isEmpty()) {
						// 此处为出现循环引用而报错的提示信息
						throw new BeanCurrentlyInCreationException(beanName,
								"Bean with name '" + beanName + "' has been injected into other beans [" +
								StringUtils.collectionToCommaDelimitedString(actualDependentBeans) +
								"] in its raw version as part of a circular reference, but has eventually been " +
								"wrapped. This means that said other beans do not use the final version of the " +
								"bean. This is often the result of over-eager type matching - consider using " +
								"'getBeanNamesForType' with the 'allowEagerInit' flag turned off, for example.");
					}
				}
			}
		}

		// Register bean as disposable.
		try {
			// 注册bean对象，方便后续在容器销毁的时候销毁对象
			registerDisposableBeanIfNecessary(beanName, bean, mbd);
		}
		catch (BeanDefinitionValidationException ex) {
			throw new BeanCreationException(
					mbd.getResourceDescription(), beanName, "Invalid destruction signature", ex);
		}

		return exposedObject;
	}

	@Override
	@Nullable
	protected Class<?> predictBeanType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		Class<?> targetType = determineTargetType(beanName, mbd, typesToMatch);
		// Apply SmartInstantiationAwareBeanPostProcessors to predict the
		// eventual type after a before-instantiation shortcut.
		if (targetType != null && !mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			boolean matchingOnlyFactoryBean = typesToMatch.length == 1 && typesToMatch[0] == FactoryBean.class;
			for (SmartInstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().smartInstantiationAware) {
				Class<?> predicted = bp.predictBeanType(targetType, beanName);
				if (predicted != null &&
						(!matchingOnlyFactoryBean || FactoryBean.class.isAssignableFrom(predicted))) {
					return predicted;
				}
			}
		}
		return targetType;
	}

	/**
	 * Determine the target type for the given bean definition.
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param mbd the merged bean definition for the bean
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the type for the bean if determinable, or {@code null} otherwise
	 */
	@Nullable
	protected Class<?> determineTargetType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		Class<?> targetType = mbd.getTargetType();
		if (targetType == null) {
			if (mbd.getFactoryMethodName() != null) {
				targetType = getTypeForFactoryMethod(beanName, mbd, typesToMatch);
			}
			else {
				targetType = resolveBeanClass(mbd, beanName, typesToMatch);
				if (mbd.hasBeanClass()) {
					targetType = getInstantiationStrategy().getActualBeanClass(mbd, beanName, this);
				}
			}
			if (ObjectUtils.isEmpty(typesToMatch) || getTempClassLoader() == null) {
				mbd.resolvedTargetType = targetType;
			}
		}
		return targetType;
	}

	/**
	 * Determine the target type for the given bean definition which is based on
	 * a factory method. Only called if there is no singleton instance registered
	 * for the target bean already.
	 * <p>This implementation determines the type matching {@link #createBean}'s
	 * different creation strategies. As far as possible, we'll perform static
	 * type checking to avoid creation of the target bean.
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param mbd the merged bean definition for the bean
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the type for the bean if determinable, or {@code null} otherwise
	 * @see #createBean
	 */
	@Nullable
	protected Class<?> getTypeForFactoryMethod(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		ResolvableType cachedReturnType = mbd.factoryMethodReturnType;
		if (cachedReturnType != null) {
			return cachedReturnType.resolve();
		}

		Class<?> commonType = null;
		Method uniqueCandidate = mbd.factoryMethodToIntrospect;

		if (uniqueCandidate == null) {
			Class<?> factoryClass;
			boolean isStatic = true;

			String factoryBeanName = mbd.getFactoryBeanName();
			if (factoryBeanName != null) {
				if (factoryBeanName.equals(beanName)) {
					throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
							"factory-bean reference points back to the same bean definition");
				}
				// Check declared factory method return type on factory class.
				factoryClass = getType(factoryBeanName);
				isStatic = false;
			}
			else {
				// Check declared factory method return type on bean class.
				factoryClass = resolveBeanClass(mbd, beanName, typesToMatch);
			}

			if (factoryClass == null) {
				return null;
			}
			factoryClass = ClassUtils.getUserClass(factoryClass);

			// If all factory methods have the same return type, return that type.
			// Can't clearly figure out exact method due to type converting / autowiring!
			int minNrOfArgs =
					(mbd.hasConstructorArgumentValues() ? mbd.getConstructorArgumentValues().getArgumentCount() : 0);
			Method[] candidates = this.factoryMethodCandidateCache.computeIfAbsent(factoryClass,
					clazz -> ReflectionUtils.getUniqueDeclaredMethods(clazz, ReflectionUtils.USER_DECLARED_METHODS));

			for (Method candidate : candidates) {
				if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate) &&
						candidate.getParameterCount() >= minNrOfArgs) {
					// Declared type variables to inspect?
					if (candidate.getTypeParameters().length > 0) {
						try {
							// Fully resolve parameter names and argument values.
							ConstructorArgumentValues cav = mbd.getConstructorArgumentValues();
							Class<?>[] paramTypes = candidate.getParameterTypes();
							String[] paramNames = null;
							if (cav.containsNamedArgument()) {
								ParameterNameDiscoverer pnd = getParameterNameDiscoverer();
								if (pnd != null) {
									paramNames = pnd.getParameterNames(candidate);
								}
							}
							Set<ConstructorArgumentValues.ValueHolder> usedValueHolders = new HashSet<>(paramTypes.length);
							Object[] args = new Object[paramTypes.length];
							for (int i = 0; i < args.length; i++) {
								ConstructorArgumentValues.ValueHolder valueHolder = cav.getArgumentValue(
										i, paramTypes[i], (paramNames != null ? paramNames[i] : null), usedValueHolders);
								if (valueHolder == null) {
									valueHolder = cav.getGenericArgumentValue(null, null, usedValueHolders);
								}
								if (valueHolder != null) {
									args[i] = valueHolder.getValue();
									usedValueHolders.add(valueHolder);
								}
							}
							Class<?> returnType = AutowireUtils.resolveReturnTypeForFactoryMethod(
									candidate, args, getBeanClassLoader());
							uniqueCandidate = (commonType == null && returnType == candidate.getReturnType() ?
									candidate : null);
							commonType = ClassUtils.determineCommonAncestor(returnType, commonType);
							if (commonType == null) {
								// Ambiguous return types found: return null to indicate "not determinable".
								return null;
							}
						}
						catch (Throwable ex) {
							if (logger.isDebugEnabled()) {
								logger.debug("Failed to resolve generic return type for factory method: " + ex);
							}
						}
					}
					else {
						uniqueCandidate = (commonType == null ? candidate : null);
						commonType = ClassUtils.determineCommonAncestor(candidate.getReturnType(), commonType);
						if (commonType == null) {
							// Ambiguous return types found: return null to indicate "not determinable".
							return null;
						}
					}
				}
			}

			mbd.factoryMethodToIntrospect = uniqueCandidate;
			if (commonType == null) {
				return null;
			}
		}

		// Common return type found: all factory methods return same type. For a non-parameterized
		// unique candidate, cache the full type declaration context of the target factory method.
		cachedReturnType = (uniqueCandidate != null ?
				ResolvableType.forMethodReturnType(uniqueCandidate) : ResolvableType.forClass(commonType));
		mbd.factoryMethodReturnType = cachedReturnType;
		return cachedReturnType.resolve();
	}

	/**
	 * This implementation attempts to query the FactoryBean's generic parameter metadata
	 * if present to determine the object type. If not present, i.e. the FactoryBean is
	 * declared as a raw type, checks the FactoryBean's {@code getObjectType} method
	 * on a plain instance of the FactoryBean, without bean properties applied yet.
	 * If this doesn't return a type yet, and {@code allowInit} is {@code true} a
	 * full creation of the FactoryBean is used as fallback (through delegation to the
	 * superclass's implementation).
	 * <p>The shortcut check for a FactoryBean is only applied in case of a singleton
	 * FactoryBean. If the FactoryBean instance itself is not kept as singleton,
	 * it will be fully created to check the type of its exposed object.
	 */
	@Override
	protected ResolvableType getTypeForFactoryBean(String beanName, RootBeanDefinition mbd, boolean allowInit) {
		// Check if the bean definition itself has defined the type with an attribute
		ResolvableType result = getTypeForFactoryBeanFromAttributes(mbd);
		if (result != ResolvableType.NONE) {
			return result;
		}

		ResolvableType beanType =
				(mbd.hasBeanClass() ? ResolvableType.forClass(mbd.getBeanClass()) : ResolvableType.NONE);

		// For instance supplied beans try the target type and bean class
		if (mbd.getInstanceSupplier() != null) {
			result = getFactoryBeanGeneric(mbd.targetType);
			if (result.resolve() != null) {
				return result;
			}
			result = getFactoryBeanGeneric(beanType);
			if (result.resolve() != null) {
				return result;
			}
		}

		// Consider factory methods
		String factoryBeanName = mbd.getFactoryBeanName();
		String factoryMethodName = mbd.getFactoryMethodName();

		// Scan the factory bean methods
		if (factoryBeanName != null) {
			if (factoryMethodName != null) {
				// Try to obtain the FactoryBean's object type from its factory method
				// declaration without instantiating the containing bean at all.
				BeanDefinition factoryBeanDefinition = getBeanDefinition(factoryBeanName);
				Class<?> factoryBeanClass;
				if (factoryBeanDefinition instanceof AbstractBeanDefinition abstractBeanDefinition &&
						abstractBeanDefinition.hasBeanClass()) {
					factoryBeanClass = abstractBeanDefinition.getBeanClass();
				}
				else {
					RootBeanDefinition fbmbd = getMergedBeanDefinition(factoryBeanName, factoryBeanDefinition);
					factoryBeanClass = determineTargetType(factoryBeanName, fbmbd);
				}
				if (factoryBeanClass != null) {
					result = getTypeForFactoryBeanFromMethod(factoryBeanClass, factoryMethodName);
					if (result.resolve() != null) {
						return result;
					}
				}
			}
			// If not resolvable above and the referenced factory bean doesn't exist yet,
			// exit here - we don't want to force the creation of another bean just to
			// obtain a FactoryBean's object type...
			if (!isBeanEligibleForMetadataCaching(factoryBeanName)) {
				return ResolvableType.NONE;
			}
		}

		// If we're allowed, we can create the factory bean and call getObjectType() early
		if (allowInit) {
			FactoryBean<?> factoryBean = (mbd.isSingleton() ?
					getSingletonFactoryBeanForTypeCheck(beanName, mbd) :
					getNonSingletonFactoryBeanForTypeCheck(beanName, mbd));
			if (factoryBean != null) {
				// Try to obtain the FactoryBean's object type from this early stage of the instance.
				Class<?> type = getTypeForFactoryBean(factoryBean);
				if (type != null) {
					return ResolvableType.forClass(type);
				}
				// No type found for shortcut FactoryBean instance:
				// fall back to full creation of the FactoryBean instance.
				return super.getTypeForFactoryBean(beanName, mbd, true);
			}
		}

		if (factoryBeanName == null && mbd.hasBeanClass() && factoryMethodName != null) {
			// No early bean instantiation possible: determine FactoryBean's type from
			// static factory method signature or from class inheritance hierarchy...
			return getTypeForFactoryBeanFromMethod(mbd.getBeanClass(), factoryMethodName);
		}
		result = getFactoryBeanGeneric(beanType);
		if (result.resolve() != null) {
			return result;
		}
		return ResolvableType.NONE;
	}

	private ResolvableType getFactoryBeanGeneric(@Nullable ResolvableType type) {
		if (type == null) {
			return ResolvableType.NONE;
		}
		return type.as(FactoryBean.class).getGeneric();
	}

	/**
	 * Introspect the factory method signatures on the given bean class,
	 * trying to find a common {@code FactoryBean} object type declared there.
	 * @param beanClass the bean class to find the factory method on
	 * @param factoryMethodName the name of the factory method
	 * @return the common {@code FactoryBean} object type, or {@code null} if none
	 */
	private ResolvableType getTypeForFactoryBeanFromMethod(Class<?> beanClass, String factoryMethodName) {
		// CGLIB subclass methods hide generic parameters; look at the original user class.
		Class<?> factoryBeanClass = ClassUtils.getUserClass(beanClass);
		FactoryBeanMethodTypeFinder finder = new FactoryBeanMethodTypeFinder(factoryMethodName);
		ReflectionUtils.doWithMethods(factoryBeanClass, finder, ReflectionUtils.USER_DECLARED_METHODS);
		return finder.getResult();
	}

	/**
	 * Obtain a reference for early access to the specified bean,
	 * typically for the purpose of resolving a circular reference.
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param mbd the merged bean definition for the bean
	 * @param bean the raw bean instance
	 * @return the object to expose as bean reference
	 */
	protected Object getEarlyBeanReference(String beanName, RootBeanDefinition mbd, Object bean) {
		Object exposedObject = bean;
		if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			for (SmartInstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().smartInstantiationAware) {
				exposedObject = bp.getEarlyBeanReference(exposedObject, beanName);
			}
		}
		return exposedObject;
	}


	//---------------------------------------------------------------------
	// Implementation methods
	//---------------------------------------------------------------------

	/**
	 * Obtain a "shortcut" singleton FactoryBean instance to use for a
	 * {@code getObjectType()} call, without full initialization of the FactoryBean.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return the FactoryBean instance, or {@code null} to indicate
	 * that we couldn't obtain a shortcut FactoryBean instance
	 */
	@Nullable
	private FactoryBean<?> getSingletonFactoryBeanForTypeCheck(String beanName, RootBeanDefinition mbd) {
		synchronized (getSingletonMutex()) {
			BeanWrapper bw = this.factoryBeanInstanceCache.get(beanName);
			if (bw != null) {
				return (FactoryBean<?>) bw.getWrappedInstance();
			}
			Object beanInstance = getSingleton(beanName, false);
			if (beanInstance instanceof FactoryBean<?> factoryBean) {
				return factoryBean;
			}
			if (isSingletonCurrentlyInCreation(beanName) ||
					(mbd.getFactoryBeanName() != null && isSingletonCurrentlyInCreation(mbd.getFactoryBeanName()))) {
				return null;
			}

			Object instance;
			try {
				// Mark this bean as currently in creation, even if just partially.
				beforeSingletonCreation(beanName);
				// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
				instance = resolveBeforeInstantiation(beanName, mbd);
				if (instance == null) {
					bw = createBeanInstance(beanName, mbd, null);
					instance = bw.getWrappedInstance();
				}
			}
			catch (UnsatisfiedDependencyException ex) {
				// Don't swallow, probably misconfiguration...
				throw ex;
			}
			catch (BeanCreationException ex) {
				// Don't swallow a linkage error since it contains a full stacktrace on
				// first occurrence... and just a plain NoClassDefFoundError afterwards.
				if (ex.contains(LinkageError.class)) {
					throw ex;
				}
				// Instantiation failure, maybe too early...
				if (logger.isDebugEnabled()) {
					logger.debug("Bean creation exception on singleton FactoryBean type check: " + ex);
				}
				onSuppressedException(ex);
				return null;
			}
			finally {
				// Finished partial creation of this bean.
				afterSingletonCreation(beanName);
			}

			FactoryBean<?> fb = getFactoryBean(beanName, instance);
			if (bw != null) {
				this.factoryBeanInstanceCache.put(beanName, bw);
			}
			return fb;
		}
	}

	/**
	 * Obtain a "shortcut" non-singleton FactoryBean instance to use for a
	 * {@code getObjectType()} call, without full initialization of the FactoryBean.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return the FactoryBean instance, or {@code null} to indicate
	 * that we couldn't obtain a shortcut FactoryBean instance
	 */
	@Nullable
	private FactoryBean<?> getNonSingletonFactoryBeanForTypeCheck(String beanName, RootBeanDefinition mbd) {
		if (isPrototypeCurrentlyInCreation(beanName)) {
			return null;
		}

		Object instance;
		try {
			// Mark this bean as currently in creation, even if just partially.
			beforePrototypeCreation(beanName);
			// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
			instance = resolveBeforeInstantiation(beanName, mbd);
			if (instance == null) {
				BeanWrapper bw = createBeanInstance(beanName, mbd, null);
				instance = bw.getWrappedInstance();
			}
		}
		catch (UnsatisfiedDependencyException ex) {
			// Don't swallow, probably misconfiguration...
			throw ex;
		}
		catch (BeanCreationException ex) {
			// Instantiation failure, maybe too early...
			if (logger.isDebugEnabled()) {
				logger.debug("Bean creation exception on non-singleton FactoryBean type check: " + ex);
			}
			onSuppressedException(ex);
			return null;
		}
		finally {
			// Finished partial creation of this bean.
			afterPrototypeCreation(beanName);
		}

		return getFactoryBean(beanName, instance);
	}

	/**
	 * Apply MergedBeanDefinitionPostProcessors to the specified bean definition,
	 * invoking their {@code postProcessMergedBeanDefinition} methods.
	 * @param mbd the merged bean definition for the bean
	 * @param beanType the actual type of the managed bean instance
	 * @param beanName the name of the bean
	 * @see MergedBeanDefinitionPostProcessor#postProcessMergedBeanDefinition
	 */
	protected void applyMergedBeanDefinitionPostProcessors(RootBeanDefinition mbd, Class<?> beanType, String beanName) {
		for (MergedBeanDefinitionPostProcessor processor : getBeanPostProcessorCache().mergedDefinition) {
			processor.postProcessMergedBeanDefinition(mbd, beanType, beanName);
		}
	}

	/**
	 * 应用实例化前后处理器，解析指定 Bean 是否存在实例化前后处理器
	 * before-instantiation shortcut for the specified bean.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return the shortcut-determined bean instance, or {@code null} if none
	 */
	@Nullable
	protected Object resolveBeforeInstantiation(String beanName, RootBeanDefinition mbd) {
		Object bean = null;
		// 如果beforeInstantiationResolved值为null或者true，那么表示尚未被处理，进行后续的处理
		if (!Boolean.FALSE.equals(mbd.beforeInstantiationResolved)) {
			// Make sure bean class is actually resolved at this point.
			// 确认beanclass确实在此处进行处理
			// 判断当前mbd是否是合成的，只有在实现aop的时候synthetic的值才为true，并且是否实现了InstantiationAwareBeanPostProcessor接口
			// 如果有实现BPP【InstantiationAwareBeanPostProcessors】,在注册BPP的时候会生成自定义InstantiationAwareBPP实例。
			if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
				// 获取类型
				Class<?> targetType = determineTargetType(beanName, mbd);
				// 注意区分 before Instantiation 实例化 和 after Initialization 初始化
				if (targetType != null) {
					bean = applyBeanPostProcessorsBeforeInstantiation(targetType, beanName);
					if (bean != null) {
						// 问：bean的自定义创建，可以放在after里面吗？
						// 答：不行，因为有判断 bean != null ，才执行此行代码，所以代理生成bean只能在before里面。
						bean = applyBeanPostProcessorsAfterInitialization(bean, beanName);
					}
				}
			}
			// 是否解析了
			mbd.beforeInstantiationResolved = (bean != null);
		}
		return bean;
	}

	/**
	 * AOP通过该扩展点对bean方法拦截增强，InstantiationAwareBeanPostProcessor的postProcessBeforeInstantiation扩展点
	 * 实现在AbstractAutoProxyCreator中，用于创建代理对象，根据不同的类型可能无法创建代理对象
	 *
	 *
	 *
	 * @param beanClass the class of the bean to be instantiated
	 * @param beanName the name of the bean
	 * @return the bean object to use instead of a default instance of the target bean, or {@code null}
	 * @see InstantiationAwareBeanPostProcessor#postProcessBeforeInstantiation
	 */
	@Nullable
	protected Object applyBeanPostProcessorsBeforeInstantiation(Class<?> beanClass, String beanName) {
		for (InstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().instantiationAware) {
			Object result = bp.postProcessBeforeInstantiation(beanClass, beanName);
			if (result != null) {
				return result;
			}
		}
		return null;
	}

	/**
	 * Create a new instance for the specified bean, using an appropriate instantiation strategy:
	 * factory method, constructor autowiring, or simple instantiation.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @param args explicit arguments to use for constructor or factory method invocation
	 * @return a BeanWrapper for the new instance
	 * @see #obtainFromSupplier
	 * @see #instantiateUsingFactoryMethod
	 * @see #autowireConstructor
	 * @see #instantiateBean
	 */
	protected BeanWrapper createBeanInstance(String beanName, RootBeanDefinition mbd, @Nullable Object[] args) {
		// 解析bean信息得到类信息
		// Make sure bean class is actually resolved at this point.
		// 确认需要创建的bean实例的类可以实例化
		Class<?> beanClass = resolveBeanClass(mbd, beanName);
		// 确保class不为空，并且访问权限是public
		if (beanClass != null && !Modifier.isPublic(beanClass.getModifiers()) && !mbd.isNonPublicAccessAllowed()) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean class isn't public, and non-public access not allowed: " + beanClass.getName());
		}

		// Supplier用于创建bean实例的，可以是通过反射、工厂方法或者其他方式创建
		// 这里就是区别工厂方法实例化，和反射实例化的地方
		// 判断当前beanDefinition中是否包含实例供应器，此处相当于一个回调方法，利用回调方法来创建bean
		Supplier<?> instanceSupplier = mbd.getInstanceSupplier();
		// bean定义是否指定了instanceSupplier
		if (instanceSupplier != null) {
			/** 使用supplier创建bean */
			return obtainFromSupplier(instanceSupplier, beanName, mbd);
		}


		// 如果没有指定，但指定了工厂方法，会调用instantiateUsingFactoryMethod来创建bean实例
		if (mbd.getFactoryMethodName() != null) {
			/** 使用factoryMethod创建bean */
			return instantiateUsingFactoryMethod(beanName, mbd, args);
		}

		// 一个类可能有多个构造器，所以Spring得根据参数个数、类型确定需要调用的构造器
		// 在使用构造器创建实例后，Spring会将解析过后确定下来的构造器或工厂方法保存在缓存中，避免再次创建相同bean时再次解析
		/** 以下为反射创建Bean */

		// Shortcut when re-creating the same bean...
		// 标记下，防止重复创建同一个bean
		boolean resolved = false;
		// 是否需要自动装配
		boolean autowireNecessary = false;
		// 如果没有参数
		if (args == null) {
			synchronized (mbd.constructorArgumentLock) {
				// 因为一个类可能由多个构造函数，所以需要根据配置文件中配置的参数或传入的参数来确定最终调用的构造函数。
				// 因为判断过程会比较，所以spring会将解析、确定好的构造函数缓存到BeanDefinition中的resolvedConstructorOrFactoryMethod字段中。
				// 在下次创建相同时直接从RootBeanDefinition中的属性resolvedConstructorOrFactoryMethod缓存的值获取，避免再次解析
				if (mbd.resolvedConstructorOrFactoryMethod != null) {
					resolved = true;
					autowireNecessary = mbd.constructorArgumentsResolved;
				}
			}
		}
		// 有构造参数的或者工厂方法
		// 首先判断是否已经解析了构造器或者工厂方法，根据是否需要自动装配来选择使用 autowireConstructor 方法或者 instantiateBean 方法来创建 Bean 实例，然后将其返回
		if (resolved) {
			// 构造器有参数
			if (autowireNecessary) {
				// 构造函数自动注入
				return autowireConstructor(beanName, mbd, null, null);
			}
			else {
				// 使用默认构造函数构造
				return instantiateBean(beanName, mbd);
			}
		}

		// 尝试从 Bean 的后置处理器中获取候选的构造器，并根据这些构造器来尝试自动装配，如果找到了合适的构造器，则使用 autowireConstructor 方法来创建 Bean 实例，否则继续执行下一步。
		// Candidate constructors for autowiring?
		// 从bean后置处理器中为自动装配寻找构造方法, 有且仅有一个有参构造或者有且仅有@Autowired注解构造
		Constructor<?>[] ctors = determineConstructorsFromBeanPostProcessors(beanClass, beanName);
		// 以下情况符合其一即可进入
		// 1、存在可选构造方法
		// 2、自动装配模型为构造函数自动装配
		// 3、给BeanDefinition中设置了构造参数值（构造器注入）
		// 4、有参与构造函数参数列表的参数
		if (ctors != null || mbd.getResolvedAutowireMode() == AUTOWIRE_CONSTRUCTOR ||
				mbd.hasConstructorArgumentValues() || !ObjectUtils.isEmpty(args)) {
			return autowireConstructor(beanName, mbd, ctors, args);
		}

		// 如果 Bean 定义中指定了优先使用的构造器，则使用这些构造器来尝试自动装配，如果找到了合适的构造器，则使用 autowireConstructor 方法来创建 Bean 实例，否则继续执行下一步
		// Preferred constructors for default construction?
		// 找出最合适的默认构造方法
		ctors = mbd.getPreferredConstructors();
		if (ctors != null) {
			// 构造函数自动注入
			return autowireConstructor(beanName, mbd, ctors, null);
		}

		// 如果以上步骤都没有找到合适的构造器，则使用默认的无参构造器来创建 Bean 实例，然后将其返回。
		// No special handling: simply use no-arg constructor.
		// 使用默认无参构造函数创建对象，如果没有无参构造且存在多个有参构造且没有@AutoWired注解构造，会报错
		return instantiateBean(beanName, mbd);
	}

	/**
	 * 从supplier获取bean
	 *
	 * Obtain a bean instance from the given supplier.
	 * @param supplier the configured supplier
	 * @param beanName the corresponding bean name
	 * @return a BeanWrapper for the new instance
	 */
	private BeanWrapper obtainFromSupplier(Supplier<?> supplier, String beanName, RootBeanDefinition mbd) {
		// 获取原先创建的beanName
		String outerBean = this.currentlyCreatedBean.get();
		// 用当前作对做替换
		this.currentlyCreatedBean.set(beanName);
		Object instance;

		try {
			// 调用supplier的方法
			instance = obtainInstanceFromSupplier(supplier, beanName, mbd);
		}
		catch (Throwable ex) {
			if (ex instanceof BeansException beansException) {
				throw beansException;
			}
			throw new BeanCreationException(beanName, "Instantiation of supplied bean failed", ex);
		}
		finally {
			if (outerBean != null) {
				this.currentlyCreatedBean.set(outerBean);
			}
			else {
				this.currentlyCreatedBean.remove();
			}
		}

		// 如果没有创建对象，默认为NullBean
		if (instance == null) {
			instance = new NullBean();
		}
		// 初始化BeanWrapper并返回
		BeanWrapper bw = new BeanWrapperImpl(instance);
		initBeanWrapper(bw);
		return bw;
	}

	/**
	 * Obtain a bean instance from the given supplier.
	 * @param supplier the configured supplier
	 * @param beanName the corresponding bean name
	 * @param mbd the bean definition for the bean
	 * @return the bean instance (possibly {@code null})
	 * @since 6.0.7
	 */
	@Nullable
	protected Object obtainInstanceFromSupplier(Supplier<?> supplier, String beanName, RootBeanDefinition mbd)
			throws Exception {

		if (supplier instanceof ThrowingSupplier<?> throwingSupplier) {
			return throwingSupplier.getWithException();
		}
		// get方法：函数式方法
		return supplier.get();
	}

	/**
	 * Overridden in order to implicitly register the currently created bean as
	 * dependent on further beans getting programmatically retrieved during a
	 * {@link Supplier} callback.
	 * @since 5.0
	 * @see #obtainFromSupplier
	 */
	@Override
	protected Object getObjectForBeanInstance(
			Object beanInstance, String name, String beanName, @Nullable RootBeanDefinition mbd) {

		String currentlyCreatedBean = this.currentlyCreatedBean.get();
		if (currentlyCreatedBean != null) {
			registerDependentBean(beanName, currentlyCreatedBean);
		}

		return super.getObjectForBeanInstance(beanInstance, name, beanName, mbd);
	}

	/**
	 * Determine candidate constructors to use for the given bean, checking all registered
	 * {@link SmartInstantiationAwareBeanPostProcessor SmartInstantiationAwareBeanPostProcessors}.
	 * @param beanClass the raw class of the bean
	 * @param beanName the name of the bean
	 * @return the candidate constructors, or {@code null} if none specified
	 * @throws org.springframework.beans.BeansException in case of errors
	 * @see org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor#determineCandidateConstructors
	 */
	@Nullable
	protected Constructor<?>[] determineConstructorsFromBeanPostProcessors(@Nullable Class<?> beanClass, String beanName)
			throws BeansException {

		if (beanClass != null && hasInstantiationAwareBeanPostProcessors()) {
			for (SmartInstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().smartInstantiationAware) {
				Constructor<?>[] ctors = bp.determineCandidateConstructors(beanClass, beanName);
				if (ctors != null) {
					return ctors;
				}
			}
		}
		return null;
	}

	/**
	 * Instantiate the given bean using its default constructor.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return a BeanWrapper for the new instance
	 */
	protected BeanWrapper instantiateBean(String beanName, RootBeanDefinition mbd) {
		try {
			// 获取实例化策略,默认是cglib，并进行实例化操作
			// getInstantiationStrategy() 默认返回的是 CglibSubclassingInstantiationStrategy对象，该对象在AbstractAutowireCapableBeanFactory()构造方法中创建
			// 其中处理实例化方式：如lookup-method 处理逻辑在instantiate()方法，
			/** 反射方式实例化bean 和 lookup-method 方式都走这里  */
			Object beanInstance = getInstantiationStrategy().instantiate(mbd, beanName, this);
			// 包装成BeanWrapper，用来类型转换TypeConverter，如<bean>中定义age="1",但实际可能是Integer，需要转换
			// interface BeanWrapper extends ConfigurablePropertyAccessor
			// interface ConfigurablePropertyAccessor extends PropertyAccessor, PropertyEditorRegistry, TypeConverter
			// 其中 PropertyEditorRegistry 可以自定义属性编辑器
			// new BeanWrapperImpl()方法中，super()调用了父类构造器，父类构造器有registerDefaultEditors();	setWrappedInstance(object);
			BeanWrapper bw = new BeanWrapperImpl(beanInstance);
			// 初始化方法：设置自定义属性编辑器，设置类型转换器
			initBeanWrapper(bw);
			return bw;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName, ex.getMessage(), ex);
		}
	}

	/**
	 * 通过工厂方法实例化，先获取构造器解析器，然后用工厂方法进行实例化
	 *
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * mbd parameter specifies a class, rather than a factoryBean, or an instance variable
	 * on a factory object itself configured using Dependency Injection.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (implying the use of constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 * @see #getBean(String, Object[])
	 */
	protected BeanWrapper instantiateUsingFactoryMethod(
			String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) {
		//  创建构造器处理器并使用factorymethod进行实例化操作(第一个参数，bean的名称，第二个参数RootBeanDefinition，第三个显式的参数)
		return new ConstructorResolver(this).instantiateUsingFactoryMethod(beanName, mbd, explicitArgs);
	}

	/**
	 * 自动装配的构造方法
	 *
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified,
	 * matching all remaining arguments with beans from the bean factory.
	 * <p>This corresponds to constructor injection: In this mode, a Spring
	 * bean factory is able to host components that expect constructor-based
	 * dependency resolution.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @param ctors the chosen candidate constructors
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (implying the use of constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	protected BeanWrapper autowireConstructor(
			String beanName, RootBeanDefinition mbd, @Nullable Constructor<?>[] ctors, @Nullable Object[] explicitArgs) {

		return new ConstructorResolver(this).autowireConstructor(beanName, mbd, ctors, explicitArgs);
	}

	/**
	 * Populate the bean instance in the given BeanWrapper with the property values
	 * from the bean definition.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @param bw the BeanWrapper with bean instance
	 */
	protected void populateBean(String beanName, RootBeanDefinition mbd, @Nullable BeanWrapper bw) {
		// 如果beanWrapper为空
		if (bw == null) {
			// bw为空且mbd有需要设置的属性则异常
			if (mbd.hasPropertyValues()) {
				throw new BeanCreationException(
						mbd.getResourceDescription(), beanName, "Cannot apply property values to null instance");
			}
			else {
				// Skip property population phase for null instance.
				// bw为空且mbd没有可填充的属性，直接return
				return;
			}
		}

		// 判断是jdk14的Record，这个对象是不可变的，所以不能赋值，会报错，或者直接退出结束
		if (bw.getWrappedClass().isRecord()) {
			if (mbd.hasPropertyValues()) {
				throw new BeanCreationException(
						mbd.getResourceDescription(), beanName, "Cannot apply property values to a record");
			}
			else {
				// Skip property population phase for records since they are immutable.
				return;
			}
		}

		// Give any InstantiationAwareBeanPostProcessors the opportunity to modify the
		// state of the bean before properties are set. This can be used, for example,
		// to support styles of field injection.
		// 给任何实现了InstantiationAwareBeanPostProcessors的子类机会去修改bean的状态再设置属性之前(默认直接返回true，
		// 可以自定义实现postProcessAfterInstantiation()设置值)，  可以被用来支持类型的字段注入

		// 设置bean的属性调用之前，调用InstantiationAwareBeanPostProcessor的前置处理器
		if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			for (InstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().instantiationAware) {
				/**
				 * 如果有实现 InstantiationAwareBeanPostProcessor 的该方法，则进入调用该方法，该方法可以实现自定义对自己的Person类进行属性赋值
				 * 返回值为true/false，自定义赋值后可以设置 postProcessAfterInstantiation 返回值：
				 *       为T(取反为F)-继续往下解析xml配置的属性值，（会覆盖after中配置的值）
				 *       为F(取反为T)-终止方法不再进行xml解析配置。
				 * */
				if (!bp.postProcessAfterInstantiation(bw.getWrappedInstance(), beanName)) {
					return;
				}
			}
		}

		// PropertyValues：包含以一个或多个PropertyValue对象的容器，通常包括针对特定目标Bean的一次更新
		// 获取PropertyValues：指bean标签里面的properties标签，可以有多个： <bean><properties></properties></bean>
		PropertyValues pvs = (mbd.hasPropertyValues() ? mbd.getPropertyValues() : null);

		// 获取自动装配模式（构造器注入还是set注入，
		// 包括五种：0-AUTOWIRE_NO，1-AUTOWIRE_BY_NAME，2-AUTOWIRE_BY_TYPE， 3-构造器注入AUTOWIRE_CONSTRUCTOR， 4-自动装配AUTOWIRE_AUTODETECT
		// 4在Spirng3.0后废弃了，默认0 ）
		int resolvedAutowireMode = mbd.getResolvedAutowireMode();

		/** XML自动装配解析  */
		// 如果是1-byName或者2-byType
		// 此处是解析的xml的自动装配：<bean id = "" autowire = "byType/byName"></bean>
		// 开启装配后，会从xml上下文中找到对应的bean自动匹配，不需要properties.否则需要在<properties name ="dog" ref="dog" />中匹配
		if (resolvedAutowireMode == AUTOWIRE_BY_NAME || resolvedAutowireMode == AUTOWIRE_BY_TYPE) {
			MutablePropertyValues newPvs = new MutablePropertyValues(pvs);
			// 按照 Bean 的名称进行自动装配，
			if (resolvedAutowireMode == AUTOWIRE_BY_NAME) {
				autowireByName(beanName, mbd, bw, newPvs);
			}
			// 或者按照 Bean 的类型进行自动装配
			if (resolvedAutowireMode == AUTOWIRE_BY_TYPE) {
				autowireByType(beanName, mbd, bw, newPvs);
			}
			pvs = newPvs;
		}

		/** @Autowired 自动装配解析，需要开启扫描<cotext:component-scan/>  */
		// 设置bean的属性之后，调用InstantiationAwareBeanPostProcessor的后置处理器
		if (hasInstantiationAwareBeanPostProcessors()) {
			if (pvs == null) {
				pvs = mbd.getPropertyValues();
			}
			for (InstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().instantiationAware) {
				// 这个属性注入看情况了，常用@AutoWired注解会走这个实现类 AutowiredAnnotationBeanPostProcessor，
				// 这个调用链很复杂，最终会调用到descriptor.resolveCandidate()方法，里面就是getBean获取到要注入的实例，并且会注册依赖关系，bean销毁时依赖关系也跟着销毁
				PropertyValues pvsToUse = bp.postProcessProperties(pvs, bw.getWrappedInstance(), beanName);
				if (pvsToUse == null) {
					return;
				}
				pvs = pvsToUse;
			}
		}

		// 进行依赖检查，检查Bean依赖是否都注入完成，如果有没注入完的会抛出异常
		boolean needsDepCheck = (mbd.getDependencyCheck() != AbstractBeanDefinition.DEPENDENCY_CHECK_NONE);
		if (needsDepCheck) {
			PropertyDescriptor[] filteredPds = filterPropertyDescriptorsForDependencyCheck(bw, mbd.allowCaching);
			checkDependencies(beanName, mbd, filteredPds, pvs);
		}
		// 如果属性值不为空，则将属性值真正的注入到Bean中
		if (pvs != null) {
			applyPropertyValues(beanName, mbd, bw, pvs);
		}
	}

	/**
	 * Fill in any missing property values with references to
	 * other beans in this factory if autowire is set to "byName".
	 * @param beanName the name of the bean we're wiring up.
	 * Useful for debugging messages; not used functionally.
	 * @param mbd bean definition to update through autowiring
	 * @param bw the BeanWrapper from which we can obtain information about the bean
	 * @param pvs the PropertyValues to register wired objects with
	 */
	protected void autowireByName(
			String beanName, AbstractBeanDefinition mbd, BeanWrapper bw, MutablePropertyValues pvs) {

		String[] propertyNames = unsatisfiedNonSimpleProperties(mbd, bw);
		for (String propertyName : propertyNames) {
			if (containsBean(propertyName)) {
				Object bean = getBean(propertyName);
				pvs.add(propertyName, bean);
				registerDependentBean(propertyName, beanName);
				if (logger.isTraceEnabled()) {
					logger.trace("Added autowiring by name from bean name '" + beanName +
							"' via property '" + propertyName + "' to bean named '" + propertyName + "'");
				}
			}
			else {
				if (logger.isTraceEnabled()) {
					logger.trace("Not autowiring property '" + propertyName + "' of bean '" + beanName +
							"' by name: no matching bean found");
				}
			}
		}
	}

	/**
	 * Abstract method defining "autowire by type" (bean properties by type) behavior.
	 * <p>This is like PicoContainer default, in which there must be exactly one bean
	 * of the property type in the bean factory. This makes bean factories simple to
	 * configure for small namespaces, but doesn't work as well as standard Spring
	 * behavior for bigger applications.
	 * @param beanName the name of the bean to autowire by type
	 * @param mbd the merged bean definition to update through autowiring
	 * @param bw the BeanWrapper from which we can obtain information about the bean
	 * @param pvs the PropertyValues to register wired objects with
	 */
	protected void autowireByType(
			String beanName, AbstractBeanDefinition mbd, BeanWrapper bw, MutablePropertyValues pvs) {

		TypeConverter converter = getCustomTypeConverter();
		if (converter == null) {
			converter = bw;
		}

		Set<String> autowiredBeanNames = new LinkedHashSet<>(4);
		String[] propertyNames = unsatisfiedNonSimpleProperties(mbd, bw);
		for (String propertyName : propertyNames) {
			try {
				PropertyDescriptor pd = bw.getPropertyDescriptor(propertyName);
				// Don't try autowiring by type for type Object: never makes sense,
				// even if it technically is an unsatisfied, non-simple property.
				if (Object.class != pd.getPropertyType()) {
					MethodParameter methodParam = BeanUtils.getWriteMethodParameter(pd);
					// Do not allow eager init for type matching in case of a prioritized post-processor.
					boolean eager = !(bw.getWrappedInstance() instanceof PriorityOrdered);
					DependencyDescriptor desc = new AutowireByTypeDependencyDescriptor(methodParam, eager);
					Object autowiredArgument = resolveDependency(desc, beanName, autowiredBeanNames, converter);
					if (autowiredArgument != null) {
						pvs.add(propertyName, autowiredArgument);
					}
					for (String autowiredBeanName : autowiredBeanNames) {
						registerDependentBean(autowiredBeanName, beanName);
						if (logger.isTraceEnabled()) {
							logger.trace("Autowiring by type from bean name '" + beanName + "' via property '" +
									propertyName + "' to bean named '" + autowiredBeanName + "'");
						}
					}
					autowiredBeanNames.clear();
				}
			}
			catch (BeansException ex) {
				throw new UnsatisfiedDependencyException(mbd.getResourceDescription(), beanName, propertyName, ex);
			}
		}
	}


	/**
	 * Return an array of non-simple bean properties that are unsatisfied.
	 * These are probably unsatisfied references to other beans in the
	 * factory. Does not include simple properties like primitives or Strings.
	 * @param mbd the merged bean definition the bean was created with
	 * @param bw the BeanWrapper the bean was created with
	 * @return an array of bean property names
	 * @see org.springframework.beans.BeanUtils#isSimpleProperty
	 */
	protected String[] unsatisfiedNonSimpleProperties(AbstractBeanDefinition mbd, BeanWrapper bw) {
		Set<String> result = new TreeSet<>();
		PropertyValues pvs = mbd.getPropertyValues();
		PropertyDescriptor[] pds = bw.getPropertyDescriptors();
		for (PropertyDescriptor pd : pds) {
			if (pd.getWriteMethod() != null && !isExcludedFromDependencyCheck(pd) && !pvs.contains(pd.getName()) &&
					!BeanUtils.isSimpleProperty(pd.getPropertyType())) {
				result.add(pd.getName());
			}
		}
		return StringUtils.toStringArray(result);
	}

	/**
	 * Extract a filtered set of PropertyDescriptors from the given BeanWrapper,
	 * excluding ignored dependency types or properties defined on ignored dependency interfaces.
	 * @param bw the BeanWrapper the bean was created with
	 * @param cache whether to cache filtered PropertyDescriptors for the given bean Class
	 * @return the filtered PropertyDescriptors
	 * @see #isExcludedFromDependencyCheck
	 * @see #filterPropertyDescriptorsForDependencyCheck(org.springframework.beans.BeanWrapper)
	 */
	protected PropertyDescriptor[] filterPropertyDescriptorsForDependencyCheck(BeanWrapper bw, boolean cache) {
		PropertyDescriptor[] filtered = this.filteredPropertyDescriptorsCache.get(bw.getWrappedClass());
		if (filtered == null) {
			filtered = filterPropertyDescriptorsForDependencyCheck(bw);
			if (cache) {
				PropertyDescriptor[] existing =
						this.filteredPropertyDescriptorsCache.putIfAbsent(bw.getWrappedClass(), filtered);
				if (existing != null) {
					filtered = existing;
				}
			}
		}
		return filtered;
	}

	/**
	 * Extract a filtered set of PropertyDescriptors from the given BeanWrapper,
	 * excluding ignored dependency types or properties defined on ignored dependency interfaces.
	 * @param bw the BeanWrapper the bean was created with
	 * @return the filtered PropertyDescriptors
	 * @see #isExcludedFromDependencyCheck
	 */
	protected PropertyDescriptor[] filterPropertyDescriptorsForDependencyCheck(BeanWrapper bw) {
		List<PropertyDescriptor> pds = new ArrayList<>(Arrays.asList(bw.getPropertyDescriptors()));
		pds.removeIf(this::isExcludedFromDependencyCheck);
		return pds.toArray(new PropertyDescriptor[0]);
	}

	/**
	 * Determine whether the given bean property is excluded from dependency checks.
	 * <p>This implementation excludes properties defined by CGLIB and
	 * properties whose type matches an ignored dependency type or which
	 * are defined by an ignored dependency interface.
	 * @param pd the PropertyDescriptor of the bean property
	 * @return whether the bean property is excluded
	 * @see #ignoreDependencyType(Class)
	 * @see #ignoreDependencyInterface(Class)
	 */
	protected boolean isExcludedFromDependencyCheck(PropertyDescriptor pd) {
		return (AutowireUtils.isExcludedFromDependencyCheck(pd) ||
				this.ignoredDependencyTypes.contains(pd.getPropertyType()) ||
				AutowireUtils.isSetterDefinedInInterface(pd, this.ignoredDependencyInterfaces));
	}

	/**
	 * Perform a dependency check that all properties exposed have been set,
	 * if desired. Dependency checks can be objects (collaborating beans),
	 * simple (primitives and String), or all (both).
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition the bean was created with
	 * @param pds the relevant property descriptors for the target bean
	 * @param pvs the property values to be applied to the bean
	 * @see #isExcludedFromDependencyCheck(java.beans.PropertyDescriptor)
	 */
	protected void checkDependencies(
			String beanName, AbstractBeanDefinition mbd, PropertyDescriptor[] pds, @Nullable PropertyValues pvs)
			throws UnsatisfiedDependencyException {

		int dependencyCheck = mbd.getDependencyCheck();
		for (PropertyDescriptor pd : pds) {
			if (pd.getWriteMethod() != null && (pvs == null || !pvs.contains(pd.getName()))) {
				boolean isSimple = BeanUtils.isSimpleProperty(pd.getPropertyType());
				boolean unsatisfied = (dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_ALL) ||
						(isSimple && dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_SIMPLE) ||
						(!isSimple && dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_OBJECTS);
				if (unsatisfied) {
					throw new UnsatisfiedDependencyException(mbd.getResourceDescription(), beanName, pd.getName(),
							"Set this property value or disable dependency checking for this bean.");
				}
			}
		}
	}

	/**
	 * Apply the given property values, resolving any runtime references
	 * to other beans in this bean factory. Must use deep copy, so we
	 * don't permanently modify this property.
	 * @param beanName the bean name passed for better exception information
	 * @param mbd the merged bean definition
	 * @param bw the BeanWrapper wrapping the target object
	 * @param pvs the new property values
	 */
	protected void applyPropertyValues(String beanName, BeanDefinition mbd, BeanWrapper bw, PropertyValues pvs) {
		// 如果pvs没有PropertyValue,直接结束方法（至此，包括@Autowired，以及<bean autowire="byType等">配置的参数）
		if (pvs.isEmpty()) {
			return;
		}

		//MutablePropertyValues：PropertyValues接口的默认实现。允许对属性进行简单操作，并提供构造函数来支持从映射 进行深度复制和构造
		MutablePropertyValues mpvs = null;
		// 原始属性列表
		List<PropertyValue> original;

		// 如果pvs是MutablePropertyValues
		if (pvs instanceof MutablePropertyValues _mpvs) {
			mpvs = _mpvs;
			//isConverted:返回该holder是否只包含转换后的值(true),或者是否仍然需要转换这些值
			//如果mpvs只包含转换后的值,设置进bw，然后返回
			if (mpvs.isConverted()) {
				// Shortcut: use the pre-converted values as-is.
				try {
					// 已完成，直接返回
					bw.setPropertyValues(mpvs);
					return;
				}
				catch (BeansException ex) {
					//捕捉Bean异常，重新抛出Bean创佳异常：错误设置属性值。
					throw new BeanCreationException(
							mbd.getResourceDescription(), beanName, "Error setting property values", ex);
				}
			}
			// 如果mpvs含有非转换后的值，获取mpvs的PropertyValue列表，等待下一步处理
			original = mpvs.getPropertyValueList();
		}
		else {
			// pvs不是MutablePropertyValues类型,获取pvs的PropertyValue对象数组，并将其转换成列表
			original = Arrays.asList(pvs.getPropertyValues());
		}


		// 获取用户自定义类型转换器
		TypeConverter converter = getCustomTypeConverter();
		// 如果转换器为空，则直接把包装类赋值给converter
		if (converter == null) {
			converter = bw;
		}
		//BeanDefinitionValueResolver:在bean工厂实现中使用Helper类，它将beanDefinition对象中包含的值解析为应用于 目标bean实例的实际值
		BeanDefinitionValueResolver valueResolver = new BeanDefinitionValueResolver(this, beanName, mbd, converter);

		// Create a deep copy, resolving any references for values.
		// 创建一个深拷贝，解析任何值引用
		/** 浅拷贝和深拷贝区别：浅拷贝是复制基本数据类型的值以及引用类型的内存地址复制给新对象，新旧对象使用同一个内存地址，修改一个对象会影响另个一对象的数据
		 * 深拷贝是直接复制创建一份新的对象，不会互相影响数据 */
		List<PropertyValue> deepCopy = new ArrayList<>(original.size());
		boolean resolveNecessary = false;
		for (PropertyValue pv : original) {
			if (pv.isConverted()) {
				deepCopy.add(pv);
			}
			else {
				String propertyName = pv.getName();
				Object originalValue = pv.getValue();
				if (originalValue == AutowiredPropertyMarker.INSTANCE) {
					Method writeMethod = bw.getPropertyDescriptor(propertyName).getWriteMethod();
					if (writeMethod == null) {
						throw new IllegalArgumentException("Autowire marker for property without write method: " + pv);
					}
					originalValue = new DependencyDescriptor(new MethodParameter(writeMethod, 0), true);
				}
				Object resolvedValue = valueResolver.resolveValueIfNecessary(pv, originalValue);
				Object convertedValue = resolvedValue;
				boolean convertible = bw.isWritableProperty(propertyName) &&
						!PropertyAccessorUtils.isNestedOrIndexedProperty(propertyName);
				if (convertible) {
					convertedValue = convertForProperty(resolvedValue, propertyName, bw, converter);
				}
				// Possibly store converted value in merged bean definition,
				// in order to avoid re-conversion for every created bean instance.
				if (resolvedValue == originalValue) {
					if (convertible) {
						pv.setConvertedValue(convertedValue);
					}
					deepCopy.add(pv);
				}
				else if (convertible && originalValue instanceof TypedStringValue typedStringValue &&
						!typedStringValue.isDynamic() &&
						!(convertedValue instanceof Collection || ObjectUtils.isArray(convertedValue))) {
					pv.setConvertedValue(convertedValue);
					deepCopy.add(pv);
				}
				else {
					resolveNecessary = true;
					deepCopy.add(new PropertyValue(pv, convertedValue));
				}
			}
		}
		if (mpvs != null && !resolveNecessary) {
			mpvs.setConverted();
		}

		// Set our (possibly massaged) deep copy.
		try {
			bw.setPropertyValues(new MutablePropertyValues(deepCopy));
		}
		catch (BeansException ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName, ex.getMessage(), ex);
		}
	}

	/**
	 * Convert the given value for the specified target property.
	 */
	@Nullable
	private Object convertForProperty(
			@Nullable Object value, String propertyName, BeanWrapper bw, TypeConverter converter) {

		if (converter instanceof BeanWrapperImpl beanWrapper) {
			return beanWrapper.convertForProperty(value, propertyName);
		}
		else {
			PropertyDescriptor pd = bw.getPropertyDescriptor(propertyName);
			MethodParameter methodParam = BeanUtils.getWriteMethodParameter(pd);
			return converter.convertIfNecessary(value, pd.getPropertyType(), methodParam);
		}
	}

	/**
	 * Initialize the given bean instance, applying factory callbacks
	 * as well as init methods and bean post processors.
	 * <p>Called from {@link #createBean} for traditionally defined beans,
	 * and from {@link #initializeBean} for existing bean instances.
	 * @param beanName the bean name in the factory (for debugging purposes)
	 * @param bean the new bean instance we may need to initialize
	 * @param mbd the bean definition that the bean was created with
	 * (can also be {@code null}, if given an existing bean instance)
	 * @return the initialized bean instance (potentially wrapped)
	 * @see BeanNameAware
	 * @see BeanClassLoaderAware
	 * @see BeanFactoryAware
	 * @see #applyBeanPostProcessorsBeforeInitialization
	 * @see #invokeInitMethods
	 * @see #applyBeanPostProcessorsAfterInitialization
	 */
	protected Object initializeBean(String beanName, Object bean, @Nullable RootBeanDefinition mbd) {
		// 回调aware方法
		// Aware接口处理器，调用BeanNameAware、BeanClassLoaderAware、beanFactoryAware
		invokeAwareMethods(beanName, bean);

		// 调用bean的后置处理器postProcessBeforeInitialization，获取代理对象
		Object wrappedBean = bean;
		// 如果mdb为null || mbd不是"synthetic"【非AOP相关配置】。一般是指只有AOP相关的prointCut配置或者Advice配置才会将 synthetic设置为true
		if (mbd == null || !mbd.isSynthetic()) {
			// 执行beanPostProcessorsBeforeInitialization扩展点
			// 将BeanPostProcessors应用到给定的现有Bean实例，调用它们的postProcessBeforeInitialization初始化方法。
			// 返回的Bean实例可能是原始Bean包装器
			/** 在这里调用 @PostConstruct 和 @PreDestroy 注解
			 *  注册在本类600多行：applyMergedBeanDefinitionPostProcessors()方法
			 * */
			wrappedBean = applyBeanPostProcessorsBeforeInitialization(wrappedBean, beanName);
		}
		// 执行初始化方法
		try {
			//调用初始化方法，先调用bean的 InitializingBean 接口的 afterPropertiesSet 方法，后调用bean的自定义初始化方法
			invokeInitMethods(beanName, wrappedBean, mbd);
		}
		catch (Throwable ex) {
			//捕捉调用初始化方法时抛出的异常，重新抛出Bean创建异常：调用初始化方法失败
			throw new BeanCreationException(
					(mbd != null ? mbd.getResourceDescription() : null), beanName, ex.getMessage(), ex);
		}
		//如果mbd为null || mbd不是"synthetic"
		if (mbd == null || !mbd.isSynthetic()) {
			// 执行beanPostProcessorsAfterInitialization扩展点，获取代理对象（AOP和事务都在这里干活）
			// 将BeanPostProcessors应用到给定的现有Bean实例，调用它们的postProcessAfterInitialization方法。
			// 返回的Bean实例可能是原始Bean包装器
			wrappedBean = applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);
		}

		//返回包装后的Bean
		return wrappedBean;
	}

	/**
	 * 回调 bean 中 Aware接口 方法
	 * @param beanName
	 * @param bean
	 */
	private void invokeAwareMethods(String beanName, Object bean) {
		//如果 bean 是 Aware 实例
		if (bean instanceof Aware) {
			//如果bean是BeanNameAware实例
			if (bean instanceof BeanNameAware beanNameAware) {
				//调用 bean 的setBeanName方法
				beanNameAware.setBeanName(beanName);
			}
			//如果bean是 BeanClassLoaderAware 实例
			if (bean instanceof BeanClassLoaderAware beanClassLoaderAware) {
				//获取此工厂的类加载器以加载Bean类(即使无法使用系统ClassLoader,也只能为null)
				ClassLoader bcl = getBeanClassLoader();
				if (bcl != null) {
					//调用 bean 的 setBeanClassLoader 方法
					beanClassLoaderAware.setBeanClassLoader(bcl);
				}
			}
			//如果bean是 BeanFactoryAware 实例
			if (bean instanceof BeanFactoryAware beanFactoryAware) {
				//调用 bean 的 setBeanFactory 方法
				beanFactoryAware.setBeanFactory(AbstractAutowireCapableBeanFactory.this);
			}
		}
	}

	/**
	 * 调用初始化方法，先调用bean的InitializingBean接口方法，后调用bean的自定义初始化方法
	 *
	 * Give a bean a chance to react now all its properties are set,
	 * and a chance to know about its owning bean factory (this object).
	 * This means checking whether the bean implements InitializingBean or defines
	 * a custom init method, and invoking the necessary callback(s) if it does.
	 * @param beanName the bean name in the factory (for debugging purposes)
	 * @param bean the new bean instance we may need to initialize
	 * @param mbd the merged bean definition that the bean was created with
	 * (can also be {@code null}, if given an existing bean instance)
	 * @throws Throwable if thrown by init methods or by the invocation process
	 * @see #invokeCustomInitMethod
	 */
	protected void invokeInitMethods(String beanName, Object bean, @Nullable RootBeanDefinition mbd)
			throws Throwable {

		// InitializingBean:当Bean的所有属性都被BeanFactory设置好后，Bean需要执行相应的接口：例如执行自定义初始化，或者仅仅是检查所有强制属性是否已经设置好。
		// bean是InitializingBean实例标记
		boolean isInitializingBean = (bean instanceof InitializingBean);
		// isExternallyManagedInitMethod是否外部受管理的Init方法名
		// 如果bean是InitializingBean实例&&(mdb为null||'afterPropertiesSet'不是外部受管理的Init方法名)
		if (isInitializingBean && (mbd == null || !mbd.hasAnyExternallyManagedInitMethod("afterPropertiesSet"))) {
			// 如果是日志级别为跟踪模式
			if (logger.isTraceEnabled()) {
				logger.trace("Invoking afterPropertiesSet() on bean with name '" + beanName + "'");
			}
			// 调用bean的afterPropertiesSet方法
			((InitializingBean) bean).afterPropertiesSet();
		}

		// 如果mbd不为null&&bean不是NullBean类
		if (mbd != null && bean.getClass() != NullBean.class) {
			// 获取mbd指定的初始化方法名
			String[] initMethodNames = mbd.getInitMethodNames();
			// 如果initMethodName不为null&&(bean不是InitializingBean实例&&'afterPropertiesSet'是初始化方法名）
			// &&initMethodName不是外部受管理的Init方法名
			if (initMethodNames != null) {
				for (String initMethodName : initMethodNames) {
					if (StringUtils.hasLength(initMethodName) &&
							!(isInitializingBean && "afterPropertiesSet".equals(initMethodName)) &&
							!mbd.hasAnyExternallyManagedInitMethod(initMethodName)) {
						// 在bean上调用指定的自定义init方法
						invokeCustomInitMethod(beanName, bean, mbd, initMethodName);
					}
				}
			}
		}
	}

	/**
	 * 取bean的自定义初始化方法，如果自身或者父类是接口类型的话，就反射出接口方法来，最后调用
	 *
	 * Invoke the specified custom init method on the given bean.
	 * Called by invokeInitMethods.
	 * <p>Can be overridden in subclasses for custom resolution of init
	 * methods with arguments.
	 * @see #invokeInitMethods
	 */
	protected void invokeCustomInitMethod(String beanName, Object bean, RootBeanDefinition mbd, String initMethodName)
			throws Throwable {
		// 获取初始化方法
		Method initMethod = (mbd.isNonPublicAccessAllowed() ?
				BeanUtils.findMethod(bean.getClass(), initMethodName) :
				ClassUtils.getMethodIfAvailable(bean.getClass(), initMethodName));

		if (initMethod == null) {
			if (mbd.isEnforceInitMethod()) {
				throw new BeanDefinitionValidationException("Could not find an init method named '" +
						initMethodName + "' on bean with name '" + beanName + "'");
			}
			else {
				if (logger.isTraceEnabled()) {
					logger.trace("No default init method named '" + initMethodName +
							"' found on bean with name '" + beanName + "'");
				}
				// Ignore non-existent default lifecycle methods.
				return;
			}
		}

		if (logger.isTraceEnabled()) {
			logger.trace("Invoking init method  '" + initMethodName + "' on bean with name '" + beanName + "'");
		}
		Method methodToInvoke = ClassUtils.getInterfaceMethodIfPossible(initMethod, bean.getClass());

		try {
			ReflectionUtils.makeAccessible(methodToInvoke);
			// 反射执行
			methodToInvoke.invoke(bean);
		}
		catch (InvocationTargetException ex) {
			throw ex.getTargetException();
		}
	}


	/**
	 * 应用所有已注册BeanPostProcessor的postProcessAfterInitalization回调,使它们有机会对FactoryBeans获得的对象进行后处理(例如,自动代理它们)
	 *
	 * Applies the {@code postProcessAfterInitialization} callback of all
	 * registered BeanPostProcessors, giving them a chance to post-process the
	 * object obtained from FactoryBeans (for example, to auto-proxy them).
	 * @see #applyBeanPostProcessorsAfterInitialization
	 */
	@Override
	protected Object postProcessObjectFromFactoryBean(Object object, String beanName) {
		// 初始化后的后处理
		return applyBeanPostProcessorsAfterInitialization(object, beanName);
	}

	/**
	 * 移除单例，并且从factoryBeanInstanceCache中移除指定beanName
	 *
	 * Overridden to clear FactoryBean instance cache as well.
	 */
	@Override
	protected void removeSingleton(String beanName) {
		synchronized (getSingletonMutex()) {
			super.removeSingleton(beanName);
			this.factoryBeanInstanceCache.remove(beanName);
		}
	}

	/**
	 * 清空单例
	 *
	 * Overridden to clear FactoryBean instance cache as well.
	 */
	@Override
	protected void clearSingletonCache() {
		synchronized (getSingletonMutex()) {
			super.clearSingletonCache();
			this.factoryBeanInstanceCache.clear();
		}
	}

	/**
	 * Expose the logger to collaborating delegates.
	 * @since 5.0.7
	 */
	Log getLogger() {
		return logger;
	}


	/**
	 * {@link RootBeanDefinition} subclass for {@code #createBean} calls with
	 * flexible selection of a Kotlin primary / single public / single non-public
	 * constructor candidate in addition to the default constructor.
	 * @see BeanUtils#getResolvableConstructor(Class)
	 */
	@SuppressWarnings("serial")
	private static class CreateFromClassBeanDefinition extends RootBeanDefinition {

		public CreateFromClassBeanDefinition(Class<?> beanClass) {
			super(beanClass);
		}

		public CreateFromClassBeanDefinition(CreateFromClassBeanDefinition original) {
			super(original);
		}

		@Override
		@Nullable
		public Constructor<?>[] getPreferredConstructors() {
			return ConstructorResolver.determinePreferredConstructors(getBeanClass());
		}

		@Override
		public RootBeanDefinition cloneBeanDefinition() {
			return new CreateFromClassBeanDefinition(this);
		}
	}


	/**
	 * Special DependencyDescriptor variant for Spring's good old autowire="byType" mode.
	 * Always optional; never considering the parameter name for choosing a primary candidate.
	 */
	@SuppressWarnings("serial")
	private static class AutowireByTypeDependencyDescriptor extends DependencyDescriptor {

		public AutowireByTypeDependencyDescriptor(MethodParameter methodParameter, boolean eager) {
			super(methodParameter, false, eager);
		}

		@Override
		@Nullable
		public String getDependencyName() {
			return null;
		}
	}


	/**
	 * {@link MethodCallback} used to find {@link FactoryBean} type information.
	 */
	private static class FactoryBeanMethodTypeFinder implements MethodCallback {

		private final String factoryMethodName;

		private ResolvableType result = ResolvableType.NONE;

		FactoryBeanMethodTypeFinder(String factoryMethodName) {
			this.factoryMethodName = factoryMethodName;
		}

		@Override
		public void doWith(Method method) throws IllegalArgumentException {
			if (isFactoryBeanMethod(method)) {
				ResolvableType returnType = ResolvableType.forMethodReturnType(method);
				ResolvableType candidate = returnType.as(FactoryBean.class).getGeneric();
				if (this.result == ResolvableType.NONE) {
					this.result = candidate;
				}
				else {
					Class<?> resolvedResult = this.result.resolve();
					Class<?> commonAncestor = ClassUtils.determineCommonAncestor(candidate.resolve(), resolvedResult);
					if (!ObjectUtils.nullSafeEquals(resolvedResult, commonAncestor)) {
						this.result = ResolvableType.forClass(commonAncestor);
					}
				}
			}
		}

		private boolean isFactoryBeanMethod(Method method) {
			return (method.getName().equals(this.factoryMethodName) &&
					FactoryBean.class.isAssignableFrom(method.getReturnType()));
		}

		ResolvableType getResult() {
			Class<?> resolved = this.result.resolve();
			boolean foundResult = resolved != null && resolved != Object.class;
			return (foundResult ? this.result : ResolvableType.NONE);
		}
	}

}

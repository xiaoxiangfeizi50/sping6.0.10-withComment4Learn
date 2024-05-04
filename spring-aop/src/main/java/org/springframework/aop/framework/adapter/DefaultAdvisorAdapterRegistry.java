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

package org.springframework.aop.framework.adapter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;

/**
 *  * AdvisorAdapterRegistry的实现类。也是SpringAOP中唯一默认的实现类。
 *  * 持有：MethodBeforeAdviceAdapter、AfterReturningAdviceAdapter、ThrowsAdviceAdapter实例。
 *
 * Default implementation of the {@link AdvisorAdapterRegistry} interface.
 * Supports {@link org.aopalliance.intercept.MethodInterceptor},
 * {@link org.springframework.aop.MethodBeforeAdvice},
 * {@link org.springframework.aop.AfterReturningAdvice},
 * {@link org.springframework.aop.ThrowsAdvice}.
 *
 * @author Rod Johnson
 * @author Rob Harrop
 * @author Juergen Hoeller
 */
@SuppressWarnings("serial")
public class DefaultAdvisorAdapterRegistry implements AdvisorAdapterRegistry, Serializable {

	// 持有一个AdvisorAdapter的List，这个List中的adapter是与实现Spring AOP的advice增强功能相对应的
	private final List<AdvisorAdapter> adapters = new ArrayList<>(3);


	/**
	 * 此方法把已有的advice实现的adapter加入进来
	 * 		问：为什么这里只有三个Adapter，around和after为什么不注册为adapter？
	 * 		答：
	 *
	 * Create a new DefaultAdvisorAdapterRegistry, registering well-known adapters.
	 */
	public DefaultAdvisorAdapterRegistry() {
		registerAdvisorAdapter(new MethodBeforeAdviceAdapter());
		registerAdvisorAdapter(new AfterReturningAdviceAdapter());
		registerAdvisorAdapter(new ThrowsAdviceAdapter());
	}


	@Override
	public Advisor wrap(Object adviceObject) throws UnknownAdviceTypeException {
		// 如果要封装的对象本身就是Advisor类型，那么无须做任何处理
		if (adviceObject instanceof Advisor advisor) {
			return advisor;
		}
		// 如果类型不是Advisor和Advice两种类型的数据，那么将不能进行封装
		if (!(adviceObject instanceof Advice advice)) {
			throw new UnknownAdviceTypeException(adviceObject);
		}
		if (advice instanceof MethodInterceptor) {
			// So well-known it doesn't even need an adapter.
			// 如果是MethodInterceptor类型则使用DefaultPointcutAdvisor封装
			return new DefaultPointcutAdvisor(advice);
		}
		// 如果存在Advisor的适配器那么也同样需要进行封装
		for (AdvisorAdapter adapter : this.adapters) {
			// Check that it is supported.
			if (adapter.supportsAdvice(advice)) {
				return new DefaultPointcutAdvisor(advice);
			}
		}
		throw new UnknownAdviceTypeException(advice);
	}

	// 将 Advisor转换为 MethodInterceptor
	@Override
	public MethodInterceptor[] getInterceptors(Advisor advisor) throws UnknownAdviceTypeException {
		List<MethodInterceptor> interceptors = new ArrayList<>(3);
		// 从Advisor中获取 Advice
		Advice advice = advisor.getAdvice();
		// 原本可以所有的advice都实现MethodInterceptor接口，但是为了提高扩展性，还需要提供适配器的模式，
		// 那么在进行MethodInterceptor组装的时候就需要多加额外的判断逻辑，不能添加两次，
		// 所以可以把实现了MethodInterceptor接口的某些advice直接用过适配器来实现，而不需要通过原来的方式实现了。
		// AspectJAfterAdvice,AspectJAfterThrowingAdvice, AspectJAroundAdvice 实现了 MethodInterceptor接口
		// AspectJMethodBeforeAdvice, AspectJAfterReturningAdvice 分别有自己的适配器 MethodBeforeAdviceAdapter、AfterReturningAdviceAdapter
		if (advice instanceof MethodInterceptor methodInterceptor) {
			interceptors.add(methodInterceptor);
		}
		for (AdvisorAdapter adapter : this.adapters) {
			if (adapter.supportsAdvice(advice)) {
				// 转换为对应的 MethodInterceptor类型
				// AfterReturningAdviceInterceptor MethodBeforeAdviceInterceptor  ThrowsAdviceInterceptor
				interceptors.add(adapter.getInterceptor(advisor));
			}
		}
		if (interceptors.isEmpty()) {
			throw new UnknownAdviceTypeException(advisor.getAdvice());
		}
		return interceptors.toArray(new MethodInterceptor[0]);
	}

	// 新增的 Advisor适配器
	@Override
	public void registerAdvisorAdapter(AdvisorAdapter adapter) {
		this.adapters.add(adapter);
	}

}

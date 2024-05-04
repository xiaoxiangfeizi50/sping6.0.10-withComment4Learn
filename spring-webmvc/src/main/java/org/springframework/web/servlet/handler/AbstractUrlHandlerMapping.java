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

package org.springframework.web.servlet.handler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.http.server.RequestPath;
import org.springframework.lang.Nullable;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.ServerHttpObservationFilter;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 根据url来进行匹配的handler抽象类，将url和对应的handler保存在一个map中，在getHandlerInternal方法中使用url从map中获取handler
 *
 * Abstract base class for URL-mapped {@link HandlerMapping} implementations.
 *
 * <p>Supports literal matches and pattern matches such as "/test/*", "/test/**",
 * and others. For details on pattern syntax refer to {@link PathPattern} when
 * parsed patterns are {@link #usesPathPatterns() enabled} or see
 * {@link AntPathMatcher} otherwise. The syntax is largely the same but the
 * {@code PathPattern} syntax is more tailored for web applications, and its
 * implementation is more efficient.
 *
 * <p>All path patterns are checked in order to find the most exact match for the
 * current request path where the "most exact" is the longest path pattern that
 * matches the current request path.
 *
 * @author Juergen Hoeller
 * @author Arjen Poutsma
 * @since 16.04.2003
 */
public abstract class AbstractUrlHandlerMapping extends AbstractHandlerMapping implements MatchableHandlerMapping {

	/**
	 * 根路径（"/"）的处理器
	 */
	@Nullable
	private Object rootHandler;

	/**
	 * 使用后置的 / 匹配
	 */
	private boolean useTrailingSlashMatch = false;

	/**
	 * 是否延迟加载处理器，默认关闭
	 */
	private boolean lazyInitHandlers = false;

	/**
	 * 路径和处理器的映射
	 *
	 * KEY：路径 lookupHandler(String, HttpServletRequest)
	 */
	private final Map<String, Object> handlerMap = new LinkedHashMap<>();

	private final Map<PathPattern, Object> pathPatternHandlerMap = new LinkedHashMap<>();


	@Override
	public void setPatternParser(@Nullable PathPatternParser patternParser) {
		Assert.state(this.handlerMap.isEmpty(),
				"PathPatternParser must be set before the initialization of " +
						"the handler map via ApplicationContextAware#setApplicationContext.");
		super.setPatternParser(patternParser);
	}

	/**
	 * Set the root handler for this handler mapping, that is,
	 * the handler to be registered for the root path ("/").
	 * <p>Default is {@code null}, indicating no root handler.
	 */
	public void setRootHandler(@Nullable Object rootHandler) {
		this.rootHandler = rootHandler;
	}

	/**
	 * Return the root handler for this handler mapping (registered for "/"),
	 * or {@code null} if none.
	 */
	@Nullable
	public Object getRootHandler() {
		return this.rootHandler;
	}

	/**
	 * Whether to match to URLs irrespective of the presence of a trailing slash.
	 * If enabled a URL pattern such as "/users" also matches to "/users/".
	 * <p>The default value is {@code false}.
	 * @deprecated as of 6.0, see
	 * {@link PathPatternParser#setMatchOptionalTrailingSeparator(boolean)}
	 */
	@Deprecated(since = "6.0")
	public void setUseTrailingSlashMatch(boolean useTrailingSlashMatch) {
		this.useTrailingSlashMatch = useTrailingSlashMatch;
		if (getPatternParser() != null) {
			getPatternParser().setMatchOptionalTrailingSeparator(useTrailingSlashMatch);
		}
	}

	/**
	 * Whether to match to URLs irrespective of the presence of a trailing slash.
	 */
	public boolean useTrailingSlashMatch() {
		return this.useTrailingSlashMatch;
	}

	/**
	 * Set whether to lazily initialize handlers. Only applicable to
	 * singleton handlers, as prototypes are always lazily initialized.
	 * Default is "false", as eager initialization allows for more efficiency
	 * through referencing the controller objects directly.
	 * <p>If you want to allow your controllers to be lazily initialized,
	 * make them "lazy-init" and set this flag to true. Just making them
	 * "lazy-init" will not work, as they are initialized through the
	 * references from the handler mapping in this case.
	 */
	public void setLazyInitHandlers(boolean lazyInitHandlers) {
		this.lazyInitHandlers = lazyInitHandlers;
	}

	/**
	 * Look up a handler for the URL path of the given request.
	 * @param request current HTTP request
	 * @return the handler instance, or {@code null} if none found
	 */
	@Override
	@Nullable
	protected Object getHandlerInternal(HttpServletRequest request) throws Exception {
		// 截取用于匹配的URL有效路径
		String lookupPath = initLookupPath(request);
		Object handler;
		if (usesPathPatterns()) {
			RequestPath path = ServletRequestPathUtils.getParsedRequestPath(request);
			// 根据路径寻找handler，此处并不是直接从map中获取，很多handler都有通配符的写法，甚至有多个匹配项，此时需要做好选择
			handler = lookupHandler(path, lookupPath, request);
		}
		else {
			handler = lookupHandler(lookupPath, request);
		}
		// 如果找不到处理器，则使用rootHandler或defaultHandler处理器
		if (handler == null) {
			// We need to care for the default handler directly, since we need to
			// expose the PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE for it as well.
			Object rawHandler = null;
			// 如果是根路径，则使用rootHandler处理器
			if (StringUtils.matchesCharacter(lookupPath, '/')) {
				// 如果请求的路径仅仅是“/”,那么使用RootHandler进行处理
				rawHandler = getRootHandler();
			}
			// 如果无法找到handler，则使用默认的handler
			if (rawHandler == null) {
				rawHandler = getDefaultHandler();
			}
			if (rawHandler != null) {
				// Bean name or resolved handler?
				// 如果找到的处理器是String类型，则从容器中找到该beanName对应的Bean作为处理器
				if (rawHandler instanceof String handlerName) {
					rawHandler = obtainApplicationContext().getBean(handlerName);
				}
				// 空方法，校验处理器。目前暂无子类实现该方法
				validateHandler(rawHandler, request);
				// 创建处理器（HandlerExecutionChain对象）
				handler = buildPathExposingHandler(rawHandler, lookupPath, lookupPath, null);
			}
		}
		return handler;
	}

	/**
	 * Look up a handler instance for the given URL path. This method is used
	 * when parsed {@code PathPattern}s are {@link #usesPathPatterns() enabled}.
	 * @param path the parsed RequestPath
	 * @param lookupPath the String lookupPath for checking direct hits
	 * @param request current HTTP request
	 * @return a matching handler, or {@code null} if not found
	 * @since 5.3
	 */
	@Nullable
	protected Object lookupHandler(
			RequestPath path, String lookupPath, HttpServletRequest request) throws Exception {

		// 查找handler
		Object handler = getDirectMatch(lookupPath, request);
		if (handler != null) {
			return handler;
		}

		// Pattern match?
		// 通过表达式进行匹配具体通过antPathMatcher实现
		List<PathPattern> matches = null;
		// 情况二，Pattern匹配合适的，并添加到 matchingPatterns 中
		for (PathPattern pattern : this.pathPatternHandlerMap.keySet()) {
			if (pattern.matches(path.pathWithinApplication())) {
				// 路径通过Pattern匹配成功
				matches = (matches != null ? matches : new ArrayList<>());
				matches.add(pattern);
			}
		}
		if (matches == null) {
			return null;
		}
		if (matches.size() > 1) {
			// 排序
			matches.sort(PathPattern.SPECIFICITY_COMPARATOR);
			if (logger.isTraceEnabled()) {
				logger.trace("Matching patterns " + matches);
			}
		}
		PathPattern pattern = matches.get(0);
		handler = this.pathPatternHandlerMap.get(pattern);
		// 如果找到的处理器是String类型，则从容器中找到该beanName对应的Bean作为处理器
		if (handler instanceof String handlerName) {
			handler = obtainApplicationContext().getBean(handlerName);
		}
		// 空方法，校验处理器。目前暂无子类实现该方法
		validateHandler(handler, request);
		// 获得最匹配的路径
		String pathWithinMapping = pattern.extractPathWithinPattern(path.pathWithinApplication()).value();
		pathWithinMapping = UrlPathHelper.defaultInstance.removeSemicolonContent(pathWithinMapping);
		// 创建处理器
		return buildPathExposingHandler(handler, pattern.getPatternString(), pathWithinMapping, null);
	}

	/**
	 * Look up a handler instance for the given URL path. This method is used
	 * when String pattern matching with {@code PathMatcher} is in use.
	 * @param lookupPath the path to match patterns against
	 * @param request current HTTP request
	 * @return a matching handler, or {@code null} if not found
	 * @see #exposePathWithinMapping
	 * @see AntPathMatcher
	 */
	@Nullable
	protected Object lookupHandler(String lookupPath, HttpServletRequest request) throws Exception {
		Object handler = getDirectMatch(lookupPath, request);
		if (handler != null) {
			return handler;
		}

		// Pattern match?
		List<String> matchingPatterns = new ArrayList<>();
		for (String registeredPattern : this.handlerMap.keySet()) {
			if (getPathMatcher().match(registeredPattern, lookupPath)) {
				matchingPatterns.add(registeredPattern);
			}
			else if (useTrailingSlashMatch()) {
				if (!registeredPattern.endsWith("/") && getPathMatcher().match(registeredPattern + "/", lookupPath)) {
					matchingPatterns.add(registeredPattern + "/");
				}
			}
		}

		String bestMatch = null;
		Comparator<String> patternComparator = getPathMatcher().getPatternComparator(lookupPath);
		if (!matchingPatterns.isEmpty()) {
			matchingPatterns.sort(patternComparator);
			if (logger.isTraceEnabled() && matchingPatterns.size() > 1) {
				logger.trace("Matching patterns " + matchingPatterns);
			}
			bestMatch = matchingPatterns.get(0);
		}
		if (bestMatch != null) {
			handler = this.handlerMap.get(bestMatch);
			if (handler == null) {
				if (bestMatch.endsWith("/")) {
					handler = this.handlerMap.get(bestMatch.substring(0, bestMatch.length() - 1));
				}
				if (handler == null) {
					throw new IllegalStateException(
							"Could not find handler for best pattern match [" + bestMatch + "]");
				}
			}
			// Bean name or resolved handler?
			if (handler instanceof String handlerName) {
				handler = obtainApplicationContext().getBean(handlerName);
			}
			validateHandler(handler, request);
			String pathWithinMapping = getPathMatcher().extractPathWithinPattern(bestMatch, lookupPath);

			// There might be multiple 'best patterns', let's make sure we have the correct URI template variables
			// for all of them
			Map<String, String> uriTemplateVariables = new LinkedHashMap<>();
			for (String matchingPattern : matchingPatterns) {
				if (patternComparator.compare(bestMatch, matchingPattern) == 0) {
					Map<String, String> vars = getPathMatcher().extractUriTemplateVariables(matchingPattern, lookupPath);
					Map<String, String> decodedVars = getUrlPathHelper().decodePathVariables(request, vars);
					uriTemplateVariables.putAll(decodedVars);
				}
			}
			if (logger.isTraceEnabled() && uriTemplateVariables.size() > 0) {
				logger.trace("URI variables " + uriTemplateVariables);
			}
			return buildPathExposingHandler(handler, bestMatch, pathWithinMapping, uriTemplateVariables);
		}

		// No handler found...
		return null;
	}

	@Nullable
	private Object getDirectMatch(String urlPath, HttpServletRequest request) throws Exception {
		// 直接根据url进行查找handler
		/**
		 * Q: handlerMap 什么时候赋值的？
		 *  	A: 找到 handlerMap 的 put() 方法，发现溯源能找到  AbstractHandlerMapping.initApplicationContext()
		 *         然后看父类 可以发现 父类 ApplicationObjectSupport 实现了 ApplicationContextAware。
		 *         因此猜测，是从 ApplicationContextAware 的 setApplicationContext() 赋值的,查看父类ApplicationObjectSupport其setAC方法，果然有调用initApplicationContext(context);
		 *
		 * 		   回顾aware有几处调用？ invokeAwareMethod() 和 invokeAwareInterface()区别？
		 * 		   		分别在哪里调用的？ ApplicationContextAwareProcessor【实现了BPP】，里面的before方法调用的是哪个方法来调用aware？
		 *
		 *         然后，跟着debug流程，在九大组件 initHandlerMapping()中创建bean的时候，会走到doCreateBean(),里面有三个特别重要的方法分别是：createBeanInstance(),
		 *         populateBean(), initializeBean(),在 initializeBean() 中 先调用 invokeAwareMethod()【调用了3个aware】 ，
		 *         然后调用 aBPPBI()方法，进入 ApplicationContextAwareProcessor 类的 PPBI(), 里面调用了 invokeAwareInterface()，里面调用了六个aware的set方法，最后
		 *         一个就是 setApplicationContext(),一步一步能走到 AbstractHandlerMapping.initApplicationContext().
		 *         执行完该父类后，在子类AbstractDetectingUrlHandlerMapping的initApplicationContext()中有调用detectHandlers()，该方法会各种解析url然后将url和mapping放进map中。
		 */
		Object handler = this.handlerMap.get(urlPath);
		if (handler != null) {
			// Bean name or resolved handler?
			// 如果找到的处理器是String类型，则从容器中找到该beanName对应的Bean作为处理器
			if (handler instanceof String handlerName) {
				handler = obtainApplicationContext().getBean(handlerName);
			}
			// 空方法，校验处理器。目前暂无子类实现该方法
			validateHandler(handler, request);
			// 创建处理器
			return buildPathExposingHandler(handler, urlPath, urlPath, null);
		}
		return null;
	}

	/**
	 * Validate the given handler against the current request.
	 * <p>The default implementation is empty. Can be overridden in subclasses,
	 * for example to enforce specific preconditions expressed in URL mappings.
	 * @param handler the handler object to validate
	 * @param request current HTTP request
	 * @throws Exception if validation failed
	 */
	protected void validateHandler(Object handler, HttpServletRequest request) throws Exception {
	}

	/**
	 * 此方法给handler注册两个内部的拦截器PathExposingHandlerInterceptor和UriTemplateVariablesHandlerInterceptor，
	 * 这两个拦截器分别在preHandler中调用exposePathWithinMapping和exposeUriTemplateVariables方法将相应的内容设置到request的属性中
	 *
	 * Build a handler object for the given raw handler, exposing the actual
	 * handler, the {@link #PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE}, as well as
	 * the {@link #URI_TEMPLATE_VARIABLES_ATTRIBUTE} before executing the handler.
	 * <p>The default implementation builds a {@link HandlerExecutionChain}
	 * with a special interceptor that exposes the path attribute and URI
	 * template variables
	 * @param rawHandler the raw handler to expose
	 * @param pathWithinMapping the path to expose before executing the handler
	 * @param uriTemplateVariables the URI template variables, can be {@code null} if no variables found
	 * @return the final handler object
	 */
	protected Object buildPathExposingHandler(Object rawHandler, String bestMatchingPattern,
			String pathWithinMapping, @Nullable Map<String, String> uriTemplateVariables) {

		// 创建HandlerExecutionChain对象
		HandlerExecutionChain chain = new HandlerExecutionChain(rawHandler);
		// 添加PathExposingHandlerInterceptor拦截器，到chain中
		chain.addInterceptor(new PathExposingHandlerInterceptor(bestMatchingPattern, pathWithinMapping));
		if (!CollectionUtils.isEmpty(uriTemplateVariables)) {
			// 添加UriTemplateVariablesHandlerInterceptor拦截器，到chain中
			chain.addInterceptor(new UriTemplateVariablesHandlerInterceptor(uriTemplateVariables));
		}
		return chain;
	}

	/**
	 * Expose the path within the current mapping as request attribute.
	 * @param pathWithinMapping the path within the current mapping
	 * @param request the request to expose the path to
	 * @see #PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE
	 */
	protected void exposePathWithinMapping(String bestMatchingPattern, String pathWithinMapping,
			HttpServletRequest request) {

		request.setAttribute(BEST_MATCHING_PATTERN_ATTRIBUTE, bestMatchingPattern);
		ServerHttpObservationFilter.findObservationContext(request)
				.ifPresent(context -> context.setPathPattern(bestMatchingPattern));
		request.setAttribute(PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, pathWithinMapping);
	}

	/**
	 * Expose the URI templates variables as request attribute.
	 * @param uriTemplateVariables the URI template variables
	 * @param request the request to expose the path to
	 * @see #PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE
	 */
	protected void exposeUriTemplateVariables(Map<String, String> uriTemplateVariables, HttpServletRequest request) {
		request.setAttribute(URI_TEMPLATE_VARIABLES_ATTRIBUTE, uriTemplateVariables);
	}

	@Override
	@Nullable
	public RequestMatchResult match(HttpServletRequest request, String pattern) {
		Assert.state(getPatternParser() == null, "This HandlerMapping uses PathPatterns.");
		// 获得请求路径
		String lookupPath = UrlPathHelper.getResolvedLookupPath(request);
		// 模式匹配，若匹配，则返回 RequestMatchResult 对象
		if (getPathMatcher().match(pattern, lookupPath)) {
			return new RequestMatchResult(pattern, lookupPath, getPathMatcher());
		}
		else if (useTrailingSlashMatch()) {
			if (!pattern.endsWith("/") && getPathMatcher().match(pattern + "/", lookupPath)) {
				return new RequestMatchResult(pattern + "/", lookupPath, getPathMatcher());
			}
		}
		return null;
	}

	/**
	 * 注册url和bean的map，注册多个string的url到一个处理器中
	 *
	 * Register the specified handler for the given URL paths.
	 * @param urlPaths the URLs that the bean should be mapped to
	 * @param beanName the name of the handler bean
	 * @throws BeansException if the handler couldn't be registered
	 * @throws IllegalStateException if there is a conflicting handler registered
	 */
	protected void registerHandler(String[] urlPaths, String beanName) throws BeansException, IllegalStateException {
		Assert.notNull(urlPaths, "URL path array must not be null");
		for (String urlPath : urlPaths) {
			// 调用另外一个重载方法完成注册
			registerHandler(urlPath, beanName);
		}
	}

	/**
	 * Register the specified handler for the given URL path.
	 * @param urlPath the URL the bean should be mapped to
	 * @param handler the handler instance or handler bean name String
	 * (a bean name will automatically be resolved into the corresponding handler bean)
	 * @throws BeansException if the handler couldn't be registered
	 * @throws IllegalStateException if there is a conflicting handler registered
	 */
	protected void registerHandler(String urlPath, Object handler) throws BeansException, IllegalStateException {
		Assert.notNull(urlPath, "URL path must not be null");
		Assert.notNull(handler, "Handler object must not be null");
		Object resolvedHandler = handler;

		// Eagerly resolve handler if referencing singleton via name.
		// 如果handler是String类型并且没有设置lazyInitHandlers，则从springmvc容器中获取handler
		if (!this.lazyInitHandlers && handler instanceof String handlerName) {
			ApplicationContext applicationContext = obtainApplicationContext();
			if (applicationContext.isSingleton(handlerName)) {
				resolvedHandler = applicationContext.getBean(handlerName);
			}
		}

		// 是否已经存在对应的handler
		Object mappedHandler = this.handlerMap.get(urlPath);
		// 检验mappedHandler是否已存在，如果已存在，并且不是当前resolvedHandler对象，则抛出异常
		if (mappedHandler != null) {
			if (mappedHandler != resolvedHandler) {
				throw new IllegalStateException(
						"Cannot map " + getHandlerDescription(handler) + " to URL path [" + urlPath +
						"]: There is already " + getHandlerDescription(mappedHandler) + " mapped.");
			}
		}
		else {
			// 如果是/根路径，则设置为rootHandler
			if (urlPath.equals("/")) {
				if (logger.isTraceEnabled()) {
					logger.trace("Root mapping to " + getHandlerDescription(handler));
				}
				// "/"设置为rootHandler
				setRootHandler(resolvedHandler);
			}
			// 如果是/*路径，则设置为默认处理器
			else if (urlPath.equals("/*")) {
				if (logger.isTraceEnabled()) {
					logger.trace("Default mapping to " + getHandlerDescription(handler));
				}
				// 对"/*"的匹配设置默认的handler
				setDefaultHandler(resolvedHandler);
			}
			else {
				// 其余的路径绑定关系则存入handlerMap中
				this.handlerMap.put(urlPath, resolvedHandler);
				if (getPatternParser() != null) {
					this.pathPatternHandlerMap.put(getPatternParser().parse(urlPath), resolvedHandler);
				}
				if (logger.isTraceEnabled()) {
					logger.trace("Mapped [" + urlPath + "] onto " + getHandlerDescription(handler));
				}
			}
		}
	}

	private String getHandlerDescription(Object handler) {
		return (handler instanceof String ? "'" + handler + "'" : handler.toString());
	}


	/**
	 * Return the handler mappings as a read-only Map, with the registered path
	 * or pattern as key and the handler object (or handler bean name in case of
	 * a lazy-init handler), as value.
	 * @see #getDefaultHandler()
	 */
	public final Map<String, Object> getHandlerMap() {
		return Collections.unmodifiableMap(this.handlerMap);
	}

	/**
	 * Identical to {@link #getHandlerMap()} but populated when parsed patterns
	 * are {@link #usesPathPatterns() enabled}; otherwise empty.
	 * @since 5.3
	 */
	public final Map<PathPattern, Object> getPathPatternHandlerMap() {
		return (this.pathPatternHandlerMap.isEmpty() ?
				Collections.emptyMap() : Collections.unmodifiableMap(this.pathPatternHandlerMap));
	}

	/**
	 * Indicates whether this handler mapping support type-level mappings. Default to {@code false}.
	 */
	protected boolean supportsTypeLevelMappings() {
		return false;
	}


	/**
	 * Special interceptor for exposing the
	 * {@link AbstractUrlHandlerMapping#PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE} attribute.
	 * @see AbstractUrlHandlerMapping#exposePathWithinMapping
	 */
	private class PathExposingHandlerInterceptor implements HandlerInterceptor {

		private final String bestMatchingPattern;

		private final String pathWithinMapping;

		public PathExposingHandlerInterceptor(String bestMatchingPattern, String pathWithinMapping) {
			this.bestMatchingPattern = bestMatchingPattern;
			this.pathWithinMapping = pathWithinMapping;
		}

		@Override
		public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
			exposePathWithinMapping(this.bestMatchingPattern, this.pathWithinMapping, request);
			request.setAttribute(BEST_MATCHING_HANDLER_ATTRIBUTE, handler);
			request.setAttribute(INTROSPECT_TYPE_LEVEL_MAPPING, supportsTypeLevelMappings());
			return true;
		}

	}

	/**
	 * Special interceptor for exposing the
	 * {@link AbstractUrlHandlerMapping#URI_TEMPLATE_VARIABLES_ATTRIBUTE} attribute.
	 * @see AbstractUrlHandlerMapping#exposePathWithinMapping
	 */
	private class UriTemplateVariablesHandlerInterceptor implements HandlerInterceptor {

		private final Map<String, String> uriTemplateVariables;

		public UriTemplateVariablesHandlerInterceptor(Map<String, String> uriTemplateVariables) {
			this.uriTemplateVariables = uriTemplateVariables;
		}

		@Override
		public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
			exposeUriTemplateVariables(this.uriTemplateVariables, request);
			return true;
		}
	}

}

/*
 * Copyright 2019-2021 the original author or authors.
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

package org.springframework.aot.test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aot.context.bootstrap.generator.bean.descriptor.BeanInstanceDescriptor.MemberDescriptor;
import org.springframework.aot.context.bootstrap.generator.bean.descriptor.InjectionPointsSupplier;
import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.lang.Nullable;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;

/**
 * {@code TestExecutionListener} which provides dependency injection support
 * for test instances whose {@code ApplicationContext} is loaded via AOT
 * mechanisms.
 *
 * @author Sam Brannen
 * @see DependencyInjectionTestExecutionListener
 * @see AotContextLoader
 * @see AotCacheAwareContextLoaderDelegate
 */
public class AotDependencyInjectionTestExecutionListener extends AbstractTestExecutionListener {

	private static final Log logger = LogFactory.getLog(AotDependencyInjectionTestExecutionListener.class);

	private static final AotContextLoader aotContextLoader = new AotContextLoader();


	/**
	 * Returns {@code 1999}, which is one less than the order configured for the
	 * standard {@link DependencyInjectionTestExecutionListener}, thereby
	 * ensuring that the {@code AotDependencyInjectionTestExecutionListener}
	 * gets a chance to perform dependency injection before the
	 * {@code DependencyInjectionTestExecutionListener} clears the
	 * {@link DependencyInjectionTestExecutionListener#REINJECT_DEPENDENCIES_ATTRIBUTE
	 * REINJECT_DEPENDENCIES_ATTRIBUTE} flag.
	 */
	@Override
	public final int getOrder() {
		return 1999;
	}

	@Override
	public void prepareTestInstance(TestContext testContext) throws Exception {
		if (aotContextLoader.isSupportedTestClass(testContext.getTestClass())) {
			if (logger.isDebugEnabled()) {
				logger.debug("Performing dependency injection for test context [" + testContext + "].");
			}
			injectDependencies(testContext);
		}
	}

	@Override
	public void beforeTestMethod(TestContext testContext) throws Exception {
		if (aotContextLoader.isSupportedTestClass(testContext.getTestClass())) {
			if (Boolean.TRUE.equals(
				testContext.getAttribute(DependencyInjectionTestExecutionListener.REINJECT_DEPENDENCIES_ATTRIBUTE))) {
				if (logger.isDebugEnabled()) {
					logger.debug("Reinjecting dependencies for test context [" + testContext + "].");
				}
				injectDependencies(testContext);
			}
		}
	}

	protected void injectDependencies(TestContext testContext) throws Exception {
		Class<?> testClass = testContext.getTestClass();
		Object testInstance = testContext.getTestInstance();
		ApplicationContext applicationContext = testContext.getApplicationContext();
		if (applicationContext instanceof ConfigurableApplicationContext) {
			ConfigurableListableBeanFactory beanFactory = ((ConfigurableApplicationContext) applicationContext).getBeanFactory();

			InjectionPointsSupplier injectionPointsSupplier = new InjectionPointsSupplier(testClass.getClassLoader());
			for (MemberDescriptor<?> injectionPoint : injectionPointsSupplier.detectInjectionPoints(testClass)) {
				if (injectionPoint.getMember() instanceof Method) {
					Method method = (Method) injectionPoint.getMember();
					if (logger.isDebugEnabled()) {
						logger.debug("Performing dependency injection for method: " + method.toGenericString());
					}
					Object[] arguments = resolveMethodArguments(beanFactory, method, testInstance, injectionPoint.isRequired());
					if (arguments != null) {
						try {
							ReflectionUtils.makeAccessible(method);
							method.invoke(testInstance, arguments);
						}
						catch (InvocationTargetException ex) {
							ReflectionUtils.rethrowException(ex.getTargetException());
						}
					}
				}
				else if (injectionPoint.getMember() instanceof Field) {
					Field field = (Field) injectionPoint.getMember();
					if (logger.isDebugEnabled()) {
						logger.debug("Performing dependency injection for field: " + field.toGenericString());
					}
					Object value = resolveFieldValue(beanFactory, field, testInstance, injectionPoint.isRequired());
					if (value != null) {
						ReflectionUtils.makeAccessible(field);
						field.set(testInstance, value);
					}
				}
			}
		}
	}

	/**
	 * Copied from {@code AutowiredMethodElement.resolveMethodArguments(Method, Object, String)}
	 * in {@link org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor}
	 * and adapted to simplify and remove caching.
	 */
	@Nullable
	private Object[] resolveMethodArguments(ConfigurableListableBeanFactory beanFactory, Method method, Object bean, boolean required) {
		Assert.notNull(beanFactory, "No BeanFactory available");
		final String beanName = null;
		final int argumentCount = method.getParameterCount();

		Object[] arguments = new Object[argumentCount];
		DependencyDescriptor[] descriptors = new DependencyDescriptor[argumentCount];
		Set<String> autowiredBeans = new LinkedHashSet<>(argumentCount);
		TypeConverter typeConverter = beanFactory.getTypeConverter();
		for (int i = 0; i < arguments.length; i++) {
			MethodParameter methodParam = new MethodParameter(method, i);
			DependencyDescriptor currDesc = new DependencyDescriptor(methodParam, required);
			currDesc.setContainingClass(bean.getClass());
			descriptors[i] = currDesc;
			try {
				Object arg = beanFactory.resolveDependency(currDesc, beanName, autowiredBeans, typeConverter);
				if (arg == null && !required) {
					arguments = null;
					break;
				}
				arguments[i] = arg;
			}
			catch (BeansException ex) {
				throw new UnsatisfiedDependencyException(null, beanName, new InjectionPoint(methodParam), ex);
			}
		}
		return arguments;
	}

	/**
	 * Copied from {@code AutowiredFieldElement.resolveFieldValue(Field, Object, String)}
	 * in {@link org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor}
	 * and adapted to simplify and remove caching.
	 */
	@Nullable
	private Object resolveFieldValue(ConfigurableListableBeanFactory beanFactory, Field field, Object bean, boolean required) {
		Assert.notNull(beanFactory, "No BeanFactory available");
		final String beanName = null;

		DependencyDescriptor desc = new DependencyDescriptor(field, required);
		desc.setContainingClass(bean.getClass());
		Set<String> autowiredBeanNames = new LinkedHashSet<>(1);
		TypeConverter typeConverter = beanFactory.getTypeConverter();
		Object value;
		try {
			value = beanFactory.resolveDependency(desc, beanName, autowiredBeanNames, typeConverter);
		}
		catch (BeansException ex) {
			throw new UnsatisfiedDependencyException(null, beanName, new InjectionPoint(field), ex);
		}
		return value;
	}

}

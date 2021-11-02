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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aot.context.bootstrap.generator.bean.descriptor.BeanInstanceDescriptor;
import org.springframework.aot.context.bootstrap.generator.bean.descriptor.BeanInstanceDescriptor.MemberDescriptor;
import org.springframework.aot.context.bootstrap.generator.bean.descriptor.BeanInstanceDescriptorFactory;
import org.springframework.aot.context.bootstrap.generator.bean.descriptor.DefaultBeanInstanceDescriptorFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
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
		ApplicationContext applicationContext = testContext.getApplicationContext();
		Assert.state(applicationContext instanceof GenericApplicationContext,
			() -> "AOT ApplicationContext must be a GenericApplicationContext instead of " +
					applicationContext.getClass().getName());
		ConfigurableListableBeanFactory beanFactory = ((GenericApplicationContext) applicationContext).getBeanFactory();

		Class<?> testClass = testContext.getTestClass();
		Object testInstance = testContext.getTestInstance();

		BeanDefinition beanDefinition = new RootBeanDefinition(testClass);
		BeanInstanceDescriptorFactory beanInstanceDescriptorFactory = new DefaultBeanInstanceDescriptorFactory(beanFactory);
		BeanInstanceDescriptor beanInstanceDescriptor = beanInstanceDescriptorFactory.create(beanDefinition);

		for (MemberDescriptor<?> injectionPoint : beanInstanceDescriptor.getInjectionPoints()) {
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

	/**
	 * Copied from {@code AutowiredMethodElement.resolveMethodArguments(Method, Object, String)}
	 * in {@link org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor}
	 * and adapted to simplify and remove caching.
	 */
	@Nullable
	private Object[] resolveMethodArguments(ConfigurableListableBeanFactory beanFactory, Method method, Object bean, boolean required) {
		Assert.notNull(beanFactory, "No BeanFactory available");
		Object[] arguments = new Object[method.getParameterCount()];
		for (int i = 0; i < arguments.length; i++) {
			MethodParameter methodParam = new MethodParameter(method, i);
			try {
				DependencyDescriptor descriptor = new DependencyDescriptor(methodParam, required);
				descriptor.setContainingClass(bean.getClass());
				Object arg = beanFactory.resolveDependency(descriptor, null);
				if (arg == null && !required) {
					arguments = null;
					break;
				}
				arguments[i] = arg;
			}
			catch (BeansException ex) {
				throw new UnsatisfiedDependencyException(null, null, new InjectionPoint(methodParam), ex);
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
		try {
			DependencyDescriptor descriptor = new DependencyDescriptor(field, required);
			descriptor.setContainingClass(bean.getClass());
			return beanFactory.resolveDependency(descriptor, null);
		}
		catch (BeansException ex) {
			throw new UnsatisfiedDependencyException(null, null, new InjectionPoint(field), ex);
		}
	}

}

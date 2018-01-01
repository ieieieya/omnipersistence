/*
 * Copyright 2018 OmniFaces
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.omnifaces.persistence;

import static javax.persistence.metamodel.Attribute.PersistentAttributeType.ELEMENT_COLLECTION;
import static javax.persistence.metamodel.Attribute.PersistentAttributeType.MANY_TO_ONE;
import static javax.persistence.metamodel.Attribute.PersistentAttributeType.ONE_TO_MANY;
import static javax.persistence.metamodel.Attribute.PersistentAttributeType.ONE_TO_ONE;
import static org.omnifaces.utils.Collections.unmodifiableSet;
import static org.omnifaces.utils.Lang.isOneOf;
import static org.omnifaces.utils.reflect.Reflections.findClass;
import static org.omnifaces.utils.reflect.Reflections.findMethod;
import static org.omnifaces.utils.reflect.Reflections.invokeMethod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import javax.persistence.ElementCollection;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.criteria.Expression;
import javax.persistence.metamodel.Attribute;

import org.omnifaces.persistence.service.BaseEntityService;

/**
 * Enumeration of all supported JPA providers.
 */
public enum Provider {

	HIBERNATE {

		@Override
		public String getDialectName(EntityManagerFactory entityManagerFactory) {
			if (HIBERNATE_SESSION_FACTORY.get().isInstance(entityManagerFactory)) {
				// 5.2+ has merged hibernate-entitymanager into hibernate-core, and made EntityManagerFactory impl an instance of SessionFactory, and removed getDialect() shortcut method.
				return invokeMethod(invokeMethod(invokeMethod(entityManagerFactory, "getJdbcServices"), "getJdbcEnvironment"), "getDialect").getClass().getSimpleName();
			}
			else {
				return invokeMethod(invokeMethod(entityManagerFactory, "getSessionFactory"), "getDialect").getClass().getSimpleName();
			}
		}

		@Override
		public boolean isAggregation(Expression<?> expression) {
			return HIBERNATE_BASIC_FUNCTION_EXPRESSION.get().isInstance(expression) && (boolean) invokeMethod(expression, "isAggregation");
		}

		@Override
		public boolean isProxy(Object object) {
			return HIBERNATE_PROXY.get().isInstance(object);
		}

		@Override
		public boolean isProxyInitialized(Object entity) {
			return isProxy(entity) && !(boolean) invokeMethod(invokeMethod(entity, "getHibernateLazyInitializer"), "isUninitialized");
		}

		@Override
		@SuppressWarnings("unchecked")
		public <E> E dereferenceProxy(E entity) {
			return isProxy(entity) ? (E) invokeMethod(invokeMethod(entity, "getHibernateLazyInitializer"), "getImplementation") : entity;
		}

		@Override
		@SuppressWarnings("unchecked")
		public <E> Class<E> getEntityType(E entity) {
			return isProxy(entity) ? (Class<E>) invokeMethod(invokeMethod(entity, "getHibernateLazyInitializer"), "getPersistentClass") : super.getEntityType(entity);
		}
	},

	ECLIPSELINK {

		@Override
		public String getDialectName(EntityManagerFactory entityManagerFactory) {
			return invokeMethod(invokeMethod(entityManagerFactory, "getDatabaseSession"), "getDatasourcePlatform").getClass().getSimpleName();
		}

		@Override
		public boolean isAggregation(Expression<?> expression) {
			return ECLIPSELINK_FUNCTION_EXPRESSION_IMPL.get().isInstance(expression) && AGGREGATE_FUNCTIONS.contains(invokeMethod(expression, "getOperation"));
		}
	},

	OPENJPA {

		@Override
		public String getDialectName(EntityManagerFactory entityManagerFactory) {
			Optional<Method> getDelegate = findMethod(entityManagerFactory, "getDelegate");
			Object openjpaEntityManagerFactory = getDelegate.isPresent() ? invokeMethod(entityManagerFactory, getDelegate.get()) : entityManagerFactory;
			return invokeMethod(invokeMethod(openjpaEntityManagerFactory, "getConfiguration"), "getDBDictionaryInstance").getClass().getSimpleName();
		}

		@Override
		public boolean isAggregation(Expression<?> expression) {
			// We could also invoke toValue() on it and then isAggregate(), but that requires ExpressionFactory and CriteriaQueryImpl arguments which are not trivial to get here.
			return AGGREGATE_FUNCTIONS.contains(expression.getClass().getSimpleName().toUpperCase());
		}

		@Override
		public boolean isElementCollection(Attribute<?, ?> attribute) {
			// For some reason OpenJPA returns PersistentAttributeType.ONE_TO_MANY on an @ElementCollection.
			return ((Field) attribute.getJavaMember()).getAnnotation(ElementCollection.class) != null;
		}

		@Override
		public boolean isOneToMany(Attribute<?, ?> attribute) {
			// For some reason OpenJPA returns PersistentAttributeType.ONE_TO_MANY on an @ElementCollection.
			return !isElementCollection(attribute) && super.isOneToMany(attribute);
		}
	},

	UNKNOWN;

	public static final String QUERY_HINT_HIBERNATE_CACHEABLE = "org.hibernate.cacheable"; // true | false
	public static final String QUERY_HINT_HIBERNATE_CACHE_REGION = "org.hibernate.cacheRegion"; // 2nd level cache region ID
	public static final String QUERY_HINT_ECLIPSELINK_MAINTAIN_CACHE = "eclipselink.maintain-cache"; // true | false
	public static final String QUERY_HINT_ECLIPSELINK_REFRESH = "eclipselink.refresh"; // true | false

	private static final Optional<Class<Object>> HIBERNATE_PROXY = findClass("org.hibernate.proxy.HibernateProxy");
	private static final Optional<Class<Object>> HIBERNATE_SESSION_FACTORY = findClass("org.hibernate.SessionFactory");
	private static final Optional<Class<Object>> HIBERNATE_3_5_0_BASIC_FUNCTION_EXPRESSION = findClass("org.hibernate.ejb.criteria.expression.function.BasicFunctionExpression");
	private static final Optional<Class<Object>> HIBERNATE_4_3_0_BASIC_FUNCTION_EXPRESSION = findClass("org.hibernate.jpa.criteria.expression.function.BasicFunctionExpression");
	private static final Optional<Class<Object>> HIBERNATE_5_2_0_BASIC_FUNCTION_EXPRESSION = findClass("org.hibernate.query.criteria.internal.expression.function.BasicFunctionExpression");
	private static final Optional<Class<Object>> HIBERNATE_BASIC_FUNCTION_EXPRESSION = Stream.of(HIBERNATE_5_2_0_BASIC_FUNCTION_EXPRESSION, HIBERNATE_4_3_0_BASIC_FUNCTION_EXPRESSION, HIBERNATE_3_5_0_BASIC_FUNCTION_EXPRESSION).filter(Optional::isPresent).findFirst().orElse(Optional.empty());
	private static final Optional<Class<Object>> ECLIPSELINK_FUNCTION_EXPRESSION_IMPL = findClass("org.eclipse.persistence.internal.jpa.querydef.FunctionExpressionImpl");
	private static final Set<String> AGGREGATE_FUNCTIONS = unmodifiableSet("MIN", "MAX", "SUM", "AVG", "COUNT");

	public static Provider of(EntityManager entityManager) {
		String packageName = entityManager.getDelegate().getClass().getPackage().getName();

		if (packageName.startsWith("org.hibernate.")) {
			return HIBERNATE;
		}
		else if (packageName.startsWith("org.eclipse.persistence.")) {
			return ECLIPSELINK;
		}
		else if (packageName.startsWith("org.apache.openjpa.")) {
			return OPENJPA;
		}
		else {
			return UNKNOWN;
		}
	}

	public static boolean is(Provider provider) {
		return BaseEntityService.getCurrentInstance().getProvider() == provider;
	}

	public String getDialectName(EntityManagerFactory entityManagerFactory) {
		throw new UnsupportedOperationException(String.valueOf(entityManagerFactory));
	}

	public boolean isAggregation(Expression<?> expression) {
		throw new UnsupportedOperationException(String.valueOf(expression));
	}

	public boolean isElementCollection(Attribute<?, ?> attribute) {
		return attribute.getPersistentAttributeType() == ELEMENT_COLLECTION;
	}

	public boolean isOneToMany(Attribute<?, ?> attribute) {
		return attribute.getPersistentAttributeType() == ONE_TO_MANY;
	}

	public boolean isManyOrOneToOne(Attribute<?, ?> attribute) {
		return isOneOf(attribute.getPersistentAttributeType(), MANY_TO_ONE, ONE_TO_ONE);
	}

	public boolean isProxy(Object entity) {
		throw new UnsupportedOperationException(String.valueOf(entity));
	}

	public boolean isProxyInitialized(Object entity) {
		throw new UnsupportedOperationException(String.valueOf(entity));
	}

	public <E> E dereferenceProxy(E entity) {
		throw new UnsupportedOperationException(String.valueOf(entity));
	}

	@SuppressWarnings("unchecked")
	public <E> Class<E> getEntityType(E entity) {
		return (Class<E>) entity.getClass();
	}

}

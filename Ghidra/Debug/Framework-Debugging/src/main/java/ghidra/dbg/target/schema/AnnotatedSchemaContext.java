/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.dbg.target.schema;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.TypeUtils;

import ghidra.dbg.DebuggerTargetObjectIface;
import ghidra.dbg.attributes.TargetObjectRef;
import ghidra.dbg.attributes.TypedTargetObjectRef;
import ghidra.dbg.target.TargetObject;
import ghidra.dbg.target.schema.DefaultTargetObjectSchema.DefaultAttributeSchema;
import ghidra.dbg.target.schema.TargetObjectSchema.AttributeSchema;
import ghidra.dbg.target.schema.TargetObjectSchema.SchemaName;
import utilities.util.reflection.ReflectionUtilities;

public class AnnotatedSchemaContext extends DefaultSchemaContext {

	static <T> Stream<Class<? extends T>> filterBounds(Class<T> base, Stream<Class<?>> bounds) {
		return bounds.filter(base::isAssignableFrom).map(c -> c.asSubclass(base));
	}

	static Stream<Class<?>> resolveUpperBounds(Class<? extends TargetObjectRef> cls, Type type) {
		if (type == null) {
			return Stream.empty();
		}
		if (type instanceof Class<?>) {
			return Stream.of((Class<?>) type);
		}
		if (type instanceof ParameterizedType) {
			ParameterizedType pt = (ParameterizedType) type;
			return resolveUpperBounds(cls, pt.getRawType());
		}
		if (type instanceof WildcardType) {
			WildcardType wt = (WildcardType) type;
			return Stream.of(TypeUtils.getImplicitUpperBounds(wt))
					.flatMap(t -> resolveUpperBounds(cls, t));
		}
		if (type instanceof TypeVariable) {
			TypeVariable<?> tv = (TypeVariable<?>) type;
			Object decl = tv.getGenericDeclaration();
			if (decl instanceof Class<?>) {
				Class<?> declCls = (Class<?>) decl;
				Map<TypeVariable<?>, Type> args = TypeUtils.getTypeArguments(cls, declCls);
				Type argTv = args.get(tv);
				if (argTv != null) {
					return resolveUpperBounds(cls, argTv);
				}
			}
			return Stream.of(TypeUtils.getImplicitBounds(tv))
					.flatMap(t -> resolveUpperBounds(cls, t));
		}
		/**
		 * NB. This method is always called with a type taken from "T extends TargetObject" So, an
		 * array should never be possible.
		 */
		throw new AssertionError("Cannot handle type: " + type);
	}

	static Set<Class<? extends TargetObject>> getBoundsOfFetchElements(
			Class<? extends TargetObjectRef> cls) {
		try {
			Method method = cls.getMethod("fetchElements", new Class<?>[] { boolean.class });
			Type ret = method.getGenericReturnType();
			Map<TypeVariable<?>, Type> argsCf =
				TypeUtils.getTypeArguments(ret, CompletableFuture.class);
			Type typeCfT = argsCf.get(CompletableFuture.class.getTypeParameters()[0]);

			Map<TypeVariable<?>, Type> argsMap = TypeUtils.getTypeArguments(typeCfT, Map.class);
			Type typeCfMapV = argsMap.get(Map.class.getTypeParameters()[1]);

			return filterBounds(TargetObject.class, resolveUpperBounds(cls, typeCfMapV))
					.collect(Collectors.toSet());
		}
		catch (NoSuchMethodException | SecurityException e) {
			throw new AssertionError(e);
		}
	}

	static Set<Class<? extends TargetObject>> getBoundsOfObjectAttributeGetter(
			Class<? extends TargetObject> cls, Method getter) {
		Class<?> retCls = getter.getReturnType();
		if (TargetObject.class.isAssignableFrom(retCls)) {
			return Set.of(retCls.asSubclass(TargetObject.class));
		}
		Type ret = getter.getGenericReturnType();
		Map<TypeVariable<?>, Type> argsTtor =
			TypeUtils.getTypeArguments(ret, TypedTargetObjectRef.class);
		if (argsTtor != null) {
			Type typeTtorT = argsTtor.get(TypedTargetObjectRef.class.getTypeParameters()[0]);

			return filterBounds(TargetObject.class, resolveUpperBounds(cls, typeTtorT))
					.collect(Collectors.toSet());
		}
		if (TargetObjectRef.class.isAssignableFrom(retCls)) {
			return Set.of(TargetObject.class);
		}
		throw new IllegalArgumentException("Getter " + getter +
			" for attribute must return primitive or subclass of " +
			TargetObjectRef.class);
	}

	protected final Map<Class<? extends TargetObject>, SchemaName> namesByClass =
		new LinkedHashMap<>();
	protected final Map<Class<? extends TargetObject>, TargetObjectSchema> schemasByClass =
		new LinkedHashMap<>();

	protected SchemaName nameFromAnnotatedClass(Class<? extends TargetObject> cls) {
		synchronized (namesByClass) {
			return namesByClass.computeIfAbsent(cls, c -> {
				TargetObjectSchemaInfo info = cls.getAnnotation(TargetObjectSchemaInfo.class);
				if (info == null) {
					// TODO: Compile-time validation?
					throw new IllegalArgumentException("Class " + cls + " is not annotated with @" +
						TargetObjectSchemaInfo.class.getSimpleName());
				}
				String name = info.name();
				if (name.equals("")) {
					return new SchemaName(cls.getSimpleName());
				}
				return new SchemaName(name);
			});
		}
	}

	protected void addPublicMethodsFromClass(SchemaBuilder builder,
			Class<? extends TargetObject> declCls, Class<? extends TargetObject> cls) {
		for (Method declMethod : declCls.getDeclaredMethods()) {
			int mod = declMethod.getModifiers();
			if (!Modifier.isPublic(mod)) {
				continue;
			}

			TargetAttributeType at = declMethod.getAnnotation(TargetAttributeType.class);
			if (at == null) {
				continue;
			}

			// In case it was overridden with a more-specific return type
			Method method;
			try {
				method = cls.getMethod(declMethod.getName(), declMethod.getParameterTypes());
			}
			catch (NoSuchMethodException | SecurityException e) {
				throw new AssertionError(e);
			}

			AttributeSchema attrSchema;
			try {
				attrSchema =
					attributeSchemaFromAnnotatedMethod(declCls, method, at);
			}
			catch (IllegalArgumentException e) {
				throw new IllegalArgumentException(
					"Could not get schema name for attribute accessor " + method + " in " + cls, e);
			}
			if (attrSchema != null) {
				builder.addAttributeSchema(attrSchema, declMethod);
			}
		}
	}

	protected TargetObjectSchema fromAnnotatedClass(Class<? extends TargetObject> cls) {
		synchronized (namesByClass) {
			SchemaName name = nameFromAnnotatedClass(cls);
			return schemasByClass.computeIfAbsent(cls, c -> {
				TargetObjectSchemaInfo info = cls.getAnnotation(TargetObjectSchemaInfo.class);
				SchemaBuilder builder = builder(name);

				Set<Class<?>> allParents = ReflectionUtilities.getAllParents(cls);
				for (Class<?> parent : allParents) {
					DebuggerTargetObjectIface ifaceAnnot =
						parent.getAnnotation(DebuggerTargetObjectIface.class);
					if (ifaceAnnot != null) {
						builder.addInterface(parent.asSubclass(TargetObject.class));
					}
				}

				builder.setCanonicalContainer(info.canonicalContainer());

				boolean sawDefaultElementType = false;
				for (TargetElementType et : info.elements()) {
					if (et.index().equals("")) {
						sawDefaultElementType = true;
					}
					builder.addElementSchema(et.index(), nameFromClass(et.type()), et);
				}
				if (!sawDefaultElementType) {
					Set<Class<? extends TargetObject>> bounds = getBoundsOfFetchElements(cls);
					if (bounds.size() != 1) {
						// TODO: Compile-time validation?
						throw new IllegalArgumentException(
							"Could not identify unique element class: " + bounds);
					}
					else {
						Class<? extends TargetObject> bound = bounds.iterator().next();
						SchemaName schemaName;
						try {
							schemaName = nameFromClass(bound);
						}
						catch (IllegalArgumentException e) {
							throw new IllegalArgumentException(
								"Could not get schema name from bound " + bound + " of " +
									cls + ".fetchElements()",
								e);
						}
						builder.setDefaultElementSchema(schemaName);
					}
				}

				addPublicMethodsFromClass(builder, cls, cls);
				for (Class<?> parent : allParents) {
					if (TargetObject.class.isAssignableFrom(parent)) {
						addPublicMethodsFromClass(builder, parent.asSubclass(TargetObject.class),
							cls);
					}
				}
				for (TargetAttributeType at : info.attributes()) {
					AttributeSchema attrSchema = attributeSchemaFromAnnotation(at);
					builder.addAttributeSchema(attrSchema, at);
				}

				return builder.buildAndAdd();
			});
		}
	}

	protected String attributeNameFromBean(String beanName, boolean isBool) {
		beanName = isBool
				? StringUtils.removeStartIgnoreCase(beanName, "is")
				: StringUtils.removeStartIgnoreCase(beanName, "get");
		if (beanName.equals("")) {
			throw new IllegalArgumentException("Attribute getter must have a name");
		}
		return beanName
				.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
				.replaceAll("([a-z])([A-Z])", "$1_$2")
				.toLowerCase();
	}

	protected AttributeSchema attributeSchemaFromAnnotation(TargetAttributeType at) {
		return new DefaultAttributeSchema(at.name(), nameFromClass(at.type()),
			at.required(), at.fixed(), at.hidden());
	}

	protected AttributeSchema attributeSchemaFromAnnotatedMethod(Class<? extends TargetObject> cls,
			Method method, TargetAttributeType at) {
		if (method.getParameterCount() != 0) {
			// TODO: Compile-time validation?
			throw new IllegalArgumentException(
				"Non-getter method " + method + " is annotated with @" +
					TargetAttributeType.class.getSimpleName());
		}
		String name = at.name();
		Class<?> ret = method.getReturnType();
		if (name.equals("")) {
			name = attributeNameFromBean(method.getName(),
				EnumerableTargetObjectSchema.BOOL.getTypes().contains(ret));
		}
		SchemaName primitiveName =
			EnumerableTargetObjectSchema.nameForPrimitive(ret);
		if (primitiveName != null) {
			return new DefaultAttributeSchema(name, primitiveName,
				at.required(), at.fixed(), at.hidden());
		}
		Set<Class<? extends TargetObject>> bounds = getBoundsOfObjectAttributeGetter(cls, method);
		if (bounds.size() != 1) {
			// TODO: Compile-time validation?
			throw new IllegalArgumentException(
				"Could not identify unique attribute class for method " + method + ": " + bounds);
		}
		return new DefaultAttributeSchema(name, nameFromClass(bounds.iterator().next()),
			at.required(), at.fixed(), at.hidden());
	}

	protected SchemaName nameFromClass(Class<?> cls) {
		SchemaName name = EnumerableTargetObjectSchema.nameForPrimitive(cls);
		if (name != null) {
			return name;
		}
		if (TargetObject.class.isAssignableFrom(cls)) {
			return nameFromAnnotatedClass(cls.asSubclass(TargetObject.class));
		}
		throw new IllegalArgumentException("Cannot figure schema from class: " + cls);
	}

	protected void fillDependencies() {
		while (fillDependenciesRound()) {
			// Action is side-effect of predicate
		}
	}

	protected boolean fillDependenciesRound() {
		Set<Class<? extends TargetObject>> classes = new HashSet<>(namesByClass.keySet());
		classes.removeAll(schemasByClass.keySet());
		if (classes.isEmpty()) {
			return false;
		}
		for (Class<? extends TargetObject> cls : classes) {
			fromAnnotatedClass(cls);
		}
		return true;
	}

	public TargetObjectSchema getSchemaForClass(Class<? extends TargetObject> cls) {
		TargetObjectSchema schema = fromAnnotatedClass(cls);
		fillDependencies();
		return schema;
	}
}

/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, Red Hat, Inc. and/or its affiliates, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.weld.bean.proxy;

import static org.jboss.weld.util.reflection.Reflections.cast;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.enterprise.inject.spi.Bean;

import org.jboss.classfilewriter.AccessFlag;
import org.jboss.classfilewriter.ClassFile;
import org.jboss.classfilewriter.ClassMethod;
import org.jboss.classfilewriter.DuplicateMemberException;
import org.jboss.classfilewriter.code.BranchEnd;
import org.jboss.classfilewriter.code.CodeAttribute;
import org.jboss.weld.Container;
import org.jboss.weld.exceptions.DefinitionException;
import org.jboss.weld.exceptions.WeldException;
import org.jboss.weld.interceptor.proxy.LifecycleMixin;
import org.jboss.weld.interceptor.util.proxy.TargetInstanceProxy;
import org.jboss.weld.logging.BeanLogger;
import org.jboss.weld.security.GetDeclaredConstructorsAction;
import org.jboss.weld.security.GetDeclaredMethodsAction;
import org.jboss.weld.security.NewInstanceAction;
import org.jboss.weld.serialization.spi.BeanIdentifier;
import org.jboss.weld.serialization.spi.ContextualStore;
import org.jboss.weld.serialization.spi.ProxyServices;
import org.jboss.weld.util.Proxies.TypeInfo;
import org.jboss.weld.util.bytecode.Boxing;
import org.jboss.weld.util.bytecode.BytecodeUtils;
import org.jboss.weld.util.bytecode.ClassFileUtils;
import org.jboss.weld.util.bytecode.ConstructorUtils;
import org.jboss.weld.util.bytecode.DeferredBytecode;
import org.jboss.weld.util.bytecode.DescriptorUtils;
import org.jboss.weld.util.bytecode.MethodInformation;
import org.jboss.weld.util.bytecode.RuntimeMethodInformation;
import org.jboss.weld.util.collections.ArraySet;
import org.jboss.weld.util.reflection.Reflections;
import org.jboss.weld.util.reflection.instantiation.InstantiatorFactory;

/**
 * Main factory to produce proxy classes and instances for Weld beans. This
 * implementation creates proxies which forward non-static method invocations to
 * a {@link BeanInstance}. All proxies implement the {@link Proxy} interface.
 *
 * @author David Allen
 * @author Stuart Douglas
 * @author Marius Bogoevici
 * @author Ales Justin
 */
public class ProxyFactory<T> {

    // Default proxy class name suffix
    public static final String PROXY_SUFFIX = "$Proxy$";
    public static final String DEFAULT_PROXY_PACKAGE = "org.jboss.weld.proxies";

    private final Class<?> beanType;
    private final Set<Class<?>> additionalInterfaces = new LinkedHashSet<Class<?>>();
    private final ClassLoader classLoader;
    private final String baseProxyName;
    private final Bean<?> bean;
    private final Class<?> proxiedBeanType;
    private final String contextId;

    public static final String CONSTRUCTED_FLAG_NAME = "constructed";

    private final InstantiatorFactory instantiatorFactory;

    protected static final BytecodeMethodResolver DEFAULT_METHOD_RESOLVER = new DefaultBytecodeMethodResolver();

    protected static final String LJAVA_LANG_REFLECT_METHOD = "Ljava/lang/reflect/Method;";
    protected static final String LJAVA_LANG_BYTE = "Ljava/lang/Byte;";
    protected static final String LJAVA_LANG_CLASS = "Ljava/lang/Class;";
    protected static final String LJAVA_LANG_OBJECT = "Ljava/lang/Object;";
    protected static final String LBEAN_IDENTIFIER = "Lorg/jboss/weld/serialization/spi/BeanIdentifier;";
    protected static final String LJAVA_LANG_STRING = "Ljava/lang/String;";
    protected static final String LJAVA_LANG_THREAD_LOCAL = "Ljava/lang/ThreadLocal;";

    protected static final String INIT_METHOD_NAME = "<init>";
    protected static final String METHOD_HANDLER_FIELD_NAME = "methodHandler";
    private static final String JAVA = "java";

    private static final Set<ProxiedMethodFilter> METHOD_FILTERS;

    static {
        GroovyMethodFilter groovy = new GroovyMethodFilter();
        if (groovy.isEnabled()) {
            METHOD_FILTERS = Collections.<ProxiedMethodFilter>singleton(groovy);
        } else {
            METHOD_FILTERS = Collections.emptySet();
        }
    }

    /**
     * created a new proxy factory from a bean instance. The proxy name is
     * generated from the bean id
     */
    public ProxyFactory(String contextId, Class<?> proxiedBeanType, Set<? extends Type> typeClosure, Bean<?> bean) {
        this(contextId, proxiedBeanType, typeClosure, bean, false);
    }

    public ProxyFactory(String contextId, Class<?> proxiedBeanType, Set<? extends Type> typeClosure, Bean<?> bean, boolean forceSuperClass) {
        this(contextId, proxiedBeanType, typeClosure, getProxyName(contextId, proxiedBeanType, typeClosure, bean), bean, forceSuperClass);
    }

    /**
     * Creates a new proxy factory when the name of the proxy class is already
     * known, such as during de-serialization
     *
     * @param proxiedBeanType the super-class for this proxy class
     * @param typeClosure     the bean types of the bean
     * @param proxyName       the name of the proxy class
     */
    public ProxyFactory(String contextId, Class<?> proxiedBeanType, Set<? extends Type> typeClosure, String proxyName, Bean<?> bean) {
        this(contextId, proxiedBeanType, typeClosure, proxyName, bean, false);
    }

    public ProxyFactory(String contextId, Class<?> proxiedBeanType, Set<? extends Type> typeClosure, String proxyName, Bean<?> bean, boolean forceSuperClass) {
        this.bean = bean;
        this.contextId = contextId;
        this.proxiedBeanType = proxiedBeanType;
        addInterfacesFromTypeClosure(typeClosure, proxiedBeanType);
        TypeInfo typeInfo = TypeInfo.of(typeClosure);
        Class<?> superClass = typeInfo.getSuperClass();
        superClass = superClass == null ? Object.class : superClass;
        if (forceSuperClass || (superClass.equals(Object.class) && additionalInterfaces.isEmpty())) {
            // No interface beans must use the bean impl as superclass
            superClass = proxiedBeanType;
        }
        this.beanType = superClass;


        addDefaultAdditionalInterfaces();
        baseProxyName = proxyName;
        if (bean != null) {
            /*
             * this may happen when creating an InjectionTarget for a decorator using BeanManager#createInjectionTarget()
             * which does not allow the bean to be specified
             */
            this.classLoader = resolveClassLoaderForBeanProxy(contextId, bean.getBeanClass(), typeInfo);
        } else {
            this.classLoader = resolveClassLoaderForBeanProxy(contextId, proxiedBeanType, typeInfo);
        }
        // hierarchy order
        List<Class<?>> list = new ArrayList<Class<?>>(additionalInterfaces);
        Collections.sort(list, ClassHierarchyComparator.INSTANCE);
        additionalInterfaces.clear();
        additionalInterfaces.addAll(list);

        InstantiatorFactory factory = Container.instance(contextId).services().get(InstantiatorFactory.class);
        if (factory != null && factory.useInstantiators() && isCreatingProxy()) {
            this.instantiatorFactory = factory;
        } else {
            this.instantiatorFactory = null;
        }
    }

    static String getProxyName(String contextId, Class<?> proxiedBeanType, Set<? extends Type> typeClosure, Bean<?> bean) {
        TypeInfo typeInfo = TypeInfo.of(typeClosure);
        String proxyPackage;
        if (proxiedBeanType.equals(Object.class)) {
            Class<?> superInterface = typeInfo.getSuperInterface();
            if (superInterface == null) {
                throw new IllegalArgumentException("Proxied bean type cannot be java.lang.Object without an interface");
            } else {
                proxyPackage = DEFAULT_PROXY_PACKAGE;
            }
        } else {
            if (proxiedBeanType.getPackage() == null) {
                proxyPackage = DEFAULT_PROXY_PACKAGE;
            } else {
                proxyPackage = proxiedBeanType.getPackage().getName();
            }
        }
        final String className;

        if (typeInfo.getSuperClass() == Object.class) {
            final StringBuilder name = new StringBuilder();
            //interface only bean.
            className = createCompoundProxyName(contextId, bean, typeInfo, name) + PROXY_SUFFIX;
        } else {
            boolean typeModified = false;
            for (Class<?> iface : typeInfo.getInterfaces()) {
                if (!iface.isAssignableFrom(typeInfo.getSuperClass())) {
                    typeModified = true;
                    break;
                }
            }
            if (typeModified) {
                //this bean has interfaces that the base type is not assignable to
                //which can happen with some creative use of the SPI
                //interface only bean.
                StringBuilder name = new StringBuilder(typeInfo.getSuperClass().getSimpleName() + "$");
                className = createCompoundProxyName(contextId, bean, typeInfo, name) + PROXY_SUFFIX;
            } else {
                className = typeInfo.getSuperClass().getSimpleName() + PROXY_SUFFIX;
            }
        }


        return proxyPackage + '.' + getEnclosingPrefix(proxiedBeanType) + className;
    }

    public void addInterfacesFromTypeClosure(Set<? extends Type> typeClosure, Class<?> proxiedBeanType) {
        for (Type type : typeClosure) {
            Class<?> c = Reflections.getRawType(type);
            // Ignore no-interface views, they are dealt with proxiedBeanType
            // (pending redesign)
            if (c.isInterface()) {
                addInterface(c);
            }
        }
    }

    private static String createCompoundProxyName(String contextId, Bean<?> bean, TypeInfo typeInfo, StringBuilder name) {
        String className;
        final List<String> interfaces = new ArrayList<String>();
        for (Class<?> type : typeInfo.getInterfaces()) {
            interfaces.add(type.getSimpleName());
        }
        Collections.sort(interfaces);
        for (final String iface : interfaces) {
            name.append(iface);
            name.append('$');
        }
        //there is a remote chance that this could generate the same
        //proxy name for two interfaces with the same simple name.
        //append the hash code of the bean id to be sure
        if (bean != null) {
            final BeanIdentifier id = Container.instance(contextId).services().get(ContextualStore.class).putIfAbsent(bean);
            int idHash = id.hashCode();
            name.append(Math.abs(idHash == Integer.MIN_VALUE ? 0 : idHash));
        }
        className = name.toString();
        return className;
    }

    private static String getEnclosingPrefix(Class<?> clazz) {
        Class<?> encl = clazz.getEnclosingClass();
        return encl == null ? "" : getEnclosingPrefix(encl) + encl.getSimpleName() + '$';
    }

    protected boolean isCreatingProxy() {
        return true;
    }

    /**
     * Adds an additional interface that the proxy should implement. The default
     * implementation will be to forward invocations to the bean instance.
     *
     * @param newInterface an interface
     */
    public void addInterface(Class<?> newInterface) {
        if (!newInterface.isInterface()) {
            throw new IllegalArgumentException(newInterface + " is not an interface");
        }
        additionalInterfaces.add(newInterface);
    }

    /**
     * Method to create a new proxy that wraps the bean instance.
     *
     * @param beanInstance the bean instance
     * @return a new proxy object
     */
    public T create(BeanInstance beanInstance) {
        T proxy;
        Class<T> proxyClass = getProxyClass();
        try {
            if (instantiatorFactory != null) {
                proxy = instantiatorFactory.getInstantiator().instantiate(proxyClass);
            } else {
                proxy = AccessController.doPrivileged(NewInstanceAction.of(proxyClass));
            }
        } catch (PrivilegedActionException e) {
            if (e.getCause() instanceof InstantiationException) {
                throw new DefinitionException(BeanLogger.LOG.proxyInstantiationFailed(this), e.getCause());
            } else if (e.getCause() instanceof IllegalAccessException) {
                throw new DefinitionException(BeanLogger.LOG.proxyInstantiationBeanAccessFailed(this), e.getCause());
            } else {
                throw new WeldException(e.getCause());
            }
        }
        ((ProxyObject) proxy).setHandler(new ProxyMethodHandler(contextId, beanInstance, bean));
        return proxy;
    }

    /**
     * Produces or returns the existing proxy class. The operation is thread-safe.
     *
     * @return always the class of the proxy
     */
    public Class<T> getProxyClass() {
        String suffix = "_$$_Weld" + getProxyNameSuffix();
        String proxyClassName = getBaseProxyName();
        if (!proxyClassName.endsWith(suffix)) {
            proxyClassName = proxyClassName + suffix;
        }
        if (proxyClassName.startsWith(JAVA)) {
            proxyClassName = proxyClassName.replaceFirst(JAVA, "org.jboss.weld");
        }
        Class<T> proxyClass = null;
        BeanLogger.LOG.generatingProxyClass(proxyClassName);
        try {
            // First check to see if we already have this proxy class
            proxyClass = cast(classLoader.loadClass(proxyClassName));
        } catch (ClassNotFoundException e) {
            // Create the proxy class for this instance
            try {
                proxyClass = createProxyClass(proxyClassName);
            } catch (Throwable e1) {
                //attempt to load the class again, just in case another thread
                //defined it between the check and the create method
                try {
                    proxyClass = cast(classLoader.loadClass(proxyClassName));
                } catch (ClassNotFoundException e2) {
                    throw BeanLogger.LOG.unableToLoadProxyClass(bean, proxiedBeanType, classLoader, e1);
                }
            }
        }
        return proxyClass;
    }

    protected Class<T> getCachedProxyClass(String proxyClassName) {
        try {
            // Check to see if we already have this proxy class
            return cast(classLoader.loadClass(proxyClassName));
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * Returns the package and base name for the proxy class.
     *
     * @return base name without suffixes
     */
    protected String getBaseProxyName() {
        return baseProxyName;
    }

    /**
     * Convenience method to determine if an object is a proxy generated by this
     * factory or any derived factory.
     *
     * @param proxySuspect the object suspected of being a proxy
     * @return true only if it is a proxy object
     */
    public static boolean isProxy(Object proxySuspect) {
        return proxySuspect instanceof ProxyObject;
    }

    /**
     * Convenience method to set the underlying bean instance for a proxy.
     *
     * @param proxy        the proxy instance
     * @param beanInstance the instance of the bean
     */
    public static <T> void setBeanInstance(String contextId, T proxy, BeanInstance beanInstance, Bean<?> bean) {
        if (proxy instanceof ProxyObject) {
            ProxyObject proxyView = (ProxyObject) proxy;
            proxyView.setHandler(new ProxyMethodHandler(contextId, beanInstance, bean));
        }
    }

    /**
     * Returns a suffix to append to the name of the proxy class. The name
     * already consists of <class-name>_$$_Weld, to which the suffix is added.
     * This allows the creation of different types of proxies for the same class.
     *
     * @return a name suffix
     */
    protected String getProxyNameSuffix() {
        return PROXY_SUFFIX;
    }

    private void addDefaultAdditionalInterfaces() {
        additionalInterfaces.add(Serializable.class);
    }

    /**
     * Sub classes may override to specify additional interfaces the proxy should
     * implement
     */
    protected void addAdditionalInterfaces(Set<Class<?>> interfaces) {

    }

    private Class<T> createProxyClass(String proxyClassName) throws Exception {
        ArraySet<Class<?>> specialInterfaces = new ArraySet<Class<?>>(
                LifecycleMixin.class, TargetInstanceProxy.class, ProxyObject.class);
        addAdditionalInterfaces(specialInterfaces);
        // Remove special interfaces from main set (deserialization scenario)
        additionalInterfaces.removeAll(specialInterfaces);

        ClassFile proxyClassType = null;
        if (getBeanType().isInterface()) {
            proxyClassType = new ClassFile(proxyClassName, Object.class.getName());
            proxyClassType.addInterface(getBeanType().getName());
        } else {
            proxyClassType = new ClassFile(proxyClassName, getBeanType().getName());
        }
        // Add interfaces which require method generation
        for (Class<?> clazz : additionalInterfaces) {
            proxyClassType.addInterface(clazz.getName());
        }
        List<DeferredBytecode> initialValueBytecode = new ArrayList<DeferredBytecode>();


        ClassMethod staticConstructor = proxyClassType.addMethod(AccessFlag.PUBLIC, "<clinit>", "V");

        addFields(proxyClassType, initialValueBytecode);
        addConstructors(proxyClassType, initialValueBytecode);
        addMethods(proxyClassType, staticConstructor);

        staticConstructor.getCodeAttribute().returnInstruction();

        // Additional interfaces whose methods require special handling
        for (Class<?> specialInterface : specialInterfaces) {
            proxyClassType.addInterface(specialInterface.getName());
        }
        // TODO: change the ProxyServices SPI to allow the container to figure out
        // which PD to use


        ProtectionDomain domain = proxiedBeanType.getProtectionDomain();
        if (proxiedBeanType.getPackage() == null || proxiedBeanType.equals(Object.class)) {
            domain = ProxyFactory.class.getProtectionDomain();
        }
        Class<T> proxyClass = cast(ClassFileUtils.toClass(proxyClassType, classLoader, domain));
        BeanLogger.LOG.createdProxyClass(proxyClass, Arrays.toString(proxyClass.getInterfaces()));
        return proxyClass;
    }

    /**
     * Adds a constructor for the proxy for each constructor declared by the base
     * bean type.
     *
     * @param proxyClassType       the Javassist class for the proxy
     * @param initialValueBytecode
     */
    protected void addConstructors(ClassFile proxyClassType, List<DeferredBytecode> initialValueBytecode) {
        try {
            if (getBeanType().isInterface()) {
                ConstructorUtils.addDefaultConstructor(proxyClassType, initialValueBytecode, isUsingUnsafeInstantiators());
            } else {
                boolean constructorFound = false;
                for (Constructor<?> constructor : AccessController.doPrivileged(new GetDeclaredConstructorsAction(getBeanType()))) {
                    if ((constructor.getModifiers() & Modifier.PRIVATE) == 0) {
                        constructorFound = true;
                        String[] exceptions = new String[constructor.getExceptionTypes().length];
                        for (int i = 0; i < exceptions.length; ++i) {
                            exceptions[i] = constructor.getExceptionTypes()[i].getName();
                        }
                        ConstructorUtils.addConstructor(DescriptorUtils.VOID_CLASS_DESCRIPTOR, DescriptorUtils.getParameterTypes(constructor.getParameterTypes()), exceptions, proxyClassType, initialValueBytecode, isUsingUnsafeInstantiators());
                    }
                }
                if (!constructorFound) {
                    // the bean only has private constructors, we need to generate
                    // two fake constructors that call each other
                    addConstructorsForBeanWithPrivateConstructors(proxyClassType);
                }
            }
        } catch (Exception e) {
            throw new WeldException(e);
        }
    }

    protected void addFields(ClassFile proxyClassType, List<DeferredBytecode> initialValueBytecode) {
        // The field representing the underlying instance or special method
        // handling
        proxyClassType.addField(AccessFlag.PRIVATE, METHOD_HANDLER_FIELD_NAME, MethodHandler.class);
        if(!isUsingUnsafeInstantiators()) {
            // field used to indicate that super() has been called
            proxyClassType.addField(AccessFlag.PRIVATE, CONSTRUCTED_FLAG_NAME, DescriptorUtils.BOOLEAN_CLASS_DESCRIPTOR);
        }

    }

    protected void addMethods(ClassFile proxyClassType, ClassMethod staticConstructor) {
        // Add all class methods for interception
        addMethodsFromClass(proxyClassType, staticConstructor);

        // Add special proxy methods
        addSpecialMethods(proxyClassType, staticConstructor);

        // Add serialization support methods
        addSerializationSupport(proxyClassType);
    }

    /**
     * Adds special serialization code. By default this is a nop
     *
     * @param proxyClassType the Javassist class for the proxy class
     */
    protected void addSerializationSupport(ClassFile proxyClassType) {
        //noop
    }

    protected void addMethodsFromClass(ClassFile proxyClassType, ClassMethod staticConstructor) {
        try {
            // Add all methods from the class hierarchy
            Class<?> cls = getBeanType();
            // first add equals/hashCode methods if required
            generateEqualsMethod(proxyClassType);

            generateHashCodeMethod(proxyClassType);

            while (cls != null) {
                for (Method method : AccessController.doPrivileged(new GetDeclaredMethodsAction(cls))) {
                    if (!Modifier.isStatic(method.getModifiers()) &&
                            !Modifier.isFinal(method.getModifiers()) &&
                            (method.getDeclaringClass() != Object.class || method.getName().equals("toString")) &&
                            isMethodAccepted(method)) {
                        try {
                            MethodInformation methodInfo = new RuntimeMethodInformation(method);
                            ClassMethod classMethod = proxyClassType.addMethod(method);
                            addConstructedGuardToMethodBody(classMethod);
                            createForwardingMethodBody(classMethod, methodInfo, staticConstructor);
                            BeanLogger.LOG.addingMethodToProxy(method);
                        } catch (DuplicateMemberException e) {
                            // do nothing. This will happen if superclass methods
                            // have been overridden
                        }
                    }
                }
                cls = cls.getSuperclass();
            }
            for (Class<?> c : additionalInterfaces) {
                for (Method method : c.getMethods()) {
                    if (!Modifier.isStatic(method.getModifiers()) && isMethodAccepted(method)) {
                        try {
                            MethodInformation methodInfo = new RuntimeMethodInformation(method);
                            ClassMethod classMethod = proxyClassType.addMethod(method);
                            createSpecialMethodBody(classMethod, methodInfo, staticConstructor);
                            BeanLogger.LOG.addingMethodToProxy(method);
                        } catch (DuplicateMemberException e) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new WeldException(e);
        }
    }

    private boolean isMethodAccepted(Method method) {
        for (ProxiedMethodFilter filter : METHOD_FILTERS) {
            if (!filter.accept(method)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Generate the body of the proxies hashCode method.
     * <p/>
     * If this method returns null, the method will not be added, and the
     * hashCode on the superclass will be used as per normal virtual method
     * resolution rules
     */
    protected void generateHashCodeMethod(ClassFile proxyClassType) {
    }

    /**
     * Generate the body of the proxies equals method.
     * <p/>
     * If this method returns null, the method will not be added, and the
     * hashCode on the superclass will be used as per normal virtual method
     * resolution rules
     *
     * @param proxyClassType The class file
     */
    protected void generateEqualsMethod(ClassFile proxyClassType) {

    }

    protected void createSpecialMethodBody(ClassMethod proxyClassType, MethodInformation method, ClassMethod staticConstructor) {
        createInterceptorBody(proxyClassType, method, staticConstructor);
    }

    /**
     * Adds the following code to a delegating method:
     * <p/>
     * <code>
     * if(!this.constructed) return super.thisMethod()
     * </code>
     * <p/>
     * This means that the proxy will not start to delegate to the underlying
     * bean instance until after the constructor has finished.
     */
    protected void addConstructedGuardToMethodBody(final ClassMethod classMethod) {
        if(isUsingUnsafeInstantiators()) {
            return;
        }
        // now create the conditional
        final CodeAttribute cond = classMethod.getCodeAttribute();
        cond.aload(0);
        cond.getfield(classMethod.getClassFile().getName(), CONSTRUCTED_FLAG_NAME, DescriptorUtils.BOOLEAN_CLASS_DESCRIPTOR);

        // jump if the proxy constructor has finished
        BranchEnd jumpMarker = cond.ifne();
        // generate the invokespecial call to the super class method
        // this is run when the proxy is being constructed
        cond.aload(0);
        cond.loadMethodParameters();
        cond.invokespecial(classMethod.getClassFile().getSuperclass(), classMethod.getName(), classMethod.getDescriptor());
        cond.returnInstruction();
        cond.branchEnd(jumpMarker);
    }

    protected void createForwardingMethodBody(ClassMethod classMethod, MethodInformation method, ClassMethod staticConstructor) {
        createInterceptorBody(classMethod, method, staticConstructor);
    }

    /**
     * Creates the given method on the proxy class where the implementation
     * forwards the call directly to the method handler.
     * <p/>
     * the generated bytecode is equivalent to:
     * <p/>
     * return (RetType) methodHandler.invoke(this,param1,param2);
     *
     * @param classMethod the class method
     * @param method      any JLR method
     * @return the method byte code
     */
    protected void createInterceptorBody(ClassMethod classMethod, MethodInformation method, ClassMethod staticConstructor) {
        invokeMethodHandler(classMethod, method, true, DEFAULT_METHOD_RESOLVER, staticConstructor);
    }

    /**
     * calls methodHandler.invoke for a given method
     *
     * @param method                 The method information
     * @param addReturnInstruction   set to true you want to return the result of
     *                               the method invocation
     * @param bytecodeMethodResolver The resolver that returns the method to invoke
     */
    protected static void invokeMethodHandler(ClassMethod classMethod, MethodInformation method, boolean addReturnInstruction, BytecodeMethodResolver bytecodeMethodResolver, ClassMethod staticConstructor) {
        // now we need to build the bytecode. The order we do this in is as
        // follows:
        // load methodHandler
        // load this
        // load the method object
        // load null
        // create a new array the same size as the number of parameters
        // push our parameter values into the array
        // invokeinterface the invoke method
        // add checkcast to cast the result to the return type, or unbox if
        // primitive
        // add an appropriate return instruction
        final CodeAttribute b = classMethod.getCodeAttribute();
        b.aload(0);
        b.getfield(classMethod.getClassFile().getName(), METHOD_HANDLER_FIELD_NAME, DescriptorUtils.classToStringRepresentation(MethodHandler.class));
        b.aload(0);
        bytecodeMethodResolver.getDeclaredMethod(classMethod, method.getDeclaringClass(), method.getName(), method.getParameterTypes(), staticConstructor);
        b.aconstNull();

        b.iconst(method.getParameterTypes().length);
        b.anewarray("java.lang.Object");

        int localVariableCount = 1;

        for (int i = 0; i < method.getParameterTypes().length; ++i) {
            String typeString = method.getParameterTypes()[i];
            b.dup(); // duplicate the array reference
            b.iconst(i);
            // load the parameter value
            BytecodeUtils.addLoadInstruction(b, typeString, localVariableCount);
            // box the parameter if necessary
            Boxing.boxIfNecessary(b, typeString);
            // and store it in the array
            b.aastore();
            if (DescriptorUtils.isWide(typeString)) {
                localVariableCount = localVariableCount + 2;
            } else {
                localVariableCount++;
            }
        }
        // now we have all our arguments on the stack
        // lets invoke the method
        b.invokeinterface(MethodHandler.class.getName(), "invoke", LJAVA_LANG_OBJECT, new String[] { LJAVA_LANG_OBJECT,
                LJAVA_LANG_REFLECT_METHOD, LJAVA_LANG_REFLECT_METHOD, "[" + LJAVA_LANG_OBJECT });
        if (addReturnInstruction) {
            // now we need to return the appropriate type
            if (method.getReturnType().equals(DescriptorUtils.VOID_CLASS_DESCRIPTOR)) {
                b.returnInstruction();
            } else if(DescriptorUtils.isPrimitive(method.getReturnType())) {
                Boxing.unbox(b, method.getReturnType());
                b.returnInstruction();
            } else {
                String castType = method.getReturnType();
                if (!method.getReturnType().startsWith("[")) {
                    castType = method.getReturnType().substring(1).substring(0, method.getReturnType().length() - 2);
                }
                b.checkcast(castType);
                b.returnInstruction();
            }
        }
    }

    /**
     * Adds methods requiring special implementations rather than just
     * delegation.
     *
     * @param proxyClassType the Javassist class description for the proxy type
     */
    protected void addSpecialMethods(ClassFile proxyClassType, ClassMethod staticConstructor) {
        try {
            // Add special methods for interceptors
            for (Method method : LifecycleMixin.class.getMethods()) {
                BeanLogger.LOG.addingMethodToProxy(method);
                MethodInformation methodInfo = new RuntimeMethodInformation(method);
                final ClassMethod classMethod = proxyClassType.addMethod(method);
                createInterceptorBody(classMethod, methodInfo, staticConstructor);
            }
            Method getInstanceMethod = TargetInstanceProxy.class.getMethod("getTargetInstance");
            Method getInstanceClassMethod = TargetInstanceProxy.class.getMethod("getTargetClass");

            MethodInformation getInstanceMethodInfo = new RuntimeMethodInformation(getInstanceMethod);
            createInterceptorBody(proxyClassType.addMethod(getInstanceMethod), getInstanceMethodInfo, staticConstructor);


            MethodInformation getInstanceClassMethodInfo = new RuntimeMethodInformation(getInstanceClassMethod);
            createInterceptorBody(proxyClassType.addMethod(getInstanceClassMethod), getInstanceClassMethodInfo, staticConstructor);

            Method setMethodHandlerMethod = ProxyObject.class.getMethod("setHandler", MethodHandler.class);
            generateSetMethodHandlerBody(proxyClassType.addMethod(setMethodHandlerMethod));

            Method getMethodHandlerMethod = ProxyObject.class.getMethod("getHandler");
            generateGetMethodHandlerBody(proxyClassType.addMethod(getMethodHandlerMethod));
        } catch (Exception e) {
            throw new WeldException(e);
        }
    }

    private static void generateSetMethodHandlerBody(ClassMethod method) {
        final CodeAttribute b = method.getCodeAttribute();
        b.aload(0);
        b.aload(1);
        b.putfield(method.getClassFile().getName(), METHOD_HANDLER_FIELD_NAME, DescriptorUtils.classToStringRepresentation(MethodHandler.class));
        b.returnInstruction();
    }

    private static void generateGetMethodHandlerBody(ClassMethod method) {
        final CodeAttribute b = method.getCodeAttribute();
        b.aload(0);
        b.getfield(method.getClassFile().getName(), METHOD_HANDLER_FIELD_NAME, DescriptorUtils.classToStringRepresentation(MethodHandler.class));
        b.returnInstruction();
    }


    /**
     * Adds two constructors to the class that call each other in order to bypass
     * the JVM class file verifier.
     * <p/>
     * This would result in a stack overflow if they were actually called,
     * however the proxy is directly created without calling the constructor
     */
    private void addConstructorsForBeanWithPrivateConstructors(ClassFile proxyClassType) {
        ClassMethod ctor = proxyClassType.addMethod(AccessFlag.PUBLIC, INIT_METHOD_NAME, DescriptorUtils.VOID_CLASS_DESCRIPTOR, LJAVA_LANG_BYTE);
        CodeAttribute b = ctor.getCodeAttribute();
        b.aload(0);
        b.aconstNull();
        b.aconstNull();
        b.invokespecial(proxyClassType.getName(), INIT_METHOD_NAME, "(" + LJAVA_LANG_BYTE + LJAVA_LANG_BYTE + ")" + DescriptorUtils.VOID_CLASS_DESCRIPTOR);
        b.returnInstruction();

        ctor = proxyClassType.addMethod(AccessFlag.PUBLIC, INIT_METHOD_NAME, DescriptorUtils.VOID_CLASS_DESCRIPTOR, LJAVA_LANG_BYTE, LJAVA_LANG_BYTE);
        b = ctor.getCodeAttribute();
        b.aload(0);
        b.aconstNull();
        b.invokespecial(proxyClassType.getName(), INIT_METHOD_NAME, "(" + LJAVA_LANG_BYTE + ")" + DescriptorUtils.VOID_CLASS_DESCRIPTOR);
        b.returnInstruction();
    }

    public Class<?> getBeanType() {
        return beanType;
    }

    public Set<Class<?>> getAdditionalInterfaces() {
        return additionalInterfaces;
    }

    public Bean<?> getBean() {
        return bean;
    }

    public String getContextId() {
        return contextId;
    }

    /**
     * Figures out the correct class loader to use for a proxy for a given bean
     */
    public static ClassLoader resolveClassLoaderForBeanProxy(String contextId, Class<?> proxiedType, TypeInfo typeInfo) {
        Class<?> superClass = typeInfo.getSuperClass();
        if (superClass.getName().startsWith(JAVA)) {
            ClassLoader cl = Container.instance(contextId).services().get(ProxyServices.class).getClassLoader(proxiedType);
            if (cl == null) {
                cl = Thread.currentThread().getContextClassLoader();
            }
            return cl;
        }
        return Container.instance(contextId).services().get(ProxyServices.class).getClassLoader(superClass);
    }

    protected boolean isUsingUnsafeInstantiators() {
        return instantiatorFactory != null;
    }

}

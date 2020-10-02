package sexy.kostya.proto4j.rpc.service;

import com.google.common.primitives.Primitives;
import sexy.kostya.proto4j.exception.Proto4jProxyingException;
import sexy.kostya.proto4j.rpc.annotation.Index;
import sexy.kostya.proto4j.rpc.annotation.MethodIdentifier;
import sexy.kostya.proto4j.rpc.annotation.Proto4jService;
import sexy.kostya.proto4j.rpc.serialization.SerializationMaster;
import sexy.kostya.proto4j.rpc.transport.packet.RpcInvocationPacket;
import sexy.kostya.proto4j.rpc.transport.packet.RpcResponsePacket;
import sexy.kostya.proto4j.transport.buffer.Buffer;
import sexy.kostya.proto4j.transport.buffer.BufferImpl;
import sexy.kostya.proto4j.transport.util.DatagramHelper;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Created by k.shandurenko on 30.09.2020
 */
public abstract class InternalServiceManagerImpl implements InternalServiceManager {

    private final static int JAVA_VERSION = getJavaVersion();

    private static int getJavaVersion() {
        String version = System.getProperty("java.version");
        if (version.startsWith("1.")) {
            version = version.substring(2, 3);
        } else {
            int dot = version.indexOf(".");
            if (dot != -1) {
                version = version.substring(0, dot);
            }
        }
        return Integer.parseInt(version);
    }

    private final Map<Integer, Map<Integer, Function<byte[], CompletionStage<byte[]>>>> implementations = new HashMap<>();
    private final MethodHandles.Lookup                                                  lookup          = MethodHandles.lookup();

    @Override
    @SuppressWarnings("unchecked")
    public <S, I extends S> int registerService(Class<S> serviceInterface, I implementation) {
        Class<?> clazz = implementation.getClass();
        if (!serviceInterface.isAssignableFrom(clazz)) {
            throw new Proto4jProxyingException(clazz.getSimpleName() + " does not inherit " + serviceInterface.getSimpleName());
        }
        if (!serviceInterface.isInterface()) {
            throw new Proto4jProxyingException(serviceInterface.getSimpleName() + " is not an interface");
        }
        if (!serviceInterface.isAnnotationPresent(Proto4jService.class)) {
            throw new Proto4jProxyingException(serviceInterface.getSimpleName() + " is not a Proto4jService");
        }
        Proto4jService annotation        = serviceInterface.getAnnotation(Proto4jService.class);
        int            serviceIdentifier = annotation.explicitIdentifier() != 0 ? annotation.explicitIdentifier() : serviceInterface.getSimpleName().hashCode();

        Map<Integer, Function<byte[], CompletionStage<byte[]>>> methods = new HashMap<>();
        for (Method m : serviceInterface.getDeclaredMethods()) {
            if (m.isDefault()) {
                continue;
            }
            try {
                Method method         = clazz.getDeclaredMethod(m.getName(), m.getParameterTypes());
                Type   returnType     = method.getGenericReturnType();
                Type[] parameterTypes = method.getGenericParameterTypes();
                int    methodIdentifier;
                if (method.isAnnotationPresent(MethodIdentifier.class)) {
                    MethodIdentifier methodAnnotation = method.getAnnotation(MethodIdentifier.class);
                    if (methodAnnotation.value() == 0) {
                        throw new Proto4jProxyingException("Identifier for " + clazz.getSimpleName() + "#" + method.getName() + " is zero, it's now allowed");
                    }
                    methodIdentifier = methodAnnotation.value();
                } else {
                    methodIdentifier = method.getName().hashCode();
                }
                Function[] readers = new Function[parameterTypes.length];
                for (int i = 0; i < parameterTypes.length; ++i) {
                    readers[i] = SerializationMaster.getReader(parameterTypes[i]);
                }
                Function<Object[], Object>                invocation = getMethodInvocation(implementation, method);
                Function<byte[], CompletionStage<byte[]>> func;
                if (returnType == void.class) {
                    func = bytes -> {
                        invocation.apply(deserializeArguments(bytes, readers));
                        return null;
                    };
                } else if (returnType instanceof ParameterizedType && ((ParameterizedType) returnType).getRawType() == CompletionStage.class) {
                    returnType = ((ParameterizedType) returnType).getActualTypeArguments()[0];
                    BiConsumer<Buffer, Object> writer = SerializationMaster.getWriter(returnType);
                    func = bytes -> {
                        Object[]                  args   = deserializeArguments(bytes, readers);
                        CompletionStage<?>        future = (CompletionStage<?>) invocation.apply(args);
                        CompletableFuture<byte[]> result = new CompletableFuture<>();
                        future.whenComplete((o, ex) -> {
                            if (ex == null) {
                                try (Buffer buf = Buffer.newBuffer()) {
                                    writer.accept(buf, o);
                                    result.complete(((BufferImpl) buf).getHandle().array());
                                }
                            } else {
                                result.completeExceptionally(ex);
                            }
                        });
                        return result;
                    };
                } else {
                    if (returnType instanceof Class) {
                        returnType = Primitives.wrap((Class) returnType);
                    }
                    BiConsumer<Buffer, Object> writer = SerializationMaster.getWriter(returnType);
                    func = bytes -> {
                        Object[] args = deserializeArguments(bytes, readers);
                        try (Buffer buffer = Buffer.newBuffer()) {
                            writer.accept(buffer, invocation.apply(args));
                            return CompletableFuture.completedFuture(((BufferImpl) buffer).getHandle().array());
                        }
                    };
                }
                methods.put(methodIdentifier, func);
            } catch (NoSuchMethodException e) {
                throw new Proto4jProxyingException(clazz.getSimpleName() + "#" + m.getName() + " is not present");
            } catch (Exception e) {
                throw new Proto4jProxyingException(clazz.getSimpleName() + "#" + m.getName() + " can't be proxied", e);
            }
        }
        this.implementations.put(serviceIdentifier, methods);
        return serviceIdentifier;
    }

    public boolean isServiceRegisteredThere(int serviceIdentifier) {
        return this.implementations.containsKey(serviceIdentifier);
    }

    @Override
    public CompletionStage<RpcResponsePacket> invoke(RpcInvocationPacket packet) {
        Map<Integer, Function<byte[], CompletionStage<byte[]>>> implementation = this.implementations.get(packet.getServiceID());
        if (implementation == null) {
            return CompletableFuture.completedFuture(new RpcResponsePacket("Unknown implementation error", null));
        }
        Function<byte[], CompletionStage<byte[]>> method = implementation.get(packet.getMethodID());
        if (method == null) {
            return CompletableFuture.completedFuture(new RpcResponsePacket("Unknown method error", null));
        }
        CompletionStage<byte[]> future = method.apply(packet.getArguments());
        if (future == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<RpcResponsePacket> result = new CompletableFuture<>();
        future.whenComplete((bytes, ex) -> {
            if (ex == null) {
                result.complete(new RpcResponsePacket(null, bytes));
            } else {
                result.complete(new RpcResponsePacket(ex.getMessage(), null));
            }
        });
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S> S getService(Class<S> clazz) {
        if (!clazz.isInterface()) {
            throw new Proto4jProxyingException(clazz.getSimpleName() + " is not an interface");
        }

        if (!clazz.isAnnotationPresent(Proto4jService.class)) {
            throw new Proto4jProxyingException(clazz.getSimpleName() + " is not a Proto4jService");
        }
        Proto4jService annotation        = clazz.getAnnotation(Proto4jService.class);
        int            serviceIdentifier = annotation.explicitIdentifier() != 0 ? annotation.explicitIdentifier() : clazz.getSimpleName().hashCode();

        Map<Method, CheckedFunction<Object[], Object>> methods           = new ConcurrentHashMap<>();
        Set<Integer>                                   methodIdentifiers = new HashSet<>();

        return (S) Proxy.newProxyInstance(clazz.getClassLoader(), new Class[]{clazz}, (proxy, passedMethod, passedArgs) -> {
            //noinspection CodeBlock2Expr
            return methods.computeIfAbsent(passedMethod, method -> {
                if (method.isDefault()) {
                    try {
                        MethodHandle handle = getTrustedHandleForMethod(clazz, method).bindTo(proxy);
                        return handle::invokeWithArguments;
                    } catch (Exception ex) {
                        throw new Proto4jProxyingException("Could not invoke default method", ex);
                    }
                }
                Type   returnType     = method.getGenericReturnType();
                Type[] parameterTypes = method.getGenericParameterTypes();
                int    methodIdentifier;
                if (method.isAnnotationPresent(MethodIdentifier.class)) {
                    MethodIdentifier methodAnnotation = method.getAnnotation(MethodIdentifier.class);
                    if (methodAnnotation.value() == 0) {
                        throw new Proto4jProxyingException("Identifier for " + clazz.getSimpleName() + "#" + method.getName() + " is zero, it's now allowed");
                    }
                    methodIdentifier = methodAnnotation.value();
                } else {
                    methodIdentifier = method.getName().hashCode();
                }
                if (!methodIdentifiers.add(methodIdentifier)) {
                    throw new Proto4jProxyingException("Identifier for " + clazz.getSimpleName() + "#" + method.getName() + " duplicates with identifier of another method");
                }

                Set<Integer>   indexParams      = new HashSet<>();
                Annotation[][] paramAnnotations = method.getParameterAnnotations();
                for (int i = 0; i < paramAnnotations.length; ++i) {
                    for (int j = 0; j < paramAnnotations[i].length; ++j) {
                        if (paramAnnotations[i][j].annotationType() == Index.class) {
                            indexParams.add(i);
                        }
                    }
                }

                BiConsumer[] writers = new BiConsumer[parameterTypes.length];
                for (int i = 0; i < parameterTypes.length; ++i) {
                    writers[i] = SerializationMaster.getWriter(parameterTypes[i]);
                }
                if (returnType == void.class) {
                    return args -> {
                        byte[]              arguments = serializeArguments(args, writers);
                        RpcInvocationPacket packet    = new RpcInvocationPacket(serviceIdentifier, methodIdentifier, calculateIndex(indexParams, args), arguments);
                        send(packet);
                        return null;
                    };
                } else if (returnType instanceof ParameterizedType && ((ParameterizedType) returnType).getRawType() == CompletionStage.class) {
                    returnType = ((ParameterizedType) returnType).getActualTypeArguments()[0];
                    Function<Buffer, Object> reader = SerializationMaster.getReader(returnType);
                    return args -> {
                        CompletableFuture   future    = new CompletableFuture();
                        byte[]              arguments = serializeArguments(args, writers);
                        RpcInvocationPacket packet    = new RpcInvocationPacket(serviceIdentifier, methodIdentifier, calculateIndex(indexParams, args), arguments);

                        CompletionStage<RpcResponsePacket> resultFuture = sendWithCallback(packet);
                        resultFuture.whenComplete((p, ex) -> {
                            if (ex == null) {
                                try (Buffer buffer = Buffer.wrap(p.getResponse())) {
                                    future.complete(reader.apply(buffer));
                                }
                            } else {
                                future.completeExceptionally(new Exception(p.getError()));
                            }
                        });
                        return future;
                    };
                } else {
                    Function<Buffer, Object> reader = SerializationMaster.getReader(returnType);
                    return args -> {
                        byte[]              arguments = serializeArguments(args, writers);
                        RpcInvocationPacket packet    = new RpcInvocationPacket(serviceIdentifier, methodIdentifier, calculateIndex(indexParams, args), arguments);

                        CompletionStage<RpcResponsePacket> resultFuture = sendWithCallback(packet);
                        RpcResponsePacket                  callback     = resultFuture.toCompletableFuture().get();
                        if (callback.getError() != null) {
                            throw new Exception(callback.getError());
                        } else {
                            try (Buffer buffer = Buffer.wrap(callback.getResponse())) {
                                Object result = reader.apply(buffer);
                                return result;
                            }
                        }
                    };
                }
            }).apply(passedArgs);
        });
    }

    public abstract void send(RpcInvocationPacket packet);

    public abstract CompletionStage<RpcResponsePacket> sendWithCallback(RpcInvocationPacket packet);

    @SuppressWarnings("unchecked")
    private byte[] serializeArguments(Object[] args, BiConsumer[] writers) {
        if (args == null || args.length == 0) {
            return DatagramHelper.ZERO_LENGTH_ARRAY;
        }
        try (Buffer buffer = Buffer.newBuffer()) {
            for (int i = 0; i < args.length; ++i) {
                writers[i].accept(buffer, args[i]);
            }
            return ((BufferImpl) buffer).getHandle().array();
        }
    }

    @SuppressWarnings("unchecked")
    private Object[] deserializeArguments(byte[] array, Function[] readers) {
        Object[] result = new Object[readers.length];
        try (Buffer buffer = Buffer.wrap(array)) {
            for (int i = 0; i < result.length; ++i) {
                result[i] = readers[i].apply(buffer);
            }
        }
        return result;
    }

    private int calculateIndex(Set<Integer> indices, Object[] args) {
        if (indices.isEmpty()) {
            return 0;
        }
        int result = 1;
        for (int index : indices) {
            Object el = args[index];
            result = 31 * result + (el == null ? 0 : el.hashCode());
        }
        return result;
    }

    private MethodHandle getTrustedHandleForMethod(Class<?> proxyClass, Method method) throws Exception {
        if (JAVA_VERSION < 8) {
            throw new Proto4jProxyingException("Proto4j RPC is not supported on java version lower than 8");
        }
        if (JAVA_VERSION == 8) {
            Constructor<MethodHandles.Lookup> constructor = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class);
            constructor.setAccessible(true);
            return constructor.newInstance(proxyClass)
                    .in(proxyClass)
                    .unreflectSpecial(method, proxyClass);
        }
        return this.lookup.findSpecial(
                proxyClass,
                method.getName(),
                MethodType.methodType(void.class, new Class[0]),
                proxyClass
        );
    }

    private Function<Object[], Object> getMethodInvocation(Object impl, Method method) throws Exception {
        MethodHandle handle = this.lookup.unreflect(method);
        return args -> {
            try {
                if (args == null || args.length == 0) {
                    return handle.invoke(impl);
                }
                Object[] realArgs = new Object[args.length + 1];
                realArgs[0] = impl;
                System.arraycopy(args, 0, realArgs, 1, args.length);
                return handle.invokeWithArguments(realArgs);
            } catch (Throwable throwable) {
                throw new Proto4jProxyingException("Could not invoke method " + method.getName(), throwable);
            }
        };
    }

    @FunctionalInterface
    private interface CheckedFunction<I, O> {
        O apply(I in) throws Throwable;
    }

}
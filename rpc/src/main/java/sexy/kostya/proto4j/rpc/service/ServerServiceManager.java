package sexy.kostya.proto4j.rpc.service;

import sexy.kostya.proto4j.exception.RpcException;
import sexy.kostya.proto4j.rpc.transport.packet.RpcInvocationPacket;
import sexy.kostya.proto4j.rpc.transport.packet.RpcResponsePacket;
import sexy.kostya.proto4j.transport.highlevel.HighChannel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Created by k.shandurenko on 02.10.2020
 */
@SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
public class ServerServiceManager<C extends HighChannel> extends BaseServiceManager<C> {

    private final Map<Integer, List<C>> implementations = new ConcurrentHashMap<>();
    private final Map<C, Set<Integer>>  revert          = new ConcurrentHashMap<>();

    public void register(C channel, int serviceID) {
        List<C> list = getOrCreateImplementations(serviceID);
        synchronized (list) {
            list.add(channel);
            this.implementations.put(serviceID, list); // because of possible synchronization problems
        }
        Set<Integer> set = this.revert.computeIfAbsent(channel, c -> new HashSet<>());
        synchronized (set) {
            set.add(serviceID);
        }
    }

    public void unregister(C channel) {
        Set<Integer> set = this.revert.remove(channel);
        if (set == null) {
            return;
        }
        synchronized (set) {
            set.forEach(serviceID -> {
                List<C> list = this.implementations.get(serviceID);
                if (list == null) {
                    return;
                }
                synchronized (list) {
                    list.remove(channel);
                    if (list.isEmpty()) {
                        this.implementations.remove(serviceID);
                    }
                }
            });
        }
    }

    private List<C> getOrCreateImplementations(int serviceID) {
        return this.implementations.computeIfAbsent(serviceID, sid -> new ArrayList<>());
    }

    @Override
    public void invokeRemote(C invoker, RpcInvocationPacket packet) {
        if (packet.isBroadcast()) {
            List<C> channels = this.implementations.get(packet.getServiceID());
            if (channels == null) {
                return;
            }
            short callbackID = packet.getCallbackID();
            if (callbackID == 0) {
                synchronized (channels) {
                    channels.forEach(channel -> channel.send(packet));
                }
            } else {
                CountDownLatch latch;
                synchronized (channels) {
                    latch = new CountDownLatch(channels.size());
                    channels.forEach(channel -> channel.sendWithCallback(packet).thenAccept(p -> {
                        latch.countDown();
                        if (latch.getCount() == 0) {
                            packet.setCallbackID(callbackID);
                            packet.respond(invoker, p);
                        }
                    }));
                }
            }
        } else {
            HighChannel channel    = getChannel(packet);
            short       callbackID = packet.getCallbackID();
            if (channel == null || !channel.isActive()) {
                if (callbackID != 0) {
                    packet.respond(invoker, new RpcResponsePacket(new RpcException(RpcException.Code.NO_SERVICE_AVAILABLE, "Could not find implementation for service"), null));
                }
                return;
            }
            if (callbackID == 0) {
                channel.send(packet);
            } else {
                channel.sendWithCallback(packet).thenAccept(response -> {
                    packet.setCallbackID(callbackID);
                    packet.respond(invoker, response);
                });
            }
        }
    }

    @Override
    protected C getChannel(RpcInvocationPacket packet) {
        List<C> list = this.implementations.get(packet.getServiceID());
        if (list == null) {
            return null;
        }
        synchronized (list) {
            if (list.isEmpty()) {
                return null;
            }
            if (packet.getIndex() == 0) {
                return list.get(ThreadLocalRandom.current().nextInt(list.size()));
            } else {
                return list.get(Math.abs(packet.getIndex()) % list.size());
            }
        }
    }
}

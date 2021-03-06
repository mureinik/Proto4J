package sexy.kostya.proto4j.rpc.transport.conclave;

import com.google.common.base.Preconditions;
import org.slf4j.LoggerFactory;
import sexy.kostya.proto4j.commons.Proto4jProperties;
import sexy.kostya.proto4j.rpc.service.ConclaveServerServiceManager;
import sexy.kostya.proto4j.rpc.transport.conclave.packet.RpcDisconnectNotificationPacket;
import sexy.kostya.proto4j.rpc.transport.conclave.packet.RpcServerPacket;
import sexy.kostya.proto4j.rpc.transport.conclave.packet.RpcServiceNotificationPacket;
import sexy.kostya.proto4j.rpc.transport.packet.RpcInvocationPacket;
import sexy.kostya.proto4j.rpc.transport.packet.RpcServicePacket;
import sexy.kostya.proto4j.transport.highlevel.Proto4jHighClient;
import sexy.kostya.proto4j.transport.highlevel.Proto4jHighServer;
import sexy.kostya.proto4j.transport.highlevel.packet.PacketHandler;
import sexy.kostya.proto4j.transport.lowlevel.Proto4jSocket;
import sexy.kostya.proto4j.transport.packet.PacketCodec;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Created by k.shandurenko on 03.10.2020
 */
public class RpcConclaveServer extends Proto4jHighServer<ConclaveChannel> {

    private final ConclaveChannel               self;
    private final Map<Integer, ConclaveChannel> channels = new ConcurrentHashMap<>();

    private final List<InetSocketAddress>      allServersAddresses;
    private final ConclaveServerServiceManager serviceManager;

    private final Set<RpcConclaveServerClient> clients = new HashSet<>();

    public RpcConclaveServer(List<InetSocketAddress> allServersAddresses, int workerThreads, int handlerThreads) {
        super(LoggerFactory.getLogger("RpcConclaveServer"), workerThreads, handlerThreads);
        Preconditions.checkArgument(!allServersAddresses.isEmpty(), "There must be at least one server address");
        this.allServersAddresses = allServersAddresses;
        this.self = new ConclaveChannel(0, null, null);
        this.channels.put(0, this.self);
        this.serviceManager = new ConclaveServerServiceManager(this);

        setPacketManager(new RpcConclavePacketManager());
        setPacketHandler(new PacketHandler<ConclaveChannel>() {

            {
                register(RpcInvocationPacket.class, serviceManager::invokeRemote);
                register(RpcServicePacket.class, (channel, packet) -> {
                    serviceManager.register(channel, packet.getServiceID());
                    packet.respond(channel, packet);
                });
                register(RpcServerPacket.class, (channel, packet) -> {
                    if (channel.isServer()) {
                        disconnect(channel, "Already registered as a server");
                        return;
                    }
                    channel.setServer(true);
                    serviceManager.addServer(channel);
                    packet.respond(channel, packet);
                });
                register(RpcServiceNotificationPacket.class, (channel, packet) -> serviceManager.serviceRegistered(channel, packet.getChannelID(), packet.getServiceID()));
                register(RpcDisconnectNotificationPacket.class, (channel, packet) -> serviceManager.channelUnregistered(channel, packet.getChannelID()));
            }

        });
        addOnDisconnect(channel -> {
            this.serviceManager.unregister(channel);
            if (channel.isServer()) {
                this.serviceManager.removeServer(channel);
            }
            this.channels.remove(channel.getId());
        });
    }

    @Override
    public CompletionStage<Void> start(String address, int port) {
        return super.start(address, port).thenAccept(v -> {
            InetSocketAddress myAddress = new InetSocketAddress(address, port);
            this.allServersAddresses.stream().filter(addr -> !addr.equals(myAddress)).forEach(addr -> {
                RpcConclaveServerClient client = new RpcConclaveServerClient(
                        Proto4jProperties.getProperty("conclaveWorkers", 2),
                        Proto4jProperties.getProperty("conclaveHandlers", 2)
                );
                try {
                    client.start(addr.getHostName(), addr.getPort()).toCompletableFuture().get(Proto4jProperties.getProperty("conclaveTimeout", 1000L), TimeUnit.MILLISECONDS);
                    this.clients.add(client);
                } catch (Exception ignored) {
                    client.shutdown();
                }
            });
        });
    }

    @Override
    public ConclaveChannel createChannel(PacketCodec codec) {
        int    id;
        Random random = ThreadLocalRandom.current();
        do {
            id = random.nextInt();
        } while (this.channels.containsKey(id));
        ConclaveChannel channel = new ConclaveChannel(id, getCallbacksRegistry(), codec);
        this.channels.put(id, channel);
        return channel;
    }

    public ConclaveChannel getSelfChannel() {
        return this.self;
    }

    public ConclaveChannel getChannel(int id) {
        return this.channels.get(id);
    }

    @Override
    protected boolean shutdownInternally() {
        if (!super.shutdownInternally()) {
            return false;
        }
        this.clients.forEach(Proto4jSocket::shutdown);
        return true;
    }

    private class RpcConclaveServerClient extends Proto4jHighClient<ConclaveChannel> {

        public RpcConclaveServerClient(int workerThreads, int handlerThreads) {
            super(LoggerFactory.getLogger("RpcConclaveServerClient"), workerThreads, handlerThreads, RpcConclaveServer.this.getCallbacksRegistry());
            setPacketManager(RpcConclaveServer.this.getPacketManager());
            setPacketHandler(RpcConclaveServer.this.getPacketHandler());
        }

        @Override
        public CompletionStage<Void> start(String address, int port) {
            return super.start(address, port).thenCompose(v -> {
                ConclaveChannel channel = getChannel();
                channel.setServer(true);
                serviceManager.addServer(channel);
                return channel.sendWithCallback(new RpcServerPacket()).thenApply(p -> v);
            });
        }

        @Override
        public ConclaveChannel createChannel(PacketCodec codec) {
            return RpcConclaveServer.this.createChannel(codec);
        }

        @Override
        protected boolean shutdownInternally() {
            ConclaveChannel channel = getChannel();
            if (channel == null) {
                return false;
            }
            super.shutdownInternally();
            serviceManager.removeServer(channel);
            channels.remove(channel.getId());
            return true;
        }

    }

}

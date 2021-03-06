package com.nukkitx.network.raknet;

import com.nukkitx.network.*;
import com.nukkitx.network.handler.ExceptionHandler;
import com.nukkitx.network.raknet.codec.DatagramRakNetDatagramCodec;
import com.nukkitx.network.raknet.codec.DatagramRakNetPacketCodec;
import com.nukkitx.network.raknet.enveloped.DirectAddressedRakNetPacket;
import com.nukkitx.network.raknet.handler.RakNetDatagramClientHandler;
import com.nukkitx.network.raknet.handler.RakNetPacketClientHandler;
import com.nukkitx.network.raknet.packet.OpenConnectionRequest1Packet;
import com.nukkitx.network.raknet.session.RakNetConnectingSession;
import com.nukkitx.network.raknet.session.RakNetSession;
import com.nukkitx.network.util.Preconditions;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPipeline;

import javax.annotation.Nonnull;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ForkJoinPool;

public class RakNetClient<T extends NetworkSession<RakNetSession>> extends RakNet<T> implements NetworkClient<T, RakNetSession> {
    private final ConcurrentMap<InetSocketAddress, RakNetConnectingSession<T>> connectingSessions = new ConcurrentHashMap<>();

    public RakNetClient(SessionManager<T> sessionManager, RakNetPacketRegistry<T> packetRegistry, SessionFactory<T, RakNetSession> sessionFactory) {
        super(sessionManager, packetRegistry, sessionFactory);
    }

    @Override
    protected void initPipeline(ChannelPipeline pipeline) throws Exception {
        pipeline.addLast("datagramRakNetPacketCodec", new DatagramRakNetPacketCodec(getPacketRegistry()))
                .addLast("raknetPacketHandler", new RakNetPacketClientHandler<>(this))
                .addLast("datagramRakNetDatagramCodec", new DatagramRakNetDatagramCodec(this))
                .addLast("raknetDatagramHandler", new RakNetDatagramClientHandler<>(this))
                .addLast("exceptionHandler", new ExceptionHandler());
    }

    @Override
    public CompletableFuture<T> connect(@Nonnull InetSocketAddress remoteAddress) throws Exception {
        Preconditions.checkNotNull(remoteAddress, "remoteAddress");

        final CompletableFuture<T> future = new CompletableFuture<>();

        ForkJoinPool.commonPool().execute(() -> {
            try {
                ChannelFuture channelFuture = getBootstrap().connect(remoteAddress).awaitUninterruptibly();
                InetSocketAddress localAddress = (InetSocketAddress) channelFuture.channel().localAddress();
                RakNetConnectingSession<T> session = new RakNetConnectingSession<>(localAddress, remoteAddress, channelFuture.channel(),
                        this, future, RakNetUtil.MAXIMUM_MTU_SIZE);
                connectingSessions.put(localAddress, session);

                OpenConnectionRequest1Packet connectionRequest = new OpenConnectionRequest1Packet();
                connectionRequest.setMtu(session.getMtu());
                connectionRequest.setProtocolVersion(RakNetUtil.RAKNET_PROTOCOL_VERSION);
                session.getChannel().writeAndFlush(new DirectAddressedRakNetPacket(connectionRequest, remoteAddress));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    @Override
    public void close() {
        getSessionManager().all().forEach(NetworkSession::disconnect);
        if (getChannel() != null) {
            getChannel().close().awaitUninterruptibly();
        }
    }

    public RakNetConnectingSession<T> getConnectingSession(InetSocketAddress localAddress) {
        return connectingSessions.get(localAddress);
    }

    public void removeConnectingSession(InetSocketAddress localAddress) {
        connectingSessions.remove(localAddress);
    }

    public static class Builder<T extends NetworkSession<RakNetSession>> extends RakNet.Builder<T> {
        public Builder<T> sessionFactory(SessionFactory<T, RakNetSession> sessionFactory) {
            super.sessionFactory(sessionFactory);
            return this;
        }

        public Builder<T> sessionManager(SessionManager<T> sessionManager) {
            super.sessionManager(sessionManager);
            return this;
        }

        public Builder<T> packet(PacketFactory<CustomRakNetPacket<T>> factory, int id) {
            super.packet(factory, id);
            return this;
        }


        @Override
        public RakNetClient<T> build() {
            Preconditions.checkNotNull(sessionFactory, "sessionFactory");
            Preconditions.checkNotNull(sessionManager, "sessionManager");
            RakNetPacketRegistry<T> registry = new RakNetPacketRegistry<>(packets);
            return new RakNetClient<>(sessionManager, registry, sessionFactory);
        }
    }
}

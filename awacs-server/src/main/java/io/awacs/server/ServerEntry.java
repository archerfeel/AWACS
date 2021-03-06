/**
 * Copyright 2016-2017 AWACS Project.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.awacs.server;

import io.awacs.common.Configurable;
import io.awacs.common.Configuration;
import io.awacs.common.Releasable;
import io.awacs.common.net.Packet;
import io.awacs.server.codec.PacketDecoder;
import io.awacs.server.codec.PacketEncoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * Created by pixyonly on 8/24/16.
 */
public final class ServerEntry implements Server, Configurable {

    private final static Logger log = LoggerFactory.getLogger(ServerEntry.class);

    private EventLoopGroup boss;

    private EventLoopGroup worker;

    private String host;

    private int port;

    private EventExecutorGroup businessGroup;

    private Map<Integer, Handler> handlerHolder = new HashMap<>();

    private Components components;

    private void initHandler() {
        Reflections ref = new Reflections("io.awacs.server.handler");
        Set<Class<? extends Handler>> classes = ref.getSubTypesOf(Handler.class);
        for (Class<? extends Handler> clazz : classes) {
            try {
                Handler handler = clazz.newInstance();
                List<Field> waitForInject = Stream.of(clazz.getDeclaredFields())
                        .filter(f -> f.isAnnotationPresent(Inject.class))
                        .collect(Collectors.toList());
                for (Field f : waitForInject) {
                    f.setAccessible(true);
                    Inject i = f.getDeclaredAnnotation(Inject.class);
                    String name = i.value();
                    Object component = components.lookup(name, f.getType());
                    f.set(handler, component);
                    log.info("Component {} injected onto handler {}", name, handler);
                }
                handlerHolder.put(Byte.toUnsignedInt(handler.key()), handler);
                log.info("Handler {} registered with key {}", clazz.getCanonicalName(), handler.key());
            } catch (InstantiationException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void start() {
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.DEBUG))
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(new PacketDecoder());
                        ch.pipeline().addLast(new PacketEncoder());
                        ch.pipeline().addLast(businessGroup, new Dispatcher(ServerEntry.this));
                    }
                });
        try {
            bootstrap.bind(host, port).sync();
        } catch (InterruptedException e) {
            stop();
        }
    }

    @Override
    public void stop() {
        boss.shutdownGracefully();
        worker.shutdownGracefully();
        businessGroup.shutdownGracefully();
        handlerHolder.values().forEach(Releasable::release);
        components.release();
    }

    @Override
    public void init(Configuration configuration) {
        components = new Components();
        components.init(configuration);
        initHandler();
        host = configuration.getString(Configurations.CFG_BIND_HOST, Configurations.DEFAULT_BIND_HOST);
        port = configuration.getInteger(Configurations.CFG_BIND_PORT, Configurations.DEFAULT_BIND_PORT);
        int bossCore = configuration.getInteger(Configurations.CFG_BOSS_CORE, Configurations.DEFAULT_BOSS_CORE);
        int workerCore = configuration.getInteger(Configurations.CFG_WORKER_CORE, Configurations.DEFAULT_WORKER_CORE);
        boss = new NioEventLoopGroup(bossCore);
        worker = new NioEventLoopGroup(workerCore);
        businessGroup = new DefaultEventExecutorGroup(Runtime.getRuntime().availableProcessors());
    }

    public static class Dispatcher extends SimpleChannelInboundHandler<Packet> {

        private ServerEntry ref;

        Dispatcher(ServerEntry ref) {
            this.ref = ref;
        }

        @Override
        public void channelRead0(ChannelHandlerContext ctx, Packet packet) throws Exception {
            InetSocketAddress address = (InetSocketAddress) ctx.channel().remoteAddress();
            Handler handler = ref.handlerHolder.get(Byte.toUnsignedInt(packet.key()));
            if (handler == null) {
                //TODO default handler
                log.info(packet.getNamespace());
                log.info(new String(packet.getBody()));
                return;
            }
            Packet response = handler.onReceive(packet, address);
            if (response != null) {
                ctx.writeAndFlush(response);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
        }
    }
}

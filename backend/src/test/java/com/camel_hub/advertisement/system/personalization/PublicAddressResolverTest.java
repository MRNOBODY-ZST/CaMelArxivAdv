package com.camel_hub.advertisement.system.personalization;
import io.netty.channel.DefaultEventLoop;
import org.junit.jupiter.api.Test;
import java.net.*;
import static org.assertj.core.api.Assertions.*;

class PublicAddressResolverTest {
    @Test void connectionResolverRejectsPrivateDnsAnswersAndPinsValidatedAddresses() throws Exception {
        var loop = new DefaultEventLoop();
        try {
            var unsafe = new PublicAddressResolver(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")});
            assertThatThrownBy(() -> unsafe.getResolver(loop).resolve(InetSocketAddress.createUnresolved("api.example.com", 443)).get()).hasCauseInstanceOf(UnknownHostException.class);
            var safe = new PublicAddressResolver(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")});
            var pinned = safe.getResolver(loop).resolve(InetSocketAddress.createUnresolved("api.example.com", 443)).get();
            assertThat(pinned.isUnresolved()).isFalse();
            assertThat(pinned.getAddress().getHostAddress()).isEqualTo("8.8.8.8");
            assertThat(pinned.getHostString()).isEqualTo("api.example.com");
            assertThatThrownBy(() -> safe.getResolver(loop).resolve(new InetSocketAddress("127.0.0.1",443)).get()).hasCauseInstanceOf(UnknownHostException.class);
        } finally { loop.shutdownGracefully().sync(); }
    }
}

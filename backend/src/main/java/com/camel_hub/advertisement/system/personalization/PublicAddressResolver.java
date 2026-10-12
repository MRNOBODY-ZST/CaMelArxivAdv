package com.camel_hub.advertisement.system.personalization;
import io.netty.resolver.*;
import io.netty.util.concurrent.EventExecutor;
import java.net.*;
import java.util.*;
import io.netty.util.concurrent.Promise;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
final class PublicAddressResolver extends AddressResolverGroup<InetSocketAddress> {
    interface Lookup { InetAddress[] resolve(String host) throws UnknownHostException; }
    private final Lookup lookup;
    PublicAddressResolver(Lookup lookup) { this.lookup = lookup; }
    @Override protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
        return new AbstractAddressResolver<InetSocketAddress>(executor, InetSocketAddress.class) {
            @Override protected boolean doIsResolved(InetSocketAddress address) { return false; }
            private Mono<List<InetSocketAddress>> resolveChecked(InetSocketAddress address) {
                return Mono.fromCallable(() -> {
                    InetAddress[] answers = address.isUnresolved() ? lookup.resolve(address.getHostString()) : new InetAddress[]{address.getAddress()};
                    if (answers.length == 0) throw new UnknownHostException("Provider endpoint is unavailable");
                    List<InetSocketAddress> result = new ArrayList<>();
                    for (InetAddress answer : answers) {
                        if (!PersonalizationPolicy.publicAddress(answer)) throw new UnknownHostException("Provider endpoint is not public");
                        result.add(new InetSocketAddress(InetAddress.getByAddress(address.getHostString(), answer.getAddress()), address.getPort()));
                    }
                    return result;
                }).subscribeOn(Schedulers.boundedElastic());
            }
            @Override protected void doResolve(InetSocketAddress address, Promise<InetSocketAddress> promise) {
                resolveChecked(address).subscribe(values -> promise.trySuccess(values.getFirst()), promise::tryFailure);
            }
            @Override protected void doResolveAll(InetSocketAddress address, Promise<List<InetSocketAddress>> promise) {
                resolveChecked(address).subscribe(promise::trySuccess, promise::tryFailure);
            }
        };
    }
}

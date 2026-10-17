package com.arkiv.player.resolver.contract;
import com.arkiv.player.resolver.contract.IExternalResolverCallback;
interface IExternalResolver {
    oneway void resolve(String protocol, String ref, IExternalResolverCallback callback);
    oneway void cancel();
}

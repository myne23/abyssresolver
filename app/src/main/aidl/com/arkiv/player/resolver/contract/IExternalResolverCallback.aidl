package com.arkiv.player.resolver.contract;
interface IExternalResolverCallback {
    oneway void onProgress(int percent, String message);
    oneway void onResult(String resultJson);
    oneway void onError(String code, String message);
}

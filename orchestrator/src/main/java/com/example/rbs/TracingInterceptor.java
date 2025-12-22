package com.example.rbs;

import io.grpc.*;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;

public class TracingInterceptor implements ServerInterceptor {
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        TextMapGetter<Metadata> getter = new TextMapGetter<Metadata>() {
            @Override
            public Iterable<String> keys(Metadata carrier) {
                return carrier.keys();
            }

            @Override
            public String get(Metadata carrier, String key) {
                return carrier.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
            }
        };

        Context context = GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                .extract(Context.current(), headers, getter);

        ServerCall.Listener<ReqT> listener;
        try (Scope scope = context.makeCurrent()) {
            listener = next.startCall(call, headers);
        }

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(listener) {
            @Override
            public void onMessage(ReqT message) {
                try (Scope scope = context.makeCurrent()) {
                    super.onMessage(message);
                }
            }

            @Override
            public void onHalfClose() {
                try (Scope scope = context.makeCurrent()) {
                    super.onHalfClose();
                }
            }

            @Override
            public void onCancel() {
                try (Scope scope = context.makeCurrent()) {
                    super.onCancel();
                }
            }

            @Override
            public void onComplete() {
                try (Scope scope = context.makeCurrent()) {
                    super.onComplete();
                }
            }

            @Override
            public void onReady() {
                try (Scope scope = context.makeCurrent()) {
                    super.onReady();
                }
            }
        };
    }
}

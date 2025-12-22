load("@rules_go//go:def.bzl", "go_binary", "go_library")
load("@rules_go//proto:def.bzl", "go_proto_library")
load("@rules_proto//proto:defs.bzl", "proto_library")

package(default_visibility = ["//visibility:public"])

proto_library(
    name = "runner_proto",
    srcs = ["src/main/proto/runner.proto"],
    visibility = ["//visibility:public"],
)

go_proto_library(
    name = "runner_go_proto",
    compilers = ["@rules_go//proto:go_grpc"],
    importpath = "github.com/example/remote-build-server/agent/src/main/proto",
    proto = ":runner_proto",
    visibility = ["//visibility:public"],
)

go_library(
    name = "agent_lib",
    srcs = ["src/main.go"],
    importpath = "github.com/example/remote-build-server/agent",
    deps = [
        ":runner_go_proto",
        "@org_golang_google_grpc//:go_default_library",
        "@org_golang_google_grpc//metadata",
        "@org_golang_google_grpc//reflection",
        "@io_opentelemetry_go_otel//:otel",
        "@io_opentelemetry_go_otel//propagation",
        "@io_opentelemetry_go_otel_exporters_stdout_stdouttrace//:stdouttrace",
        "@io_opentelemetry_go_otel_sdk//resource",
        "@io_opentelemetry_go_otel_sdk//trace",
        "@io_opentelemetry_go_otel_trace//:trace",
        "@io_opentelemetry_go_otel//semconv/v1.17.0:v1_17_0",
    ],
)

go_binary(
    name = "agent",
    embed = [":agent_lib"],
    visibility = ["//visibility:public"],
)

load("@rules_go//proto:def.bzl", "go_proto_library")
load("@rules_go//go:def.bzl", "go_binary", "go_library")
load("@rules_proto//proto:defs.bzl", "proto_library")

proto_library(
    name = "runner_proto",
    srcs = ["src/main/proto/runner.proto"],
    visibility = ["//visibility:public"],
    deps = ["@com_google_protobuf//:empty_proto"],
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
    visibility = ["//visibility:private"],
    deps = [
        ":runner_go_proto",
        "//orchestrator:orchestrator_go_grpc",
        "@io_opentelemetry_go_otel//:otel",
        "@io_opentelemetry_go_otel//attribute",
        "@io_opentelemetry_go_otel//propagation",
        "@io_opentelemetry_go_otel//semconv/v1.17.0:v1_17_0",
        "@io_opentelemetry_go_otel_exporters_stdout_stdouttrace//:stdouttrace",
        "@io_opentelemetry_go_otel_sdk//resource",
        "@io_opentelemetry_go_otel_sdk//trace",
        "@io_opentelemetry_go_otel_trace//:trace",
        "@org_golang_google_grpc//:grpc",
        "@org_golang_google_grpc//codes",
        "@org_golang_google_grpc//credentials/insecure",
        "@org_golang_google_grpc//metadata",
        "@org_golang_google_grpc//peer",
        "@org_golang_google_grpc//reflection",
        "@org_golang_google_grpc//status",
        "@org_golang_google_protobuf//types/known/emptypb",
    ],
)

go_binary(
    name = "agent",
    embed = [":agent_lib"],
    visibility = ["//visibility:public"],
)

load("@rules_pkg//:pkg.bzl", "pkg_tar")
load("@rules_oci//oci:defs.bzl", "oci_image")

pkg_tar(
    name = "agent_layer",
    srcs = [":agent"],
    package_dir = "/app",
)

oci_image(
    name = "image",
    base = "@distroless_static",
    entrypoint = ["/app/agent"],
    tars = [":agent_layer"],
    visibility = ["//visibility:public"],
)

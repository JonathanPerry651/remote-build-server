load("@rules_go//go:def.bzl", "go_binary", "go_library")

go_library(
    name = "proxy_lib",
    srcs = ["src/main.go"],
    importpath = "github.com/example/remote-build-server/proxy",
    visibility = ["//visibility:private"],
    deps = [
        "@org_golang_google_grpc//:grpc",
        "@org_golang_google_grpc//credentials/insecure",
        "@org_golang_google_grpc//metadata",
        "@org_golang_google_protobuf//types/known/emptypb",
    ],
)

go_binary(
    name = "proxy",
    embed = [":proxy_lib"],
    visibility = ["//visibility:public"],
)

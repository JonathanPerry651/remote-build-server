load("@rules_go//go:def.bzl", "go_binary", "go_library")

go_library(
    name = "verifier_lib",
    srcs = ["verifier.go"],
    importpath = "github.com/example/remote-build-server/e2e/test_tools",
    visibility = ["//visibility:private"],
    deps = [
        "@org_golang_google_grpc//:grpc",
        "@org_golang_google_grpc//credentials/insecure",
        "@org_golang_google_grpc//metadata",
        "@org_golang_google_protobuf//types/known/emptypb",
    ],
)

go_binary(
    name = "verifier",
    embed = [":verifier_lib"],
    visibility = ["//visibility:public"],
)

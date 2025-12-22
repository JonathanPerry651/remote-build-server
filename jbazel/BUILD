load("@rules_go//go:def.bzl", "go_binary", "go_library")

go_library(
    name = "jbazel_lib",
    srcs = ["src/main.go"],
    importpath = "github.com/example/remote-build-server/jbazel",
    visibility = ["//visibility:private"],
    deps = [
        "//orchestrator:orchestrator_go_grpc",
        "@org_golang_google_grpc//:go_default_library",
        "@org_golang_google_grpc//credentials/insecure",
    ],
)

go_binary(
    name = "jbazel",
    embed = [":jbazel_lib"],
    visibility = ["//visibility:public"],
)

load("@rules_java//java:defs.bzl", "java_binary")

java_binary(
    name = "e2e_client",
    srcs = ["src/test/java/com/example/rbs/e2e/E2eTestClient.java"],
    main_class = "com.example.rbs.e2e.E2eTestClient",
    deps = [
        "//orchestrator:orchestrator_java_grpc",
        "//orchestrator:orchestrator_java_proto",
        "@maven//:io_grpc_grpc_api",
        "@maven//:io_grpc_grpc_stub",
        "@maven//:io_grpc_grpc_netty_shaded",
        "@maven//:io_fabric8_kubernetes_client",
        "@maven//:io_fabric8_kubernetes_client_api",
        "@maven//:io_fabric8_kubernetes_model_core",
        "@maven//:org_slf4j_slf4j_simple",
        "@maven//:org_assertj_assertj_core",
    ],
)

sh_test(
    name = "kind_test",
    srcs = ["kind_test.sh"],
    data = [
        ":e2e_client",
        "//orchestrator:tarball",
        "//infra:k8s/orchestrator.yaml",
        "@kind//file",
        "@kubectl//file",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = ["manual", "local", "exclusive"],
)

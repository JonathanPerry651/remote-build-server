load("@rules_java//java:defs.bzl", "java_binary")

java_binary(
    name = "e2e_client",
    srcs = ["src/test/java/com/example/rbs/e2e/E2eTestClient.java"],
    main_class = "com.example.rbs.e2e.E2eTestClient",
    deps = [
        "//orchestrator:orchestrator_java_grpc",
        "//orchestrator:orchestrator_java_proto",
        "@maven//:io_fabric8_kubernetes_client",
        "@maven//:io_fabric8_kubernetes_client_api",
        "@maven//:io_fabric8_kubernetes_model_core",
        "@maven//:io_grpc_grpc_api",
        "@maven//:io_grpc_grpc_netty_shaded",
        "@maven//:io_grpc_grpc_stub",
        "@maven//:org_assertj_assertj_core",
        "@maven//:org_slf4j_slf4j_simple",
    ],
)

sh_test(
    name = "kind_test",
    srcs = ["kind_test.sh"],
    data = [
        ":e2e_client",
        "//infra:k8s/orchestrator.yaml",
        "//orchestrator:tarball",
        "@bazel_tools//tools/bash/runfiles",
        "@kind//file",
        "@kubectl//file",
    ],
    tags = [
        "exclusive",
        "local",
        "manual",
    ],
)

sh_test(
    name = "mirror_test",
    srcs = ["mirror_test.sh"],
    data = [
        "//agent",
        "//e2e/test_tools:verifier",
        "//jbazel/src:jbazel",
        "//orchestrator:server",
        "//proxy",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = [
        "exclusive",
        "local",
        "manual",
    ],
)

sh_test(
    name = "proxy_test",
    srcs = ["proxy_test.sh"],
    args = [
        "$(location //agent:agent)",
        "$(location //proxy:proxy)",
        "$(location //e2e/test_tools:verifier)",
    ],
    data = [
        "//agent",
        "//e2e/test_tools:verifier",
        "//proxy",
    ],
    tags = [
        "exclusive",
        "local",
        "manual",
    ],
)

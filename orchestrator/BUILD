load("@rules_go//proto:def.bzl", "go_proto_library")
load("@rules_java//java:defs.bzl", "java_binary", "java_library", "java_proto_library")
load("@rules_oci//oci:defs.bzl", "oci_image", "oci_tarball")
load("@rules_pkg//:pkg.bzl", "pkg_tar")
load("@rules_proto//proto:defs.bzl", "proto_library")
load("@rules_proto_grpc_java//:defs.bzl", "java_grpc_library")

package(default_visibility = ["//visibility:public"])

proto_library(
    name = "orchestrator_proto",
    srcs = ["src/main/proto/orchestrator.proto"],
    visibility = ["//visibility:public"],
    deps = ["@googleapis//google/rpc:status_proto"],
)

java_proto_library(
    name = "orchestrator_java_proto",
    deps = [":orchestrator_proto"],
)

java_grpc_library(
    name = "orchestrator_java_grpc",
    protos = [":orchestrator_proto"],
    deps = [
        ":orchestrator_java_proto",
        "@maven//:com_google_api_grpc_proto_google_common_protos",
    ],
)

go_proto_library(
    name = "orchestrator_go_grpc",
    compilers = ["@rules_go//proto:go_grpc"],
    importpath = "github.com/example/remote-build-server/orchestrator/src/main/proto",
    proto = ":orchestrator_proto",
    visibility = ["//visibility:public"],
)

java_library(
    name = "orchestrator_lib",
    srcs = glob(["src/main/java/**/*.java"]),
    deps = [
        ":orchestrator_java_grpc",
        ":orchestrator_java_proto",
        "@maven//:com_google_cloud_google_cloud_spanner",
        "@maven//:io_fabric8_kubernetes_client",
        "@maven//:io_fabric8_kubernetes_client_api",
        "@maven//:io_fabric8_kubernetes_model_core",
        "@maven//:io_grpc_grpc_api",
        "@maven//:io_grpc_grpc_netty_shaded",
        "@maven//:io_grpc_grpc_protobuf",
        "@maven//:io_grpc_grpc_stub",
        "@maven//:io_opentelemetry_opentelemetry_api",
        "@maven//:io_opentelemetry_opentelemetry_context",
        "@maven//:io_opentelemetry_opentelemetry_exporter_logging",
        "@maven//:io_opentelemetry_opentelemetry_sdk",
        "@maven//:io_opentelemetry_opentelemetry_sdk_common",
        "@maven//:io_opentelemetry_opentelemetry_sdk_trace",
        "@maven//:javax_annotation_javax_annotation_api",
        "@maven//:org_projectlombok_lombok",
        "@maven//:org_slf4j_slf4j_simple",
    ],
)

java_binary(
    name = "server",
    main_class = "com.example.rbs.OrchestratorServer",
    runtime_deps = [
        ":orchestrator_lib",
    ],
)

java_test(
    name = "orchestrator_test",
    srcs = ["src/test/java/com/example/rbs/OrchestratorServiceTest.java"],
    args = ["--security-manager=allow"],
    test_class = "com.example.rbs.OrchestratorServiceTest",
    deps = [
        ":orchestrator_java_proto",
        ":orchestrator_lib",
        "@maven//:com_google_cloud_google_cloud_spanner",
        "@maven//:io_fabric8_kubernetes_client",
        "@maven//:io_fabric8_kubernetes_client_api",
        "@maven//:io_grpc_grpc_api",
        "@maven//:io_grpc_grpc_stub",
        "@maven//:org_mockito_mockito_core",
    ],
)

java_test(
    name = "process_lifecycle_test",
    srcs = [
        "src/test/java/com/example/rbs/ProcessLifecycleTest.java",
    ],
    data = [
        "//agent",
    ],
    env = {
        "AGENT_BINARY": "$(location //agent:agent)",
    },
    test_class = "com.example.rbs.ProcessLifecycleTest",
    deps = [
        ":orchestrator_java_proto",
        ":orchestrator_lib",
        "@maven//:com_google_cloud_google_cloud_spanner",
        "@maven//:io_grpc_grpc_api",
        "@maven//:io_grpc_grpc_stub",
        "@maven//:org_assertj_assertj_core",
        "@maven//:org_mockito_mockito_core",
    ],
)

pkg_tar(
    name = "app_layer",
    srcs = [":server_deploy.jar"],
    package_dir = "/app",
)

oci_image(
    name = "image",
    base = "@distroless_java",
    entrypoint = [
        "java",
        "-jar",
        "/app/server_deploy.jar",
    ],
    tars = [":app_layer"],
)

genrule(
    name = "tarball",
    srcs = [":image"],
    outs = ["tarball.tar"],
    cmd = """
        mkdir -p image_mod
        cp -RL $(location :image)/* image_mod/
        chmod -R u+w image_mod
        python3 -c "import json; f='image_mod/index.json'; d=json.load(open(f)); d['manifests'][0]['annotations']={'org.opencontainers.image.ref.name':'localhost/orchestrator:latest'}; json.dump(d,open(f,'w'))"
        tar -C image_mod -cf $@ .
    """,
    visibility = ["//visibility:public"],
)

java_test(
    name = "kubernetes_compute_service_test",
    srcs = [
        "src/test/java/com/example/rbs/KubernetesComputeServiceTest.java",
    ],
    test_class = "com.example.rbs.KubernetesComputeServiceTest",
    deps = [
        ":orchestrator_lib",
        "@maven//:io_fabric8_kubernetes_client",
        "@maven//:io_fabric8_kubernetes_client_api",
        "@maven//:io_fabric8_kubernetes_model_core",
        "@maven//:io_fabric8_kubernetes_server_mock",
        "@maven//:junit_junit",
    ],
)

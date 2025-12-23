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
load("@rules_oci//oci:defs.bzl", "oci_image", "oci_image_index", "oci_tarball")

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

genrule(
    name = "tarball",
    srcs = [":image"],
    outs = ["tarball.tar"],
    cmd = """
        mkdir -p image_mod
        cp -RL $(location :image)/* image_mod/
        chmod -R u+w image_mod
        python3 -c "import json; f='image_mod/index.json'; d=json.load(open(f)); d['manifests'][0]['annotations']={'org.opencontainers.image.ref.name':'localhost/agent:latest'}; json.dump(d,open(f,'w'))"
        tar -C image_mod -cf $@ .
    """,
    visibility = ["//visibility:public"],
)

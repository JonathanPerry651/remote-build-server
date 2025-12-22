package(default_visibility = ["//visibility:public"])

genrule(
    name = "proxy_as_java",
    srcs = ["//proxy"],
    outs = ["bin/java"],
    cmd = "cp $(location //proxy) $@",
    executable = True,
)

filegroup(
    name = "rbs_javabase",
    srcs = [":proxy_as_java"],
)

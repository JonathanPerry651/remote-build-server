//go:build tools

package tools

import (
	_ "github.com/spf13/cobra"
	_ "go.opentelemetry.io/otel"
	_ "go.opentelemetry.io/otel/exporters/stdout/stdouttrace"
	_ "go.opentelemetry.io/otel/sdk/resource"
	_ "go.opentelemetry.io/otel/sdk/trace"
	_ "google.golang.org/grpc"
	_ "google.golang.org/protobuf/proto"
)

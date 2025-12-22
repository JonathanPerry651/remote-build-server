package main

import (
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"syscall"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
	"google.golang.org/protobuf/types/known/emptypb"
)

func main() {
	listenPath := flag.String("listen-path", "", "Path to the UDS socket to listen on")
	targetAddr := flag.String("target-addr", "", "Address of the remote agent (gRPC)")
	flag.Parse()

	if *listenPath == "" || *targetAddr == "" {
		fmt.Fprintf(os.Stderr, "Usage: proxy --listen-path <path> --target-addr <addr>\n")
		os.Exit(1)
	}

	// Setup Logger
	logger := slog.New(slog.NewTextHandler(os.Stderr, nil))
	slog.SetDefault(logger)

	// Clean up old socket
	if err := os.Remove(*listenPath); err != nil && !os.IsNotExist(err) {
		slog.Error("failed to remove existing socket", "path", *listenPath, "error", err)
		os.Exit(1)
	}

	// Listen UDS
	lis, err := net.Listen("unix", *listenPath)
	if err != nil {
		slog.Error("failed to listen", "path", *listenPath, "error", err)
		os.Exit(1)
	}
	defer lis.Close()

	// Handle cleanup
	c := make(chan os.Signal, 1)
	signal.Notify(c, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-c
		os.Remove(*listenPath)
		os.Exit(0)
	}()

	slog.Info("Proxy listening", "path", *listenPath, "target", *targetAddr)

	// Single connection to Agent (multiplexed)
	conn, err := grpc.Dial(*targetAddr, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		slog.Error("failed to connect to agent", "target", *targetAddr, "error", err)
		os.Exit(1)
	}
	defer conn.Close()

	// Generic gRPC Server
	// We use UnknownServiceHandler to catch all requests.
	opts := []grpc.ServerOption{
		grpc.UnknownServiceHandler(func(srv interface{}, stream grpc.ServerStream) error {
			return forwardStream(stream, conn)
		}),
	}
	s := grpc.NewServer(opts...)

	if err := s.Serve(lis); err != nil {
		slog.Error("failed to serve", "error", err)
		os.Exit(1)
	}
}

func forwardStream(serverStream grpc.ServerStream, clientConn *grpc.ClientConn) error {
	ctx := serverStream.Context()

	// 1. Extract Method Name
	methodName, ok := grpc.Method(ctx)
	if !ok {
		return fmt.Errorf("failed to extract method name from context")
	}

	// 2. Copy Metadata (Incoming -> Outgoing)
	md, _ := metadata.FromIncomingContext(ctx)
	outCtx := metadata.NewOutgoingContext(ctx, md)

	// 3. Initiate Client Stream to Agent
	// We use emptypb.Empty as the message type for CreateStream/SendMsg/RecvMsg
	// The grpc.CallContentSubtype("proto") is default.
	// We rely on the fact that Unmarshalling arbitrary bytes into Empty places them in UnknownFields,
	// and Marshalling Empty with UnknownFields writes them back EXACTLY.
	desc := &grpc.StreamDesc{
		ServerStreams: true,
		ClientStreams: true,
	}

	clientStream, err := clientConn.NewStream(outCtx, desc, methodName)
	if err != nil {
		return fmt.Errorf("failed to create client stream: %w", err)
	}

	slog.Info("Forwarding call", "method", methodName)

	errChan := make(chan error, 2)

	// Server -> Client (Request)
	go func() {
		for {
			var frame emptypb.Empty
			// This reads the raw bytes into frame.UnknownFields
			if err := serverStream.RecvMsg(&frame); err != nil {
				if err != io.EOF {
					errChan <- err
				}
				clientStream.CloseSend() // Close upstream
				return
			}

			if err := clientStream.SendMsg(&frame); err != nil {
				errChan <- err
				return
			}
		}
	}()

	// Client -> Server (Response)
	go func() {
		// Header handling
		md, err := clientStream.Header()
		if err == nil {
			serverStream.SendHeader(md)
		}

		for {
			var frame emptypb.Empty
			if err := clientStream.RecvMsg(&frame); err != nil {
				if err != io.EOF {
					errChan <- err
				} else {
					// Finish stream trailer
					serverStream.SetTrailer(clientStream.Trailer())
					errChan <- nil
				}
				return
			}

			if err := serverStream.SendMsg(&frame); err != nil {
				errChan <- err
				return
			}
		}
	}()

	return <-errChan
}

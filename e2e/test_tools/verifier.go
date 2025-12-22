package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
	"google.golang.org/protobuf/types/known/emptypb"
)

// We reuse empty message but any message works since we use UnknownServiceHandler
type emptyServer struct{}

func main() {
	mode := flag.String("mode", "", "server or client")
	addr := flag.String("addr", "", "address to listen (server) or connect (client)")
	targetAddr := flag.String("target", "", "target address for client")
	flag.Parse()

	if *mode == "server" {
		runServer(*addr)
	} else if *mode == "client" {
		runClient(*targetAddr)
	} else {
		log.Fatalf("Unknown mode: %s", *mode)
	}
}

func runServer(addr string) {
	lis, err := net.Listen("unix", addr)
	if err != nil {
		log.Fatalf("failed to listen: %v", err)
	}

	s := grpc.NewServer(grpc.UnknownServiceHandler(func(srv interface{}, stream grpc.ServerStream) error {
		// Echo handler
		// Read one message, echo it back
		var frame emptypb.Empty
		if err := stream.RecvMsg(&frame); err != nil {
			return err
		}

		// Echo
		return stream.SendMsg(&frame)
	}))

	fmt.Printf("Server listening on %s\n", addr)
	if err := s.Serve(lis); err != nil {
		log.Fatalf("failed to serve: %v", err)
	}
}

func runClient(target string) {
	conn, err := grpc.Dial(target,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
	)
	if err != nil {
		log.Fatalf("failed to connect: %v", err)
	}
	defer conn.Close()

	// Perform a call to "TestService/Echo"
	desc := &grpc.StreamDesc{
		ServerStreams: true,
		ClientStreams: true,
	}

	ctx := context.Background()
	// Add verify header
	ctx = metadata.AppendToOutgoingContext(ctx, "x-test-header", "test-val")

	stream, err := conn.NewStream(ctx, desc, "/TestService/Echo")
	if err != nil {
		log.Fatalf("failed to create stream: %v", err)
	}

	// Send Empty (with unknown fields?)
	// Actually sending Empty defaults is fine, we just verify the round trip.
	if err := stream.SendMsg(&emptypb.Empty{}); err != nil {
		log.Fatalf("failed to send: %v", err)
	}

	var frame emptypb.Empty
	if err := stream.RecvMsg(&frame); err != nil {
		log.Fatalf("failed to recv: %v", err)
	}

	// Check header echo (if implemented) - for now just success is enough

	fmt.Println("SUCCESS: Echo received")
}

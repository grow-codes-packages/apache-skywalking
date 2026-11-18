// Command example shows the basics of the Apache SkyWalking Go SDK: configuration
// from the environment, health, an authenticated ping and error handling.
//
//	GROW_APACHE_SKYWALKING_URL=http://127.0.0.1:12800 GROW_APACHE_SKYWALKING_TOKEN=... go run .
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"time"

	skywalking "github.com/grow-codes-packages/apache-skywalking/go"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := skywalking.ConfigFromEnv()
	if err != nil {
		return err // CONFIG error naming the variable, never its value
	}
	cfg.Logger = slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelWarn}))
	cfg.Hooks = &skywalking.Hooks{
		OnError: func(info skywalking.ErrorInfo) {
			if info.WillRetry {
				fmt.Fprintf(os.Stderr, "retrying %s %s (attempt %d)\n", info.Method, info.URL, info.Attempt)
			}
		},
	}
	fmt.Println("config:", cfg) // credentials print as [REDACTED]

	client, err := skywalking.NewClient(cfg)
	if err != nil {
		return err
	}
	defer func() { _ = client.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	health, err := client.Health(ctx)
	if err != nil {
		return err
	}
	fmt.Printf("health: healthy=%t status=%s\n", health.Healthy, health.Status)

	latency, err := client.Ping(ctx)
	switch {
	case errors.Is(err, skywalking.ErrAuthentication):
		return fmt.Errorf("check GROW_APACHE_SKYWALKING_TOKEN: %w", err)
	case err != nil:
		return err
	}
	fmt.Println("ping:", latency)

	version, err := client.ServerVersion(ctx)
	switch {
	case errors.Is(err, skywalking.ErrUnsupported):
		fmt.Println("server version: not exposed")
	case err != nil:
		return err
	default:
		fmt.Println("server version:", version)
	}
	return nil
}
